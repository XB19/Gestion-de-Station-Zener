package tg.zener.station.validations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.cards.CardEvents;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.CurrentUser;

/**
 * Actions sensibles de l'Admin soumises à une seconde personne (Admin ou DAF, jamais le demandeur) :
 * droits d'un rôle, paramètres financiers (liste security.sensitive_settings), ajustement manuel du solde d'une
 * carte, attribution du rôle Admin ou DAF. La demande garde le contenu à appliquer (payload) ; il est appliqué
 * à l'approbation, avec l'avant / après au journal (catégorie Sécurité).
 */
@Service
public class SensitiveActions {

    private final Db db;
    private final Settings settings;
    private final ValidationService validations;
    private final Journal journal;
    private final CardEvents cardEvents;

    public SensitiveActions(Db db, Settings settings, ValidationService validations, Journal journal, CardEvents cardEvents) {
        this.db = db;
        this.settings = settings;
        this.validations = validations;
        this.journal = journal;
        this.cardEvents = cardEvents;
    }

    public static final Set<String> SENSITIVE_ROLES = Set.of("ADMIN", "DAF");

    public boolean sensitiveSetting(String key) {
        return settings.getList("security.sensitive_settings").stream().map(String::trim).anyMatch(key::equals);
    }

    private Map<String, Object> pending(long id) {
        Map<String, Object> v = db.one("SELECT id, ref, kind, status FROM validation_request WHERE id = :id", Db.p("id", id));
        Map<String, Object> out = new LinkedHashMap<>(v);
        out.put("pending", true);
        out.put("message", "Demande " + v.get("ref") + " envoyée : une seconde personne (Admin ou DAF) doit l'approuver");
        return out;
    }

