package tg.zener.station.shifts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/**
 * Postes : le pompiste ouvre son poste, le met en pause et consulte à tout moment ce qu'il doit
 * remettre ; le chef de poste ou le gérant enregistre les remises et clôture le poste.
 */
@RestController
@RequestMapping("/api/shifts")
public class ShiftController {

    private final Db db;
    private final ShiftService shifts;

    public ShiftController(Db db, ShiftService shifts) {
        this.db = db;
        this.shifts = shifts;
    }

    public record StartForm(Long configId, String pumps) {}

    @PostMapping("/start")
    @PreAuthorize("hasAuthority('VENTES_POMPE')")
    public Map<String, Object> start(@RequestBody(required = false) StartForm f) {
        CurrentUser u = Auth.user();
        if (u.stationId() == null) {
            throw new ApiException("Vous n'êtes rattaché à aucune station");
        }
        shifts.start(u.id(), u.stationId(), f == null ? null : f.configId(), f == null ? null : f.pumps());
        return me();
    }

    @PostMapping("/pause")
    @PreAuthorize("hasAuthority('VENTES_POMPE')")
    public Map<String, Object> pause() {
        shifts.setPaused(requireCurrent(), true, Auth.user().id());
        return me();
    }

    @PostMapping("/resume")
    @PreAuthorize("hasAuthority('VENTES_POMPE')")
    public Map<String, Object> resume() {
        shifts.setPaused(requireCurrent(), false, Auth.user().id());
        return me();
    }

    /**
     * Écran de clôture du pompiste : poste en cours (ou dernier poste), ventes, espèces attendues,
     * remises, reste à remettre, total restant dû sur les postes clos et historique.
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser u = Auth.user();
        Long current = shifts.currentShiftId(u.id());
        Long shown = current != null ? current
                : db.value("SELECT id FROM employee_shift WHERE employee_id = :e ORDER BY started_at DESC LIMIT 1",
                        Db.p("e", u.id()), Long.class);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("current", current != null);
        Map<String, Object> shift = shown == null ? null : shifts.summary(shown);
        if (shift != null) {
            shift.put("sales", sales(shown, 100));
        }
        res.put("shift", shift);
        res.put("outstanding", db.sum("""
                \sSELECT COALESCE(sum(greatest(COALESCE(expected_cash, 0) - COALESCE(remitted_cash, 0), 0)), 0)
                FROM employee_shift WHERE employee_id = :e AND status = 'CLOS'""", Db.p("e", u.id())));
        res.put("history", history(u.id(), 20));
        res.put("configs", u.stationId() == null ? List.of() : db.list(
                "SELECT id, name, start_time, end_time FROM shift_config WHERE station_id = :s ORDER BY start_time",
                Db.p("s", u.stationId())));
        return res;
    }

    /** Postes d'une station (chef de poste, gérant) : en cours, clos avec écart, filtres par jour. */
    @GetMapping
    @PreAuthorize("hasAuthority('CLOTURER_POSTE') or hasAuthority('RAPPORTS')")
    public Page list(@RequestParam(required = false) Long stationId, @RequestParam(required = false) String status,
                     @RequestParam(required = false) Long employeeId, @RequestParam(required = false) Boolean withGap,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Long station = Auth.scopedStation(stationId);
        String where = """
                 \sWHERE (CAST(:s AS bigint) IS NULL OR es.station_id = :s) AND (CAST(:st AS text) IS NULL OR es.status = :st)
                   AND (CAST(:e AS bigint) IS NULL OR es.employee_id = :e)
                   AND (CAST(:d AS date) IS NULL OR (es.started_at >= CAST(:d AS date) AND es.started_at < CAST(:d AS date) + 1))
                   AND (CAST(:g AS boolean) IS NULL OR NOT CAST(:g AS boolean)
                        OR (es.status = 'CLOS' AND COALESCE(es.expected_cash, 0) <> COALESCE(es.remitted_cash, 0)))""";
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> p = Db.p("s", station, "st", status == null || status.isBlank() ? null : status, "e", employeeId,
                "d", day, "g", withGap, "lim", sz, "off", pg * sz);
        List<Map<String, Object>> items = db.list(ShiftService.SELECT + where
                + " ORDER BY (es.status = 'CLOS'), es.started_at DESC LIMIT :lim OFFSET :off", p);
        items.forEach(ShiftService::decorate);
        return new Page(items, db.count("SELECT count(*) FROM employee_shift es" + where, p), pg, sz);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> s = checkAccess(id, false);
        s.put("sales", sales(id, 500));
        return s;
    }

    public record RemitForm(@NotNull @Positive Long amount, String note) {}

    @PostMapping("/{id}/remit")
    @PreAuthorize("hasAuthority('CLOTURER_POSTE')")
    public Map<String, Object> remit(@PathVariable long id, @Valid @RequestBody RemitForm f) {
        checkAccess(id, true);
        shifts.remit(id, f.amount(), f.note(), Auth.user().id());
        return get(id);
    }

    public record CloseForm(@NotNull @Min(0) Long remittedCash, String note) {}

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('CLOTURER_POSTE')")
    public Map<String, Object> close(@PathVariable long id, @Valid @RequestBody CloseForm f) {
        checkAccess(id, true);
        shifts.close(id, f.remittedCash(), f.note(), Auth.user().id());
        return get(id);
    }

    // ------------------------------------------------------------------

    private long requireCurrent() {
        Long id = shifts.currentShiftId(Auth.user().id());
        if (id == null) {
            throw ApiException.conflict("Aucun poste en cours");
        }
        return id;
    }

    /** Le titulaire du poste le consulte ; sinon il faut le droit de clôture et la même station. */
    private Map<String, Object> checkAccess(long id, boolean manage) {
        Map<String, Object> s = shifts.summary(id);
        CurrentUser u = Auth.user();
        boolean own = ((Number) s.get("employeeId")).longValue() == u.id();
        if (manage || !own) {
            if (!u.can("CLOTURER_POSTE") && !u.can("RAPPORTS")) {
                throw ApiException.forbidden("Vous n'avez pas le droit de consulter ce poste");
            }
            Auth.checkStation(((Number) s.get("stationId")).longValue());
        }
        return s;
    }

    private List<Map<String, Object>> sales(long shiftId, int limit) {
        return db.list("""
                \sSELECT x.id, x.at, x.kind, x.payment_method, x.total, pu.code AS pump_code, c.number AS card_number,
                       (SELECT string_agg(CASE WHEN p.category = 'CARBURANT'
                                               THEN replace(trim(to_char(sl.quantity, 'FM999999990.0')), '.', ',') || ' L ' || p.name
                                               ELSE trim(to_char(sl.quantity, 'FM999999990')) || ' × ' || p.name END, ', ')
                        FROM sale_line sl JOIN product p ON p.id = sl.product_id WHERE sl.sale_id = x.id) AS description
                FROM sale x LEFT JOIN pump pu ON pu.id = x.pump_id LEFT JOIN card c ON c.id = x.card_id
                WHERE x.employee_shift_id = :s ORDER BY x.at DESC LIMIT :l""", Db.p("s", shiftId, "l", limit));
    }

    private List<Map<String, Object>> history(long employeeId, int limit) {
        List<Map<String, Object>> rows = db.list(ShiftService.SELECT
                + " WHERE es.employee_id = :e ORDER BY es.started_at DESC LIMIT :l", Db.p("e", employeeId, "l", limit));
        rows.forEach(ShiftService::decorate);
        return rows;
    }
}
