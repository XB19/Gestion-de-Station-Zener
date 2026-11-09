package tg.zener.station.stock;

import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;

/**
 * Seuils d'alerte ajustés automatiquement selon la tendance de consommation :
 * seuil = consommation moyenne des 7 derniers jours × (délai de livraison + jours de marge),
 * majorée de la hausse de tendance si la consommation augmente. Recalculés chaque nuit.
 */
@Service
public class ThresholdService {

    private final Db db;
    private final Settings settings;
    private final StockService stock;

    public ThresholdService(Db db, Settings settings, StockService stock) {
        this.db = db;
        this.settings = settings;
        this.stock = stock;
    }

    /** Seuil automatique : moyenne journalière × couverture, majorée si la tendance est à la hausse. */
    static double threshold(double last7, double prev7, int leadDays, long safetyDays) {
        double daily = last7 / 7.0;
        double growth = prev7 > 0 ? Math.max(0, (last7 - prev7) / prev7) : 0;
        return Math.ceil(daily * (1 + growth) * (leadDays + safetyDays));
    }

    static Double trend(double last7, double prev7) {
        return prev7 > 0 ? Math.round((last7 - prev7) / prev7 * 1000) / 10.0 : null;
    }

    @Scheduled(cron = "0 30 1 * * *", zone = "${app.timezone}")
    public void recomputeAll() {
        long safety = settings.getLong("alerts.safety_days", 2);
        for (Map<String, Object> t : db.list("""
                \sSELECT t.id, t.lead_days,
                       COALESCE(-sum(m.quantity) FILTER (WHERE m.at >= now() - interval '7 days'), 0) AS last7,
                       COALESCE(-sum(m.quantity) FILTER (WHERE m.at < now() - interval '7 days'), 0) AS prev7
                FROM tank t LEFT JOIN stock_movement m ON m.tank_id = t.id AND m.kind IN ('VENTE', 'LIVRAISON')
                     AND m.at >= now() - interval '14 days'
                GROUP BY t.id""")) {
            double last7 = ((Number) t.get("last7")).doubleValue();
            double prev7 = ((Number) t.get("prev7")).doubleValue();
            db.update("UPDATE tank SET threshold_auto = :th, trend_pct = :tr WHERE id = :id",
                    Db.p("id", t.get("id"), "th", last7 > 0 ? threshold(last7, prev7, ((Number) t.get("leadDays")).intValue(), safety) : null,
                            "tr", trend(last7, prev7)));
            stock.checkTank(((Number) t.get("id")).longValue());
        }
        for (Map<String, Object> l : db.list("""
                \sSELECT l.id, l.lead_days,
                       COALESCE(-sum(m.quantity) FILTER (WHERE m.at >= now() - interval '7 days'), 0) AS last7,
                       COALESCE(-sum(m.quantity) FILTER (WHERE m.at < now() - interval '7 days'), 0) AS prev7
                FROM stock_level l LEFT JOIN stock_movement m ON m.product_id = l.product_id AND m.tank_id IS NULL
                     AND (m.station_id = l.station_id OR m.pdv_id = l.pdv_id)
                     AND m.kind IN ('VENTE', 'ECHANGE') AND m.at >= now() - interval '14 days'
                GROUP BY l.id""")) {
            double last7 = ((Number) l.get("last7")).doubleValue();
            double prev7 = ((Number) l.get("prev7")).doubleValue();
            db.update("UPDATE stock_level SET threshold_auto = :th, trend_pct = :tr WHERE id = :id",
                    Db.p("id", l.get("id"), "th", last7 > 0 ? threshold(last7, prev7, ((Number) l.get("leadDays")).intValue(), safety) : null,
                            "tr", trend(last7, prev7)));
            stock.checkLevel(((Number) l.get("id")).longValue());
        }
    }
}
