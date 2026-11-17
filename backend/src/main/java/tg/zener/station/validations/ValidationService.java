package tg.zener.station.validations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Refs;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.notifications.Notifier;
import tg.zener.station.security.CurrentUser;

/**
 * Circuit de validation (version provisoire, avant la hiérarchie détaillée annoncée) :
 * - recharges au-dessus du palier : une étape, validée par le DAF (paramètre validation.approver_roles) ;
 * - ouverture ou réouverture d'un point de vente : Admin ou DAF ;
 * - actions sensibles de l'Admin (droits d'un rôle, paramètres financiers, ajustement de solde, compte Admin / DAF) :
 *   une seconde personne, Admin ou DAF.
 * Le demandeur ne valide jamais sa propre demande. Le pouvoir de valider peut être délégué (validation_delegation)
 * à un utilisateur ou à un rôle, pour une période, des types de demandes et un montant maximal : la décision garde
 * le nom du délégué et celui de la personne pour qui il a validé. Toute la résolution des valideurs passe par
 * {@link #approverRoles} et {@link #delegationFor} : la refonte n'aura qu'à les remplacer.
 */
@Service
public class ValidationService {

    public static final String RECHARGE = "RECHARGE";
    public static final String RECHARGE_MASSE = "RECHARGE_MASSE";
    public static final String OUVERTURE_PDV = "OUVERTURE_PDV";
    public static final String DROITS_ROLE = "DROITS_ROLE";
    public static final String PARAMETRES = "PARAMETRES";
    public static final String AJUSTEMENT_SOLDE = "AJUSTEMENT_SOLDE";
    public static final String COMPTE_SENSIBLE = "COMPTE_SENSIBLE";
    public static final Set<String> ADMIN_KINDS = Set.of(DROITS_ROLE, PARAMETRES, AJUSTEMENT_SOLDE, COMPTE_SENSIBLE);

    /** Publié quand une demande est entièrement approuvée ou refusée. */
    public record Decided(long requestId, String kind, Long targetId, boolean approved, long deciderId) {}

    public record NewRequest(String kind, String beneficiary, String summary, Long amount, boolean companyCard,
                             String source, String paymentInfo, Long targetId) {}

    private final Db db;
    private final Refs refs;
    private final Settings settings;
    private final Journal journal;
    private final Notifier notifier;
    private final ApplicationEventPublisher events;

    public ValidationService(Db db, Refs refs, Settings settings, Journal journal, Notifier notifier,
                             ApplicationEventPublisher events) {
        this.db = db;
        this.refs = refs;
        this.settings = settings;
        this.journal = journal;
        this.notifier = notifier;
        this.events = events;
    }

    public long threshold() {
        return settings.getLong("validation.threshold", 500_000);
    }

    /** Rôles qui décident d'un type de demande. */
    public String[] approverRoles(String kind) {
        if (OUVERTURE_PDV.equals(kind) || ADMIN_KINDS.contains(kind)) {
            return new String[] {"ADMIN", "DAF"};
        }
        List<String> roles = settings.getList("validation.approver_roles").stream().map(String::trim).filter(r -> !r.isEmpty()).toList();
        return roles.isEmpty() ? new String[] {"DAF"} : roles.toArray(String[]::new);
    }

    @Transactional
    public long create(NewRequest r, CurrentUser requester) {
        return create(r, requester, null);
    }

    /** payload : contenu d'une action sensible, appliqué à l'approbation (JSON). */
    @Transactional
    public long create(NewRequest r, CurrentUser requester, Object payload) {
        String ref = refs.validation();
        long id = db.insert("""
                \sINSERT INTO validation_request (ref, kind, beneficiary, summary, amount, company_card, source, payment_info,
                                                target_id, requested_by, payload)
                VALUES (:ref, :k, :b, :s, :a, :cc, :src, :pay, :t, :u, CAST(:pl AS jsonb))""",
                Db.p("ref", ref, "k", r.kind(), "b", r.beneficiary(), "s", r.summary(), "a", r.amount(),
                        "cc", r.companyCard(), "src", r.source(), "pay", r.paymentInfo(), "t", r.targetId(), "u", requester.id(),
                        "pl", payload == null ? null : Journal.toJson(payload)));
        String[] roles = approverRoles(r.kind());
        db.update("""
                \sINSERT INTO validation_step (request_id, step_no, roles, status) VALUES (:r, 1, :roles, 'EN_ATTENTE')""",
                Db.p("r", id, "roles", db.array("text", roles)));
        journal.log("VALIDATION_DEMANDEE", ADMIN_KINDS.contains(r.kind()) ? Journal.SECURITE : Journal.VALIDATIONS,
                ADMIN_KINDS.contains(r.kind()) ? "Action sensible soumise à approbation" : "Validation demandée",
                ref + " · " + r.beneficiary() + " · " + r.summary(), r.amount(), requester.stationId(), null);
        notifyApprovers(id, r.kind(), roles, r.amount(), requester.id());
        return id;
    }

