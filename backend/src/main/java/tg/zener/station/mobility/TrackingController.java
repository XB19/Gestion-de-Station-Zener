package tg.zener.station.mobility;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/**
 * Suivi GPS en direct : le téléphone du livreur envoie sa position ; la carte en direct affiche
 * les livreurs et les stations mobiles avec l'ancienneté de leur dernière position.
 */
@RestController
@RequestMapping("/api/tracking")
public class TrackingController {

    private final Db db;
    private final Tracking tracking;
    private final Settings settings;

    public TrackingController(Db db, Tracking tracking, Settings settings) {
        this.db = db;
        this.tracking = tracking;
        this.settings = settings;
    }

    public record Ping(@NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                       @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                       BigDecimal accuracyM, BigDecimal speedKmh, BigDecimal heading, Long deliveryId, Instant at) {}

    /** Position du livreur connecté ; rattachée à sa livraison en cours. */
    @PostMapping("/ping")
    @PreAuthorize("hasAuthority('LIVRER')")
    public Map<String, Object> ping(@Valid @RequestBody Ping p) {
        CurrentUser u = Auth.user();
        Long delivery = p.deliveryId();
        if (delivery != null && db.count("SELECT count(*) FROM delivery_order WHERE id = :d AND agent_id = :a",
                Db.p("d", delivery, "a", u.id())) == 0) {
            throw ApiException.forbidden("Cette livraison est affectée à un autre livreur");
        }
        if (delivery == null) {
            delivery = db.value("SELECT id FROM delivery_order WHERE agent_id = :a AND status = 'EN_ROUTE' LIMIT 1",
                    Db.p("a", u.id()), Long.class);
        }
        tracking.record("LIVREUR", u.id(), delivery, p.latitude(), p.longitude(), p.accuracyM(), p.speedKmh(), p.heading(), p.at(),
                u.name());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("deliveryId", delivery);
        return res;
    }

    /** Dernières positions des livreurs (24 h) et des stations mobiles, avec leur ancienneté. */
    @GetMapping("/live")
    @PreAuthorize("hasAuthority('LIVRAISONS') or hasAuthority('STATIONS_GERER') or hasAuthority('STATION_EXPLOITER')")
    public Map<String, Object> live() {
        long stale = settings.getLong("mobility.tracking_stale_seconds", 180);
        List<Map<String, Object>> agents = db.list("""
                \sSELECT e.id, e.first_name || ' ' || e.last_name AS name, e.phone,
                       CASE WHEN e.photo_path IS NULL THEN NULL ELSE '/files/' || e.photo_path END AS photo_url,
                       lp.latitude, lp.longitude, lp.speed_kmh, lp.heading, lp.accuracy_m, lp.at,
                       CAST(extract(epoch FROM now() - lp.at) AS int) AS age_seconds,
                       d.id AS delivery_id, d.ref AS delivery_ref, d.status AS delivery_status, d.address AS delivery_address,
                       d.latitude AS destination_latitude, d.longitude AS destination_longitude, d.vehicle
                FROM employee e
                JOIN LATERAL (SELECT * FROM location_ping WHERE subject = 'LIVREUR' AND subject_id = e.id
                              ORDER BY at DESC LIMIT 1) lp ON true
                LEFT JOIN delivery_order d ON d.agent_id = e.id AND d.status = 'EN_ROUTE'
                WHERE e.active AND e.role_code = 'LIVREUR' AND lp.at > now() - interval '24 hours'
                ORDER BY lp.at DESC""");
        Long scope = Auth.user().scope();
        List<Map<String, Object>> mobile = db.list("""
                \sSELECT s.id, s.name, s.short_name, s.mode, COALESCE(lp.latitude, s.latitude) AS latitude,
                       COALESCE(lp.longitude, s.longitude) AS longitude, lp.speed_kmh, lp.heading,
                       COALESCE(lp.at, s.last_seen_at) AS at,
                       CAST(extract(epoch FROM now() - COALESCE(lp.at, s.last_seen_at)) AS int) AS age_seconds
                FROM station s
                LEFT JOIN LATERAL (SELECT * FROM location_ping WHERE subject = 'STATION_MOBILE' AND subject_id = s.id
                                   ORDER BY at DESC LIMIT 1) lp ON true
                WHERE s.active AND s.kind = 'MOBILE' AND (CAST(:scope AS bigint) IS NULL OR s.id = :scope)""",
                Db.p("scope", scope));
        for (List<Map<String, Object>> l : List.of(agents, mobile)) {
            for (Map<String, Object> r : l) {
                Number age = (Number) r.get("ageSeconds");
                r.put("stale", age == null || age.longValue() > stale);
            }
        }
        if (scope != null) {
            agents = List.of(); // un gérant ne suit que sa station mobile
        }
        return Map.of("agents", agents, "mobileStations", mobile, "staleSeconds", stale, "at", Instant.now());
    }
}
