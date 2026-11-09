package tg.zener.station.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.security.JwtService;
import tg.zener.station.security.UserLoader;

/** Connexion (écran 01) et profil de l'utilisateur connecté. */
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
        Map<String, Object> e = db.one("SELECT id, password_hash, active FROM employee WHERE lower(email) = lower(:e)",
                Db.p("e", r.email().trim()));
        if (e == null || e.get("passwordHash") == null || !encoder.matches(r.password(), (String) e.get("passwordHash"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "IDENTIFIANTS", "Email ou mot de passe incorrect");
        }
        if (!Boolean.TRUE.equals(e.get("active"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DESACTIVE", "Ce compte est désactivé");
        }
        long id = ((Number) e.get("id")).longValue();
        CurrentUser user = users.load(id);
        journal.logAs(id, "CONNEXION", Journal.SYSTEME, "Connexion", user.name(), null, user.stationId(), null);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("token", jwt.issue(id, Boolean.TRUE.equals(r.remember())));
        res.put("user", profile(user));
        return res;
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me() {
        return profile(Auth.user());
    }

    public record PasswordChange(@NotBlank String current, @NotBlank @Size(min = 8) String next) {}

    @PostMapping("/auth/password")
    public Map<String, Object> changePassword(@Valid @RequestBody PasswordChange r) {
        CurrentUser u = Auth.user();
        String hash = db.value("SELECT password_hash FROM employee WHERE id = :id", Db.p("id", u.id()), String.class);
        if (hash == null || !encoder.matches(r.current(), hash)) {
            throw new ApiException("Mot de passe actuel incorrect");
        }
        db.update("UPDATE employee SET password_hash = :h WHERE id = :id", Db.p("h", encoder.encode(r.next()), "id", u.id()));
        return Map.of("ok", true);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    private Map<String, Object> profile(CurrentUser u) {
        Map<String, Object> e = db.one("""
                \sSELECT e.id, e.first_name, e.last_name, e.email, e.photo_path, e.role_code AS role, r.label AS role_label,
                       e.station_id, s.name AS station_name
                FROM employee e JOIN role r ON r.code = e.role_code LEFT JOIN station s ON s.id = e.station_id
                WHERE e.id = :id""", Db.p("id", u.id()));
        e.put("name", u.name());
        e.put("initials", u.initials());
        Object photo = e.remove("photoPath");
        e.put("photoUrl", photo == null ? null : "/files/" + photo);
        e.put("rights", u.rights().stream().sorted().toList());
        e.put("stationBound", u.stationBound());
        return e;
    }
}
