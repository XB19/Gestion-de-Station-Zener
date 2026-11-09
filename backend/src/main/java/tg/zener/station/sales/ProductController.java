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

    public ProductController(Db db, Journal journal) {
        this.db = db;
        this.journal = journal;
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

    public record PriceUpdate(@NotEmpty List<@Valid PriceChange> changes) {}

    @PutMapping("/api/prices")
    @PreAuthorize("hasAuthority('PRIX')")
    @Transactional
    public List<Map<String, Object>> updatePrices(@Valid @RequestBody PriceUpdate u) {
        for (PriceChange c : u.changes()) {
            Map<String, Object> p = db.require("SELECT id, name, card_type_code FROM product WHERE code = :c",
                    Db.p("c", c.productCode()), "Produit inconnu : " + c.productCode());
            Long before = db.value("SELECT price FROM product_price WHERE product_id = :p AND profile = :pr",
                    Db.p("p", p.get("id"), "pr", c.profile()), Long.class);
            db.update("""
                    \sINSERT INTO product_price (product_id, profile, price, updated_at, updated_by) VALUES (:p, :pr, :v, now(), :u)
                    ON CONFLICT (product_id, profile) DO UPDATE SET price = EXCLUDED.price, updated_at = now(),
                        updated_by = EXCLUDED.updated_by""",
                    Db.p("p", p.get("id"), "pr", c.profile(), "v", c.price(), "u", Auth.user().id()));
            if (p.get("cardTypeCode") != null && "GRAND_PUBLIC".equals(c.profile())) {
                db.update("UPDATE card_type SET price = :v WHERE code = :c", Db.p("v", c.price(), "c", p.get("cardTypeCode")));
            }
            if (before == null || before.longValue() != c.price()) {
                journal.log("PRIX", Journal.MODIFICATIONS, "Prix modifié",
                        p.get("name") + " · " + label(c.profile()) + " · " + (before == null ? "—" : before) + " → " + c.price() + " F",
                        null, null, null);
            }
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
