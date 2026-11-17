package tg.zener.station.clients;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.notifications.Notifier;

/**
 * Rappels de licence (J-30, J-7, J-1 par défaut, paramètre licence.reminder_days) : planifiés comme
 * rappels datés (table reminder), envoyés par le ReminderJob — un rappel manqué pendant un arrêt
 * du serveur part au redémarrage. Un renouvellement annule les rappels de l'ancienne échéance.
 */
@Component
public class LicenceReminderJob {

    private final Db db;
    private final Settings settings;
    private final Notifier notifier;

    public LicenceReminderJob(Db db, Settings settings, Notifier notifier) {
        this.db = db;
        this.settings = settings;
        this.notifier = notifier;
    }

    /** Toutes les heures (et 20 s après le démarrage) : rappels de toutes les licences actives. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 20_000)
    public void run() {
        List<Integer> days = new ArrayList<>();
        for (String d : settings.getList("licence.reminder_days")) {
            try {
                days.add(Integer.parseInt(d.trim()));
            } catch (NumberFormatException ignored) {
                // valeur ignorée
            }
        }
        for (Map<String, Object> l : db.list("""
                \sSELECT l.id, c.name, c.email, l.end_date, l.status FROM licence l JOIN client c ON c.id = l.client_id""")) {
            long id = ((Number) l.get("id")).longValue();
            notifier.cancel("LICENCE", id, null);
            if (!"ACTIVE".equals(l.get("status"))) {
                continue;
            }
            LocalDate end = (LocalDate) l.get("endDate");
            for (int d : days) {
                LocalDate due = end.minusDays(d);
                if (due.isBefore(LocalDate.now().minusDays(1))) {
                    continue; // échéance d'un rappel déjà passée depuis longtemps : rien à rattraper
                }
                String subject = "Licence ZENER : échéance dans " + d + " jour" + (d > 1 ? "s" : "") + " · " + l.get("name");
                notifier.schedule("LICENCE", "LICENCE", id, due, d <= 1 ? Notifier.URGENT : Notifier.ATTENTION, subject,
                        "La licence annuelle de " + l.get("name") + " expire le " + end + ".\nPensez à la renouveler depuis l'écran Clients & licences.",
                        "/clients", null, List.of(), l.get("email") == null ? List.of() : List.of((String) l.get("email")));
            }
        }
    }
}
