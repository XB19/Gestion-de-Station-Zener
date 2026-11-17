package tg.zener.station.notifications;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.Mailer;

/**
 * Point d'entrée unique des notifications : résout les destinataires d'après la règle du type
 * (rôles, rattachement à la station), écrit une notification par personne (cloche, lu / non lu),
 * envoie l'email si la règle le prévoit (ou si la priorité est URGENT / CRITIQUE) et prévient les
 * écrans ouverts par le flux temps réel (événement « notification »).
 */
@Service
public class Notifier {

    public static final String INFO = "INFO";
    public static final String ATTENTION = "ATTENTION";
    public static final String URGENT = "URGENT";
    public static final String CRITIQUE = "CRITIQUE";

    /** Message : type (règle), priorité, titre, texte, lien de l'application, station concernée. */
    public record Message(String kind, String priority, String title, String body, String link, Long stationId) {}

    private final Db db;
    private final Mailer mailer;
    private final LiveFeed live;

    public Notifier(Db db, Mailer mailer, LiveFeed live) {
        this.db = db;
        this.mailer = mailer;
        this.live = live;
    }

    public int send(Message m) {
        return send(m, List.of(), List.of());
    }

    /** extraIds : destinataires en plus de la règle (demandeur, livreur…) ; extraEmails : adresses externes (client, logistique). */
    public int send(Message m, Collection<Long> extraIds, Collection<String> extraEmails) {
        return send(m, extraIds, extraEmails, false);
    }

    /** onlyExtra : destinataires calculés par l'appelant (la règle ne sert qu'à l'email et à l'activation). */
    public int send(Message m, Collection<Long> extraIds, Collection<String> extraEmails, boolean onlyExtra) {
        Map<String, Object> rule = db.one("SELECT roles, email, active FROM notification_rule WHERE kind = :k", Db.p("k", m.kind()));
        boolean active = rule == null || Boolean.TRUE.equals(rule.get("active"));
        Set<Long> ids = new LinkedHashSet<>(extraIds == null ? List.of() : extraIds);
        if (active && rule != null && !onlyExtra) {
            @SuppressWarnings("unchecked")
            List<Object> roles = (List<Object>) rule.get("roles");
            if (!roles.isEmpty()) {
                db.list("""
                        \sSELECT e.id FROM employee e JOIN role r ON r.code = e.role_code
                        WHERE e.active AND e.role_code = ANY(:r)
                          AND (NOT r.station_bound OR CAST(:s AS bigint) IS NULL OR e.station_id = :s
                               OR e.id = (SELECT manager_id FROM station WHERE id = :s))""",
                        Db.p("r", db.array("text", roles.stream().map(String::valueOf).toArray()), "s", m.stationId()))
                        .forEach(r -> ids.add(((Number) r.get("id")).longValue()));
            }
        }
        if (!active && ids.isEmpty()) {
            return 0;
        }
        for (Long id : ids) {
            db.update("""
                    \sINSERT INTO notification (employee_id, kind, priority, title, body, link, station_id)
                    VALUES (:e, :k, :p, :t, :b, :l, :s)""",
                    Db.p("e", id, "k", m.kind(), "p", m.priority() == null ? INFO : m.priority(), "t", m.title(), "b", m.body(),
                            "l", m.link(), "s", m.stationId()));
        }
        boolean mail = rule == null ? URGENT.equals(m.priority()) || CRITIQUE.equals(m.priority())
                : Boolean.TRUE.equals(rule.get("email")) || CRITIQUE.equals(m.priority());
        List<String> to = new ArrayList<>();
        if (mail && !ids.isEmpty()) {
            db.list("SELECT email FROM employee WHERE id = ANY(:ids) AND email IS NOT NULL",
                    Db.p("ids", db.array("bigint", ids.toArray()))).forEach(r -> to.add((String) r.get("email")));
        }
        if (extraEmails != null) {
            extraEmails.stream().filter(e -> e != null && !e.isBlank() && !to.contains(e)).forEach(to::add);
        }
        if (!to.isEmpty()) {
            mailer.queue(to, prefix(m.priority()) + m.title(), (m.body() == null ? "" : m.body() + "\n")
                    + (m.link() == null ? "" : "\nÀ voir dans l'application : " + m.link().replaceFirst("^#?", "#")), m.kind());
        }
        live.publish("notification", m.stationId(), Map.of("kind", m.kind(), "priority", m.priority() == null ? INFO : m.priority(),
                "title", m.title(), "recipients", ids));
        return ids.size();
    }

    private static String prefix(String priority) {
        return switch (priority == null ? INFO : priority) {
            case CRITIQUE -> "[CRITIQUE] ";
            case URGENT -> "[URGENT] ";
            default -> "";
        };
    }

    /** Planifie un rappel daté (sans doublon : même référence, même type, même jour). */
    public void schedule(String kind, String refType, long refId, LocalDate dueOn, String priority, String title, String body,
                         String link, Long stationId, Collection<Long> extraIds, Collection<String> extraEmails) {
        db.update("""
                \sINSERT INTO reminder (kind, ref_type, ref_id, due_on, priority, title, body, link, station_id, extra_recipients, extra_emails)
                VALUES (:k, :rt, :ri, :d, :p, :t, :b, :l, :s, :er, :ee)
                ON CONFLICT (ref_type, ref_id, kind, due_on) DO UPDATE
                    SET title = EXCLUDED.title, body = EXCLUDED.body, cancelled_at = NULL
                    WHERE reminder.sent_at IS NULL""",
                Db.p("k", kind, "rt", refType, "ri", refId, "d", dueOn, "p", priority, "t", title, "b", body, "l", link,
                        "s", stationId, "er", db.array("bigint", (extraIds == null ? List.<Long>of() : extraIds).toArray()),
                        "ee", db.array("text", (extraEmails == null ? List.<String>of() : extraEmails).toArray())));
    }

    /** Annule les rappels non envoyés d'une référence (licence renouvelée, carte Club renouvelée…). */
    public void cancel(String refType, long refId, String kind) {
        db.update("""
                \sUPDATE reminder SET cancelled_at = now()
                WHERE ref_type = :rt AND ref_id = :ri AND (CAST(:k AS text) IS NULL OR kind = :k) AND sent_at IS NULL AND cancelled_at IS NULL""",
                Db.p("rt", refType, "ri", refId, "k", kind));
    }
}
