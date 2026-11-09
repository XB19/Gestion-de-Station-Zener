package tg.zener.station.recharges;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.Refs;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.validations.ValidationService;

/**
 * Recharges : au-dessus du palier (par carte), la recharge attend sa validation ;
 * sinon la carte est créditée immédiatement.
 */
@Service
public class RechargeService {

    private final Db db;
    private final Refs refs;
    private final ValidationService validations;
    private final Journal journal;
    private final LiveFeed live;

    public RechargeService(Db db, Refs refs, ValidationService validations, Journal journal, LiveFeed live) {
        this.db = db;
        this.refs = refs;
        this.validations = validations;
        this.journal = journal;
        this.live = live;
    }

    public record Request(String cardNumber, long amount, String channel, String paymentMethod, String paymentRef,
                          Long stationId, Long pdvId) {}

    /** Recharge unitaire ou à distance. */
    @Transactional
    public Map<String, Object> recharge(Request r, CurrentUser user) {
        if (r.amount() <= 0) {
            throw new ApiException("Montant invalide");
        }
        Map<String, Object> card = db.require("""
                \sSELECT c.id, c.number, c.status, h.kind AS holder_kind, h.name AS holder_name
                FROM card c LEFT JOIN holder h ON h.id = c.holder_id WHERE c.number = :n""",
                Db.p("n", CardService.normalize(r.cardNumber())), "Carte introuvable");
        if (!"ACTIVE".equals(card.get("status"))) {
            throw ApiException.conflict("Seule une carte active peut être rechargée");
        }
        boolean needsValidation = r.amount() > validations.threshold();
        long id = db.insert("""
                \sINSERT INTO recharge (ref, card_id, amount, channel, payment_method, payment_ref, status, station_id, pdv_id,
                                      requested_by)
                VALUES (:ref, :c, :a, :ch, :pm, :pr, :st, :s, :p, :u)""",
                Db.p("ref", refs.recharge(), "c", card.get("id"), "a", r.amount(), "ch", r.channel(), "pm", r.paymentMethod(),
                        "pr", r.paymentRef(), "st", needsValidation ? "EN_VALIDATION" : "CREDITEE", "s", r.stationId(),
                        "p", r.pdvId(), "u", user.id()));
        if (needsValidation) {
            long v = validations.create(new ValidationService.NewRequest(ValidationService.RECHARGE,
                    (String) card.get("holderName"), "DISTANCE".equals(r.channel()) ? "Recharge à distance" : "Recharge unitaire",
                    r.amount(), "ENTREPRISE".equals(card.get("holderKind")),
                    "DISTANCE".equals(r.channel()) ? "Recharge à distance" : "Recharge au guichet",
                    paymentLabel(r.paymentMethod(), r.paymentRef()), id), user);
            db.update("UPDATE recharge SET validation_id = :v WHERE id = :id", Db.p("id", id, "v", v));
        } else {
            credit(id, user.id());
        }
        return db.one("SELECT * FROM recharge WHERE id = :id", Db.p("id", id));
    }

    /** Crédite la carte (recharge validée ou sous le palier). */
    @Transactional
    public void credit(long rechargeId, Long actorId) {
        Map<String, Object> r = db.require("""
                \sSELECT r.id, r.card_id, r.amount, r.channel, r.station_id, r.pdv_id, r.payment_method, c.number
                FROM recharge r JOIN card c ON c.id = r.card_id WHERE r.id = :id FOR UPDATE OF r""",
                Db.p("id", rechargeId), "Recharge introuvable");
        long amount = ((Number) r.get("amount")).longValue();
        Long balance = db.value("UPDATE card SET balance = balance + :a WHERE id = :c RETURNING balance",
                Db.p("a", amount, "c", r.get("cardId")), Long.class);
        db.update("UPDATE recharge SET status = 'CREDITEE', credited_at = now() WHERE id = :id", Db.p("id", rechargeId));
        db.update("""
                \sINSERT INTO card_movement (card_id, kind, amount, balance_after, station_id, recharge_id, detail)
                VALUES (:c, 'RECHARGE', :a, :b, :s, :r, :d)""",
                Db.p("c", r.get("cardId"), "a", amount, "b", balance, "s", r.get("stationId"), "r", rechargeId,
                        "d", channelLabel((String) r.get("channel"))));
        if (!"MASSE".equals(r.get("channel"))) {
            Long station = r.get("stationId") == null ? null : ((Number) r.get("stationId")).longValue();
            Long pdv = r.get("pdvId") == null ? null : ((Number) r.get("pdvId")).longValue();
            journal.logAs(actorId, "RECHARGE", Journal.RECHARGES, "Recharge carte " + CardService.mask((String) r.get("number")),
                    channelLabel((String) r.get("channel")) + " · " + paymentLabel((String) r.get("paymentMethod"), null),
                    amount, station, pdv);
        }
    }

