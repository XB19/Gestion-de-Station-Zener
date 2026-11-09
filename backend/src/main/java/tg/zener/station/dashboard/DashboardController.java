package tg.zener.station.dashboard;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.JournalController;
import tg.zener.station.security.Auth;
import tg.zener.station.stations.StationController;

/**
 * Tableau de bord (écran 02) : recette et volume de la période comparés à la période précédente
 * au même moment, stations en ligne, validations en attente, répartition des recettes,
 * courbes cumulées, flux récent, état des stations, points de vente ouverts et alertes de stock.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final Db db;
    private final StationController stations;
    private final ZoneId zone;
    private final tg.zener.station.common.Settings settings;

    public DashboardController(Db db, StationController stations, @Value("${app.timezone}") String zone,
                               tg.zener.station.common.Settings settings) {
        this.settings = settings;
        this.db = db;
        this.stations = stations;
        this.zone = ZoneId.of(zone);
    }

    /** Bornes d'une période et de la période précédente, arrêtée au même moment. */
    record Range(LocalDateTime from, LocalDateTime to, LocalDateTime prevFrom, LocalDateTime prevTo, boolean hourly, int buckets) {}

    static Range range(String period, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return switch (period == null ? "JOUR" : period.toUpperCase()) {
            case "JOUR" -> new Range(today.atStartOfDay(), now, today.minusDays(1).atStartOfDay(), now.minusDays(1), true, 24);
            case "SEMAINE" -> new Range(today.minusDays(6).atStartOfDay(), now, today.minusDays(13).atStartOfDay(), now.minusDays(7),
                    false, 7);
            case "MOIS" -> {
                LocalDateTime from = today.withDayOfMonth(1).atStartOfDay();
                yield new Range(from, now, from.minusMonths(1), now.minusMonths(1), false, today.lengthOfMonth());
            }
            default -> throw new ApiException("Période inconnue : JOUR, SEMAINE ou MOIS");
        };
    }

    @GetMapping
    public Map<String, Object> dashboard(@RequestParam(required = false) String period) {
        Long scope = Auth.scopedStation(null);
        LocalDateTime now = ZonedDateTime.now(zone).toLocalDateTime();
        Range r = range(period, now);
        Map<String, Object> p = Db.p("s", scope, "f", r.from(), "t", r.to(), "pf", r.prevFrom(), "pt", r.prevTo(),
                "offline", settings.getLong("pdv.offline_seconds", 300));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period == null ? "JOUR" : period.toUpperCase());
        out.put("from", r.from());
        out.put("to", r.to());
        out.put("generatedAt", now);

        // ------------------------------------------------ recette et volume
        String stationFilter = " (CAST(:s AS bigint) IS NULL OR x.station_id = :s)";
        Map<String, Object> rev = db.one("""
                \sSELECT COALESCE(sum(x.total) FILTER (WHERE x.at >= :f AND x.at < :t), 0) AS revenue,
                       COALESCE(sum(x.total) FILTER (WHERE x.at >= :pf AND x.at < :pt), 0) AS revenue_prev,
                       count(*) FILTER (WHERE x.at >= :f AND x.at < :t) AS transactions,
                       count(*) FILTER (WHERE x.at >= :pf AND x.at < :pt) AS transactions_prev
                FROM sale x WHERE x.at >= :pf AND x.at < :t AND """ + stationFilter, p);
        long revenue = num(rev.get("revenue"));
        long revenuePrev = num(rev.get("revenuePrev"));
        out.put("revenue", Map.of("value", revenue, "previous", revenuePrev, "changePct", pctOrZero(revenue, revenuePrev),
                "transactions", rev.get("transactions"), "transactionsPrev", rev.get("transactionsPrev")));

        List<Map<String, Object>> fuels = db.list("""
                \sSELECT p.code, p.name,
                       COALESCE(sum(sl.quantity) FILTER (WHERE x.at >= :f AND x.at < :t), 0) AS liters,
                       COALESCE(sum(sl.quantity) FILTER (WHERE x.at >= :pf AND x.at < :pt), 0) AS liters_prev
                FROM sale x JOIN sale_line sl ON sl.sale_id = x.id JOIN product p ON p.id = sl.product_id
                WHERE p.category = 'CARBURANT' AND x.at >= :pf AND x.at < :t AND """ + stationFilter + """
                 \sGROUP BY p.code, p.name ORDER BY liters DESC""", p);
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal volumePrev = BigDecimal.ZERO;
        for (Map<String, Object> f : fuels) {
            volume = volume.add((BigDecimal) f.get("liters"));
            volumePrev = volumePrev.add((BigDecimal) f.get("litersPrev"));
        }
        for (Map<String, Object> f : fuels) {
            f.put("sharePct", volume.signum() == 0 ? 0
                    : Math.round(((BigDecimal) f.get("liters")).doubleValue() * 100 / volume.doubleValue()));
        }
        out.put("volume", Map.of("value", volume, "previous", volumePrev,
                "changePct", pctOrZero(volume.doubleValue(), volumePrev.doubleValue()), "byFuel", fuels));

        // ------------------------------------------------ stations
        Map<String, Object> st = stations.summary();
        out.put("stations", Map.of("total", st.get("total"), "online", st.get("online"), "unstable", st.get("unstable"),
                "offline", st.get("offline"), "autonomous", st.get("autonomous")));

        // ------------------------------------------------ validations
        out.put("validations", scope != null ? Map.of("pending", 0, "amount", 0) : db.one("""
                \sSELECT count(*) AS pending, COALESCE(sum(amount), 0) AS amount,
                       count(*) FILTER (WHERE requested_at > now() - interval '10 minutes') AS last_10_min
                FROM validation_request WHERE status = 'EN_ATTENTE'""", Map.of()));

        // ------------------------------------------------ répartition des recettes
        Map<String, Object> split = db.one("""
                \sSELECT COALESCE(sum(x.total) FILTER (WHERE x.kind = 'CARBURANT'), 0) AS fuel,
                       COALESCE(sum(x.total) FILTER (WHERE x.kind = 'CARTE'), 0) AS cards,
                       COALESCE(sum(x.total) FILTER (WHERE x.kind IN ('BOUTIQUE', 'ECHANGE_GAZ')), 0) AS shop
                FROM sale x WHERE x.at >= :f AND x.at < :t AND """ + stationFilter, p);
        long recharges = db.sum("""
                \sSELECT COALESCE(sum(amount), 0) FROM recharge
                WHERE status = 'CREDITEE' AND credited_at >= :f AND credited_at < :t
                  AND (CAST(:s AS bigint) IS NULL OR station_id = :s)""", p);
        long fuel = num(split.get("fuel"));
        long cards = num(split.get("cards")) + recharges;
        long shop = num(split.get("shop"));
        long all = fuel + cards + shop;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        breakdown.add(part("CARBURANT", "Carburant", fuel, all));
        breakdown.add(part("CARTES_RECHARGES", "Cartes & recharges", cards, all));
        breakdown.add(part("GAZ_LUBRIFIANTS", "Gaz & lubrifiants", shop, all));
        out.put("breakdown", breakdown);
        out.put("recharges", recharges);

        // ------------------------------------------------ courbes cumulées
        out.put("curves", curves(r, p, stationFilter, now));

        // ------------------------------------------------ flux, stations, PDV, alertes
        out.put("feed", db.list(JournalController.SELECT
                + " WHERE (CAST(:s AS bigint) IS NULL OR o.station_id = :s) ORDER BY o.at DESC, o.id DESC LIMIT 10", p));
        List<Map<String, Object>> list = stations.list(null, null, null, null, null, null, null);
        out.put("stationStates", list.subList(0, Math.min(list.size(), 8)));
        out.put("pdv", scope != null ? List.of() : db.list("""
                \sSELECT p.id, p.code, p.name, p.kind, p.status, p.end_date, p.last_sync_at,
                       CASE WHEN p.end_date IS NOT NULL THEN p.end_date - current_date END AS days_left,
                       (p.kind = 'TEMPORAIRE' AND (p.last_sync_at IS NULL
                            OR p.last_sync_at < now() - make_interval(secs => CAST(:offline AS int)))) AS offline,
                       COALESCE((SELECT sum(total) FROM sale WHERE pdv_id = p.id AND at >= :f AND at < :t), 0) AS amount,
                       (SELECT count(*) FROM pdv_card pc WHERE pc.pdv_id = p.id) AS cards_allocated,
                       (SELECT count(*) FROM pdv_card pc WHERE pc.pdv_id = p.id AND pc.sold_at IS NOT NULL) AS cards_sold,
                       COALESCE(round(100.0 * (SELECT count(*) FROM pdv_card pc WHERE pc.pdv_id = p.id AND pc.sold_at IS NOT NULL)
                                      / NULLIF((SELECT count(*) FROM pdv_card pc WHERE pc.pdv_id = p.id), 0)), 0) AS sold_pct
                FROM pdv p WHERE p.status = 'OUVERT' ORDER BY p.kind, amount DESC""", p));
        out.put("stockAlerts", db.list("""
                \sSELECT a.id, a.station_id, COALESCE(s.short_name, pv.name) AS place, p.name AS product_name, p.category,
                       a.level, a.threshold, a.severity, a.raised_at, a.tank_id
                FROM stock_alert a JOIN product p ON p.id = a.product_id
                LEFT JOIN station s ON s.id = a.station_id LEFT JOIN pdv pv ON pv.id = a.pdv_id
                WHERE a.resolved_at IS NULL AND (CAST(:s AS bigint) IS NULL OR a.station_id = :s)
                ORDER BY a.severity, a.raised_at DESC LIMIT 6""", p));
        return out;
    }

    /** Recette cumulée par heure (période JOUR) ou par jour, période en cours vs précédente. */
    private Map<String, Object> curves(Range r, Map<String, Object> p, String stationFilter, LocalDateTime now) {
        String bucket = r.hourly() ? "CAST(extract(hour FROM x.at) AS int)"
                : "CAST(extract(day FROM x.at - CAST(:base AS timestamp)) AS int)";
        Map<String, Object> q = new LinkedHashMap<>(p);
        long[] current = new long[r.buckets()];
        long[] previous = new long[r.buckets()];
        for (int k = 0; k < 2; k++) {
            q.put("base", k == 0 ? r.from() : r.prevFrom());
            q.put("a", k == 0 ? r.from() : r.prevFrom());
            // période en cours jusqu'à maintenant ; période précédente entière (la veille complète, par exemple)
            q.put("b", k == 0 ? r.to() : r.hourly() ? r.prevFrom().plusDays(1) : r.from());
            long[] target = k == 0 ? current : previous;
            for (Map<String, Object> row : db.list("SELECT " + bucket + " AS b, sum(x.total) AS v FROM sale x WHERE x.at >= :a AND x.at < :b AND "
                    + stationFilter + " GROUP BY 1", q)) {
                int i = ((Number) row.get("b")).intValue();
                if (i >= 0 && i < target.length) {
                    target[i] += num(row.get("v"));
                }
            }
        }
        int last = r.hourly() ? now.getHour() : (int) Duration.between(r.from(), now).toDays();
        List<Object> cur = new ArrayList<>();
        List<Object> prev = new ArrayList<>();
        long c = 0;
        long pv = 0;
        for (int i = 0; i < r.buckets(); i++) {
            c += current[i];
            pv += previous[i];
            cur.add(i <= last ? c : null); // pas de valeur pour les heures à venir
            prev.add(pv);
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < r.buckets(); i++) {
            labels.add(r.hourly() ? String.format("%02dh", i) : r.from().toLocalDate().plusDays(i).toString());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit", r.hourly() ? "HEURE" : "JOUR");
        m.put("labels", labels);
        m.put("current", cur);
        m.put("previous", prev);
        m.put("cumulatedAt", now);
        return m;
    }

    private static Map<String, Object> part(String code, String label, long amount, long total) {
        return Map.of("code", code, "label", label, "amount", amount,
                "pct", total == 0 ? 0 : Math.round(amount * 1000.0 / total) / 10.0);
    }

    static double pctOrZero(double now, double before) {
        return before == 0 ? 0 : Math.round((now - before) / before * 1000) / 10.0;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
