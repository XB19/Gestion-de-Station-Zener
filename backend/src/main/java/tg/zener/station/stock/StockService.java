package tg.zener.station.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.Mailer;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;

/**
 * Stocks : cuves de carburant (stations) et articles (lubrifiants, gaz, accessoires, cartes)
 * des stations et points de vente. Chaque mouvement vérifie le seuil d'alerte : sous le seuil,
 * une alerte est ouverte et un email part au gérant et à la logistique.
 */
@Service
public class StockService {

    private final Db db;
    private final Settings settings;
    private final Mailer mailer;
    private final Journal journal;
    private final LiveFeed live;

    public StockService(Db db, Settings settings, Mailer mailer, Journal journal, LiveFeed live) {
        this.db = db;
        this.settings = settings;
        this.mailer = mailer;
        this.journal = journal;
        this.live = live;
    }

    // ------------------------------------------------------------------ carburant (cuves)

    /** Cuve d'un carburant dans une station (la plus remplie). */
    public long tankFor(long stationId, long productId) {
        Long id = db.value("""
                \sSELECT id FROM tank WHERE station_id = :s AND product_id = :p ORDER BY volume DESC, id LIMIT 1""",
                Db.p("s", stationId, "p", productId), Long.class);
        if (id == null) {
            throw new ApiException("Aucune cuve pour ce carburant dans la station");
        }
        return id;
    }

    /** Mouvement sur une cuve (quantité négative pour une sortie). */
    @Transactional
    public void moveTank(long tankId, BigDecimal quantity, String kind, String ref, Long userId, String note) {
        Map<String, Object> t = db.require("""
                \sUPDATE tank SET volume = greatest(volume + :q, 0), updated_at = now() WHERE id = :id
                RETURNING station_id, product_id, volume""", Db.p("id", tankId, "q", quantity), "Cuve introuvable");
        db.update("""
                \sINSERT INTO stock_movement (station_id, product_id, tank_id, quantity, kind, ref, note, user_id)
                VALUES (:s, :p, :t, :q, :k, :r, :n, :u)""",
                Db.p("s", t.get("stationId"), "p", t.get("productId"), "t", tankId, "q", quantity, "k", kind, "r", ref,
                        "n", note, "u", userId));
        checkTank(tankId);
    }

    /** Relevé de jauge envoyé par le PC station : volume mesuré (pas de mouvement comptable). */
    public void setTankVolume(long tankId, BigDecimal volume) {
        db.update("UPDATE tank SET volume = :v, updated_at = now() WHERE id = :id", Db.p("id", tankId, "v", volume));
        checkTank(tankId);
    }

    public void checkTank(long tankId) {
        Map<String, Object> t = db.one("""
                \sSELECT t.id, t.station_id, t.product_id, t.volume, COALESCE(t.threshold_manual, t.threshold_auto) AS threshold,
                       t.trend_pct, p.name AS product_name, s.short_name
                FROM tank t JOIN product p ON p.id = t.product_id JOIN station s ON s.id = t.station_id WHERE t.id = :id""",
                Db.p("id", tankId));
        evaluate(t, (Number) t.get("stationId"), null, tankId, "L");
    }

    // ------------------------------------------------------------------ articles

    /** Mouvement d'un article dans une station ou un point de vente (quantité négative pour une sortie). */
    @Transactional
    public BigDecimal move(Long stationId, Long pdvId, long productId, BigDecimal quantity, String kind, String ref,
                           Long userId, String note) {
        if ((stationId == null) == (pdvId == null)) {
            throw new ApiException("Indiquez une station ou un point de vente");
        }
        String conflict = stationId != null ? "(station_id, product_id) WHERE station_id IS NOT NULL"
                : "(pdv_id, product_id) WHERE pdv_id IS NOT NULL";
        Map<String, Object> level = db.one("""
                \sINSERT INTO stock_level (station_id, pdv_id, product_id, quantity) VALUES (:s, :pv, :p, :q)
                ON CONFLICT """ + conflict + """
                 \sDO UPDATE SET quantity = stock_level.quantity + EXCLUDED.quantity, updated_at = now()
                RETURNING id, quantity""", Db.p("s", stationId, "pv", pdvId, "p", productId, "q", quantity));
        BigDecimal after = (BigDecimal) level.get("quantity");
        if (after.signum() < 0) {
            String name = db.value("SELECT name FROM product WHERE id = :p", Db.p("p", productId), String.class);
            throw new ApiException("Stock insuffisant : " + name + " (" + after.add(quantity.negate()).stripTrailingZeros().toPlainString()
                    + " disponible)");
        }
        db.update("""
                \sINSERT INTO stock_movement (station_id, pdv_id, product_id, quantity, kind, ref, note, user_id)
                VALUES (:s, :pv, :p, :q, :k, :r, :n, :u)""",
                Db.p("s", stationId, "pv", pdvId, "p", productId, "q", quantity, "k", kind, "r", ref, "n", note, "u", userId));
        checkLevel(((Number) level.get("id")).longValue());
        return after;
    }

