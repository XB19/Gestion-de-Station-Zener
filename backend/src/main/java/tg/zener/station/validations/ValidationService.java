package tg.zener.station.validations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Mailer;
import tg.zener.station.common.Refs;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.CurrentUser;

/**
 * Circuit de validation. Recharges au-dessus du palier : Comptable, puis DAF pour les cartes
 * entreprises. Ouverture d'un point de vente temporaire : Admin ou DAF.
 * Le demandeur ne peut pas valider sa propre demande.
 */
@Service
public class ValidationService {

    public static final String RECHARGE = "RECHARGE";
    public static final String RECHARGE_MASSE = "RECHARGE_MASSE";
    public static final String OUVERTURE_PDV = "OUVERTURE_PDV";

    /** Publié quand une demande est entièrement approuvée ou refusée. */
    public record Decided(long requestId, String kind, Long targetId, boolean approved, long deciderId) {}

    public record NewRequest(String kind, String beneficiary, String summary, Long amount, boolean companyCard,
                             String source, String paymentInfo, Long targetId) {}

    private final Db db;
    private final Refs refs;
    private final Settings settings;
    private final Journal journal;
    private final Mailer mailer;
    private final ApplicationEventPublisher events;

    public ValidationService(Db db, Refs refs, Settings settings, Journal journal, Mailer mailer,
                             ApplicationEventPublisher events) {
        this.db = db;
        this.refs = refs;
        this.settings = settings;
        this.journal = journal;
        this.mailer = mailer;
        this.events = events;
    }

    public long threshold() {
        return settings.getLong("validation.threshold", 500_000);
    }

