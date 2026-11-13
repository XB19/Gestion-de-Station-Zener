package tg.zener.station.sales;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;

/**
 * Prix par produit et profil avec historique (phase 2g, price_logs de LTStation) : chaque changement est conservé
 * (ancien, nouveau, date d'effet, auteur) ; un changement daté dans le futur est planifié puis appliqué à l'heure dite.
 */
@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);

    private final Db db;
    private final Journal journal;

    public PriceService(Db db, Journal journal) {
        this.db = db;
        this.journal = journal;
    }

    /** Applique (date d'effet nulle ou passée) ou planifie un prix. Renvoie true si le prix a changé maintenant. */
    @Transactional
    public boolean set(long productId, String profile, long price, Instant effectiveAt, String note, Long employeeId) {
        Map<String, Object> p = db.require("SELECT id, name, card_type_code FROM product WHERE id = :id", Db.p("id", productId),
                "Produit introuvable");
        Long before = db.value("SELECT price FROM product_price WHERE product_id = :p AND profile = :pr", Db.p("p", productId, "pr", profile),
                Long.class);
        if (effectiveAt != null && effectiveAt.isAfter(Instant.now().plusSeconds(30))) {
            // un seul changement planifié par produit et profil : le nouveau remplace l'ancien
            db.update("""
                    \sUPDATE price_change SET status = 'ANNULE' WHERE product_id = :p AND profile = :pr AND status = 'PLANIFIE'""",
                    Db.p("p", productId, "pr", profile));
            db.update("""
                    \sINSERT INTO price_change (product_id, profile, old_price, new_price, effective_at, status, created_by, note)
                    VALUES (:p, :pr, :o, :n, :at, 'PLANIFIE', :u, :note)""",
                    Db.p("p", productId, "pr", profile, "o", before, "n", price, "at", java.sql.Timestamp.from(effectiveAt), "u", employeeId,
                            "note", note));
            journal.logAs(employeeId, "PRIX_PLANIFIE", Journal.MODIFICATIONS, "Changement de prix planifié",
                    p.get("name") + " · " + label(profile) + " · " + price + " F à partir du " + effectiveAt, null, null, null);
            return false;
        }
        if (before != null && before == price) {
            return false;
        }
        apply(productId, profile, before, price, employeeId, note, (String) p.get("name"), (String) p.get("cardTypeCode"));
        return true;
    }

    private void apply(long productId, String profile, Long before, long price, Long employeeId, String note, String name, String cardType) {
        db.update("""
                \sINSERT INTO product_price (product_id, profile, price, updated_at, updated_by) VALUES (:p, :pr, :v, now(), :u)
                ON CONFLICT (product_id, profile) DO UPDATE SET price = EXCLUDED.price, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                Db.p("p", productId, "pr", profile, "v", price, "u", employeeId));
        db.update("""
                \sINSERT INTO price_change (product_id, profile, old_price, new_price, effective_at, status, created_by, applied_at, note)
                VALUES (:p, :pr, :o, :n, now(), 'APPLIQUE', :u, now(), :note)""",
                Db.p("p", productId, "pr", profile, "o", before, "n", price, "u", employeeId, "note", note));
        if (cardType != null && "GRAND_PUBLIC".equals(profile)) {
            db.update("UPDATE card_type SET price = :v WHERE code = :c", Db.p("v", price, "c", cardType));
        }
        journal.logAs(employeeId, "PRIX", Journal.MODIFICATIONS, "Prix modifié",
                name + " · " + label(profile) + " · " + (before == null ? "—" : before) + " → " + price + " F", null, null, null);
    }

    /** Chaque minute : applique les changements planifiés arrivés à échéance. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    @Transactional
    public void applyPlanned() {
        for (Map<String, Object> c : db.list("""
                \sSELECT pc.id, pc.product_id, pc.profile, pc.new_price, pc.created_by, pc.note, p.name, p.card_type_code
                FROM price_change pc JOIN product p ON p.id = pc.product_id
                WHERE pc.status = 'PLANIFIE' AND pc.effective_at <= now() ORDER BY pc.effective_at FOR UPDATE OF pc SKIP LOCKED""")) {
            long productId = ((Number) c.get("productId")).longValue();
            String profile = (String) c.get("profile");
            Long before = db.value("SELECT price FROM product_price WHERE product_id = :p AND profile = :pr",
                    Db.p("p", productId, "pr", profile), Long.class);
            long price = ((Number) c.get("newPrice")).longValue();
            db.update("""
                    \sINSERT INTO product_price (product_id, profile, price, updated_at, updated_by) VALUES (:p, :pr, :v, now(), :u)
                    ON CONFLICT (product_id, profile) DO UPDATE SET price = EXCLUDED.price, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                    Db.p("p", productId, "pr", profile, "v", price, "u", c.get("createdBy")));
            db.update("UPDATE price_change SET status = 'APPLIQUE', applied_at = now(), old_price = :o WHERE id = :id",
                    Db.p("id", c.get("id"), "o", before));
            if (c.get("cardTypeCode") != null && "GRAND_PUBLIC".equals(profile)) {
                db.update("UPDATE card_type SET price = :v WHERE code = :c", Db.p("v", price, "c", c.get("cardTypeCode")));
            }
            journal.logAs(null, "PRIX", Journal.MODIFICATIONS, "Prix planifié appliqué",
                    c.get("name") + " · " + label(profile) + " · " + (before == null ? "—" : before) + " → " + price + " F", null, null, null);
            log.info("Prix planifié appliqué : {} {} → {} F", c.get("name"), profile, price);
        }
    }

    @Transactional
    public void cancel(long changeId, Long employeeId) {
        if (db.update("UPDATE price_change SET status = 'ANNULE' WHERE id = :id AND status = 'PLANIFIE'", Db.p("id", changeId)) == 0) {
            throw ApiException.conflict("Ce changement n'est pas (ou plus) planifié");
        }
        journal.logAs(employeeId, "PRIX_PLANIFIE", Journal.MODIFICATIONS, "Changement de prix planifié annulé", null, null, null, null);
    }

    static String label(String profile) {
        return switch (profile) {
            case "GRAND_PUBLIC" -> "grand public";
            case "EMPLOYE_STATION" -> "employé station";
            case "EMPLOYE_GROUPE" -> "employé groupe";
            case "PARTENAIRE" -> "partenaire";
            default -> profile;
        };
    }
}
