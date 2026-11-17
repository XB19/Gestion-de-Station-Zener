package tg.zener.station.notifications;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;

/** Cloche de l'application : notifications de l'utilisateur connecté ; règles d'envoi (Paramètres). */
@RestController
public class NotificationController {

    private final Db db;
    private final Journal journal;

    public NotificationController(Db db, Journal journal) {
        this.db = db;
        this.journal = journal;
    }

    @GetMapping("/api/notifications")
    public Page list(@RequestParam(required = false) Boolean unread, @RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Integer size) {
        int sz = Page.size(size);
        int pg = Page.page(page);
        String where = " WHERE n.employee_id = :e AND (NOT CAST(:u AS boolean) OR n.read_at IS NULL)";
        Map<String, Object> p = Db.p("e", Auth.user().id(), "u", Boolean.TRUE.equals(unread), "l", sz, "o", pg * sz);
        return new Page(db.list("""
                \sSELECT n.id, n.kind, n.priority, n.title, n.body, n.link, n.created_at, n.read_at, s.short_name AS station_name,
                       r.label AS kind_label
                FROM notification n LEFT JOIN station s ON s.id = n.station_id LEFT JOIN notification_rule r ON r.kind = n.kind"""
                + where + " ORDER BY n.read_at IS NULL DESC, n.created_at DESC LIMIT :l OFFSET :o", p),
                db.count("SELECT count(*) FROM notification n" + where, p), pg, sz);
    }

    @GetMapping("/api/notifications/summary")
    public Map<String, Object> summary() {
        return db.one("""
                \sSELECT count(*) FILTER (WHERE read_at IS NULL) AS unread,
                       count(*) FILTER (WHERE read_at IS NULL AND priority IN ('URGENT', 'CRITIQUE')) AS urgent
                FROM notification WHERE employee_id = :e""", Db.p("e", Auth.user().id()));
    }

    @PostMapping("/api/notifications/{id}/read")
    public Map<String, Object> read(@PathVariable long id) {
        db.update("UPDATE notification SET read_at = COALESCE(read_at, now()) WHERE id = :id AND employee_id = :e",
                Db.p("id", id, "e", Auth.user().id()));
        return summary();
    }

    @PostMapping("/api/notifications/read-all")
    public Map<String, Object> readAll() {
        db.update("UPDATE notification SET read_at = now() WHERE employee_id = :e AND read_at IS NULL", Db.p("e", Auth.user().id()));
        return summary();
    }

    // ------------------------------------------------------------------ règles

    @GetMapping("/api/notifications/rules")
    @PreAuthorize("hasAuthority('PARAMETRES')")
    public List<Map<String, Object>> rules() {
        return db.list("SELECT kind, label, roles, email, active FROM notification_rule ORDER BY sort, kind");
    }

    public record RuleForm(List<String> roles, Boolean email, Boolean active) {}

    @PutMapping("/api/notifications/rules/{kind}")
    @PreAuthorize("hasAuthority('PARAMETRES')")
    public List<Map<String, Object>> updateRule(@PathVariable String kind, @RequestBody RuleForm f) {
        Map<String, Object> before = db.require("SELECT label, roles, email, active FROM notification_rule WHERE kind = :k",
                Db.p("k", kind), "Règle introuvable");
        if (f.roles() != null) {
            long known = db.count("SELECT count(*) FROM role WHERE code = ANY(:r)", Db.p("r", db.array("text", f.roles().toArray())));
            if (known != f.roles().size()) {
                throw new ApiException("Rôle inconnu");
            }
        }
        db.update("""
                \sUPDATE notification_rule SET roles = COALESCE(:r, roles), email = COALESCE(:e, email), active = COALESCE(:a, active)
                WHERE kind = :k""",
                Db.p("k", kind, "r", f.roles() == null ? null : db.array("text", f.roles().toArray()), "e", f.email(), "a", f.active()));
        Map<String, Object> after = db.one("SELECT roles, email, active FROM notification_rule WHERE kind = :k", Db.p("k", kind));
        journal.audit("NOTIFICATIONS", Journal.MODIFICATIONS, "Règle de notification modifiée", (String) before.get("label"),
                before, after, null, null);
        return rules();
    }
}
