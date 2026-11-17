package tg.zener.station.validations;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;
import tg.zener.station.notifications.Notifier;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/**
 * Délégation du pouvoir de valider : le titulaire (le DAF, ou un Admin pour l'ouverture des points de vente)
 * confie temporairement son pouvoir à un utilisateur ou à un rôle. Seul le titulaire crée ses délégations
 * (l'Admin ne peut pas se donner le pouvoir du DAF) ; il peut les révoquer à tout moment.
 */
@RestController
public class DelegationController {

    private final Db db;
    private final Journal journal;
    private final Notifier notifier;

    public DelegationController(Db db, Journal journal, Notifier notifier) {
        this.db = db;
        this.journal = journal;
        this.notifier = notifier;
    }

    private static final String SELECT = """
            \sSELECT d.id, d.from_employee, f.first_name || ' ' || f.last_name AS from_name, fr.label AS from_role_label,
                   d.to_employee, t.first_name || ' ' || t.last_name AS to_name, d.to_role, tr.label AS to_role_label,
                   d.kinds, d.max_amount, d.starts_at, d.ends_at, d.note, d.created_at, d.revoked_at,
                   CASE WHEN d.revoked_at IS NOT NULL THEN 'REVOQUEE' WHEN d.ends_at IS NOT NULL AND d.ends_at <= now() THEN 'EXPIREE'
                        WHEN d.starts_at > now() THEN 'A_VENIR' ELSE 'ACTIVE' END AS state,
                   (SELECT count(*) FROM validation_step st WHERE st.delegated_from = d.from_employee
                      AND st.decided_by IN (SELECT e.id FROM employee e WHERE e.id = d.to_employee OR e.role_code = d.to_role)
                      AND st.decided_at >= d.starts_at AND (d.ends_at IS NULL OR st.decided_at <= d.ends_at)) AS uses
            FROM validation_delegation d JOIN employee f ON f.id = d.from_employee JOIN role fr ON fr.code = f.role_code
            LEFT JOIN employee t ON t.id = d.to_employee LEFT JOIN role tr ON tr.code = d.to_role""";

    /** Délégations données par l'utilisateur ou reçues ; l'Admin et le DAF voient toutes les délégations. */
    @GetMapping("/api/validations/delegations")
    public List<Map<String, Object>> list() {
        CurrentUser u = Auth.user();
        boolean all = "ADMIN".equals(u.role()) || "DAF".equals(u.role()) || u.can("AUDIT");
        return db.list(SELECT + """
                 \sWHERE CAST(:all AS boolean) OR d.from_employee = :u OR d.to_employee = :u OR d.to_role = :r
                ORDER BY d.revoked_at NULLS FIRST, d.created_at DESC LIMIT 200""",
                Db.p("all", all, "u", u.id(), "r", u.role()));
    }

    public record DelegationForm(Long toEmployeeId, String toRole, List<String> kinds, Long maxAmount, Instant startsAt, Instant endsAt,
                                 String note) {}

    @PostMapping("/api/validations/delegations")
    public Map<String, Object> create(@Valid @RequestBody DelegationForm f) {
        CurrentUser u = Auth.user();
        if (!"DAF".equals(u.role()) && !"ADMIN".equals(u.role())) {
            throw ApiException.forbidden("Seul un titulaire du pouvoir de valider (DAF, Admin) peut le déléguer");
        }
        if ((f.toEmployeeId() == null) == (f.toRole() == null || f.toRole().isBlank())) {
            throw new ApiException("Choisissez un utilisateur ou un rôle délégataire");
        }
        if (f.toEmployeeId() != null && f.toEmployeeId() == u.id()) {
            throw new ApiException("Vous ne pouvez pas vous déléguer votre propre pouvoir");
        }
        if (f.toEmployeeId() != null && db.count("SELECT count(*) FROM employee WHERE id = :e AND active", Db.p("e", f.toEmployeeId())) == 0) {
            throw ApiException.notFound("Utilisateur introuvable");
        }
        String toRole = f.toRole() == null || f.toRole().isBlank() ? null : f.toRole().trim();
        if (toRole != null && db.count("SELECT count(*) FROM role WHERE code = :r", Db.p("r", toRole)) == 0) {
            throw new ApiException("Rôle inconnu");
        }
        List<String> kinds = f.kinds() == null || f.kinds().isEmpty() ? null : f.kinds();
        if (kinds != null && kinds.stream().anyMatch(k -> !List.of("RECHARGE", "RECHARGE_MASSE", "OUVERTURE_PDV").contains(k))) {
            throw new ApiException("Seules les validations de recharges et d'ouverture de point de vente se délèguent");
        }
        Instant start = f.startsAt() == null ? Instant.now() : f.startsAt();
        if (f.endsAt() != null && !f.endsAt().isAfter(start)) {
            throw new ApiException("La fin doit suivre le début");
        }
        long id = db.insert("""
                \sINSERT INTO validation_delegation (from_employee, to_employee, to_role, kinds, max_amount, starts_at, ends_at, note, created_by)
                VALUES (:f, :te, :tr, :k, :m, :s, :e, :n, :f)""",
                Db.p("f", u.id(), "te", f.toEmployeeId(), "tr", toRole,
                        "k", kinds == null ? null : db.array("text", kinds.toArray()), "m", f.maxAmount(),
                        "s", java.sql.Timestamp.from(start), "e", f.endsAt() == null ? null : java.sql.Timestamp.from(f.endsAt()),
                        "n", f.note() == null || f.note().isBlank() ? null : f.note().trim()));
        Map<String, Object> d = db.one(SELECT + " WHERE d.id = :id", Db.p("id", id));
        String to = d.get("toName") != null ? (String) d.get("toName") : "rôle " + d.get("toRoleLabel");
        journal.audit("DELEGATION", Journal.SECURITE, "Pouvoir de validation délégué", u.name() + " → " + to, null, d, f.note(), null);
        notifier.send(new Notifier.Message("VALIDATION", Notifier.INFO, "Pouvoir de validation délégué par " + u.name(),
                "Vous pouvez valider les demandes au nom de " + u.name()
                        + (f.endsAt() == null ? "" : " jusqu'au " + f.endsAt()) + (f.maxAmount() == null ? "" : ", jusqu'à " + f.maxAmount() + " F") + ".",
                "/validations", null), f.toEmployeeId() == null ? List.of() : List.of(f.toEmployeeId()), List.of(), true);
        return d;
    }

    @PostMapping("/api/validations/delegations/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable long id) {
        CurrentUser u = Auth.user();
        Map<String, Object> d = db.require(SELECT + " WHERE d.id = :id", Db.p("id", id), "Délégation introuvable");
        if (((Number) d.get("fromEmployee")).longValue() != u.id() && !"DAF".equals(u.role())) {
            throw ApiException.forbidden("Seul le titulaire (ou le DAF) révoque une délégation");
        }
        db.update("UPDATE validation_delegation SET revoked_at = now(), revoked_by = :u WHERE id = :id AND revoked_at IS NULL",
                Db.p("id", id, "u", u.id()));
        journal.audit("DELEGATION", Journal.SECURITE, "Délégation révoquée", d.get("fromName") + " → "
                + (d.get("toName") != null ? d.get("toName") : d.get("toRoleLabel")), d, null, null, null);
        return db.one(SELECT + " WHERE d.id = :id", Db.p("id", id));
    }
}