    @Transactional
    public long create(NewRequest r, CurrentUser requester) {
        String ref = refs.validation();
        long id = db.insert("""
                \sINSERT INTO validation_request (ref, kind, beneficiary, summary, amount, company_card, source, payment_info,
                                                target_id, requested_by)
                VALUES (:ref, :k, :b, :s, :a, :cc, :src, :pay, :t, :u)""",
                Db.p("ref", ref, "k", r.kind(), "b", r.beneficiary(), "s", r.summary(), "a", r.amount(),
                        "cc", r.companyCard(), "src", r.source(), "pay", r.paymentInfo(), "t", r.targetId(), "u", requester.id()));
        List<String[]> steps = new ArrayList<>();
        if (OUVERTURE_PDV.equals(r.kind())) {
            steps.add(new String[] {"ADMIN", "DAF"});
        } else {
            steps.add(new String[] {"COMPTABLE"});
            if (r.companyCard() && settings.getBool("validation.company_needs_daf", true)) {
                steps.add(new String[] {"DAF"});
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            db.update("""
                    \sINSERT INTO validation_step (request_id, step_no, roles, status)
                    VALUES (:r, :n, :roles, :s)""",
                    Db.p("r", id, "n", i + 1, "roles", db.array("text", steps.get(i)), "s", i == 0 ? "EN_ATTENTE" : "A_VENIR"));
        }
        journal.log("VALIDATION_DEMANDEE", Journal.VALIDATIONS, "Validation demandée", ref + " · " + r.beneficiary(),
                r.amount(), requester.stationId(), null);
        notifyApprovers(id, steps.get(0));
        return id;
    }

    /** Décision sur l'étape en cours. */
    @Transactional
    public void decide(long requestId, boolean approve, String comment, CurrentUser user) {
        Map<String, Object> req = db.require("SELECT * FROM validation_request WHERE id = :id FOR UPDATE",
                Db.p("id", requestId), "Demande introuvable");
        if (!"EN_ATTENTE".equals(req.get("status"))) {
            throw ApiException.conflict("Cette demande a déjà été traitée");
        }
        if (((Number) req.get("requestedBy")).longValue() == user.id()) {
            throw ApiException.forbidden("Vous ne pouvez pas valider votre propre demande");
        }
        String kind = (String) req.get("kind");
        String right = OUVERTURE_PDV.equals(kind) ? "PDV_VALIDER" : "VALIDER_MONTANTS";
        if (!user.can(right)) {
            throw ApiException.forbidden("Vous n'avez pas le droit de valider cette demande");
        }
        Map<String, Object> step = db.require("""
                \sSELECT id, step_no, roles FROM validation_step WHERE request_id = :r AND status = 'EN_ATTENTE'""",
                Db.p("r", requestId), "Étape introuvable");
        @SuppressWarnings("unchecked")
        List<Object> roles = (List<Object>) step.get("roles");
        if (!roles.contains(user.role())) {
            throw ApiException.forbidden("Cette étape doit être validée par : " + String.join(" ou ", roles.stream().map(String::valueOf).toList()));
        }
        if (db.count("SELECT count(*) FROM validation_step WHERE request_id = :r AND decided_by = :u",
                Db.p("r", requestId, "u", user.id())) > 0) {
            throw ApiException.forbidden("Vous avez déjà validé une étape de cette demande");
        }
        db.update("""
                \sUPDATE validation_step SET status = :s, decided_by = :u, decided_at = now(), comment = :c WHERE id = :id""",
                Db.p("id", step.get("id"), "s", approve ? "APPROUVE" : "REFUSE", "u", user.id(), "c", blank(comment)));
        String ref = (String) req.get("ref");
        Long amount = req.get("amount") == null ? null : ((Number) req.get("amount")).longValue();
        int stepNo = ((Number) step.get("stepNo")).intValue();
        if (!approve) {
            finish(req, false, user);
            journal.log("VALIDATION_REFUSEE", Journal.VALIDATIONS, "Demande refusée", ref + " · " + req.get("beneficiary"),
                    amount, null, null);
            return;
        }
        Map<String, Object> next = db.one("""
                \sSELECT id, roles FROM validation_step WHERE request_id = :r AND step_no = :n""",
                Db.p("r", requestId, "n", stepNo + 1));
        if (next != null) {
            db.update("UPDATE validation_step SET status = 'EN_ATTENTE' WHERE id = :id", Db.p("id", next.get("id")));
            journal.log("VALIDATION_ETAPE", Journal.VALIDATIONS, "Montant approuvé",
                    ref + " · " + (stepNo == 1 ? "1re" : stepNo + "e") + " validation", amount, null, null);
            @SuppressWarnings("unchecked")
            List<Object> nextRoles = (List<Object>) next.get("roles");
            notifyApprovers(requestId, nextRoles.stream().map(String::valueOf).toArray(String[]::new));
        } else {
            finish(req, true, user);
            journal.log("VALIDATION_APPROUVEE", Journal.VALIDATIONS, "Demande approuvée", ref + " · " + req.get("beneficiary"),
                    amount, null, null);
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
        Map<String, Object> requester = db.one("SELECT email FROM employee WHERE id = :id", Db.p("id", req.get("requestedBy")));
        if (requester != null && requester.get("email") != null) {
            mailer.queue(List.of((String) requester.get("email")),
                    "Demande " + req.get("ref") + (approved ? " approuvée" : " refusée"),
                    req.get("summary") + " · " + req.get("beneficiary") + "\nDécision : " + (approved ? "approuvée" : "refusée")
                            + " par " + user.name(), "VALIDATION");
        }
    }

    private void notifyApprovers(long requestId, String[] roles) {
        Map<String, Object> req = db.one("SELECT ref, summary, beneficiary, amount FROM validation_request WHERE id = :id",
                Db.p("id", requestId));
        List<String> to = db.list("SELECT email FROM employee WHERE active AND email IS NOT NULL AND role_code = ANY(:r)",
                Db.p("r", db.array("text", roles))).stream().map(r -> (String) r.get("email")).toList();
        mailer.queue(to, "Validation demandée · " + req.get("ref"),
                req.get("summary") + " · " + req.get("beneficiary")
                        + (req.get("amount") == null ? "" : "\nMontant : " + req.get("amount") + " FCFA")
                        + "\nÀ valider dans l'écran Validations.", "VALIDATION");
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
