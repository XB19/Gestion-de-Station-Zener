package tg.zener.station.notifications;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tg.zener.station.common.Db;

/**
 * Envoi des rappels arrivés à échéance. Toutes les 5 minutes (et 30 s après le démarrage) : un rappel
 * dont la date est passée pendant un arrêt du serveur part au redémarrage, une seule fois.
 */
@Component
public class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

    private final Db db;
    private final Notifier notifier;

    public ReminderJob(Db db, Notifier notifier) {
        this.db = db;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void run() {
        for (Map<String, Object> r : db.list("""
                \sSELECT id, kind, priority, title, body, link, station_id, extra_recipients, extra_emails FROM reminder
                WHERE sent_at IS NULL AND cancelled_at IS NULL AND due_on <= current_date ORDER BY due_on, id LIMIT 200""")) {
            try {
                @SuppressWarnings("unchecked")
                List<Object> ids = (List<Object>) r.get("extraRecipients");
                @SuppressWarnings("unchecked")
                List<Object> emails = (List<Object>) r.get("extraEmails");
                notifier.send(new Notifier.Message((String) r.get("kind"), (String) r.get("priority"), (String) r.get("title"),
                                (String) r.get("body"), (String) r.get("link"),
                                r.get("stationId") == null ? null : ((Number) r.get("stationId")).longValue()),
                        ids.stream().map(x -> ((Number) x).longValue()).toList(), emails.stream().map(String::valueOf).toList());
                db.update("UPDATE reminder SET sent_at = now() WHERE id = :id", Db.p("id", r.get("id")));
            } catch (Exception e) {
                log.warn("Rappel {} non envoyé : {}", r.get("id"), e.getMessage());
            }
        }
    }
}
