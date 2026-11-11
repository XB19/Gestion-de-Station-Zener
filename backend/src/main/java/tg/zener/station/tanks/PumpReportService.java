package tg.zener.station.tanks;

import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tg.zener.station.common.Db;

/**
 * Relevés d'index des pistolets (équivalent de daily_gun_reports / monthly_gun_reports de LTStation) :
 * pour chaque poste clos, chaque jour et chaque mois écoulé, index de début et de fin, volume compteur
 * et volume vendu sur le pistolet. L'écart (compteur − ventes) révèle une distribution non enregistrée.
 * <p>
 * L'index à un instant t se déduit de l'index actuel du pistolet moins les ventes postérieures à t ;
 * un relevé journalier commence à l'index de fin du jour précédent, si bien qu'une avance du compteur
 * hors vente (signal du PC station, essai de pompe) apparaît dans l'écart du jour où elle a eu lieu.
 */
@Service
public class PumpReportService {

    private static final Logger log = LoggerFactory.getLogger(PumpReportService.class);
    /** Profondeur du rattrapage (jours) lors de la première génération. */
    static final int BACKFILL_DAYS = 62;

    /** Index d'un pistolet à l'instant :t (ventes postérieures retranchées de l'index actuel). */
    private static String indexAt(String t) {
        return " (p.index_total - COALESCE((SELECT sum(l.quantity) FROM sale x JOIN sale_line l ON l.sale_id = x.id"
                + " WHERE x.pump_id = p.id AND x.at >= " + t + "), 0))";
    }

    private static final String WINDOW_SALES = """
            \sLEFT JOIN LATERAL (
                SELECT count(DISTINCT x.id) AS n, sum(l.quantity) AS vol, sum(l.amount) AS amount
                FROM sale x JOIN sale_line l ON l.sale_id = x.id
                WHERE x.pump_id = p.id AND x.at >= :a AND x.at < :b) sv ON true""";

    private final Db db;

    public PumpReportService(Db db) {
        this.db = db;
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "${app.timezone}")
    public void job() {
        try {
            generate();
        } catch (Exception e) {
            log.warn("Relevés des pistolets : {}", e.getMessage());
        }
    }

    /** Génère les relevés manquants (postes clos, jours et mois écoulés). Renvoie le nombre de lignes créées. */
    public synchronized int generate() {
        return shifts() + days() + months();
    }

    /** Postes clos depuis moins de BACKFILL_DAYS jours sans relevé : pistolets du poste ou ayant servi pendant le poste. */
    int shifts() {
        int n = 0;
        for (Map<String, Object> es : db.list("""
                \sSELECT es.id, es.started_at, es.ended_at FROM employee_shift es
                WHERE es.status = 'CLOS' AND es.ended_at IS NOT NULL AND es.ended_at > now() - make_interval(days => :d)
                  AND NOT EXISTS (SELECT 1 FROM pump_report r WHERE r.employee_shift_id = es.id)
                ORDER BY es.ended_at LIMIT 500""", Db.p("d", BACKFILL_DAYS))) {
            n += db.update("""
                    \sINSERT INTO pump_report (pump_id, station_id, product_id, kind, period_start, employee_shift_id, started_at, ended_at,
                                             start_index, end_index, meter_volume, sold_volume, gap, transactions, amount)
                    SELECT p.id, p.station_id, p.product_id, 'POSTE', CAST(:a AS date), es.id, :a, :b,
                           idx.s, idx.e, idx.e - idx.s, COALESCE(sv.vol, 0), (idx.e - idx.s) - COALESCE(sv.vol, 0),
                           COALESCE(sv.n, 0), COALESCE(sv.amount, 0)
                    FROM employee_shift es JOIN pump p ON p.station_id = es.station_id""" + WINDOW_SALES + """
                     \sCROSS JOIN LATERAL (SELECT""" + indexAt(":a") + " AS s," + indexAt(":b") + """
                     \sAS e) idx
                    WHERE es.id = :id
                      AND (p.code = ANY(string_to_array(replace(COALESCE(es.pumps, ''), ' ', ''), ','))
                           OR EXISTS (SELECT 1 FROM sale y WHERE y.employee_shift_id = es.id AND y.pump_id = p.id))
                    ON CONFLICT DO NOTHING""",
                    Db.p("id", es.get("id"), "a", ts(es.get("startedAt")), "b", ts(es.get("endedAt"))));
        }
        return n;
    }

