package tg.zener.station.notifications;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;

/**
 * Niveaux des cuves de carburant en % de la capacité (paramètre alerts.tank_levels, 50,40,30,5 par défaut) :
 * chaque palier franchi vers le bas est notifié une fois, avec une priorité croissante
 * (50 % information, 40 % attention, 30 % urgent : commande à passer, dernier palier critique, rappelé
 * toutes les alerts.critical_repeat_hours heures jusqu'à la livraison). Au-dessus du premier palier
 * (après une livraison), le compteur repart à zéro.
 */
@Service
public class FuelLevelAlerts {

    private final Db db;
    private final Settings settings;
    private final Notifier notifier;

    public FuelLevelAlerts(Db db, Settings settings, Notifier notifier) {
        this.db = db;
        this.settings = settings;
        this.notifier = notifier;
    }

    public List<Integer> tiers() {
        List<Integer> t = new ArrayList<>();
        for (String s : settings.getList("alerts.tank_levels")) {
            try {
                t.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // valeur ignorée
            }
        }
        if (t.isEmpty()) {
            t.addAll(List.of(50, 40, 30, 5));
        }
        t.sort((a, b) -> b - a);
        return t;
    }

    /** Appelé à chaque changement de volume d'une cuve (vente, livraison, jauge). */
    public void check(long tankId) {
        Map<String, Object> t = db.one("""
                \sSELECT t.id, t.name, t.station_id, t.volume, t.capacity, t.alert_tier, t.alert_notified_at, t.trend_pct,
                       p.id AS product_id, p.code AS product_code, p.name AS product_name, s.short_name, s.name AS station_name,
                       (now() - t.alert_notified_at) > make_interval(hours => CAST(:rh AS int)) AS repeat_due
                FROM tank t JOIN product p ON p.id = t.product_id JOIN station s ON s.id = t.station_id WHERE t.id = :id""",
                Db.p("id", tankId, "rh", settings.getLong("alerts.critical_repeat_hours", 4)));
        if (t == null || t.get("capacity") == null || ((BigDecimal) t.get("capacity")).signum() <= 0) {
            return;
        }
        BigDecimal pct = ((BigDecimal) t.get("volume")).multiply(BigDecimal.valueOf(100))
                .divide((BigDecimal) t.get("capacity"), 1, RoundingMode.HALF_UP);
        List<Integer> tiers = tiers();
        Integer reached = null;
        for (Integer tier : tiers) {
            if (pct.compareTo(BigDecimal.valueOf(tier)) < 0) {
                reached = tier;
            }
        }
        Integer current = t.get("alertTier") == null ? null : ((Number) t.get("alertTier")).intValue();
        if (reached == null) {
            if (current != null) {
                db.update("UPDATE tank SET alert_tier = NULL, alert_notified_at = NULL WHERE id = :id", Db.p("id", tankId));
            }
            return;
        }
        int last = tiers.get(tiers.size() - 1);
        boolean worse = current == null || reached < current;
        boolean repeat = reached == last && Boolean.TRUE.equals(t.get("repeatDue"));
        if (!worse && !repeat) {
            if (current != null && reached > current) {
                // remontée partielle (réception incomplète) : on suit le palier courant sans renotifier
                db.update("UPDATE tank SET alert_tier = :t WHERE id = :id", Db.p("id", tankId, "t", reached));
            }
            return;
        }
        int index = tiers.indexOf(reached);
        String priority = index == tiers.size() - 1 ? Notifier.CRITIQUE : index == tiers.size() - 2 ? Notifier.URGENT
                : index == 0 ? Notifier.INFO : Notifier.ATTENTION;
        String kind = "CUVE_" + (index == tiers.size() - 1 ? 5 : index == tiers.size() - 2 ? 30 : index == 0 ? 50 : 40);
        String level = pct.stripTrailingZeros().toPlainString().replace('.', ',') + " %";
        String title = t.get("productName") + " · " + t.get("shortName") + " : cuve à " + level;
        String body = t.get("stationName") + " · " + t.get("name") + " : " + volume((BigDecimal) t.get("volume")) + " L sur "
                + volume((BigDecimal) t.get("capacity")) + " L (" + level + ", sous le palier de " + reached + " %)."
                + (t.get("trendPct") == null ? "" : "\nTendance de consommation sur 7 jours : " + t.get("trendPct") + " %.")
                + (Notifier.URGENT.equals(priority) || Notifier.CRITIQUE.equals(priority)
                        ? "\nCommande de carburant à passer depuis l'écran Approvisionnement." : "");
        String link = Notifier.URGENT.equals(priority) || Notifier.CRITIQUE.equals(priority)
                ? "/approvisionnement?station=" + t.get("stationId") + "&product=" + t.get("productCode") + "&quantity="
                        + ((BigDecimal) t.get("capacity")).subtract((BigDecimal) t.get("volume")).divide(BigDecimal.valueOf(1000), 0, RoundingMode.DOWN)
                                .multiply(BigDecimal.valueOf(1000)).toPlainString()
                : "/stations/" + t.get("stationId");
        List<String> extra = Notifier.URGENT.equals(priority) || Notifier.CRITIQUE.equals(priority)
                ? settings.getList("alerts.logistics_emails") : List.of();
        notifier.send(new Notifier.Message(kind, priority, title, body, link, ((Number) t.get("stationId")).longValue()), List.of(), extra);
        db.update("UPDATE tank SET alert_tier = :t, alert_notified_at = now() WHERE id = :id", Db.p("id", tankId, "t", reached));
    }

    private static String volume(BigDecimal v) {
        return String.format(java.util.Locale.ROOT, "%,d", v.setScale(0, RoundingMode.HALF_UP).longValue()).replace(',', ' ');
    }
}
