package tg.zener.station.fleets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.cards.CardEvents;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Refs;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.validations.ValidationService;

/**
 * Flottes d'entreprise : compte flotte (recharge, dotation des cartes, reprise), affectation des véhicules
 * et des chauffeurs aux cartes. Équivalent simplifié de joy_fill_fleet_* de LTStation.
 * <p>
 * Une flotte est un groupe de cartes (card_group) d'un titulaire ENTREPRISE. Le compte flotte est crédité
 * par une recharge (validation Comptable puis DAF au-dessus du palier) ; il ne paie rien lui-même : il est
 * réparti sur les cartes de la flotte (dotation) et peut récupérer le solde d'une carte (reprise).
 */
@Service
public class FleetService {

    private final Db db;
    private final Refs refs;
    private final Journal journal;
    private final ValidationService validations;
    private final CardEvents cardEvents;

    public FleetService(Db db, Refs refs, Journal journal, ValidationService validations, CardEvents cardEvents) {
        this.db = db;
        this.refs = refs;
        this.journal = journal;
        this.validations = validations;
        this.cardEvents = cardEvents;
    }

    /** Flotte et son titulaire ; refus si ce n'est pas une flotte d'entreprise. */
    Map<String, Object> fleet(long groupId) {
        Map<String, Object> g = db.require("""
                \sSELECT g.id, g.name, g.balance, g.active, g.holder_id, h.name AS holder_name, h.kind AS holder_kind
                FROM card_group g JOIN holder h ON h.id = g.holder_id WHERE g.id = :id""", Db.p("id", groupId), "Flotte introuvable");
        if (!"ENTREPRISE".equals(g.get("holderKind"))) {
            throw new ApiException("Ce groupe n'appartient pas à une entreprise");
        }
        return g;
    }

    private static String label(Map<String, Object> g) {
        return g.get("holderName") + " · " + g.get("name");
    }

    // ------------------------------------------------------------------ recharge du compte flotte

    /** Recharge du compte flotte ; au-dessus du palier, elle part en validation (Comptable puis DAF). */
    @Transactional
    public Map<String, Object> recharge(long groupId, long amount, String paymentMethod, String paymentRef, String note,
                                        CurrentUser user) {
        if (amount <= 0) {
            throw new ApiException("Montant invalide");
        }
        Map<String, Object> g = fleet(groupId);
        if (!Boolean.TRUE.equals(g.get("active"))) {
            throw ApiException.conflict("Cette flotte est désactivée");
        }
        String ref = refs.fleetRecharge();
        boolean needsValidation = amount > validations.threshold();
        long id = db.insert("""
                \sINSERT INTO fleet_recharge (ref, group_id, amount, payment_method, payment_ref, status, requested_by, note)
                VALUES (:ref, :g, :a, :pm, :pr, :st, :u, :n)""",
                Db.p("ref", ref, "g", groupId, "a", amount, "pm", paymentMethod, "pr", paymentRef,
                        "st", "EN_VALIDATION", "u", user.id(), "n", note));
        if (needsValidation) {
            long v = validations.create(new ValidationService.NewRequest(ValidationService.RECHARGE_FLOTTE, label(g),
                    "Recharge du compte flotte · " + ref, amount, true, "Compte flotte", paymentLabel(paymentMethod, paymentRef), id), user);
            db.update("UPDATE fleet_recharge SET validation_id = :v WHERE id = :id", Db.p("id", id, "v", v));
        } else {
            credit(id, user.id());
        }
        return db.one("SELECT * FROM fleet_recharge WHERE id = :id", Db.p("id", id));
    }