    private static String need(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException("Motif requis pour une action sensible");
        }
        return reason.trim();
    }

    // ------------------------------------------------------------------ demandes

    @Transactional
    public Map<String, Object> requestRoleRights(String roleCode, List<String> rights, String reason, CurrentUser u) {
        String label = db.value("SELECT label FROM role WHERE code = :c", Db.p("c", roleCode), String.class);
        if (label == null) {
            throw ApiException.notFound("Rôle introuvable");
        }
        if (db.count("SELECT count(*) FROM validation_request WHERE kind = 'DROITS_ROLE' AND status = 'EN_ATTENTE' AND payload->>'role' = :r",
                Db.p("r", roleCode)) > 0) {
            throw ApiException.conflict("Une modification des droits de ce rôle attend déjà une approbation");
        }
        Set<String> before = currentRights(roleCode);
        List<String> added = new ArrayList<>(rights);
        added.removeAll(before);
        List<String> removed = new ArrayList<>(before);
        removed.removeAll(rights);
        String summary = "Droits du rôle " + label + (added.isEmpty() ? "" : " · + " + String.join(", ", added))
                + (removed.isEmpty() ? "" : " · − " + String.join(", ", removed));
        long id = validations.create(new ValidationService.NewRequest(ValidationService.DROITS_ROLE, label, summary, null, false,
                need(reason), null, null), u, Map.of("role", roleCode, "rights", rights, "before", before, "reason", reason.trim()));
        return pending(id);
    }

    @Transactional
    public Map<String, Object> requestSettings(Map<String, String> values, String reason, CurrentUser u) {
        Map<String, Object> before = new LinkedHashMap<>();
        values.keySet().forEach(k -> before.put(k, settings.get(k, null)));
        String summary = "Paramètres · " + String.join(", ", values.entrySet().stream()
                .map(e -> e.getKey() + " : " + before.get(e.getKey()) + " → " + e.getValue()).toList());
        long id = validations.create(new ValidationService.NewRequest(ValidationService.PARAMETRES, "Paramètres financiers", summary, null,
                false, need(reason), null, null), u, Map.of("values", values, "before", before, "reason", reason.trim()));
        return pending(id);
    }

    @Transactional
    public Map<String, Object> requestAdjustment(long cardId, long amount, String reason, CurrentUser u) {
        Map<String, Object> c = db.require("""
                \sSELECT c.number, c.balance, h.name FROM card c LEFT JOIN holder h ON h.id = c.holder_id WHERE c.id = :id""",
                Db.p("id", cardId), "Carte introuvable");
        if (amount == 0) {
            throw new ApiException("Montant nul");
        }
        if (((Number) c.get("balance")).longValue() + amount < 0) {
            throw ApiException.conflict("Le solde ne peut pas devenir négatif");
        }
        long id = validations.create(new ValidationService.NewRequest(ValidationService.AJUSTEMENT_SOLDE,
                c.get("name") == null ? CardService.mask((String) c.get("number")) : (String) c.get("name"),
                "Ajustement de solde · carte " + CardService.mask((String) c.get("number")) + " · " + (amount > 0 ? "+" : "") + amount + " F",
                Math.abs(amount), false, need(reason), null, cardId), u, Map.of("cardId", cardId, "amount", amount, "reason", reason.trim()));
        return pending(id);
    }

    /** Rôle Admin ou DAF pour un employé (création : compte inactif jusqu'à l'approbation). */
    @Transactional
    public Map<String, Object> requestSensitiveRole(long employeeId, String role, String previousRole, boolean activate, String reason,
                                                    CurrentUser u) {
        Map<String, Object> e = db.require("SELECT first_name || ' ' || last_name AS name FROM employee WHERE id = :id",
                Db.p("id", employeeId), "Employé introuvable");
        String label = db.value("SELECT label FROM role WHERE code = :c", Db.p("c", role), String.class);
        long id = validations.create(new ValidationService.NewRequest(ValidationService.COMPTE_SENSIBLE, (String) e.get("name"),
                (activate ? "Nouveau compte " : "Rôle ") + label + " pour " + e.get("name"), null, false,
                reason == null || reason.isBlank() ? "Compte " + label : reason.trim(), null, employeeId), u,
                Map.of("employeeId", employeeId, "role", role, "previousRole", previousRole == null ? "" : previousRole, "activate", activate));
        return pending(id);
    }

    // ------------------------------------------------------------------ application après approbation

    @EventListener
    @Transactional
    public void onDecision(ValidationService.Decided d) {
        if (!ValidationService.ADMIN_KINDS.contains(d.kind())) {
            return;
        }
        Map<String, Object> req = db.one("SELECT ref, payload::text AS payload, requested_by FROM validation_request WHERE id = :id",
                Db.p("id", d.requestId()));
        Map<String, Object> p = parse((String) req.get("payload"));
        String ref = (String) req.get("ref");
        if (!d.approved()) {
            if (ValidationService.COMPTE_SENSIBLE.equals(d.kind()) && Boolean.TRUE.equals(p.get("activate"))) {
                journal.logAs(d.deciderId(), "COMPTE_REFUSE", Journal.SECURITE, "Compte non créé (refusé)", ref, null, null, null);
            }
            return;
        }
        switch (d.kind()) {
            case ValidationService.DROITS_ROLE -> {
                String role = (String) p.get("role");
                @SuppressWarnings("unchecked")
                List<String> rights = (List<String>) p.get("rights");
                Set<String> before = currentRights(role);
                db.update("DELETE FROM role_right WHERE role_code = :c", Db.p("c", role));
                if (!rights.isEmpty()) {
                    db.update("INSERT INTO role_right (role_code, right_code) SELECT :c, unnest(CAST(:r AS text[]))",
                            Db.p("c", role, "r", db.array("text", rights.toArray())));
                }
                auditAs(d, "DROITS_ROLE", "Droits du rôle modifiés (approuvé)", ref + " · " + role, Map.of("rights", before),
                        Map.of("rights", rights), (String) p.get("reason"));
            }
            case ValidationService.PARAMETRES -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> values = (Map<String, Object>) p.get("values");
                Map<String, Object> before = new LinkedHashMap<>();
                values.forEach((k, v) -> {
                    before.put(k, settings.get(k, null));
                    settings.set(k, String.valueOf(v));
                });
                auditAs(d, "PARAMETRES", "Paramètres financiers modifiés (approuvé)", ref + " · " + String.join(", ", values.keySet()),
                        before, values, (String) p.get("reason"));
            }
            case ValidationService.AJUSTEMENT_SOLDE -> {
                long cardId = ((Number) p.get("cardId")).longValue();
                long amount = ((Number) p.get("amount")).longValue();
                Map<String, Object> c = db.require("""
                        \sUPDATE card SET balance = balance + :a WHERE id = :id AND balance + :a >= 0 RETURNING number, balance""",
                        Db.p("id", cardId, "a", amount), "Solde insuffisant pour cet ajustement");
                long after = ((Number) c.get("balance")).longValue();
                db.update("""
                        \sINSERT INTO card_movement (card_id, kind, amount, balance_after, detail, employee_id)
                        VALUES (:c, 'AJUSTEMENT', :a, :b, :d, :u)""",
                        Db.p("c", cardId, "a", amount, "b", after, "d", "Ajustement · " + p.get("reason") + " · " + ref,
                                "u", ((Number) req.get("requestedBy")).longValue()));
                cardEvents.recordAs(d.deciderId(), cardId, CardEvents.MODIFICATION, "Ajustement de solde approuvé · " + ref, null, null);
                auditAs(d, "AJUSTEMENT_SOLDE", "Ajustement de solde (approuvé)", ref + " · " + CardService.mask((String) c.get("number")),
                        Map.of("balance", after - amount), Map.of("balance", after), (String) p.get("reason"));
            }
            case ValidationService.COMPTE_SENSIBLE -> {
                long employeeId = ((Number) p.get("employeeId")).longValue();
                String role = (String) p.get("role");
                db.update("UPDATE employee SET role_code = :r, active = CASE WHEN :act THEN true ELSE active END WHERE id = :id",
                        Db.p("id", employeeId, "r", role, "act", Boolean.TRUE.equals(p.get("activate"))));
                auditAs(d, "COMPTE_SENSIBLE", "Rôle " + role + " attribué (approuvé)", ref, Map.of("role", p.get("previousRole")),
                        Map.of("role", role), null);
            }
            default -> {
            }
        }
    }

    private void auditAs(ValidationService.Decided d, String type, String title, String detail, Object before, Object after, String reason) {
        journal.audit(type, Journal.SECURITE, title, detail, before, after, reason, null);
    }

    private Set<String> currentRights(String role) {
        Set<String> s = new LinkedHashSet<>();
        db.list("SELECT right_code FROM role_right WHERE role_code = :c ORDER BY right_code", Db.p("c", role))
                .forEach(r -> s.add((String) r.get("rightCode")));
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Contenu de la demande illisible", e);
        }
    }
}
