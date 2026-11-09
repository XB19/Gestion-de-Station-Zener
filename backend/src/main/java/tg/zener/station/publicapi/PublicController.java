package tg.zener.station.publicapi;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.Db;
import tg.zener.station.stations.StationService;

/**
 * Informations affichées sur l'écran de connexion (sans authentification) : uniquement des
 * agrégats du réseau et les trois dernières opérations, sans nom de personne.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final Db db;
    private final StationService stations;

    public PublicController(Db db, StationService stations) {
        this.db = db;
        this.stations = stations;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(db.one("""
                \sSELECT COALESCE(sum(total) FILTER (WHERE at >= current_date), 0) AS revenue_today,
                       COALESCE(sum(total) FILTER (WHERE at >= current_date - 1 AND at < now() - interval '1 day'), 0) AS revenue_yesterday
                FROM sale WHERE at >= current_date - 1""", Map.of()));
        m.putAll(db.one("""
                \sSELECT count(*) AS stations_total, count(*) FILTER (WHERE status = 'EN_LIGNE') AS stations_online,
                       count(DISTINCT country) AS countries
                FROM (SELECT s.country, """ + stations.statusSql() + """
                \sAS status FROM station s WHERE s.active) x""", Map.of()));
        m.putAll(db.one("""
                \sSELECT count(*) FILTER (WHERE status = 'ACTIVE') AS active_cards,
                       count(*) FILTER (WHERE status = 'ACTIVE' AND type_code = 'FLOTTE') AS fleet_cards
                FROM card""", Map.of()));
        m.put("licenceEnd", db.value("""
                \sSELECT max(l.end_date) FROM licence l JOIN client c ON c.id = l.client_id
                WHERE c.kind = 'GROUPE' AND l.status = 'ACTIVE'""", Map.of(), java.time.LocalDate.class));
        m.put("feed", db.list("""
                \sSELECT o.at, o.category, o.type, o.title, o.amount, COALESCE(pv.name, s.short_name) AS place
                FROM operation_log o LEFT JOIN station s ON s.id = o.station_id LEFT JOIN pdv pv ON pv.id = o.pdv_id
                WHERE o.category IN ('VENTES_CARBURANT', 'RECHARGES', 'VALIDATIONS', 'VENTES_CARTES')
                  AND o.type NOT IN ('REFUS_CARTE')
                ORDER BY o.at DESC, o.id DESC LIMIT 3"""));
        return m;
    }
}
