package tg.zener.station.stations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;

/** Stations (écrans 03 et 04) : liste, carte, fiche, mode de fonctionnement, équipement. */
@RestController
@RequestMapping("/api/stations")
public class StationController {

    private final Db db;
    private final StationService stations;
    private final Journal journal;

    public StationController(Db db, StationService stations, Journal journal) {
        this.db = db;
        this.stations = stations;
        this.journal = journal;
    }

    private String baseSelect() {
        return """
                \sSELECT s.id, s.code, s.name, s.short_name, s.type, s.kind, s.country, s.zone, s.address,
                       s.latitude, s.longitude, s.mode, s.auto_switch, s.client_id, c.name AS client_name,
                       s.reader_connected, s.last_seen_at,
                       """ + stations.statusSql() + """
                        \sAS status,
                       COALESCE((SELECT sum(total) FROM sale WHERE station_id = s.id AND at >= current_date), 0) AS revenue_today,
                       COALESCE((SELECT sum(sl.quantity) FROM sale_line sl JOIN sale x ON x.id = sl.sale_id
                                 JOIN product p ON p.id = sl.product_id
                                 WHERE x.station_id = s.id AND x.at >= current_date AND p.category = 'CARBURANT'), 0) AS volume_today,
                       (SELECT COALESCE(json_agg(json_build_object('product', p.code, 'name', t.name,
                                    'pct', round(100 * t.volume / t.capacity)) ORDER BY t.id), '[]')
                        FROM tank t JOIN product p ON p.id = t.product_id WHERE t.station_id = s.id) AS tanks,
                       (SELECT p.name FROM stock_alert a JOIN product p ON p.id = a.product_id
                        WHERE a.station_id = s.id AND a.resolved_at IS NULL AND a.tank_id IS NOT NULL
                        ORDER BY a.severity, a.raised_at LIMIT 1) AS low_tank,
                       CAST(extract(epoch FROM now() - s.last_seen_at) / 60 AS int) AS minutes_since_seen
                FROM station s LEFT JOIN client c ON c.id = s.client_id""";
    }

