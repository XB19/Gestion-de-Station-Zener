package tg.zener.station.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.Db;
import tg.zener.station.common.Mailer;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.stations.StationService;

/**
 * Rapports journaliers générés automatiquement pour chaque station (le gérant les commente
 * au lieu de les rédiger) et résumé quotidien envoyé par email à la direction et aux gérants.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH'h'mm");

    private final Db db;
    private final Settings settings;
    private final StationService stations;
    private final Mailer mailer;
    private final Journal journal;

    public ReportService(Db db, Settings settings, StationService stations, Mailer mailer, Journal journal) {
        this.db = db;
        this.settings = settings;
        this.stations = stations;
        this.mailer = mailer;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ génération

    /** Calcule (ou recalcule) le rapport d'une station pour un jour. Un rapport déjà commenté garde son commentaire. */
    @Transactional
    public long generate(long stationId, LocalDate day) {
        Map<String, Object> data = compute(stationId, day);
        boolean offline = Boolean.TRUE.equals(data.get("offline"));
        Long id = db.value("""
                \sINSERT INTO daily_report (station_id, day, data, status)
                VALUES (:s, :d, CAST(:data AS jsonb), :st)
                ON CONFLICT (station_id, day) DO UPDATE SET data = EXCLUDED.data, generated_at = now(),
                    status = CASE WHEN daily_report.status = 'COMMENTE' THEN 'COMMENTE' ELSE EXCLUDED.status END
                RETURNING id""",
                Db.p("s", stationId, "d", day, "data", db.toJson(data), "st", offline ? "INCOMPLET" : "A_COMMENTER"), Long.class);
        return id;
    }

    Map<String, Object> compute(long stationId, LocalDate day) {
        Map<String, Object> st = db.require("""
                \sSELECT s.id, s.name, s.short_name, s.last_seen_at,
                       (s.last_seen_at IS NULL OR s.last_seen_at < now() - make_interval(secs => :unstable)) AS offline
                FROM station s WHERE s.id = :s""",
                Db.p("s", stationId, "unstable", settings.getLong("station.unstable_seconds", 900)), "Station introuvable");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stationId", stationId);
        d.put("stationName", st.get("name"));
        d.put("day", day.toString());
        d.put("offline", Boolean.TRUE.equals(st.get("offline")) && !day.isBefore(LocalDate.now().minusDays(1)));
        d.put("lastSeenAt", st.get("lastSeenAt"));

        Map<String, Object> today = totals(stationId, day);
        Map<String, Object> prev = totals(stationId, day.minusDays(1));
        d.putAll(today);
        d.put("previous", prev);
        d.put("revenueChangePct", pct(num(today.get("revenue")), num(prev.get("revenue"))));
        d.put("volumeChangePct", pct(dec(today.get("volume")), dec(prev.get("volume"))));
        d.put("transactionsChangePct", pct(num(today.get("transactions")), num(prev.get("transactions"))));

        Map<String, Object> p = Db.p("s", stationId, "d", day);
        d.put("byFuel", db.list("""
                \sSELECT p.code, p.name, sum(sl.quantity) AS liters, sum(sl.amount) AS amount
                FROM sale x JOIN sale_line sl ON sl.sale_id = x.id JOIN product p ON p.id = sl.product_id
                WHERE x.station_id = :s AND x.at >= :d AND x.at < CAST(:d AS date) + 1 AND p.category = 'CARBURANT'
                GROUP BY p.code, p.name ORDER BY p.code DESC""", p));
        d.put("byPayment", pivot(db.list("""
                \sSELECT payment_method AS k, sum(total) AS v FROM sale
                WHERE station_id = :s AND at >= :d AND at < CAST(:d AS date) + 1 GROUP BY payment_method""", p)));
        d.put("byKind", pivot(db.list("""
                \sSELECT kind AS k, sum(total) AS v FROM sale
                WHERE station_id = :s AND at >= :d AND at < CAST(:d AS date) + 1 GROUP BY kind""", p)));
        d.put("litersByHour", stations.litersByHour(stationId, day));
        d.put("shifts", shifts(stationId, day));
        d.put("cashGap", db.sum("""
                \sSELECT COALESCE(sum(COALESCE(expected_cash, 0) - COALESCE(remitted_cash, 0)), 0) FROM employee_shift
                WHERE station_id = :s AND status = 'CLOS' AND started_at >= :d AND started_at < CAST(:d AS date) + 1""", p));
        d.put("openShifts", db.count("""
                \sSELECT count(*) FROM employee_shift WHERE station_id = :s AND status <> 'CLOS'
                  AND started_at < CAST(:d AS date) + 1""", p));
        d.put("incidents", db.one("""
                \sSELECT (SELECT count(*) FROM card_refusal WHERE station_id = :s AND at >= :d AND at < CAST(:d AS date) + 1) AS card_refusals,
                       (SELECT count(*) FROM mode_change WHERE station_id = :s AND at >= :d AND at < CAST(:d AS date) + 1) AS mode_changes,
                       (SELECT count(*) FROM stock_alert WHERE station_id = :s AND raised_at >= :d
                          AND raised_at < CAST(:d AS date) + 1) AS stock_alerts""", p));
        d.put("tanks", db.list("""
                \sSELECT t.name, p.code AS product_code, t.volume, t.capacity, round(100 * t.volume / t.capacity) AS pct
                FROM tank t JOIN product p ON p.id = t.product_id WHERE t.station_id = :s ORDER BY t.id""", p));
        return d;
    }

    private Map<String, Object> totals(long stationId, LocalDate day) {
        return db.one("""
                \sSELECT COALESCE(sum(x.total), 0) AS revenue, count(*) AS transactions,
                       COALESCE((SELECT sum(sl.quantity) FROM sale y JOIN sale_line sl ON sl.sale_id = y.id
                                 JOIN product p ON p.id = sl.product_id
                                 WHERE y.station_id = :s AND y.at >= :d AND y.at < CAST(:d AS date) + 1
                                   AND p.category = 'CARBURANT'), 0) AS volume
                FROM sale x WHERE x.station_id = :s AND x.at >= :d AND x.at < CAST(:d AS date) + 1""",
                Db.p("s", stationId, "d", day));
    }

    /**
     * Rapports de poste : une ligne par poste type de la station (Matin, Soir, Nuit…), limitée
     * au jour calendaire ; sans poste type, une ligne par poste d'employé.
     */
    private List<Map<String, Object>> shifts(long stationId, LocalDate day) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> configs = db.list(
                "SELECT id, name, start_time, end_time FROM shift_config WHERE station_id = :s ORDER BY start_time",
                Db.p("s", stationId));
        for (Map<String, Object> c : configs) {
            LocalTime from = (LocalTime) c.get("startTime");
            LocalTime to = (LocalTime) c.get("endTime");
            String timeCond = from.isBefore(to) ? " CAST(x.at AS time) >= :f AND CAST(x.at AS time) < :t"
                     : " (CAST(x.at AS time) >= :f OR CAST(x.at AS time) < :t)";
            Map<String, Object> p = Db.p("s", stationId, "d", day, "f", from, "t", to, "c", c.get("id"));
            Map<String, Object> row = db.one("""
                    \sSELECT COALESCE(sum(x.total), 0) AS revenue, count(*) AS transactions,
                           COALESCE(sum((SELECT sum(sl.quantity) FROM sale_line sl JOIN product p ON p.id = sl.product_id
                                         WHERE sl.sale_id = x.id AND p.category = 'CARBURANT')), 0) AS liters,
                           count(*) FILTER (WHERE x.mode = 'AUTONOME') AS autonomous,
                           count(*) FILTER (WHERE x.mode = 'CONVENTIONNEL') AS conventional
                    FROM sale x WHERE x.station_id = :s AND x.at >= :d AND x.at < CAST(:d AS date) + 1 AND """ + timeCond, p);
            Map<String, Object> staff = db.one("""
                    \sSELECT (SELECT e.first_name || ' ' || e.last_name FROM employee_shift es JOIN employee e ON e.id = es.employee_id
                            WHERE es.station_id = :s AND es.config_id = :c AND es.started_at >= CAST(:d AS date) - 1
                              AND es.started_at < CAST(:d AS date) + 1
                            ORDER BY (e.role_code = 'CHEF_POSTE') DESC, es.started_at DESC LIMIT 1) AS chief,
                           COALESCE((SELECT sum(COALESCE(es.expected_cash, 0) - COALESCE(es.remitted_cash, 0))
                                     FROM employee_shift es WHERE es.station_id = :s AND es.config_id = :c AND es.status = 'CLOS'
                                       AND es.started_at >= :d AND es.started_at < CAST(:d AS date) + 1), 0) AS gap,
                           (SELECT count(*) FROM employee_shift es WHERE es.station_id = :s AND es.config_id = :c
                              AND es.status <> 'CLOS' AND es.started_at < CAST(:d AS date) + 1) AS open_count""", p);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("configId", c.get("id"));
            r.put("name", c.get("name"));
            r.put("label", c.get("name") + " " + from.format(HM).replace("h00", "h") + "–" + to.format(HM).replace("h00", "h"));
            r.put("from", from);
            r.put("to", to);
            r.put("chief", staff.get("chief"));
            r.putAll(row);
            r.put("gap", staff.get("gap"));
            r.put("closed", num(staff.get("openCount")) == 0);
            long auto = num(row.get("autonomous"));
            long conv = num(row.get("conventional"));
            String mode = auto == 0 && conv == 0 ? null : auto > conv ? "AUTONOME" : "CONVENTIONNEL";
            if (mode == null && !stations.schedule(stationId).isEmpty()) {
                mode = stations.schedule(stationId).modeAt(LocalDateTime.of(day, from.plusMinutes(1)));
            }
            r.put("mode", mode);
            r.put("modeLabel", mode == null ? null : StationService.modeLabel(mode));
            r.remove("autonomous");
            r.remove("conventional");
            out.add(r);
        }
        if (configs.isEmpty()) {
            for (Map<String, Object> es : db.list("""
                    \sSELECT es.id, e.first_name || ' ' || e.last_name AS chief, es.started_at, es.ended_at, es.status,
                           COALESCE(es.expected_cash, 0) - COALESCE(es.remitted_cash, 0) AS gap,
                           COALESCE((SELECT sum(total) FROM sale WHERE employee_shift_id = es.id), 0) AS revenue,
                           (SELECT count(*) FROM sale WHERE employee_shift_id = es.id) AS transactions,
                           COALESCE((SELECT sum(sl.quantity) FROM sale x JOIN sale_line sl ON sl.sale_id = x.id
                                     JOIN product p ON p.id = sl.product_id
                                     WHERE x.employee_shift_id = es.id AND p.category = 'CARBURANT'), 0) AS liters
                    FROM employee_shift es JOIN employee e ON e.id = es.employee_id
                    WHERE es.station_id = :s AND es.started_at >= :d AND es.started_at < CAST(:d AS date) + 1
                    ORDER BY es.started_at""", Db.p("s", stationId, "d", day))) {
                es.put("label", "Poste de " + es.get("chief"));
                es.put("closed", "CLOS".equals(es.get("status")));
                out.add(es);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tâches planifiées

    /**
     * Chaque minute : à partir de l'heure report.daily_time, génère les rapports du jour manquants ;
     * rattrape aussi ceux de la veille (serveur arrêté à l'heure prévue). Envoie le résumé email
     * à email.summary_time.
     */
    @Scheduled(cron = "0 * * * * *", zone = "${app.timezone}")
    public void runDue() {
        LocalDateTime now = stations.now();
        try {
            LocalTime reportTime = settings.getTime("report.daily_time", LocalTime.of(23, 59));
            int n = 0;
            if (!now.toLocalTime().isBefore(reportTime)) {
                n += generateMissing(now.toLocalDate());
            }
            n += generateMissing(now.toLocalDate().minusDays(1));
            if (n > 0) {
                journal.logAs(null, "RAPPORTS_GENERES", Journal.SYSTEME, "Rapports journaliers générés",
                        n + (n > 1 ? " stations" : " station"), null, null, null);
            }
            LocalTime summaryTime = settings.getTime("email.summary_time", LocalTime.of(23, 59));
            String last = settings.get("email.summary_last_day", "");
            if (!now.toLocalTime().isBefore(summaryTime) && !now.toLocalDate().toString().equals(last)) {
                sendSummary(now.toLocalDate());
            }
        } catch (Exception e) {
            log.error("Génération des rapports impossible", e);
        }
    }

    /** Rapports manquants d'un jour, pour les stations ouvertes ce jour-là. */
    public int generateMissing(LocalDate day) {
        int n = 0;
        for (Map<String, Object> s : db.list("""
                \sSELECT s.id FROM station s
                WHERE s.active AND COALESCE(s.opened_on, CAST(s.created_at AS date)) <= :d
                  AND CAST(s.created_at AS date) <= :d
                  AND NOT EXISTS (SELECT 1 FROM daily_report r WHERE r.station_id = s.id AND r.day = :d)""", Db.p("d", day))) {
            generate(((Number) s.get("id")).longValue(), day);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ résumé quotidien par email

    public record Summary(String subject, String body, List<String> recipients) {}

    public Summary summary(LocalDate day) {
        Map<String, Object> p = Db.p("d", day);
        Map<String, Object> t = db.one("""
                \sSELECT COALESCE(sum(total) FILTER (WHERE at >= :d AND at < CAST(:d AS date) + 1), 0) AS revenue,
                       COALESCE(sum(total) FILTER (WHERE at >= CAST(:d AS date) - 1 AND at < :d), 0) AS revenue_prev,
                       count(*) FILTER (WHERE at >= :d AND at < CAST(:d AS date) + 1) AS transactions
                FROM sale WHERE at >= CAST(:d AS date) - 1 AND at < CAST(:d AS date) + 1""", p);
        BigDecimal volume = db.value("""
                \sSELECT COALESCE(sum(sl.quantity), 0) FROM sale x JOIN sale_line sl ON sl.sale_id = x.id
                JOIN product p ON p.id = sl.product_id
                WHERE x.at >= :d AND x.at < CAST(:d AS date) + 1 AND p.category = 'CARBURANT'""", p, BigDecimal.class);
        List<Map<String, Object>> byStation = db.list("""
                \sSELECT s.short_name, COALESCE(sum(x.total), 0) AS revenue,
                       COALESCE(sum((SELECT sum(sl.quantity) FROM sale_line sl JOIN product p ON p.id = sl.product_id
                                     WHERE sl.sale_id = x.id AND p.category = 'CARBURANT')), 0) AS liters
                FROM station s LEFT JOIN sale x ON x.station_id = s.id AND x.at >= :d AND x.at < CAST(:d AS date) + 1
                WHERE s.active GROUP BY s.id, s.short_name ORDER BY revenue DESC""", p);
        List<Map<String, Object>> alerts = db.list("""
                \sSELECT a.severity, p.name AS product_name, COALESCE(s.short_name, pv.name) AS place
                FROM stock_alert a JOIN product p ON p.id = a.product_id
                LEFT JOIN station s ON s.id = a.station_id LEFT JOIN pdv pv ON pv.id = a.pdv_id
                WHERE a.resolved_at IS NULL ORDER BY a.severity, a.raised_at""");
        Map<String, Object> v = db.one("""
                \sSELECT count(*) AS n, COALESCE(sum(amount), 0) AS amount FROM validation_request WHERE status = 'EN_ATTENTE'""", Map.of());
        long pdvRevenue = db.sum("""
                \sSELECT COALESCE(sum(total), 0) FROM sale WHERE pdv_id IS NOT NULL AND at >= :d AND at < CAST(:d AS date) + 1""", p);
        long toComment = db.count("SELECT count(*) FROM daily_report WHERE status <> 'COMMENTE' AND day >= CAST(:d AS date) - 7", p);

        long revenue = num(t.get("revenue"));
        StringBuilder b = new StringBuilder();
        b.append("Résumé de la journée du ").append(day.format(DAY)).append("\n\n");
        b.append("Recette des stations : ").append(fcfa(revenue));
        Double change = pct(revenue, num(t.get("revenuePrev")));
        if (change != null) {
            b.append(" (").append(change >= 0 ? "+" : "").append(String.format(java.util.Locale.ROOT, "%.1f", change).replace('.', ',')).append(" % vs veille)");
        }
        b.append("\nVolume distribué : ").append(qty(volume)).append(" L");
        b.append("\nTransactions : ").append(t.get("transactions"));
        if (pdvRevenue > 0) {
            b.append("\nVentes de cartes en points de vente : ").append(fcfa(pdvRevenue));
        }
        b.append("\n\nPar station :\n");
        for (Map<String, Object> s : byStation) {
            b.append("  - ").append(s.get("shortName")).append(" : ").append(fcfa(num(s.get("revenue"))))
                    .append(" · ").append(qty(dec(s.get("liters")))).append(" L\n");
        }
        b.append("\nAlertes de stock ouvertes : ").append(alerts.size());
        for (Map<String, Object> a : alerts.subList(0, Math.min(alerts.size(), 10))) {
            b.append("\n  - ").append("CRITIQUE".equals(a.get("severity")) ? "[critique] " : "[bas] ")
                    .append(a.get("productName")).append(" · ").append(a.get("place"));
        }
        b.append("\n\nValidations en attente : ").append(v.get("n"));
        if (num(v.get("n")) > 0) {
            b.append(" · ").append(fcfa(num(v.get("amount"))));
        }
        b.append("\nRapports journaliers à commenter (7 derniers jours) : ").append(toComment);
        b.append("\n\n— ZENER Station Manager");

        LinkedHashSet<String> to = new LinkedHashSet<>(settings.getList("email.summary_recipients"));
        if (settings.getBool("email.summary_to_managers", true)) {
            db.list("SELECT email FROM employee WHERE active AND role_code = 'GERANT' AND email IS NOT NULL")
                    .forEach(r -> to.add((String) r.get("email")));
        }
        return new Summary("ZENER · Résumé du " + day.format(DAY) + " · " + fcfa(revenue), b.toString(), new ArrayList<>(to));
    }

    @Transactional
    public long sendSummary(LocalDate day) {
        Summary s = summary(day);
        long id = mailer.queue(s.recipients(), s.subject(), s.body(), "RESUME_QUOTIDIEN");
        settings.set("email.summary_last_day", day.toString());
        journal.logAs(null, "RESUME_EMAIL", Journal.SYSTEME, "Résumé quotidien envoyé",
                s.recipients().size() + " destinataires", null, null, null);
        return id;
    }

    // ------------------------------------------------------------------ utilitaires

    private static Map<String, Object> pivot(List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        rows.forEach(r -> m.put((String) r.get("k"), r.get("v")));
        return m;
    }

    static Double pct(double now, double before) {
        return before == 0 ? null : Math.round((now - before) / before * 1000) / 10.0;
    }

    static Double pct(BigDecimal now, BigDecimal before) {
        return pct(now.doubleValue(), before.doubleValue());
    }

    static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    static String fcfa(long amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount).replace(',', ' ') + " FCFA";
    }

    static String qty(BigDecimal q) {
        return String.format(java.util.Locale.ROOT, "%,d", q.setScale(0, java.math.RoundingMode.HALF_UP).longValue()).replace(',', ' ');
    }
}