    /** Jours écoulés sans relevé, du plus ancien au plus récent (l'index de début reprend la fin de la veille). */
    int days() {
        LocalDate today = LocalDate.now();
        LocalDate first = db.value("""
                \sSELECT greatest(CAST(min(x.at) AS date), CAST(:f AS date)) FROM sale x WHERE x.pump_id IS NOT NULL""",
                Db.p("f", today.minusDays(BACKFILL_DAYS)), LocalDate.class);
        if (first == null) {
            return 0;
        }
        long pumps = db.count("SELECT count(*) FROM pump", Map.of());
        int n = 0;
        for (LocalDate d = first; d.isBefore(today); d = d.plusDays(1)) {
            if (db.count("SELECT count(*) FROM pump_report WHERE kind = 'JOUR' AND period_start = :d", Db.p("d", d)) >= pumps) {
                continue;
            }
            n += db.update("""
                    \sINSERT INTO pump_report (pump_id, station_id, product_id, kind, period_start, started_at, ended_at,
                                             start_index, end_index, meter_volume, sold_volume, gap, transactions, amount)
                    SELECT p.id, p.station_id, p.product_id, 'JOUR', :d, :a, :b,
                           COALESCE(prev.end_index, idx.s), idx.e, idx.e - COALESCE(prev.end_index, idx.s), COALESCE(sv.vol, 0),
                           (idx.e - COALESCE(prev.end_index, idx.s)) - COALESCE(sv.vol, 0), COALESCE(sv.n, 0), COALESCE(sv.amount, 0)
                    FROM pump p
                    LEFT JOIN pump_report prev ON prev.pump_id = p.id AND prev.kind = 'JOUR' AND prev.period_start = :d - 1""" + WINDOW_SALES + """
                     \sCROSS JOIN LATERAL (SELECT""" + indexAt(":a") + " AS s," + indexAt(":b") + """
                     \sAS e) idx
                    WHERE NOT EXISTS (SELECT 1 FROM pump_report r WHERE r.pump_id = p.id AND r.kind = 'JOUR' AND r.period_start = :d)
                    ON CONFLICT DO NOTHING""",
                    Db.p("d", d, "a", d, "b", d.plusDays(1)));
        }
        return n;
    }

    /** Mois écoulés : cumul des relevés journaliers du mois. */
    int months() {
        return db.update("""
                \sINSERT INTO pump_report (pump_id, station_id, product_id, kind, period_start, started_at, ended_at,
                                         start_index, end_index, meter_volume, sold_volume, gap, transactions, amount)
                SELECT j.pump_id, j.station_id, j.product_id, 'MOIS', j.month, min(j.started_at), max(j.ended_at),
                       (array_agg(j.start_index ORDER BY j.period_start))[1], (array_agg(j.end_index ORDER BY j.period_start DESC))[1],
                       sum(j.meter_volume), sum(j.sold_volume), sum(j.gap), sum(j.transactions), sum(j.amount)
                FROM (SELECT r.*, CAST(date_trunc('month', r.period_start) AS date) AS month FROM pump_report r WHERE r.kind = 'JOUR') j
                WHERE j.month < CAST(date_trunc('month', current_date) AS date)
                  AND NOT EXISTS (SELECT 1 FROM pump_report m WHERE m.pump_id = j.pump_id AND m.kind = 'MOIS' AND m.period_start = j.month)
                GROUP BY j.pump_id, j.station_id, j.product_id, j.month
                ON CONFLICT DO NOTHING""", Map.of());
    }

    private static Object ts(Object instant) {
        return instant instanceof java.time.Instant i ? java.sql.Timestamp.from(i) : instant;
    }
}
