package tg.zener.station.loyalty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;

/**
 * Fidélité et remises (phase 2d). Les achats payés par carte rapportent des points au titulaire (règles par
 * catégorie ou par produit, cartes VIP multipliées) ; les points se convertissent en crédit sur une carte.
 * Les remises (palier de consommation sur 12 mois, flotte, profil) s'appliquent ligne par ligne aux achats par carte :
 * la meilleure remise applicable est retenue. Les cartes des employés ne cumulent pas de points.
 */
@Service
public class LoyaltyService {

    /** Remise retenue pour une ligne : taux (%) et règle. */
    public record Discount(BigDecimal pct, Long ruleId) {
        public static final Discount NONE = new Discount(BigDecimal.ZERO, null);

        public long on(long amount) {
            return pct.signum() == 0 ? 0 : pct.multiply(BigDecimal.valueOf(amount)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .longValue();
        }
    }

    private final Db db;
    private final Settings settings;
    private final Journal journal;

    public LoyaltyService(Db db, Settings settings, Journal journal) {
        this.db = db;
        this.settings = settings;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ remises

    /** Consommation du titulaire sur 12 mois glissants (achats payés par ses cartes). */
    public long consumption12m(long holderId) {
        return db.sum("""
                \sSELECT COALESCE(sum(x.total), 0) FROM sale x JOIN card c ON c.id = x.card_id
                WHERE c.holder_id = :h AND x.at >= now() - interval '12 months'""", Db.p("h", holderId));
    }

    /** Règles de remise actives qui peuvent concerner cette carte (filtrées ensuite par produit). */
    public List<Map<String, Object>> rulesFor(Map<String, Object> card) {
        Object holder = card.get("holderId");
        if (holder == null) {
            return List.of();
        }
        long consumption = consumption12m(((Number) holder).longValue());
        return db.list("""
                \sSELECT id, category, product_id, discount_pct FROM discount_rule
                WHERE active AND ((scope = 'PALIER' AND min_consumption <= :c)
                               OR (scope = 'GROUPE' AND card_group_id = CAST(:g AS bigint))
                               OR (scope = 'PROFIL' AND profile = :p))
                ORDER BY discount_pct DESC""",
                Db.p("c", consumption, "g", card.get("groupId"), "p", card.get("holderProfile") == null ? "GRAND_PUBLIC" : card.get("holderProfile")));
    }

    /** Meilleure remise parmi les règles pour un produit (règle produit, règle catégorie ou règle générale). */
    public static Discount best(List<Map<String, Object>> rules, long productId, String category) {
        for (Map<String, Object> r : rules) {   // triées par taux décroissant
            Object p = r.get("productId");
            Object c = r.get("category");
            boolean matches = p != null ? ((Number) p).longValue() == productId : c == null || c.equals(category);
            if (matches) {
                return new Discount((BigDecimal) r.get("discountPct"), ((Number) r.get("id")).longValue());
            }
        }
        return Discount.NONE;
    }

    // ------------------------------------------------------------------ points

    /** Points gagnés par une vente payée par carte (lignes : productId, catégorie, montant net). */
    @Transactional
    public long earn(long saleId, Map<String, Object> card, List<Object[]> lines, Long employeeId) {
        if (!settings.getBool("loyalty.enabled", true) || card.get("holderId") == null || "EMPLOYE".equals(card.get("holderKind"))) {
            return 0;
        }
        Map<Long, BigDecimal> byProduct = new LinkedHashMap<>();
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (Map<String, Object> r : db.list("SELECT category, product_id, points_per_1000 FROM loyalty_rule WHERE active")) {
            if (r.get("productId") != null) {
                byProduct.put(((Number) r.get("productId")).longValue(), (BigDecimal) r.get("pointsPer1000"));
            } else {
                byCategory.put((String) r.get("category"), (BigDecimal) r.get("pointsPer1000"));
            }
        }
        BigDecimal points = BigDecimal.ZERO;
        for (Object[] l : lines) {
            long productId = (Long) l[0];
            BigDecimal rate = byProduct.getOrDefault(productId, byCategory.get((String) l[1]));
            if (rate != null) {
                points = points.add(rate.multiply(BigDecimal.valueOf((Long) l[2])).divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP));
            }
        }
        if ("VIP".equals(card.get("typeCode"))) {
            points = points.multiply(BigDecimal.valueOf(settings.getDouble("loyalty.vip_multiplier", 2)));
        }
        long earned = points.setScale(0, RoundingMode.DOWN).longValue();
        if (earned <= 0) {
            return 0;
        }
        long holderId = ((Number) card.get("holderId")).longValue();
        long balance = db.value("UPDATE holder SET loyalty_points = loyalty_points + :p WHERE id = :h RETURNING loyalty_points",
                Db.p("h", holderId, "p", earned), Long.class);
        db.update("""
                \sINSERT INTO loyalty_movement (holder_id, card_id, sale_id, kind, points, balance_after, employee_id)
                VALUES (:h, :c, :s, 'GAIN', :p, :b, :e)""",
                Db.p("h", holderId, "c", card.get("id"), "s", saleId, "p", earned, "b", balance, "e", employeeId));
        db.update("UPDATE sale SET points_earned = :p WHERE id = :s", Db.p("s", saleId, "p", earned));
        return earned;
    }