    @EventListener
    public void onDecision(ValidationService.Decided d) {
        if (ValidationService.RECHARGE.equals(d.kind()) || ValidationService.RECHARGE_MASSE.equals(d.kind())) {
            for (Map<String, Object> r : db.list("SELECT id FROM recharge WHERE validation_id = :v AND status = 'EN_VALIDATION'",
                    Db.p("v", d.requestId()))) {
                long id = ((Number) r.get("id")).longValue();
                if (d.approved()) {
                    credit(id, d.deciderId());
                } else {
                    db.update("UPDATE recharge SET status = 'REFUSEE' WHERE id = :id", Db.p("id", id));
                }
            }
            if (ValidationService.RECHARGE_MASSE.equals(d.kind()) && d.targetId() != null) {
                finishBatchIfDone(d.targetId(), d.deciderId());
            }
        }
    }

    void finishBatchIfDone(long batchId, Long actorId) {
        long waiting = db.count("SELECT count(*) FROM recharge WHERE batch_id = :b AND status = 'EN_VALIDATION'", Db.p("b", batchId));
        if (waiting == 0) {
            db.update("UPDATE recharge_batch SET status = 'TERMINE' WHERE id = :b", Db.p("b", batchId));
        }
        Map<String, Object> t = db.one("""
                \sSELECT count(*) AS n, COALESCE(sum(amount), 0) AS total FROM recharge
                WHERE batch_id = :b AND status = 'CREDITEE' AND credited_at > now() - interval '1 minute'""", Db.p("b", batchId));
        if (((Number) t.get("n")).longValue() > 0) {
            journal.logAs(actorId, "RECHARGE_MASSE", Journal.RECHARGES, "Recharge en masse",
                    t.get("n") + " cartes créditées · lot " + batchId, ((Number) t.get("total")).longValue(), null, null);
            live.publish("recharge-batch", null, Map.of("batchId", batchId));
        }
    }

    // ------------------------------------------------------------------ recharge à distance

    /** Le nom saisi correspond-il au titulaire ? (sans accents ni majuscules ; tous les mots doivent figurer) */
    public boolean nameMatches(String cardNumber, String name) {
        Map<String, Object> h = db.one("""
                \sSELECT h.name FROM card c JOIN holder h ON h.id = c.holder_id WHERE c.number = :n""",
                Db.p("n", CardService.normalize(cardNumber)));
        if (h == null || name == null || name.isBlank()) {
            return false;
        }
        Set<String> holder = words((String) h.get("name"));
        Set<String> given = words(name);
        return !given.isEmpty() && holder.containsAll(given) && given.size() >= Math.min(2, holder.size());
    }

    private static Set<String> words(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        return new HashSet<>(Arrays.asList(n.replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")));
    }

    static String channelLabel(String channel) {
        return switch (channel) {
            case "DISTANCE" -> "À distance";
            case "MASSE" -> "En masse";
            default -> "Au guichet";
        };
    }

    static String paymentLabel(String method, String ref) {
        String label = switch (method) {
            case "MOBILE_MONEY" -> "Mobile money";
            case "VIREMENT" -> "Virement";
            case "CARTE_BANCAIRE" -> "Carte bancaire";
            default -> "Espèces";
        };
        return ref == null || ref.isBlank() ? label : label + " · " + ref;
    }
}