    /** Crédit du compte flotte (immédiat, ou après validation). */
    void credit(long rechargeId, long actorId) {
        Map<String, Object> r = db.require("""
                \sUPDATE fleet_recharge SET status = 'CREDITEE', credited_at = now() WHERE id = :id AND status = 'EN_VALIDATION'
                RETURNING group_id, amount, ref, payment_method, payment_ref""", Db.p("id", rechargeId), "Recharge introuvable");
        long groupId = num(r.get("groupId"));
        long amount = num(r.get("amount"));
        Long balance = db.value("UPDATE card_group SET balance = balance + :a WHERE id = :g RETURNING balance",
                Db.p("g", groupId, "a", amount), Long.class);
        db.update("""
                \sINSERT INTO fleet_movement (group_id, kind, amount, balance_after, fleet_recharge_id, employee_id, note)
                VALUES (:g, 'RECHARGE', :a, :b, :r, :e, :n)""",
                Db.p("g", groupId, "a", amount, "b", balance, "r", rechargeId, "e", actorId,
                        "n", r.get("ref") + " · " + paymentLabel((String) r.get("paymentMethod"), (String) r.get("paymentRef"))));
        Map<String, Object> g = fleet(groupId);
        journal.logAs(actorId, "RECHARGE_FLOTTE", Journal.RECHARGES, "Compte flotte rechargé", label(g) + " · " + r.get("ref"),
                amount, null, null);
    }

    @EventListener
    public void onDecision(ValidationService.Decided d) {
        if (!ValidationService.RECHARGE_FLOTTE.equals(d.kind()) || d.targetId() == null) {
            return;
        }
        if (d.approved()) {
            credit(d.targetId(), d.deciderId());
        } else {
            db.update("UPDATE fleet_recharge SET status = 'REFUSEE' WHERE id = :id AND status = 'EN_VALIDATION'", Db.p("id", d.targetId()));
        }
    }

    // ------------------------------------------------------------------ dotation et reprise

    public record Line(long cardId, long amount) {}