    /** Délégation active permettant à l'utilisateur de décider pour un titulaire (son id), ou null. */
    public Long delegationFor(CurrentUser user, String kind, String[] roles, Long amount, long requesterId) {
        return db.value("""
                \sSELECT d.from_employee FROM validation_delegation d JOIN employee f ON f.id = d.from_employee
                WHERE d.revoked_at IS NULL AND d.starts_at <= now() AND (d.ends_at IS NULL OR d.ends_at > now())
                  AND (d.to_employee = :u OR d.to_role = :role) AND f.active AND f.role_code = ANY(:roles)
                  AND (d.kinds IS NULL OR :k = ANY(d.kinds))
                  AND (d.max_amount IS NULL OR CAST(:a AS bigint) IS NULL OR CAST(:a AS bigint) <= d.max_amount)
                  AND d.from_employee <> :req
                ORDER BY d.id LIMIT 1""",
                Db.p("u", user.id(), "role", user.role(), "roles", db.array("text", roles), "k", kind, "a", amount, "req", requesterId),
                Long.class);
    }

    /** Peut décider : null si non, 0 si en son nom propre, sinon l'id de la personne pour qui il valide. */
    public Long authority(CurrentUser user, String kind, String[] roles, Long amount, long requesterId) {
        if (user.id() == requesterId) {
            return null;
        }
        boolean byRole = java.util.Arrays.asList(roles).contains(user.role());
        if (byRole && (ADMIN_KINDS.contains(kind) || user.can(OUVERTURE_PDV.equals(kind) ? "PDV_VALIDER" : "VALIDER_MONTANTS"))) {
            return 0L;
        }
        return delegationFor(user, kind, roles, amount, requesterId);
    }

    /** Décision sur l'étape en cours. */
    @Transactional
    public void decide(long requestId, boolean approve, String comment, CurrentUser user) {
        Map<String, Object> req = db.require("SELECT * FROM validation_request WHERE id = :id FOR UPDATE",
                Db.p("id", requestId), "Demande introuvable");
        if (!"EN_ATTENTE".equals(req.get("status"))) {
            throw ApiException.conflict("Cette demande a déjà été traitée");
        }
        long requester = ((Number) req.get("requestedBy")).longValue();
        if (requester == user.id()) {
            throw ApiException.forbidden("Vous ne pouvez pas valider votre propre demande");
        }
        String kind = (String) req.get("kind");
        Map<String, Object> step = db.require("""
                \sSELECT id, step_no, roles FROM validation_step WHERE request_id = :r AND status = 'EN_ATTENTE'""",
                Db.p("r", requestId), "Étape introuvable");
        @SuppressWarnings("unchecked")
        String[] roles = ((List<Object>) step.get("roles")).stream().map(String::valueOf).toArray(String[]::new);
        Long amount = req.get("amount") == null ? null : ((Number) req.get("amount")).longValue();
        Long authority = authority(user, kind, roles, amount, requester);
        if (authority == null) {
            throw ApiException.forbidden("Cette demande doit être validée par : " + String.join(" ou ", roles)
                    + " (ou une personne à qui ce pouvoir a été délégué)");
        }
        if (db.count("SELECT count(*) FROM validation_step WHERE request_id = :r AND decided_by = :u",
                Db.p("r", requestId, "u", user.id())) > 0) {
            throw ApiException.forbidden("Vous avez déjà validé une étape de cette demande");
        }
        Long delegatedFrom = authority == 0 ? null : authority;
        db.update("""
                \sUPDATE validation_step SET status = :s, decided_by = :u, decided_at = now(), comment = :c, delegated_from = :d WHERE id = :id""",
                Db.p("id", step.get("id"), "s", approve ? "APPROUVE" : "REFUSE", "u", user.id(), "c", blank(comment), "d", delegatedFrom));
        String ref = (String) req.get("ref");
        String delegation = delegatedFrom == null ? "" : " · par délégation de "
                + db.value("SELECT first_name || ' ' || last_name FROM employee WHERE id = :id", Db.p("id", delegatedFrom), String.class);
        int stepNo = ((Number) step.get("stepNo")).intValue();
        String category = ADMIN_KINDS.contains(kind) ? Journal.SECURITE : Journal.VALIDATIONS;
        if (!approve) {
            finish(req, false, user);
            journal.log("VALIDATION_REFUSEE", category, "Demande refusée", ref + " · " + req.get("beneficiary") + delegation, amount, null, null);
            return;
        }
        Map<String, Object> next = db.one("SELECT id, roles FROM validation_step WHERE request_id = :r AND step_no = :n",
                Db.p("r", requestId, "n", stepNo + 1));
        if (next != null) {
            db.update("UPDATE validation_step SET status = 'EN_ATTENTE' WHERE id = :id", Db.p("id", next.get("id")));
            journal.log("VALIDATION_ETAPE", category, "Montant approuvé", ref + " · étape " + stepNo + delegation, amount, null, null);
            @SuppressWarnings("unchecked")
            List<Object> nextRoles = (List<Object>) next.get("roles");
            notifyApprovers(requestId, kind, nextRoles.stream().map(String::valueOf).toArray(String[]::new), amount, requester);
        } else {
            finish(req, true, user);
            journal.log("VALIDATION_APPROUVEE", category, "Demande approuvée", ref + " · " + req.get("beneficiary") + delegation, amount, null, null);
        }
    }

