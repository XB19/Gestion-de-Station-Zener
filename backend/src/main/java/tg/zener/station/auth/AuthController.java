package tg.zener.station.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.RequestInfo;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.security.JwtService;
import tg.zener.station.security.UserLoader;

/**
 * Connexion (écran 01) et profil de l'utilisateur connecté. Chaque connexion ouvre une session (adresse IP,
 * appareil) inscrite dans le jeton ; chaque tentative, réussie ou non, est conservée. Les actions faites
 * ensuite portent l'identifiant de session : on sait depuis quel appareil un compte a agi.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final Db db;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final UserLoader users;
    private final Journal journal;

    public AuthController(Db db, JwtService jwt, PasswordEncoder encoder, UserLoader users, Journal journal) {
        this.db = db;
        this.jwt = jwt;
        this.encoder = encoder;
        this.users = users;
        this.journal = journal;
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password, Boolean remember) {}

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest r) {
        String email = r.email().trim();
        Map<String, Object> e = db.one("SELECT id, password_hash, active FROM employee WHERE lower(email) = lower(:e)", Db.p("e", email));
        Long employeeId = e == null ? null : ((Number) e.get("id")).longValue();
        if (e == null || e.get("passwordHash") == null || !encoder.matches(r.password(), (String) e.get("passwordHash"))) {
            attempt(email, employeeId, false, e == null ? "Compte inconnu" : "Mot de passe incorrect", null);
            if (employeeId != null) {
                long recent = db.count("""
                        \sSELECT count(*) FROM login_attempt WHERE employee_id = :e AND NOT success AND at > now() - interval '15 minutes'""",
                        Db.p("e", employeeId));
                if (recent == 5) {
                    journal.logAs(employeeId, "CONNEXION_ECHECS", Journal.SECURITE, "Échecs de connexion répétés",
                            recent + " mots de passe incorrects en 15 min · " + RequestInfo.ip(), null, null, null);
                }
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "IDENTIFIANTS", "Email ou mot de passe incorrect");
        }
        if (!Boolean.TRUE.equals(e.get("active"))) {
            attempt(email, employeeId, false, "Compte désactivé", null);
            throw new ApiException(HttpStatus.FORBIDDEN, "DESACTIVE", "Ce compte est désactivé");
        }
        CurrentUser user = users.load(employeeId);
        boolean remember = Boolean.TRUE.equals(r.remember());
        UUID sid = UUID.randomUUID();
        Instant expires = jwt.expiry(remember);
        db.update("""
                \sINSERT INTO auth_session (id, employee_id, expires_at, ip, user_agent) VALUES (:id, :e, :x, :ip, :ua)""",
                Db.p("id", sid, "e", employeeId, "x", java.sql.Timestamp.from(expires), "ip", RequestInfo.ip(), "ua", RequestInfo.userAgent()));
        attempt(email, employeeId, true, null, sid);
        if (RequestInfo.request() != null) {
            RequestInfo.request().setAttribute(RequestInfo.SESSION_ATTR, sid);
        }
        journal.logAs(employeeId, "CONNEXION", Journal.SYSTEME, "Connexion",
                user.name() + " · " + RequestInfo.userAgent() + " · " + RequestInfo.ip(), null, user.stationId(), null);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("token", jwt.issue(employeeId, remember, sid));
        res.put("user", profile(user));
        return res;
    }

    private void attempt(String email, Long employeeId, boolean success, String reason, UUID sid) {
        db.update("""
                \sINSERT INTO login_attempt (email, employee_id, success, reason, ip, user_agent, session_id)
                VALUES (:m, :e, :s, :r, :ip, :ua, :sid)""",
                Db.p("m", email.toLowerCase(), "e", employeeId, "s", success, "r", reason, "ip", RequestInfo.ip(),
                        "ua", RequestInfo.userAgent(), "sid", sid));
    }

    /** Déconnexion : la session est close, le jeton ne sert plus. */
    @PostMapping("/auth/logout")
    public Map<String, Object> logout() {
        UUID sid = RequestInfo.sessionId();
        if (sid != null) {
            db.update("UPDATE auth_session SET revoked_at = now(), revoked_by = :u WHERE id = :s AND revoked_at IS NULL",
                    Db.p("s", sid, "u", Auth.user().id()));
        }
        return Map.of("ok", true);
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me() {
        return profile(Auth.user());
    }

    /** Mes sessions ouvertes et mes dernières connexions (pour repérer un usage du compte par quelqu'un d'autre). */
    @GetMapping("/auth/sessions")
    public Map<String, Object> mySessions() {
        long me = Auth.user().id();
        return Map.of(
                "current", String.valueOf(RequestInfo.sessionId()),
                "sessions", db.list("""
                        \sSELECT id, created_at, last_seen_at, expires_at, ip, user_agent FROM auth_session
                        WHERE employee_id = :e AND revoked_at IS NULL AND expires_at > now() ORDER BY last_seen_at DESC""", Db.p("e", me)),
                "attempts", db.list("""
                        \sSELECT at, success, reason, ip, user_agent FROM login_attempt WHERE employee_id = :e ORDER BY at DESC LIMIT 20""",
                        Db.p("e", me)));
    }

    @PostMapping("/auth/sessions/{id}/revoke")
    public Map<String, Object> revokeMine(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        db.update("UPDATE auth_session SET revoked_at = now(), revoked_by = :e WHERE id = :s AND employee_id = :e AND revoked_at IS NULL",
                Db.p("s", id, "e", Auth.user().id()));
        return mySessions();
    }

    public record PasswordChange(String current, @NotBlank @Size(min = 8) String next) {}

    /** Changement de mot de passe (obligatoire à la première connexion avec un mot de passe provisoire). */
    @PostMapping("/auth/password")
    public Map<String, Object> changePassword(@Valid @RequestBody PasswordChange r) {
        CurrentUser u = Auth.user();
        Map<String, Object> e = db.one("SELECT password_hash, must_change_password FROM employee WHERE id = :id", Db.p("id", u.id()));
        String hash = (String) e.get("passwordHash");
        if (hash == null || r.current() == null || !encoder.matches(r.current(), hash)) {
            throw new ApiException("Mot de passe actuel incorrect");
        }
        if (encoder.matches(r.next(), hash)) {
            throw new ApiException("Le nouveau mot de passe doit être différent de l'actuel");
        }
        db.update("""
                \sUPDATE employee SET password_hash = :h, must_change_password = false, password_changed_at = now() WHERE id = :id""",
                Db.p("h", encoder.encode(r.next()), "id", u.id()));
        // les autres sessions sont fermées : seul cet appareil reste connecté
        db.update("UPDATE auth_session SET revoked_at = now(), revoked_by = :e WHERE employee_id = :e AND revoked_at IS NULL AND id IS DISTINCT FROM :s",
                Db.p("e", u.id(), "s", RequestInfo.sessionId()));
        journal.logAs(u.id(), "MOT_DE_PASSE", Journal.SECURITE, "Mot de passe changé", u.name(), null, u.stationId(), null);
        return profile(u);
    }

    /** Fin de la visite guidée de la première connexion. */
    @PostMapping("/auth/onboarded")
    public Map<String, Object> onboarded() {
        CurrentUser u = Auth.user();
        db.update("UPDATE employee SET onboarded_at = COALESCE(onboarded_at, now()) WHERE id = :id", Db.p("id", u.id()));
        return profile(u);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    private Map<String, Object> profile(CurrentUser u) {
        Map<String, Object> e = db.one("""
                \sSELECT e.id, e.first_name, e.last_name, e.email, e.photo_path, e.role_code AS role, r.label AS role_label,
                       e.station_id, s.name AS station_name, e.must_change_password, (e.onboarded_at IS NULL) AS needs_tour
                FROM employee e JOIN role r ON r.code = e.role_code LEFT JOIN station s ON s.id = e.station_id
                WHERE e.id = :id""", Db.p("id", u.id()));
        e.put("name", u.name());
        e.put("initials", u.initials());
        Object photo = e.remove("photoPath");
        e.put("photoUrl", photo == null ? null : "/files/" + photo);
        e.put("rights", u.rights().stream().sorted().toList());
        e.put("stationBound", u.stationBound());
        e.put("lastLogin", db.one("""
                \sSELECT at, ip, user_agent FROM login_attempt WHERE employee_id = :e AND success AND session_id IS DISTINCT FROM :s
                ORDER BY at DESC LIMIT 1""", Db.p("e", u.id(), "s", RequestInfo.sessionId())));
        return e;
    }
}
