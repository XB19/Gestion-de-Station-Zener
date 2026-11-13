package tg.zener.station.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;

/** Produits et prix par profil d'acheteur (tableau « Prix par profil » de l'écran Ventes & stocks). */
@RestController
public class ProductController {

    static final List<String> PROFILES = List.of("GRAND_PUBLIC", "EMPLOYE_STATION", "EMPLOYE_GROUPE", "PARTENAIRE");

    private final Db db;
    private final Journal journal;
    private final PriceService prices;

    public ProductController(Db db, Journal journal, PriceService prices) {
        this.db = db;
        this.journal = journal;
        this.prices = prices;
    }

    /** Historique des prix (changements appliqués, planifiés, annulés). */
    @GetMapping("/api/prices/history")
    public List<Map<String, Object>> history(@RequestParam(required = false) String productCode, @RequestParam(required = false) String status) {
        return db.list("""
                \sSELECT pc.id, p.code AS product_code, p.name AS product_name, p.category, pc.profile, pc.old_price, pc.new_price,
                       pc.new_price - pc.old_price AS change, pc.effective_at, pc.status, pc.created_at, pc.applied_at, pc.note,
                       e.first_name || ' ' || e.last_name AS created_by_name
                FROM price_change pc JOIN product p ON p.id = pc.product_id LEFT JOIN employee e ON e.id = pc.created_by
                WHERE (CAST(:p AS text) IS NULL OR p.code = :p) AND (CAST(:s AS text) IS NULL OR pc.status = :s)
                ORDER BY pc.status = 'PLANIFIE' DESC, pc.effective_at DESC, pc.id DESC LIMIT 300""",
                Db.p("p", productCode, "s", status));
    }

    @PostMapping("/api/prices/changes/{id}/cancel")
    @PreAuthorize("hasAuthority('PRIX')")
    public Map<String, Object> cancelChange(@PathVariable long id) {
        prices.cancel(id, Auth.user().id());
        return Map.of("ok", true);
    }

    @GetMapping("/api/products")
    public List<Map<String, Object>> products(@RequestParam(required = false) String category) {
        return db.list("""
                \sSELECT id, code, name, category, unit, gas_size_kg, deposit_amount, card_type_code, active
                FROM product WHERE CAST(:c AS text) IS NULL OR category = :c ORDER BY category, id""", Db.p("c", category));
    }

    /** Une ligne par produit, une colonne par profil : grand public, employé station, employé groupe, partenaire. */
    @GetMapping("/api/prices")
    public List<Map<String, Object>> prices(@RequestParam(required = false) String category) {
        List<Map<String, Object>> rows = products(category);
        for (Map<String, Object> r : rows) {
            Map<String, Object> prices = new LinkedHashMap<>();
            for (Map<String, Object> p : db.list("SELECT profile, price FROM product_price WHERE product_id = :id",
                    Db.p("id", r.get("id")))) {
                prices.put((String) p.get("profile"), p.get("price"));
            }
            r.put("prices", prices);
        }
        return rows;
    }

    public record PriceChange(@NotBlank String productCode,
                              @NotBlank @Pattern(regexp = "GRAND_PUBLIC|EMPLOYE_STATION|EMPLOYE_GROUPE|PARTENAIRE") String profile,
                              @NotNull @Min(0) Long price) {}

    /** effectiveAt : date d'effet (nulle ou passée : immédiat ; future : changement planifié). */
    public record PriceUpdate(@NotEmpty List<@Valid PriceChange> changes, java.time.Instant effectiveAt, String note) {}

    @PutMapping("/api/prices")
    @PreAuthorize("hasAuthority('PRIX')")
    @Transactional
    public List<Map<String, Object>> updatePrices(@Valid @RequestBody PriceUpdate u) {
        for (PriceChange c : u.changes()) {
            Long productId = db.value("SELECT id FROM product WHERE code = :c", Db.p("c", c.productCode()), Long.class);
            if (productId == null) {
                throw ApiException.notFound("Produit inconnu : " + c.productCode());
            }
            prices.set(productId, c.profile(), c.price(), u.effectiveAt(), u.note() == null || u.note().isBlank() ? null : u.note().trim(),
                    Auth.user().id());
        }
        return prices(null);
    }

    public record ProductForm(@NotBlank String code, @NotBlank String name,
                              @NotBlank @Pattern(regexp = "CARBURANT|LUBRIFIANT|GAZ|ACCESSOIRE") String category,
                              @NotBlank String unit, BigDecimal gasSizeKg, Long depositAmount, @NotNull @Min(0) Long publicPrice,
                              Boolean active) {}

    @PostMapping("/api/products")
    @PreAuthorize("hasAuthority('PRIX')")
    @Transactional
    public List<Map<String, Object>> create(@Valid @RequestBody ProductForm f) {
        long id = db.insert("""
                \sINSERT INTO product (code, name, category, unit, gas_size_kg, deposit_amount)
                VALUES (upper(:c), :n, :cat, :u, :g, :d)""",
                Db.p("c", f.code().trim(), "n", f.name().trim(), "cat", f.category(), "u", f.unit(), "g", f.gasSizeKg(),
                        "d", f.depositAmount()));
        for (String profile : PROFILES) {
            db.update("INSERT INTO product_price (product_id, profile, price, updated_by) VALUES (:p, :pr, :v, :u)",
                    Db.p("p", id, "pr", profile, "v", f.publicPrice(), "u", Auth.user().id()));
            db.update("""
                    \sINSERT INTO price_change (product_id, profile, new_price, effective_at, status, created_by, applied_at, note)
                    VALUES (:p, :pr, :v, now(), 'APPLIQUE', :u, now(), 'Prix initial')""",
                    Db.p("p", id, "pr", profile, "v", f.publicPrice(), "u", Auth.user().id()));
        }
        journal.log("PRODUIT", Journal.MODIFICATIONS, "Produit créé", f.name(), null, null, null);
        return prices(null);
    }

    @PutMapping("/api/products/{id}")
    @PreAuthorize("hasAuthority('PRIX')")
    public List<Map<String, Object>> update(@PathVariable long id, @Valid @RequestBody ProductForm f) {
        int n = db.update("""
                \sUPDATE product SET name = :n, unit = :u, gas_size_kg = :g, deposit_amount = :d, active = COALESCE(:a, active)
                WHERE id = :id AND category <> 'CARTE'""",
                Db.p("id", id, "n", f.name().trim(), "u", f.unit(), "g", f.gasSizeKg(), "d", f.depositAmount(), "a", f.active()));
        if (n == 0) {
            throw ApiException.notFound("Produit introuvable");
        }
        return prices(null);
    }

    static String label(String profile) {
        return switch (profile) {
            case "EMPLOYE_STATION" -> "employé station";
            case "EMPLOYE_GROUPE" -> "employé groupe";
            case "PARTENAIRE" -> "partenaire";
            default -> "grand public";
        };
    }
}