    /** Ajoute les libellés affichés par le frontend (statut, mode, type, état). */
    private Map<String, Object> decorate(Map<String, Object> s) {
        s.put("statusLabel", StationService.statusLabel(s.get("status")));
        s.put("modeLabel", StationService.modeLabel(s.get("mode")));
        s.put("typeLabel", StationService.typeLabel(s.get("type")));
        String state = "Normal";
        if (!"HORS_LIGNE".equals(s.get("status")) && !Boolean.TRUE.equals(s.get("readerConnected"))) {
            state = "Lecteur déconnecté";
        } else if (s.get("lowTank") != null) {
            state = "Cuve " + s.get("lowTank") + " basse";
        }
        s.put("state", state);
        return s;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String mode,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String country,
                                          @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) Long clientId) {
        Long scope = Auth.scopedStation(null);
        List<Map<String, Object>> rows = db.list("SELECT * FROM (" + baseSelect() + """
                 \sWHERE s.active) s
                WHERE (CAST(:scope AS bigint) IS NULL OR s.id = :scope)
                  AND (CAST(:q AS text) IS NULL OR (s.name || ' ' || COALESCE(s.client_name, '') || ' ' || COALESCE(s.zone, ''))
                        ILIKE '%' || :q || '%')
                  AND (CAST(:type AS text) IS NULL OR s.type = :type)
                  AND (CAST(:mode AS text) IS NULL OR s.mode = :mode)
                  AND (CAST(:status AS text) IS NULL OR s.status = :status)
                  AND (CAST(:country AS text) IS NULL OR s.country = :country)
                  AND (CAST(:kind AS text) IS NULL OR s.kind = :kind)
                  AND (CAST(:client AS bigint) IS NULL OR s.client_id = :client)
                ORDER BY s.revenue_today DESC, s.name""",
                Db.p("scope", scope, "q", blank(q), "type", blank(type), "mode", blank(mode), "status", blank(status),
                        "country", blank(country), "kind", blank(kind), "client", clientId));
        rows.forEach(this::decorate);
        return rows;
    }

    /** Indicateurs de l'écran Stations. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Long scope = Auth.scopedStation(null);
        Map<String, Object> m = db.one("""
                \sSELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'EN_LIGNE') AS online,
                       count(*) FILTER (WHERE status = 'INSTABLE') AS unstable,
                       count(*) FILTER (WHERE status = 'HORS_LIGNE') AS offline,
                       count(*) FILTER (WHERE mode = 'AUTONOME') AS autonomous,
                       count(*) FILTER (WHERE mode = 'AUTONOME' AND auto_switch AND mode_override_until IS NULL
                                        AND EXISTS (SELECT 1 FROM station_mode_window w WHERE w.station_id = x.id)) AS autonomous_by_schedule,
                       count(*) FILTER (WHERE type = 'SUCCURSALE') AS succursales,
                       count(*) FILTER (WHERE type = 'GERANCE_LIBRE') AS gerances,
                       count(DISTINCT client_id) FILTER (WHERE type = 'GERANCE_LIBRE') AS manager_clients,
                       COALESCE(sum(revenue_today), 0) AS revenue_today
                FROM (SELECT s.*, """ + stations.statusSql() + """
                       \sAS status,
                       COALESCE((SELECT sum(total) FROM sale WHERE station_id = s.id AND at >= current_date), 0) AS revenue_today
                      FROM station s WHERE s.active AND (CAST(:scope AS bigint) IS NULL OR s.id = :scope)) x""",
                Db.p("scope", scope));
        m.put("byCountry", db.list("""
                \sSELECT country, count(*) AS count FROM station
                WHERE active AND (CAST(:scope AS bigint) IS NULL OR id = :scope)
                GROUP BY country ORDER BY count DESC""", Db.p("scope", scope)));
        return m;
    }

    /** Points de la carte de géolocalisation. */
    @GetMapping("/map")
    public List<Map<String, Object>> map(@RequestParam(required = false) String country) {
        Long scope = Auth.scopedStation(null);
        List<Map<String, Object>> rows = db.list("""
                \sSELECT s.id, s.name, s.short_name, s.kind, s.country, s.latitude, s.longitude, s.reader_connected,
                       s.reader_port, """ + stations.statusSql() + """
                        \sAS status,
                       CAST(extract(epoch FROM now() - s.last_seen_at) / 60 AS int) AS minutes_since_seen
                FROM station s
                WHERE s.active AND s.latitude IS NOT NULL
                  AND (CAST(:c AS text) IS NULL OR s.country = :c) AND (CAST(:scope AS bigint) IS NULL OR s.id = :scope)
                ORDER BY s.latitude DESC""", Db.p("c", blank(country), "scope", scope));
        rows.forEach(r -> r.put("statusLabel", StationService.statusLabel(r.get("status"))));
        return rows;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Auth.checkStation(id);
        Map<String, Object> s = db.require("SELECT * FROM (" + baseSelect() + ") s WHERE s.id = :id",
                Db.p("id", id), "Station introuvable");
        decorate(s);
        s.putAll(db.one("""
                \sSELECT s.opened_on, s.hub_ports, s.reader_port, s.serial_port, s.serial_baud, s.mode_override_until,
                       s.manager_id, m.first_name || ' ' || m.last_name AS manager_name,
                       (s.api_key_hash IS NOT NULL) AS has_api_key,
                       l.end_date AS licence_end,
                       CASE WHEN l.id IS NULL THEN 'Aucune' WHEN l.status = 'RESILIEE' THEN 'Résiliée'
                            WHEN l.end_date < current_date THEN 'Expirée' ELSE 'Active' END AS licence_status
                FROM station s
                LEFT JOIN employee m ON m.id = s.manager_id
                LEFT JOIN LATERAL (SELECT * FROM licence l WHERE l.client_id = s.client_id AND l.status <> 'REMPLACEE'
                                   ORDER BY l.end_date DESC LIMIT 1) l ON true
                WHERE s.id = :id""", Db.p("id", id)));

        s.put("pumps", db.list("""
                \sSELECT pu.id, pu.code, pu.port, pu.state, pu.current_volume, pu.index_total, pu.updated_at,
                       p.code AS product_code, p.name AS product_name
                FROM pump pu JOIN product p ON p.id = pu.product_id
                WHERE pu.station_id = :id ORDER BY length(pu.code), pu.code""", Db.p("id", id)));
        int ports = ((List<?>) s.get("pumps")).size() + (s.get("readerPort") == null ? 0 : 1);
        s.put("hubPortsUsed", ports);

        s.put("tanks", db.list("""
                \sSELECT t.id, t.name, p.code AS product_code, p.name AS product_name, t.capacity, t.volume,
                       round(100 * t.volume / t.capacity) AS pct,
                       t.threshold_manual, t.threshold_auto, COALESCE(t.threshold_manual, t.threshold_auto) AS threshold,
                       round(100 * COALESCE(t.threshold_manual, t.threshold_auto, 0) / t.capacity) AS threshold_pct,
                       t.lead_days, t.trend_pct,
                       (t.volume < COALESCE(t.threshold_manual, t.threshold_auto, 0)) AS below_threshold
                FROM tank t JOIN product p ON p.id = t.product_id
                WHERE t.station_id = :id ORDER BY t.id""", Db.p("id", id)));
        s.put("alerts", db.list("""
                \sSELECT a.id, p.name AS product_name, a.level, a.threshold, a.severity, a.raised_at, a.emailed_at, a.tank_id
                FROM stock_alert a JOIN product p ON p.id = a.product_id
                WHERE a.station_id = :id AND a.resolved_at IS NULL ORDER BY a.severity, a.raised_at""", Db.p("id", id)));

        s.put("windows", windows(id));
        LocalDateTime next = stations.schedule(id).nextSwitch(stations.now());
        s.put("nextSwitchAt", next);
        s.put("nextSwitchMode", next == null ? null : stations.schedule(id).modeAt(next));

        s.put("team", db.list("""
                \sSELECT e.id, e.first_name || ' ' || e.last_name AS name,
                       upper(left(e.first_name, 1) || left(e.last_name, 1)) AS initials,
                       r.label AS role, es.pumps, es.status, es.started_at, sc.name AS shift_name,
                       CASE WHEN e.photo_path IS NULL THEN NULL ELSE '/files/' || e.photo_path END AS photo_url,
                       sc.start_time, sc.end_time
                FROM employee_shift es
                JOIN employee e ON e.id = es.employee_id JOIN role r ON r.code = e.role_code
                LEFT JOIN shift_config sc ON sc.id = es.config_id
                WHERE es.station_id = :id AND es.status <> 'CLOS' ORDER BY r.sort, e.last_name""", Db.p("id", id)));
        s.put("shiftConfigs", db.list("SELECT id, name, start_time, end_time FROM shift_config WHERE station_id = :id ORDER BY start_time",
                Db.p("id", id)));

        s.put("today", db.one("""
                \sSELECT COALESCE(sum(x.total), 0) AS revenue, count(*) AS transactions,
                       COALESCE((SELECT sum(sl.quantity) FROM sale_line sl JOIN sale y ON y.id = sl.sale_id
                                 JOIN product p ON p.id = sl.product_id
                                 WHERE y.station_id = :id AND y.at >= current_date AND p.category = 'CARBURANT'), 0) AS volume
                FROM sale x WHERE x.station_id = :id AND x.at >= current_date""", Db.p("id", id)));
        s.put("litersByHour", litersByHour(id, LocalDate.now()));
        return s;
    }

    /** Litres distribués par heure et par carburant : { SUPER: [24 valeurs], GASOIL: […] }. */
    Map<String, Object> litersByHour(long stationId, LocalDate day) {
        return stations.litersByHour(stationId, day);
    }

    // ------------------------------------------------------------------ création et modification

    public record StationForm(@NotBlank String name, @NotBlank String shortName,
                              @NotBlank @Pattern(regexp = "SUCCURSALE|GERANCE_LIBRE") String type,
                              @Pattern(regexp = "FIXE|MOBILE") String kind,
                              Long clientId, String country, String zone, String address,
                              BigDecimal latitude, BigDecimal longitude, LocalDate openedOn, Long managerId,
                              @Min(1) Integer hubPorts, String readerPort, String serialPort, Integer serialBaud) {}

    @PostMapping
    @PreAuthorize("hasAuthority('STATIONS_GERER')")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody StationForm f) {
        checkCoordinates(f);
        String code = "ST-" + String.format("%03d", db.sum("SELECT COALESCE(max(id), 0) + 1 FROM station", Map.of()));
        long id = db.insert("""
                \sINSERT INTO station (code, name, short_name, type, kind, client_id, country, zone, address, latitude, longitude,
                                     opened_on, manager_id, hub_ports, reader_port, serial_port, serial_baud)
                VALUES (:code, :n, :sn, :t, COALESCE(:k, 'FIXE'), :c, COALESCE(:co, 'Togo'), :z, :a, :lat, :lon,
                        COALESCE(:o, current_date), :m, COALESCE(:hp, 12), :rp, :sp, :sb)""",
                Db.p("code", code, "n", f.name().trim(), "sn", f.shortName().trim(), "t", f.type(), "k", f.kind(),
                        "c", f.clientId(), "co", f.country(), "z", f.zone(), "a", f.address(), "lat", f.latitude(),
                        "lon", f.longitude(), "o", f.openedOn(), "m", f.managerId(), "hp", f.hubPorts(),
                        "rp", f.readerPort(), "sp", f.serialPort(), "sb", f.serialBaud()));
        // Plages par défaut : conventionnel 06h–22h en semaine, 06h–23h le samedi, 07h–20h dimanche et fériés
        db.update("""
                \sINSERT INTO station_mode_window (station_id, label, days, holidays, conventional_from, conventional_to) VALUES
                    (:s, 'Lundi – Vendredi', '{1,2,3,4,5}', false, '06:00', '22:00'),
                    (:s, 'Samedi', '{6}', false, '06:00', '23:00'),
                    (:s, 'Dimanche & fériés', '{7}', true, '07:00', '20:00')""", Db.p("s", id));
        String key = stations.newApiKey(id);
        journal.log("STATION_CREEE", Journal.MODIFICATIONS, "Station créée", f.name(), null, id, null);
        Map<String, Object> s = get(id);
        s.put("apiKey", key); // affichée une seule fois, à saisir dans le PC station
        return s;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('STATIONS_GERER')")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody StationForm f) {
        checkCoordinates(f);
        int n = db.update("""
                \sUPDATE station SET name = :n, short_name = :sn, type = :t, kind = COALESCE(:k, kind), client_id = :c,
                       country = COALESCE(:co, country), zone = :z, address = :a, latitude = :lat, longitude = :lon,
                       opened_on = COALESCE(:o, opened_on), manager_id = :m, hub_ports = COALESCE(:hp, hub_ports),
                       reader_port = :rp, serial_port = :sp, serial_baud = :sb
                WHERE id = :id""",
                Db.p("id", id, "n", f.name().trim(), "sn", f.shortName().trim(), "t", f.type(), "k", f.kind(),
                        "c", f.clientId(), "co", f.country(), "z", f.zone(), "a", f.address(), "lat", f.latitude(),
                        "lon", f.longitude(), "o", f.openedOn(), "m", f.managerId(), "hp", f.hubPorts(),
                        "rp", f.readerPort(), "sp", f.serialPort(), "sb", f.serialBaud()));
        if (n == 0) {
            throw ApiException.notFound("Station introuvable");
        }
        checkPorts(id);
        journal.log("STATION_MODIFIEE", Journal.MODIFICATIONS, "Station modifiée", f.name(), null, id, null);
        return get(id);
    }

    public record Assignment(Long clientId) {}

    /** Attribution d'une station à un client (Admin). */
    @PutMapping("/{id}/client")
    @PreAuthorize("hasAuthority('STATIONS_GERER')")
    public Map<String, Object> assign(@PathVariable long id, @RequestBody Assignment a) {
        String client = a.clientId() == null ? "aucun client"
                : (String) db.require("SELECT name FROM client WHERE id = :c", Db.p("c", a.clientId()), "Client introuvable").get("name");
        String station = (String) db.require("SELECT name FROM station WHERE id = :id", Db.p("id", id), "Station introuvable").get("name");
        db.update("UPDATE station SET client_id = :c WHERE id = :id", Db.p("id", id, "c", a.clientId()));
        journal.log("STATION_ATTRIBUEE", Journal.MODIFICATIONS, "Station attribuée", station + " → " + client, null, id, null);
        return get(id);
    }

    /** Désactivation (les données restent consultables). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('STATIONS_GERER')")
    public Map<String, Object> deactivate(@PathVariable long id) {
        db.update("UPDATE station SET active = false WHERE id = :id", Db.p("id", id));
        journal.log("STATION_DESACTIVEE", Journal.MODIFICATIONS, "Station désactivée", String.valueOf(id), null, id, null);
        return Map.of("ok", true);
    }

    /** Nouvelle clé pour le PC station (l'ancienne cesse de fonctionner). */
    @PostMapping("/{id}/api-key")
    @PreAuthorize("hasAuthority('STATIONS_GERER')")
    public Map<String, Object> regenerateKey(@PathVariable long id) {
        Map<String, Object> s = db.require("SELECT code FROM station WHERE id = :id", Db.p("id", id), "Station introuvable");
        String key = stations.newApiKey(id);
        journal.log("CLE_STATION", Journal.MODIFICATIONS, "Clé du PC station régénérée", (String) s.get("code"), null, id, null);
        return Map.of("stationCode", s.get("code"), "apiKey", key);
    }

    // ------------------------------------------------------------------ mode de fonctionnement

    public record ModeRequest(@NotBlank @Pattern(regexp = "CONVENTIONNEL|AUTONOME") String mode) {}

    /** Bascule manuelle, possible à tout moment. */
    @PostMapping("/{id}/mode")
    @PreAuthorize("hasAuthority('STATION_EXPLOITER')")
    public Map<String, Object> switchMode(@PathVariable long id, @Valid @RequestBody ModeRequest r) {
        Auth.checkStation(id);
        stations.switchMode(id, r.mode(), "MANUEL", Auth.user().id());
        return get(id);
    }

    public record AutoSwitch(boolean enabled) {}

    @PutMapping("/{id}/auto-switch")
    @PreAuthorize("hasAuthority('STATION_EXPLOITER')")
    public Map<String, Object> autoSwitch(@PathVariable long id, @RequestBody AutoSwitch r) {
        Auth.checkStation(id);
        db.update("UPDATE station SET auto_switch = :a, mode_override_until = NULL WHERE id = :id", Db.p("id", id, "a", r.enabled()));
        journal.log("BASCULE_AUTO", Journal.MODIFICATIONS,
                r.enabled() ? "Bascule automatique activée" : "Bascule automatique désactivée", null, null, id, null);
        return get(id);
    }

    @GetMapping("/{id}/mode-history")
    public List<Map<String, Object>> modeHistory(@PathVariable long id) {
        Auth.checkStation(id);
        return db.list("""
                \sSELECT mc.id, mc.from_mode, mc.to_mode, mc.trigger, mc.at, e.first_name || ' ' || e.last_name AS user_name
                FROM mode_change mc LEFT JOIN employee e ON e.id = mc.user_id
                WHERE mc.station_id = :id ORDER BY mc.at DESC LIMIT 100""", Db.p("id", id));
    }

    List<Map<String, Object>> windows(long stationId) {
        return db.list("""
                \sSELECT id, label, days, holidays, conventional_from, conventional_to FROM station_mode_window
                WHERE station_id = :s ORDER BY days[1], id""", Db.p("s", stationId));
    }

    public record WindowForm(@NotBlank String label, @NotEmpty List<Integer> days, Boolean holidays,
                             @NotNull LocalTime conventionalFrom, @NotNull LocalTime conventionalTo) {}

    @PostMapping("/{id}/windows")
    @PreAuthorize("hasAuthority('STATION_EXPLOITER')")
    public List<Map<String, Object>> addWindow(@PathVariable long id, @Valid @RequestBody WindowForm f) {
        Auth.checkStation(id);
        checkDays(f.days());
        db.update("""
                \sINSERT INTO station_mode_window (station_id, label, days, holidays, conventional_from, conventional_to)
                VALUES (:s, :l, :d, COALESCE(:h, false), :f, :t)""",
                Db.p("s", id, "l", f.label().trim(), "d", db.array("smallint", f.days().toArray()), "h", f.holidays(),
                        "f", f.conventionalFrom(), "t", f.conventionalTo()));
        journal.log("PLAGE_HORAIRE", Journal.MODIFICATIONS, "Plage horaire ajoutée", f.label(), null, id, null);
        return windows(id);
    }

    @PutMapping("/{id}/windows/{windowId}")
    @PreAuthorize("hasAuthority('STATION_EXPLOITER')")
    public List<Map<String, Object>> updateWindow(@PathVariable long id, @PathVariable long windowId,
                                                  @Valid @RequestBody WindowForm f) {
        Auth.checkStation(id);
        checkDays(f.days());
        int n = db.update("""
                \sUPDATE station_mode_window SET label = :l, days = :d, holidays = COALESCE(:h, false),
                       conventional_from = :f, conventional_to = :t
                WHERE id = :w AND station_id = :s""",
                Db.p("s", id, "w", windowId, "l", f.label().trim(), "d", db.array("smallint", f.days().toArray()),
                        "h", f.holidays(), "f", f.conventionalFrom(), "t", f.conventionalTo()));
        if (n == 0) {
            throw ApiException.notFound("Plage introuvable");
        }
        journal.log("PLAGE_HORAIRE", Journal.MODIFICATIONS, "Plage horaire modifiée",
                f.label() + " · " + f.conventionalFrom() + " → " + f.conventionalTo(), null, id, null);
        return windows(id);
    }

    @DeleteMapping("/{id}/windows/{windowId}")
    @PreAuthorize("hasAuthority('STATION_EXPLOITER')")
    public List<Map<String, Object>> deleteWindow(@PathVariable long id, @PathVariable long windowId) {
        Auth.checkStation(id);
        db.update("DELETE FROM station_mode_window WHERE id = :w AND station_id = :s", Db.p("s", id, "w", windowId));
        return windows(id);
    }

    // ------------------------------------------------------------------ pistolets, cuves, postes

    public record PumpForm(@NotBlank String code, @NotBlank String port, @NotBlank String productCode,
                           @Pattern(regexp = "LIBRE|DISTRIBUE|HORS_SERVICE") String state) {}

    @PostMapping("/{id}/pumps")
    @PreAuthorize("hasAuthority('STATIONS_GERER')")
    @Transactional
    public Map<String, Object> addPump(@PathVariable long id, @Valid @RequestBody PumpForm f) {
        db.update("""
                \sINSERT INTO pump (station_id, code, port, product_id, state)
                VALUES (:s, :c, :p, (SELECT id FROM product WHERE code = :pc AND category = 'CARBURANT'), COALESCE(:st, 'LIBRE'))""",
                Db.p("s", id, "c", f.code().trim(), "p", f.port().trim().toUpperCase(), "pc", f.productCode(), "st", f.state()));
        checkPorts(id);
        journal.log("POMPE", Journal.MODIFICATIONS, "Pistolet ajouté", f.code() + " · " + f.port(), null, id, null);
        return get(id);
    }

    @PutMapping("/{id}/pumps/{pumpId}")
    @PreAuthorize("hasAuthority('STATION_EXPLOITER')")
    @Transactional
    public Map<String, Object> updatePump(@PathVariable long id, @PathVariable long pumpId, @Valid @RequestBody PumpForm f) {
        Auth.checkStation(id);
        int n = db.update("""
                \sUPDATE pump SET code = :c, port = :p, state = COALESCE(:st, state), updated_at = now(),
                       product_id = (SELECT id FROM product WHERE code = :pc AND category = 'CARBURANT')
                WHERE id = :pid AND station_id = :s""",
                Db.p("s", id, "pid", pumpId, "c", f.code().trim(), "p", f.port().trim().toUpperCase(), "pc", f.productCode(),
                        "st", f.state()));
        if (n == 0) {
            throw ApiException.notFound("Pistolet introuvable");
        }
        checkPorts(id);
        journal.log("POMPE", Journal.MODIFICATIONS, "Pistolet modifié", f.code() + " · " + f.state(), null, id, null);
        return get(id);
    }

    @DeleteMapping("/{id}/pumps/{pumpId}")
    @PreAuthorize("hasAuthority('STATIONS_GERER')")
    public Map<String, Object> deletePump(@PathVariable long id, @PathVariable long pumpId) {
        if (db.count("SELECT count(*) FROM sale WHERE pump_id = :p", Db.p("p", pumpId)) > 0) {
            throw new ApiException("Ce pistolet a des ventes : passez-le « hors service » au lieu de le supprimer");
        }
        db.update("DELETE FROM pump WHERE id = :p AND station_id = :s", Db.p("s", id, "p", pumpId));
        return get(id);
    }

    public record TankForm(@NotBlank String name, @NotBlank String productCode, @NotNull @Positive BigDecimal capacity,
                           BigDecimal volume, BigDecimal thresholdManual, @Min(0) Integer leadDays) {}

    @PostMapping("/{id}/tanks")
    @PreAuthorize("hasAuthority('STATIONS_GERER')")
    public Map<String, Object> addTank(@PathVariable long id, @Valid @RequestBody TankForm f) {
        db.update("""
                \sINSERT INTO tank (station_id, product_id, name, capacity, volume, threshold_manual, lead_days)
                VALUES (:s, (SELECT id FROM product WHERE code = :pc AND category = 'CARBURANT'), :n, :c, COALESCE(:v, 0), :tm,
                        COALESCE(:ld, 3))""",
                Db.p("s", id, "pc", f.productCode(), "n", f.name().trim(), "c", f.capacity(), "v", f.volume(),
                        "tm", f.thresholdManual(), "ld", f.leadDays()));
        journal.log("CUVE", Journal.MODIFICATIONS, "Cuve ajoutée", f.name(), null, id, null);
        return get(id);
    }

    /** Modification d'une cuve : nom, capacité, seuil manuel (null = seuil automatique), délai de livraison. */
    @PutMapping("/{id}/tanks/{tankId}")
    @PreAuthorize("hasAuthority('STOCKS')")
    public Map<String, Object> updateTank(@PathVariable long id, @PathVariable long tankId, @Valid @RequestBody TankForm f) {
        Auth.checkStation(id);
        int n = db.update("""
                \sUPDATE tank SET name = :n, capacity = :c, threshold_manual = :tm, lead_days = COALESCE(:ld, lead_days),
                       updated_at = now()
                WHERE id = :t AND station_id = :s""",
                Db.p("s", id, "t", tankId, "n", f.name().trim(), "c", f.capacity(), "tm", f.thresholdManual(), "ld", f.leadDays()));
        if (n == 0) {
            throw ApiException.notFound("Cuve introuvable");
        }
        return get(id);
    }

    public record ShiftConfigForm(@NotBlank String name, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {}

    @PostMapping("/{id}/shift-configs")
    @PreAuthorize("hasAuthority('STATION_EXPLOITER')")
    public List<Map<String, Object>> addShiftConfig(@PathVariable long id, @Valid @RequestBody ShiftConfigForm f) {
        Auth.checkStation(id);
        db.update("INSERT INTO shift_config (station_id, name, start_time, end_time) VALUES (:s, :n, :a, :b)",
                Db.p("s", id, "n", f.name().trim(), "a", f.startTime(), "b", f.endTime()));
        return db.list("SELECT id, name, start_time, end_time FROM shift_config WHERE station_id = :s ORDER BY start_time",
                Db.p("s", id));
    }

    @DeleteMapping("/{id}/shift-configs/{configId}")
    @PreAuthorize("hasAuthority('STATION_EXPLOITER')")
    public List<Map<String, Object>> deleteShiftConfig(@PathVariable long id, @PathVariable long configId) {
        Auth.checkStation(id);
        db.update("DELETE FROM shift_config WHERE id = :c AND station_id = :s", Db.p("s", id, "c", configId));
        return db.list("SELECT id, name, start_time, end_time FROM shift_config WHERE station_id = :s ORDER BY start_time",
                Db.p("s", id));
    }

    // ------------------------------------------------------------------ contrôles

    /** Un appareil par port, et pas plus d'appareils que de ports sur le hub. */
    private void checkPorts(long stationId) {
        Map<String, Object> s = db.one("SELECT hub_ports, reader_port FROM station WHERE id = :id", Db.p("id", stationId));
        long pumps = db.count("SELECT count(*) FROM pump WHERE station_id = :id", Db.p("id", stationId));
        int used = (int) pumps + (s.get("readerPort") == null ? 0 : 1);
        int hubPorts = ((Number) s.get("hubPorts")).intValue();
        if (used > hubPorts) {
            throw new ApiException("Le hub n'a que " + hubPorts + " ports : " + used + " appareils déclarés");
        }
        if (s.get("readerPort") != null && db.count("SELECT count(*) FROM pump WHERE station_id = :id AND port = :p",
                Db.p("id", stationId, "p", s.get("readerPort"))) > 0) {
            throw new ApiException("Le port " + s.get("readerPort") + " est déjà utilisé par un pistolet");
        }
    }

    private static void checkDays(List<Integer> days) {
        if (days.stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
            throw new ApiException("Les jours vont de 1 (lundi) à 7 (dimanche)");
        }
    }

    private static void checkCoordinates(StationForm f) {
        if (f.latitude() != null && f.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0
                || f.longitude() != null && f.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new ApiException("Coordonnées GPS invalides");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
