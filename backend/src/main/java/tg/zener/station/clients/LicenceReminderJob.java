package tg.zener.station.clients;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tg.zener.station.common.Db;
import tg.zener.station.common.Mailer;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;

/** Rappels automatiques avant l'échéance des licences (par défaut J-30, J-7, J-1). */
@Component
public class LicenceReminderJob {

    private final Db db;
    private final Settings settings;
    private final Mailer mailer;
    private final Journal journal;

    public LicenceReminderJob(Db db, Settings settings, Mailer mailer, Journal journal) {
        this.db = db;
        this.settings = settings;
        this.mailer = mailer;
        this.journal = journal;
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "${app.timezone}")
    public void run() {
        List<Integer> days = new ArrayList<>();
        for (String d : settings.getList("licence.reminder_days")) {
            try {
                days.add(Integer.parseInt(d));
            } catch (NumberFormatException ignored) {
                // valeur ignorée
            }
        }
        List<String> admins = db.list("SELECT email FROM employee WHERE role_code IN ('ADMIN', 'DAF') AND active AND email IS NOT NULL")
                .stream().map(r -> (String) r.get("email")).toList();
        for (Map<String, Object> l : db.list("""
                \sSELECT c.id, c.name, c.email, l.end_date, l.end_date - current_date AS days_left
                FROM licence l JOIN client c ON c.id = l.client_id
                WHERE l.status = 'ACTIVE' AND l.end_date >= current_date""")) {
            int left = ((Number) l.get("daysLeft")).intValue();
            if (!days.contains(left)) {
                continue;
            }
            List<String> to = new ArrayList<>(admins);
            if (l.get("email") != null) {
                to.add((String) l.get("email"));
            }
            String subject = "Licence ZENER : échéance dans " + left + " jour" + (left > 1 ? "s" : "") + " · " + l.get("name");
            mailer.queue(to, subject, "La licence annuelle de " + l.get("name") + " expire le " + l.get("endDate")
                    + ".\nPensez à la renouveler depuis l'écran Clients & licences.", "LICENCE");
            journal.logAs(null, "RAPPEL_LICENCE", Journal.SYSTEME, "Rappel de licence envoyé",
                    l.get("name") + " · J-" + left, null, null, null);
        }
    }
}