    public void checkLevel(long levelId) {
        Map<String, Object> l = db.one("""
                \sSELECT l.id, l.station_id, l.pdv_id, l.product_id, l.quantity AS volume,
                       COALESCE(l.threshold_manual, l.threshold_auto) AS threshold, l.trend_pct,
                       p.name AS product_name, COALESCE(s.short_name, pv.name) AS short_name, p.unit
                FROM stock_level l JOIN product p ON p.id = l.product_id
                LEFT JOIN station s ON s.id = l.station_id LEFT JOIN pdv pv ON pv.id = l.pdv_id
                WHERE l.id = :id""", Db.p("id", levelId));
        evaluate(l, (Number) l.get("stationId"), (Number) l.get("pdvId"), null, (String) l.get("unit"));
    }

    /** Ouvre, aggrave ou clôt l'alerte de stock selon le niveau et le seuil. */
    private void evaluate(Map<String, Object> s, Number stationId, Number pdvId, Long tankId, String unit) {
        Long station = stationId == null ? null : stationId.longValue();
        Long pdv = pdvId == null ? null : pdvId.longValue();
        long productId = ((Number) s.get("productId")).longValue();
        BigDecimal level = (BigDecimal) s.get("volume");
        BigDecimal threshold = (BigDecimal) s.get("threshold");
        String match = tankId != null ? "tank_id = :t" : "tank_id IS NULL AND product_id = :p AND "
                + (station != null ? "station_id = :s" : "pdv_id = :pv");
        Map<String, Object> open = db.one("SELECT id, severity FROM stock_alert WHERE resolved_at IS NULL AND " + match,
                Db.p("t", tankId, "p", productId, "s", station, "pv", pdv));
        if (threshold == null || level.compareTo(threshold) >= 0) {
            if (open != null) {
                db.update("UPDATE stock_alert SET resolved_at = now() WHERE id = :id", Db.p("id", open.get("id")));
            }
            return;
        }
        double ratio = settings.getDouble("alerts.critical_ratio", 0.6);
        String severity = level.compareTo(threshold.multiply(BigDecimal.valueOf(ratio))) < 0 ? "CRITIQUE" : "BAS";
        if (open != null) {
            db.update("UPDATE stock_alert SET level = :l, threshold = :th, severity = :sv WHERE id = :id",
                    Db.p("id", open.get("id"), "l", level, "th", threshold, "sv", severity));
            return;
        }
        long alertId = db.insert("""
                \sINSERT INTO stock_alert (station_id, pdv_id, product_id, tank_id, level, threshold, severity)
                VALUES (:s, :pv, :p, :t, :l, :th, :sv)""",
                Db.p("s", station, "pv", pdv, "p", productId, "t", tankId, "l", level, "th", threshold, "sv", severity));
        String where = s.get("productName") + " · " + s.get("shortName");
        String detail = qty(level) + " " + unit + " restants < seuil " + qty(threshold) + " " + unit;
        List<String> to = new ArrayList<>(settings.getList("alerts.logistics_emails"));
        if (station != null) {
            db.list("""
                    \sSELECT email FROM employee WHERE active AND email IS NOT NULL
                      AND (id = (SELECT manager_id FROM station WHERE id = :s) OR (station_id = :s AND role_code = 'GERANT'))""",
                    Db.p("s", station)).forEach(r -> to.add((String) r.get("email")));
        }
        Object trend = s.get("trendPct");
        mailer.queue(to, "Alerte de stock " + severity.toLowerCase() + " · " + where,
                where + " : " + detail + (trend == null ? "" : "\nTendance de consommation sur 7 jours : " + trend + " %")
                        + "\nPensez à réapprovisionner.", "ALERTE_STOCK");
        db.update("UPDATE stock_alert SET emailed_at = now() WHERE id = :id", Db.p("id", alertId));
        journal.logAs(null, "ALERTE_STOCK", Journal.SYSTEME, "Email d'alerte envoyé", where + " · " + detail, null, station, pdv);
        live.publish("alert", station, Map.of("alertId", alertId, "severity", severity, "title", where, "detail", detail));
    }

    static String qty(BigDecimal q) {
        return String.format(java.util.Locale.ROOT, "%,d", q.setScale(0, RoundingMode.HALF_UP).longValue()).replace(',', ' ');
    }

    // ------------------------------------------------------------------ bouteilles de gaz

    /** Ajuste le parc de bouteilles (vides, chez les clients, endommagées) d'une station. */
    public void bottles(long stationId, long productId, int emptyDelta, int customerDelta, int damagedDelta) {
        db.update("""
                \sINSERT INTO gas_bottle_stock (station_id, product_id, empty_count, customer_count, damaged_count)
                VALUES (:s, :p, greatest(:e, 0), greatest(:c, 0), greatest(:d, 0))
                ON CONFLICT (station_id, product_id) DO UPDATE SET
                    empty_count = greatest(gas_bottle_stock.empty_count + :e, 0),
                    customer_count = greatest(gas_bottle_stock.customer_count + :c, 0),
                    damaged_count = greatest(gas_bottle_stock.damaged_count + :d, 0)""",
                Db.p("s", stationId, "p", productId, "e", emptyDelta, "c", customerDelta, "d", damagedDelta));
    }
}