    /** Utilisation de points : convertis en crédit sur une carte du titulaire. */
    @Transactional
    public Map<String, Object> redeem(long cardId, long points, String note, long employeeId) {
        Map<String, Object> c = db.require("""
                \sSELECT c.id, c.number, c.status, c.holder_id, h.loyalty_points FROM card c JOIN holder h ON h.id = c.holder_id
                WHERE c.id = :id FOR UPDATE OF c, h""", Db.p("id", cardId), "Carte introuvable");
        long min = settings.getLong("loyalty.min_redeem", 100);
        if (points < min) {
            throw new ApiException("Utilisation minimale : " + min + " points");
        }
        if (points > ((Number) c.get("loyaltyPoints")).longValue()) {
            throw ApiException.conflict("Points insuffisants (" + c.get("loyaltyPoints") + " disponibles)");
        }
        if (!"ACTIVE".equals(c.get("status"))) {
            throw ApiException.conflict("La carte doit être active");
        }
        long amount = points * settings.getLong("loyalty.point_value", 5);
        long holderId = ((Number) c.get("holderId")).longValue();
        long left = db.value("UPDATE holder SET loyalty_points = loyalty_points - :p WHERE id = :h RETURNING loyalty_points",
                Db.p("h", holderId, "p", points), Long.class);
        long balance = db.value("UPDATE card SET balance = balance + :a WHERE id = :c RETURNING balance", Db.p("c", cardId, "a", amount), Long.class);
        db.update("""
                \sINSERT INTO card_movement (card_id, kind, amount, balance_after, detail, employee_id)
                VALUES (:c, 'FIDELITE', :a, :b, :d, :e)""",
                Db.p("c", cardId, "a", amount, "b", balance, "d", points + " points de fidélité", "e", employeeId));
        db.update("""
                \sINSERT INTO loyalty_movement (holder_id, card_id, kind, points, balance_after, amount, employee_id, note)
                VALUES (:h, :c, 'UTILISATION', :p, :b, :a, :e, :n)""",
                Db.p("h", holderId, "c", cardId, "p", -points, "b", left, "a", amount, "e", employeeId, "n", note));
        journal.logAs(employeeId, "FIDELITE", Journal.RECHARGES, "Points de fidélité utilisés",
                points + " points → carte " + CardService.mask((String) c.get("number")), amount, null, null);
        return Map.of("points", points, "amount", amount, "pointsLeft", left, "cardBalance", balance);
    }

    /** Ajustement manuel (geste commercial, correction). */
    @Transactional
    public Map<String, Object> adjust(long holderId, long points, String note, long employeeId) {
        if (points == 0) {
            throw new ApiException("Indiquez un nombre de points");
        }
        Long left = db.value("""
                \sUPDATE holder SET loyalty_points = loyalty_points + :p WHERE id = :h AND loyalty_points + :p >= 0 RETURNING loyalty_points""",
                Db.p("h", holderId, "p", points), Long.class);
        if (left == null) {
            throw ApiException.conflict("Le solde de points ne peut pas devenir négatif");
        }
        db.update("""
                \sINSERT INTO loyalty_movement (holder_id, kind, points, balance_after, employee_id, note)
                VALUES (:h, 'AJUSTEMENT', :p, :b, :e, :n)""", Db.p("h", holderId, "p", points, "b", left, "e", employeeId, "n", note));
        journal.logAs(employeeId, "FIDELITE", Journal.MODIFICATIONS, "Points de fidélité ajustés", (points > 0 ? "+" : "") + points + " · " + note,
                null, null, null);
        return Map.of("pointsLeft", left);
    }
}
