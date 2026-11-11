package tg.zener.station.tanks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.security.Auth;

/**
 * Pistolets et cuves : mesures (pige), réceptions de carburant, alarmes et relevés d'index des pistolets.
 * Lecture : droits d'exploitation ou de stock ; saisie : droit STOCKS. Périmètre station pour un gérant.
 */
@RestController
public class TankController {

    private static final String READ = "hasAuthority('STOCKS') or hasAuthority('STATION_EXPLOITER') or hasAuthority('STATIONS_GERER')"
            + " or hasAuthority('RAPPORTS')";

    private final Db db;
    private final TankService tanks;
    private final PumpReportService reports;

    public TankController(Db db, TankService tanks, PumpReportService reports) {
        this.db = db;
        this.tanks = tanks;
        this.reports = reports;
    }

    private long stationOf(long tankId) {
        Long s = db.value("SELECT station_id FROM tank WHERE id = :id", Db.p("id", tankId), Long.class);
        if (s == null) {
            throw ApiException.notFound("Cuve introuvable");
        }
        Auth.checkStation(s);
        return s;
    }

    // ------------------------------------------------------------------ mesures

    /** Historique des mesures d'une cuve (jauge et pige), du plus récent au plus ancien. */
    @GetMapping("/api/tanks/{id}/readings")
    @PreAuthorize(READ)
    public List<Map<String, Object>> readings(@PathVariable long id,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) Integer limit) {
        stationOf(id);
        return db.list("""
                \sSELECT r.id, r.at, r.source, r.volume, r.book_volume, r.gap, r.height_mm, r.temperature, r.water_height_mm,
                       r.water_volume, r.density, r.note, e.first_name || ' ' || e.last_name AS employee_name
                FROM tank_reading r LEFT JOIN employee e ON e.id = r.employee_id
                WHERE r.tank_id = :t AND (CAST(:f AS date) IS NULL OR r.at >= CAST(:f AS date))
                ORDER BY r.at DESC LIMIT :l""", Db.p("t", id, "f", from, "l", limit == null ? 200 : Math.min(limit, 2000)));
    }

    public record ManualForm(@NotNull @DecimalMin("0") BigDecimal volume, BigDecimal heightMm, BigDecimal temperature,
                             BigDecimal waterHeightMm, BigDecimal waterVolume, BigDecimal density, Boolean adjust, String note) {}

    /** Mesure manuelle (pige) ; avec adjust, l'écart avec le stock théorique devient un ajustement (inventaire). */
    @PostMapping("/api/tanks/{id}/readings")
    @PreAuthorize("hasAuthority('STOCKS')")
    public Map<String, Object> manual(@PathVariable long id, @Valid @RequestBody ManualForm f) {
        stationOf(id);
        return tanks.manual(id, new TankService.Measure(f.volume(), f.heightMm(), f.temperature(), f.waterHeightMm(),
                f.waterVolume(), f.density()), Boolean.TRUE.equals(f.adjust()), Auth.user().id(), blank(f.note()));
    }

    // ------------------------------------------------------------------ réceptions

    public record ReceptionForm(@NotNull @Positive BigDecimal ticketVolume, @Positive BigDecimal receivedVolume,
                                @DecimalMin("0") BigDecimal soldDuring, String supplier, String truckPlate, String driver,
                                String ticketNumber, BigDecimal ticketDensity, BigDecimal temperature, String note) {}

    /** Réception de carburant (dépotage) dans une cuve : bon du fournisseur et volume constaté. */
    @PostMapping("/api/tanks/{id}/deliveries")
    @PreAuthorize("hasAuthority('STOCKS')")
    public Map<String, Object> receive(@PathVariable long id, @Valid @RequestBody ReceptionForm f) {
        stationOf(id);
        return tanks.receive(id, new TankService.Reception(f.ticketVolume(), f.receivedVolume(), f.soldDuring(), blank(f.supplier()),
                blank(f.truckPlate()), blank(f.driver()), blank(f.ticketNumber()), f.ticketDensity(), f.temperature(), null, null,
                blank(f.note())), Auth.user().id());
    }

    @GetMapping("/api/tanks/deliveries")
    @PreAuthorize(READ)
    public Page deliveries(@RequestParam(required = false) Long stationId, @RequestParam(required = false) Integer page,
                           @RequestParam(required = false) Integer size) {
        Long station = Auth.scopedStation(stationId);
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> p = Db.p("s", station, "l", sz, "o", pg * sz);
        String where = " WHERE (CAST(:s AS bigint) IS NULL OR d.station_id = :s)";
        return new Page(db.list("""
                \sSELECT d.id, d.ref, d.ended_at, d.station_id, s.short_name AS station_name, t.name AS tank_name, p.name AS product_name,
                       d.supplier, d.truck_plate, d.driver, d.ticket_number, d.ticket_volume, d.received_volume, d.gap,
                       d.volume_before, d.volume_after, e.first_name || ' ' || e.last_name AS employee_name
                FROM tank_delivery d JOIN station s ON s.id = d.station_id JOIN tank t ON t.id = d.tank_id
                JOIN product p ON p.id = d.product_id LEFT JOIN employee e ON e.id = d.employee_id""" + where
                + " ORDER BY d.ended_at DESC LIMIT :l OFFSET :o", p),
                db.count("SELECT count(*) FROM tank_delivery d" + where, p), pg, sz);
    }

    // ------------------------------------------------------------------ alarmes

    /** Alarmes des cuves : ouvertes seulement (par défaut) ou historique. */
    @GetMapping("/api/tanks/alarms")
    @PreAuthorize(READ)
    public List<Map<String, Object>> alarms(@RequestParam(required = false) Long stationId,
                                            @RequestParam(required = false) Boolean all) {
        Long station = Auth.scopedStation(stationId);
        List<Map<String, Object>> rows = db.list("""
                \sSELECT a.id, a.tank_id, t.name AS tank_name, p.name AS product_name, a.station_id, s.short_name AS station_name,
                       a.kind, a.started_at, a.ended_at, a.value, a.threshold, a.acknowledged_at, a.note,
                       e.first_name || ' ' || e.last_name AS acknowledged_by_name
                FROM tank_alarm a JOIN tank t ON t.id = a.tank_id JOIN product p ON p.id = t.product_id
                JOIN station s ON s.id = a.station_id LEFT JOIN employee e ON e.id = a.acknowledged_by
                WHERE (CAST(:s AS bigint) IS NULL OR a.station_id = :s) AND (CAST(:all AS boolean) OR a.ended_at IS NULL)
                ORDER BY a.ended_at NULLS FIRST, a.started_at DESC LIMIT 200""", Db.p("s", station, "all", Boolean.TRUE.equals(all)));
        rows.forEach(r -> r.put("label", TankService.label((String) r.get("kind"))));
        return rows;
    }

    public record AckForm(String note) {}

    @PostMapping("/api/tanks/alarms/{id}/acknowledge")
    @PreAuthorize("hasAuthority('STOCKS') or hasAuthority('STATION_EXPLOITER')")
    public Map<String, Object> acknowledge(@PathVariable long id, @RequestBody(required = false) AckForm f) {
        Long s = db.value("SELECT station_id FROM tank_alarm WHERE id = :id", Db.p("id", id), Long.class);
        if (s == null) {
            throw ApiException.notFound("Alarme introuvable");
        }
        Auth.checkStation(s);
        return tanks.acknowledge(id, Auth.user().id(), f == null ? null : blank(f.note()));
    }

    // ------------------------------------------------------------------ relevés des pistolets

    /** Relevés d'index : kind POSTE, JOUR ou MOIS ; écart = volume compteur − volume vendu. */
    @GetMapping("/api/pumps/reports")
    @PreAuthorize(READ)
    public Page pumpReports(@RequestParam(required = false) Long stationId, @RequestParam(required = false) String kind,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) Boolean withGap,
                            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Long station = Auth.scopedStation(stationId);
        String k = kind == null ? "JOUR" : kind.toUpperCase();
        if (!List.of("POSTE", "JOUR", "MOIS").contains(k)) {
            throw new ApiException("Type de relevé : POSTE, JOUR ou MOIS");
        }
        int sz = Page.size(size);
        int pg = Page.page(page);
        String where = """
                 \sWHERE r.kind = :k AND (CAST(:s AS bigint) IS NULL OR r.station_id = :s)
                   AND (CAST(:f AS date) IS NULL OR r.period_start >= CAST(:f AS date))
                   AND (CAST(:t AS date) IS NULL OR r.period_start <= CAST(:t AS date))
                   AND (NOT CAST(:g AS boolean) OR abs(r.gap) >= 0.01)""";
        Map<String, Object> p = Db.p("k", k, "s", station, "f", from, "t", to, "g", Boolean.TRUE.equals(withGap), "l", sz, "o", pg * sz);
        return new Page(db.list("""
                \sSELECT r.id, r.kind, r.period_start, r.started_at, r.ended_at, r.station_id, s.short_name AS station_name,
                       pu.code AS pump_code, p.name AS product_name, r.start_index, r.end_index, r.meter_volume, r.sold_volume,
                       r.gap, r.transactions, r.amount, e.first_name || ' ' || e.last_name AS employee_name
                FROM pump_report r JOIN pump pu ON pu.id = r.pump_id JOIN station s ON s.id = r.station_id
                JOIN product p ON p.id = r.product_id
                LEFT JOIN employee_shift es ON es.id = r.employee_shift_id LEFT JOIN employee e ON e.id = es.employee_id""" + where
                + " ORDER BY r.period_start DESC, r.started_at DESC, s.short_name, pu.code LIMIT :l OFFSET :o", p),
                db.count("SELECT count(*) FROM pump_report r" + where, p), pg, sz);
    }

    /** Génération immédiate des relevés manquants (le job tourne toutes les 5 minutes). */
    @PostMapping("/api/pumps/reports/generate")
    @PreAuthorize("hasAuthority('STOCKS') or hasAuthority('RAPPORTS')")
    public Map<String, Object> generate() {
        return Map.of("created", reports.generate());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
