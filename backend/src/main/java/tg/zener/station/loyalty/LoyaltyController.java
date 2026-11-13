package tg.zener.station.loyalty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;

/** Écran Fidélité : règles de points, remises, clients et mouvements ; utilisation des points sur une carte. */
@RestController
public class LoyaltyController {

    private static final String READ = "hasAuthority('FIDELITE') or hasAuthority('TRANSACTIONS')";

    private final Db db;
    private final LoyaltyService loyalty;
    private final Settings settings;
    private final Journal journal;

    public LoyaltyController(Db db, LoyaltyService loyalty, Settings settings, Journal journal) {
        this.db = db;
        this.loyalty = loyalty;
        this.settings = settings;
        this.journal = journal;
    }

    @GetMapping("/api/loyalty/summary")
    @PreAuthorize(READ)
    public Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>(db.one("""
                \sSELECT COALESCE(sum(points) FILTER (WHERE kind = 'GAIN' AND at >= date_trunc('month', now())), 0) AS earned_month,
                       COALESCE(-sum(points) FILTER (WHERE kind = 'UTILISATION' AND at >= date_trunc('month', now())), 0) AS used_month,
                       COALESCE(sum(amount) FILTER (WHERE kind = 'UTILISATION' AND at >= date_trunc('month', now())), 0) AS credited_month,
                       (SELECT COALESCE(sum(loyalty_points), 0) FROM holder) AS outstanding,
                       (SELECT count(*) FROM holder WHERE loyalty_points > 0) AS members,
                       (SELECT COALESCE(sum(discount_total), 0) FROM sale WHERE at >= date_trunc('month', now())) AS discounts_month
                FROM loyalty_movement""", Map.of()));
        s.put("pointValue", settings.getLong("loyalty.point_value", 5));
        s.put("minRedeem", settings.getLong("loyalty.min_redeem", 100));
        s.put("vipMultiplier", settings.getDouble("loyalty.vip_multiplier", 2));
        s.put("enabled", settings.getBool("loyalty.enabled", true));
        return s;
    }

    // ------------------------------------------------------------------ règles de points

    @GetMapping("/api/loyalty/rules")
    @PreAuthorize(READ)
    public List<Map<String, Object>> rules() {
        return db.list("""
                \sSELECT r.id, r.category, r.product_id, p.name AS product_name, r.points_per_1000, r.active, r.updated_at
                FROM loyalty_rule r LEFT JOIN product p ON p.id = r.product_id ORDER BY r.product_id NULLS FIRST, r.category""");
    }

    public record RuleForm(String category, String productCode, @NotNull @DecimalMin("0") BigDecimal pointsPer1000, Boolean active) {}

    @PostMapping("/api/loyalty/rules")
    @PreAuthorize("hasAuthority('FIDELITE')")
    @Transactional
    public List<Map<String, Object>> saveRule(@Valid @RequestBody RuleForm f) {
        Long productId = f.productCode() == null || f.productCode().isBlank() ? null
                : db.value("SELECT id FROM product WHERE code = :c", Db.p("c", f.productCode()), Long.class);
        String category = productId != null ? null : f.category();
        if (productId == null && category == null) {
            throw new ApiException("Catégorie ou produit requis");
        }
        String conflict = productId != null ? "(product_id) WHERE product_id IS NOT NULL" : "(category) WHERE category IS NOT NULL";
        db.update("""
                \sINSERT INTO loyalty_rule (category, product_id, points_per_1000, active, updated_by) VALUES (:c, :p, :r, COALESCE(:a, true), :u)
                ON CONFLICT""" + " " + conflict + """
                 \sDO UPDATE SET points_per_1000 = EXCLUDED.points_per_1000, active = EXCLUDED.active, updated_at = now(),
                    updated_by = EXCLUDED.updated_by""",
                Db.p("c", category, "p", productId, "r", f.pointsPer1000(), "a", f.active(), "u", Auth.user().id()));
        journal.log("FIDELITE_REGLE", Journal.MODIFICATIONS, "Règle de points modifiée",
                (productId != null ? f.productCode() : category) + " · " + f.pointsPer1000().stripTrailingZeros().toPlainString() + " pt / 1 000 F",
                null, null, null);
        return rules();
    }

    // ------------------------------------------------------------------ remises

    @GetMapping("/api/discounts")
    @PreAuthorize(READ)
    public List<Map<String, Object>> discounts() {
        return db.list("""
                \sSELECT d.id, d.name, d.scope, d.category, d.product_id, p.name AS product_name, d.min_consumption, d.card_group_id,
                       g.name AS group_name, h.name AS holder_name, d.profile, d.discount_pct, d.active, d.created_at,
                       (SELECT count(*) FROM sale_line l WHERE l.discount_rule_id = d.id) AS uses,
                       (SELECT COALESCE(sum(l.discount_amount), 0) FROM sale_line l WHERE l.discount_rule_id = d.id) AS granted
                FROM discount_rule d LEFT JOIN product p ON p.id = d.product_id LEFT JOIN card_group g ON g.id = d.card_group_id
                LEFT JOIN holder h ON h.id = g.holder_id ORDER BY d.active DESC, d.scope, d.discount_pct DESC""");
    }

    public record DiscountForm(@NotBlank String name, @NotBlank @Pattern(regexp = "PALIER|GROUPE|PROFIL") String scope,
                               @Pattern(regexp = "CARBURANT|LUBRIFIANT|GAZ|ACCESSOIRE") String category, String productCode,
                               Long minConsumption, Long cardGroupId,
                               @Pattern(regexp = "GRAND_PUBLIC|EMPLOYE_STATION|EMPLOYE_GROUPE|PARTENAIRE") String profile,
                               @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "100", inclusive = false) BigDecimal discountPct,
                               Boolean active) {}

    @PostMapping("/api/discounts")
    @PreAuthorize("hasAuthority('FIDELITE')")
    public List<Map<String, Object>> createDiscount(@Valid @RequestBody DiscountForm f) {
        db.update("""
                \sINSERT INTO discount_rule (name, scope, category, product_id, min_consumption, card_group_id, profile, discount_pct, active, created_by)
                VALUES (:n, :s, :c, (SELECT id FROM product WHERE code = :pc), :m, :g, :pr, :d, COALESCE(:a, true), :u)""", params(f));
        journal.log("REMISE", Journal.MODIFICATIONS, "Remise créée", f.name() + " · " + f.discountPct().stripTrailingZeros().toPlainString() + " %",
                null, null, null);
        return discounts();
    }

    @PutMapping("/api/discounts/{id}")
    @PreAuthorize("hasAuthority('FIDELITE')")
    public List<Map<String, Object>> updateDiscount(@PathVariable long id, @Valid @RequestBody DiscountForm f) {
        Map<String, Object> p = params(f);
        p.put("id", id);
        int n = db.update("""
                \sUPDATE discount_rule SET name = :n, scope = :s, category = :c, product_id = (SELECT id FROM product WHERE code = :pc),
                       min_consumption = :m, card_group_id = :g, profile = :pr, discount_pct = :d, active = COALESCE(:a, active)
                WHERE id = :id""", p);
        if (n == 0) {
            throw ApiException.notFound("Remise introuvable");
        }
        journal.log("REMISE", Journal.MODIFICATIONS, "Remise modifiée", f.name(), null, null, null);
        return discounts();
    }

    private Map<String, Object> params(DiscountForm f) {
        boolean palier = "PALIER".equals(f.scope());
        boolean groupe = "GROUPE".equals(f.scope());
        boolean profil = "PROFIL".equals(f.scope());
        if (palier && f.minConsumption() == null || groupe && f.cardGroupId() == null || profil && f.profile() == null) {
            throw new ApiException(palier ? "Consommation minimale requise" : groupe ? "Flotte requise" : "Profil requis");
        }
        return Db.p("n", f.name().trim(), "s", f.scope(), "c", blank(f.category()), "pc", blank(f.productCode()),
                "m", palier ? f.minConsumption() : null, "g", groupe ? f.cardGroupId() : null, "pr", profil ? f.profile() : null,
                "d", f.discountPct(), "a", f.active(), "u", Auth.user().id());
    }

    // ------------------------------------------------------------------ clients et mouvements

    /** Titulaires et leurs points, consommation 12 mois et palier atteint. */
    @GetMapping("/api/loyalty/holders")
    @PreAuthorize(READ)
    public List<Map<String, Object>> holders(@RequestParam(required = false) String q) {
        return db.list("""
                \sSELECT h.id, h.name, h.kind, h.loyalty_points, c12.amount AS consumption_12m,
                       (SELECT d.name FROM discount_rule d WHERE d.active AND d.scope = 'PALIER' AND d.min_consumption <= c12.amount
                        ORDER BY d.min_consumption DESC LIMIT 1) AS tier,
                       (SELECT max(m.at) FROM loyalty_movement m WHERE m.holder_id = h.id) AS last_movement_at,
                       (SELECT c.id FROM card c WHERE c.holder_id = h.id AND c.status = 'ACTIVE' ORDER BY c.id LIMIT 1) AS card_id
                FROM holder h
                CROSS JOIN LATERAL (SELECT COALESCE(sum(x.total), 0) AS amount FROM sale x JOIN card c ON c.id = x.card_id
                                    WHERE c.holder_id = h.id AND x.at >= now() - interval '12 months') c12
                WHERE h.kind <> 'EMPLOYE' AND (h.loyalty_points > 0 OR c12.amount > 0)
                  AND (CAST(:q AS text) IS NULL OR lower(h.name) LIKE '%' || lower(:q) || '%')
                ORDER BY h.loyalty_points DESC LIMIT 200""", Db.p("q", blank(q)));
    }

    @GetMapping("/api/loyalty/movements")
    @PreAuthorize(READ)
    public Page movements(@RequestParam(required = false) Long holderId, @RequestParam(required = false) Integer page,
                          @RequestParam(required = false) Integer size) {
        int sz = Page.size(size);
        int pg = Page.page(page);
        String where = " WHERE (CAST(:h AS bigint) IS NULL OR m.holder_id = :h)";
        Map<String, Object> p = Db.p("h", holderId, "l", sz, "o", pg * sz);
        return new Page(db.list("""
                \sSELECT m.id, m.at, m.kind, m.points, m.balance_after, m.amount, m.note, h.name AS holder_name, c.number, x.total AS sale_total,
                       e.first_name || ' ' || e.last_name AS employee_name
                FROM loyalty_movement m JOIN holder h ON h.id = m.holder_id LEFT JOIN card c ON c.id = m.card_id
                LEFT JOIN sale x ON x.id = m.sale_id LEFT JOIN employee e ON e.id = m.employee_id""" + where
                + " ORDER BY m.at DESC, m.id DESC LIMIT :l OFFSET :o", p),
                db.count("SELECT count(*) FROM loyalty_movement m" + where, p), pg, sz);
    }

    public record RedeemForm(@NotNull Long cardId, @Positive long points, String note) {}

    @PostMapping("/api/loyalty/redeem")
    @PreAuthorize("hasAuthority('FIDELITE') or hasAuthority('RECHARGER')")
    public Map<String, Object> redeem(@Valid @RequestBody RedeemForm f) {
        return loyalty.redeem(f.cardId(), f.points(), blank(f.note()), Auth.user().id());
    }

    public record AdjustForm(@NotNull Long holderId, long points, @NotBlank String note) {}

    @PostMapping("/api/loyalty/adjust")
    @PreAuthorize("hasAuthority('FIDELITE')")
    public Map<String, Object> adjust(@Valid @RequestBody AdjustForm f) {
        return loyalty.adjust(f.holderId(), f.points(), f.note().trim(), Auth.user().id());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
