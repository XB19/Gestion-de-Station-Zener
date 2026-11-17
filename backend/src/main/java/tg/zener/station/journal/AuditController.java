package tg.zener.station.journal;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.security.Auth;

/**
 * Traces de sécurité (droit AUDIT : Admin, DAF) : tentatives de connexion, sessions ouvertes (révocables),
 * détail d'une entrée du journal (adresse IP, appareil, session, avant / après, motif).
 */
@RestController
@PreAuthorize("hasAuthority('AUDIT')")
public class AuditController {

    private final Db db;
    private final Journal journal;

    public AuditController(Db db, Journal journal) {
        this.db = db;
        this.journal = journal;
    }

    @GetMapping("/api/audit/logins")
    public Page logins(@RequestParam(required = false) Long employeeId, @RequestParam(required = false) Boolean failed,
                       @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        int sz = Page.size(size);
        int pg = Page.page(page);
        String where = " WHERE (CAST(:e AS bigint) IS NULL OR a.employee_id = :e) AND (NOT CAST(:f AS boolean) OR NOT a.success)";
        Map<String, Object> p = Db.p("e", employeeId, "f", Boolean.TRUE.equals(failed), "l", sz, "o", pg * sz);
        return new Page(db.list("""
                \sSELECT a.id, a.at, a.email, a.employee_id, e.first_name || ' ' || e.last_name AS employee_name, r.label AS role_label,
                       a.success, a.reason, a.ip, a.user_agent, a.session_id
                FROM login_attempt a LEFT JOIN employee e ON e.id = a.employee_id LEFT JOIN role r ON r.code = e.role_code"""
                + where + " ORDER BY a.at DESC LIMIT :l OFFSET :o", p),
                db.count("SELECT count(*) FROM login_attempt a" + where, p), pg, sz);
    }

    @GetMapping("/api/audit/sessions")
    public Object sessions(@RequestParam(required = false) Long employeeId) {
        return db.list("""
                \sSELECT s.id, s.employee_id, e.first_name || ' ' || e.last_name AS employee_name, r.label AS role_label,
                       s.created_at, s.last_seen_at, s.expires_at, s.ip, s.user_agent,
                       (SELECT count(*) FROM operation_log o WHERE o.session_id = s.id) AS actions
                FROM auth_session s JOIN employee e ON e.id = s.employee_id JOIN role r ON r.code = e.role_code
                WHERE s.revoked_at IS NULL AND s.expires_at > now() AND (CAST(:e AS bigint) IS NULL OR s.employee_id = :e)
                ORDER BY s.last_seen_at DESC LIMIT 300""", Db.p("e", employeeId));
    }

    @PostMapping("/api/audit/sessions/{id}/revoke")
    public Object revoke(@PathVariable UUID id) {
        Map<String, Object> s = db.require("""
                \sUPDATE auth_session SET revoked_at = now(), revoked_by = :u WHERE id = :id AND revoked_at IS NULL
                RETURNING employee_id, ip, user_agent""", Db.p("id", id, "u", Auth.user().id()), "Session introuvable ou déjà fermée");
        String who = db.value("SELECT first_name || ' ' || last_name FROM employee WHERE id = :e", Db.p("e", s.get("employeeId")), String.class);
        journal.audit("SESSION_REVOQUEE", Journal.SECURITE, "Session fermée par un administrateur",
                who + " · " + s.get("userAgent") + " · " + s.get("ip"), s, null, null, null);
        return sessions(null);
    }

    /** Détail complet d'une entrée du journal (avant / après, motif, d'où). */
    @GetMapping("/api/audit/entries/{id}")
    public Map<String, Object> entry(@PathVariable long id) {
        return db.require("""
                \sSELECT o.id, o.at, o.type, o.category, o.title, o.detail, o.amount, o.ip, o.user_agent, o.session_id, o.reason,
                       o.before_value::text AS before_value, o.after_value::text AS after_value,
                       e.first_name || ' ' || e.last_name AS actor_name, s.created_at AS session_started_at, s.ip AS session_ip
                FROM operation_log o LEFT JOIN employee e ON e.id = o.actor_id LEFT JOIN auth_session s ON s.id = o.session_id
                WHERE o.id = :id""", Db.p("id", id), "Entrée introuvable");
    }
}