    private void finish(Map<String, Object> req, boolean approved, CurrentUser user) {
        long id = ((Number) req.get("id")).longValue();
        db.update("UPDATE validation_request SET status = :s, decided_at = now() WHERE id = :id",
                Db.p("id", id, "s", approved ? "APPROUVEE" : "REFUSEE"));
        if (!approved) {
            db.update("UPDATE validation_step SET status = 'REFUSE' WHERE request_id = :id AND status = 'A_VENIR'", Db.p("id", id));
        }
        events.publishEvent(new Decided(id, (String) req.get("kind"),
                req.get("targetId") == null ? null : ((Number) req.get("targetId")).longValue(), approved, user.id()));
        notifier.send(new Notifier.Message("VALIDATION_DECISION", approved ? Notifier.INFO : Notifier.ATTENTION,
                "Demande " + req.get("ref") + (approved ? " approuvée" : " refusée"),
                req.get("summary") + " · " + req.get("beneficiary") + "\nDécision : " + (approved ? "approuvée" : "refusée") + " par " + user.name(),
                "/validations?id=" + id, null), List.of(((Number) req.get("requestedBy")).longValue()), List.of());
    }

    /** Valideurs de l'étape et délégués actifs (sans le demandeur). */
    private void notifyApprovers(long requestId, String kind, String[] roles, Long amount, long requester) {
        Map<String, Object> req = db.one("SELECT ref, summary, beneficiary, amount FROM validation_request WHERE id = :id",
                Db.p("id", requestId));
        Set<Long> ids = new LinkedHashSet<>();
        db.list("SELECT id FROM employee WHERE active AND role_code = ANY(:r) AND id <> :req",
                Db.p("r", db.array("text", roles), "req", requester)).forEach(r -> ids.add(((Number) r.get("id")).longValue()));
        db.list("""
                \sSELECT DISTINCT e.id FROM validation_delegation d JOIN employee f ON f.id = d.from_employee
                JOIN employee e ON e.active AND (e.id = d.to_employee OR e.role_code = d.to_role)
                WHERE d.revoked_at IS NULL AND d.starts_at <= now() AND (d.ends_at IS NULL OR d.ends_at > now())
                  AND f.role_code = ANY(:r) AND (d.kinds IS NULL OR :k = ANY(d.kinds))
                  AND (d.max_amount IS NULL OR CAST(:a AS bigint) IS NULL OR CAST(:a AS bigint) <= d.max_amount) AND e.id <> :req""",
                Db.p("r", db.array("text", roles), "k", kind, "a", amount, "req", requester))
                .forEach(r -> ids.add(((Number) r.get("id")).longValue()));
        boolean admin = ADMIN_KINDS.contains(kind);
        // règle « sans rôle » : les destinataires sont calculés ici (valideurs + délégués, jamais le demandeur)
        notifier.send(new Notifier.Message(admin ? "ADMIN_DEMANDE" : "VALIDATION", admin ? Notifier.URGENT : Notifier.ATTENTION,
                (admin ? "Action sensible à approuver · " : "Validation demandée · ") + req.get("ref"),
                req.get("summary") + " · " + req.get("beneficiary")
                        + (req.get("amount") == null ? "" : "\nMontant : " + req.get("amount") + " FCFA") + "\nÀ traiter dans l'écran Validations.",
                "/validations?id=" + requestId, null), new ArrayList<>(ids), List.of(), true);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
