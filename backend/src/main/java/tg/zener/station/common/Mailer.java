package tg.zener.station.common;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Emails : mis en file dans email_outbox, puis envoyés par SMTP si un serveur est configuré
 * (MAIL_HOST). Sans serveur, ils restent consultables dans la file (aperçu depuis l'application).
 */
@Service
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final Db db;
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final boolean enabled;

    public Mailer(Db db, ObjectProvider<JavaMailSender> sender,
                  @Value("${app.mail.from}") String from,
                  @Value("${spring.mail.host:}") String host) {
        this.db = db;
        this.sender = sender;
        this.from = from;
        this.enabled = host != null && !host.isBlank();
    }

    public long queue(List<String> recipients, String subject, String body, String kind) {
        List<String> to = recipients.stream().filter(r -> r != null && r.contains("@")).distinct().toList();
        if (to.isEmpty()) {
            return 0;
        }
        return db.insert("""
                \sINSERT INTO email_outbox (recipients, subject, body, kind)
                VALUES (:to, :s, :b, :k)""",
                Db.p("to", db.array("text", to.toArray()), "s", subject, "b", body, "k", kind));
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    void flush() {
        if (!enabled) {
            return;
        }
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            return;
        }
        for (Map<String, Object> m : db.list("""
                \sSELECT id, recipients, subject, body FROM email_outbox
                WHERE sent_at IS NULL AND (error IS NULL OR created_at > now() - interval '1 day')
                ORDER BY id LIMIT 20""")) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(from);
                @SuppressWarnings("unchecked")
                List<Object> to = (List<Object>) m.get("recipients");
                msg.setTo(to.stream().map(String::valueOf).toArray(String[]::new));
                msg.setSubject((String) m.get("subject"));
                msg.setText((String) m.get("body"));
                mail.send(msg);
                db.update("UPDATE email_outbox SET sent_at = now(), error = NULL WHERE id = :id", Db.p("id", m.get("id")));
            } catch (Exception e) {
                log.warn("Envoi de l'email {} impossible : {}", m.get("id"), e.getMessage());
                db.update("UPDATE email_outbox SET error = :e WHERE id = :id", Db.p("id", m.get("id"), "e", e.getMessage()));
            }
        }
    }
}