    /** Dotation : le compte flotte crédite les cartes de la flotte (une ou plusieurs, montants propres à chacune). */
    @Transactional
    public List<Map<String, Object>> allocate(long groupId, List<Line> lines, String note, long employeeId) {
        Map<String, Object> g = fleet(groupId);
        long total = lines.stream().mapToLong(Line::amount).sum();
        if (lines.isEmpty() || lines.stream().anyMatch(l -> l.amount() <= 0)) {
            throw new ApiException("Indiquez un montant positif pour chaque carte");
        }
        if (db.value("UPDATE card_group SET balance = balance - :t WHERE id = :g AND balance >= :t RETURNING balance",
                Db.p("g", groupId, "t", total), Long.class) == null) {
            throw ApiException.conflict("Solde du compte flotte insuffisant (" + fmt(num(g.get("balance"))) + " F pour " + fmt(total) + " F)");
        }
        // le solde du compte après chaque ligne, dans l'ordre
        long running = num(g.get("balance"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Line l : lines) {
            Map<String, Object> c = card(l.cardId(), groupId);
            String status = (String) c.get("status");
            if (!"ACTIVE".equals(status) && !"A_ACTIVER".equals(status)) {
                throw ApiException.conflict("Carte " + CardService.mask((String) c.get("number")) + " " + c.get("statusLabel")
                        + " : dotation impossible");
            }
            running -= l.amount();
            long before = num(c.get("balance"));
            long after = db.value("UPDATE card SET balance = balance + :a WHERE id = :c RETURNING balance",
                    Db.p("c", l.cardId(), "a", l.amount()), Long.class);
            long fm = db.insert("""
                    \sINSERT INTO fleet_movement (group_id, kind, amount, balance_after, card_id, card_balance_before, card_balance_after,
                                                employee_id, note)
                    VALUES (:g, 'DOTATION', :a, :b, :c, :cb, :ca, :e, :n)""",
                    Db.p("g", groupId, "a", -l.amount(), "b", running, "c", l.cardId(), "cb", before, "ca", after, "e", employeeId,
                            "n", note));
            db.update("""
                    \sINSERT INTO card_movement (card_id, kind, amount, balance_after, detail, employee_id, fleet_movement_id)
                    VALUES (:c, 'DOTATION', :a, :b, :d, :e, :fm)""",
                    Db.p("c", l.cardId(), "a", l.amount(), "b", after, "d", "Dotation · " + g.get("name"), "e", employeeId, "fm", fm));
            out.add(Map.of("cardId", l.cardId(), "amount", l.amount(), "balanceAfter", after));
        }
        journal.logAs(employeeId, "DOTATION_FLOTTE", Journal.RECHARGES, "Dotation des cartes de la flotte",
                label(g) + " · " + lines.size() + " carte(s)", total, null, null);
        return out;
    }

    /** Reprise : le solde (ou une partie) d'une carte revient au compte flotte. */
    @Transactional
    public Map<String, Object> reclaim(long groupId, long cardId, Long amount, String note, long employeeId) {
        Map<String, Object> g = fleet(groupId);
        Map<String, Object> c = card(cardId, groupId);
        long before = num(c.get("balance"));
        long a = amount == null ? before : amount;
        if (a <= 0) {
            throw new ApiException("Rien à reprendre sur cette carte");
        }
        Long after = db.value("UPDATE card SET balance = balance - :a WHERE id = :c AND balance >= :a RETURNING balance",
                Db.p("c", cardId, "a", a), Long.class);
        if (after == null) {
            throw ApiException.conflict("Solde de la carte insuffisant (" + fmt(before) + " F)");
        }
        long balance = db.value("UPDATE card_group SET balance = balance + :a WHERE id = :g RETURNING balance",
                Db.p("g", groupId, "a", a), Long.class);
        long fm = db.insert("""
                \sINSERT INTO fleet_movement (group_id, kind, amount, balance_after, card_id, card_balance_before, card_balance_after,
                                            employee_id, note)
                VALUES (:g, 'REPRISE', :a, :b, :c, :cb, :ca, :e, :n)""",
                Db.p("g", groupId, "a", a, "b", balance, "c", cardId, "cb", before, "ca", after, "e", employeeId, "n", note));
        db.update("""
                \sINSERT INTO card_movement (card_id, kind, amount, balance_after, detail, employee_id, fleet_movement_id)
                VALUES (:c, 'REPRISE', :a, :b, :d, :e, :fm)""",
                Db.p("c", cardId, "a", -a, "b", after, "d", "Reprise · " + g.get("name"), "e", employeeId, "fm", fm));
        journal.logAs(employeeId, "REPRISE_FLOTTE", Journal.RECHARGES, "Reprise sur une carte de la flotte",
                label(g) + " · " + CardService.mask((String) c.get("number")), a, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardId", cardId);
        out.put("amount", a);
        out.put("cardBalance", after);
        out.put("fleetBalance", balance);
        return out;
    }

    private Map<String, Object> card(long cardId, long groupId) {
        Map<String, Object> c = db.require("""
                \sSELECT c.id, c.number, c.status, c.balance, c.group_id,
                       CASE c.status WHEN 'ACTIVE' THEN 'active' WHEN 'BLOQUEE' THEN 'bloquée' WHEN 'INACTIVE' THEN 'inactive'
                                     WHEN 'A_ACTIVER' THEN 'à activer' ELSE 'en stock' END AS status_label
                FROM card c WHERE c.id = :id FOR UPDATE""", Db.p("id", cardId), "Carte introuvable");
        if (c.get("groupId") == null || num(c.get("groupId")) != groupId) {
            throw new ApiException("La carte " + CardService.mask((String) c.get("number")) + " n'appartient pas à cette flotte");
        }
        return c;
    }

    // ------------------------------------------------------------------ affectations

    /** Véhicule et chauffeur d'une carte (nuls pour retirer) ; les champs texte de la carte suivent. */
    @Transactional
    public void assignCard(long cardId, Long vehicleId, Long driverId, long employeeId) {
        Map<String, Object> c = db.require("SELECT id, holder_id, number FROM card WHERE id = :id FOR UPDATE", Db.p("id", cardId),
                "Carte introuvable");
        Object holder = c.get("holderId");
        Map<String, Object> v = vehicleId == null ? null : db.require(
                "SELECT plate, brand, model, holder_id FROM vehicle WHERE id = :id", Db.p("id", vehicleId), "Véhicule introuvable");
        Map<String, Object> d = driverId == null ? null : db.require(
                "SELECT name, holder_id FROM driver WHERE id = :id", Db.p("id", driverId), "Chauffeur introuvable");
        if ((v != null && !v.get("holderId").equals(holder)) || (d != null && !d.get("holderId").equals(holder))) {
            throw new ApiException("Le véhicule et le chauffeur doivent appartenir au titulaire de la carte");
        }
        String vehicleLabel = v == null ? null : String.join(" ", java.util.stream.Stream.of((String) v.get("brand"), (String) v.get("model"))
                .filter(x -> x != null && !x.isBlank()).toList());
        db.update("""
                \sUPDATE card SET vehicle_id = :v, driver_id = :d, vehicle_plate = :vp, vehicle_label = NULLIF(:vl, ''), driver_name = :dn
                WHERE id = :id""",
                Db.p("id", cardId, "v", vehicleId, "d", driverId, "vp", v == null ? null : v.get("plate"), "vl", vehicleLabel,
                        "dn", d == null ? null : d.get("name")));
        cardEvents.recordAs(employeeId, cardId, CardEvents.AFFECTATION,
                (v == null ? "sans véhicule" : (String) v.get("plate")) + " · " + (d == null ? "sans chauffeur" : (String) d.get("name")),
                null, null);
    }

    /** Affectation d'un chauffeur à un véhicule : clôt les affectations ouvertes de l'un et de l'autre. */
    @Transactional
    public void assignDriver(long vehicleId, Long driverId, String note, long employeeId) {
        Map<String, Object> v = db.require("SELECT id, holder_id, plate FROM vehicle WHERE id = :id FOR UPDATE", Db.p("id", vehicleId),
                "Véhicule introuvable");
        db.update("UPDATE vehicle_assignment SET ended_at = now() WHERE ended_at IS NULL AND (vehicle_id = :v OR driver_id = :d)",
                Db.p("v", vehicleId, "d", driverId));
        if (driverId != null) {
            Map<String, Object> d = db.require("SELECT holder_id, name FROM driver WHERE id = :id AND active", Db.p("id", driverId),
                    "Chauffeur introuvable ou désactivé");
            if (!d.get("holderId").equals(v.get("holderId"))) {
                throw new ApiException("Le chauffeur et le véhicule n'appartiennent pas à la même entreprise");
            }
            db.update("""
                    \sINSERT INTO vehicle_assignment (vehicle_id, driver_id, assigned_by, note) VALUES (:v, :d, :e, :n)""",
                    Db.p("v", vehicleId, "d", driverId, "e", employeeId, "n", note));
            // les cartes du véhicule suivent le nouveau chauffeur
            db.update("UPDATE card SET driver_id = :d, driver_name = :dn WHERE vehicle_id = :v",
                    Db.p("v", vehicleId, "d", driverId, "dn", d.get("name")));
        }
        journal.logAs(employeeId, "AFFECTATION_VEHICULE", Journal.MODIFICATIONS, "Affectation de véhicule",
                v.get("plate") + (driverId == null ? " · sans chauffeur" : " · " + db.value("SELECT name FROM driver WHERE id = :d",
                        Db.p("d", driverId), String.class)), null, null, null);
    }

    /** Kilométrage saisi au plein : enregistré sur la vente et sur le véhicule (s'il progresse). */
    public void odometer(long saleId, Integer km) {
        if (km == null) {
            return;
        }
        if (km < 0) {
            throw new ApiException("Kilométrage invalide");
        }
        db.update("UPDATE sale SET odometer_km = :k WHERE id = :s", Db.p("s", saleId, "k", km));
        db.update("""
                \sUPDATE vehicle v SET odometer_km = :k FROM sale x
                WHERE x.id = :s AND v.id = x.vehicle_id AND (v.odometer_km IS NULL OR v.odometer_km < :k)""", Db.p("s", saleId, "k", km));
    }

    static String paymentLabel(String method, String ref) {
        String m = switch (method == null ? "" : method) {
            case "ESPECES" -> "espèces";
            case "MOBILE_MONEY" -> "mobile money";
            case "VIREMENT" -> "virement";
            case "CARTE_BANCAIRE" -> "carte bancaire";
            default -> method;
        };
        return ref == null || ref.isBlank() ? m : m + " " + ref;
    }

    static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }

    static long num(Object o) {
        return ((Number) o).longValue();
    }
}
