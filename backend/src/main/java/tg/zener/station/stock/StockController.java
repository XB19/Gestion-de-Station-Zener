package tg.zener.station.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Refs;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/** Stocks & alertes, parc de bouteilles de gaz, entrées, inventaires et achats (écran 09). */
@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final Db db;
    private final StockService stock;
    private final ThresholdService thresholds;
    private final Refs refs;
    private final Journal journal;
    private final Settings settings;

    public StockController(Db db, StockService stock, ThresholdService thresholds, Refs refs, Journal journal,
                           Settings settings) {
        this.db = db;
        this.stock = stock;
        this.thresholds = thresholds;
        this.refs = refs;
        this.journal = journal;
        this.settings = settings;
    }

    /**
     * Niveaux de stock (cuves et articles) avec seuil, tendance sur 7 jours et état
     * (Critique, Bas, Normal). Tri : les plus urgents d'abord.
     */
    @GetMapping
    public List<Map<String, Object>> levels(@RequestParam(required = false) Long stationId,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) Boolean alertsOnly) {
        Long station = Auth.scopedStation(stationId);
        List<Map<String, Object>> rows = db.list("""
                \sSELECT * FROM (
                    SELECT 'CUVE' AS source, t.id, t.station_id, NULL::bigint AS pdv_id, s.short_name AS place,
                           p.code AS product_code, p.name AS product_name, p.category, 'L' AS unit,
                           t.volume AS quantity, t.capacity, t.threshold_manual, t.threshold_auto,
                           COALESCE(t.threshold_manual, t.threshold_auto) AS threshold, t.lead_days, t.trend_pct
                    FROM tank t JOIN product p ON p.id = t.product_id JOIN station s ON s.id = t.station_id
                    UNION ALL
                    SELECT 'ARTICLE', l.id, l.station_id, l.pdv_id, COALESCE(s.short_name, pv.name), p.code, p.name, p.category, p.unit,
                           l.quantity, l.capacity, l.threshold_manual, l.threshold_auto,
                           COALESCE(l.threshold_manual, l.threshold_auto), l.lead_days, l.trend_pct
                    FROM stock_level l JOIN product p ON p.id = l.product_id
                    LEFT JOIN station s ON s.id = l.station_id LEFT JOIN pdv pv ON pv.id = l.pdv_id
                ) x
                WHERE (CAST(:s AS bigint) IS NULL OR x.station_id = :s) AND (CAST(:c AS text) IS NULL OR x.category = :c)""",
                Db.p("s", station, "c", category));
        double ratio = settings.getDouble("alerts.critical_ratio", 0.6);
        for (Map<String, Object> r : rows) {
            BigDecimal q = (BigDecimal) r.get("quantity");
            BigDecimal th = (BigDecimal) r.get("threshold");
            BigDecimal cap = (BigDecimal) r.get("capacity");
            String state = th == null || q.compareTo(th) >= 0 ? "Normal"
                    : q.compareTo(th.multiply(BigDecimal.valueOf(ratio))) < 0 ? "Critique" : "Bas";
            r.put("state", state);
            BigDecimal ref = cap != null ? cap : th != null ? th.multiply(BigDecimal.valueOf(3)) : null;
            r.put("levelPct", ref == null || ref.signum() == 0 ? null
                    : Math.min(100, q.multiply(BigDecimal.valueOf(100)).divide(ref, 0, java.math.RoundingMode.HALF_UP).intValue()));
            r.put("autoThreshold", r.get("thresholdManual") == null && r.get("thresholdAuto") != null);
        }
        rows.sort((a, b) -> Integer.compare(rank(a.get("state")), rank(b.get("state"))));
        if (Boolean.TRUE.equals(alertsOnly)) {
            rows.removeIf(r -> "Normal".equals(r.get("state")));
        }
        return rows;
    }

    private static int rank(Object state) {
        return "Critique".equals(state) ? 0 : "Bas".equals(state) ? 1 : 2;
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(required = false) Long stationId,
                                            @RequestParam(required = false) Boolean includeResolved) {
        Long station = Auth.scopedStation(stationId);
        return db.list("""
                \sSELECT a.id, a.station_id, a.pdv_id, COALESCE(s.short_name, pv.name) AS place, p.name AS product_name,
                       p.category, a.tank_id, a.level, a.threshold, a.severity, a.raised_at, a.emailed_at, a.resolved_at
                FROM stock_alert a JOIN product p ON p.id = a.product_id
                LEFT JOIN station s ON s.id = a.station_id LEFT JOIN pdv pv ON pv.id = a.pdv_id
                WHERE (CAST(:s AS bigint) IS NULL OR a.station_id = :s) AND (:all OR a.resolved_at IS NULL)
                ORDER BY a.resolved_at NULLS FIRST, a.severity, a.raised_at DESC LIMIT 200""",
                Db.p("s", station, "all", Boolean.TRUE.equals(includeResolved)));
    }

    /** Parc de bouteilles : pleines (stock), vides, chez les clients, endommagées. */
    @GetMapping("/gas-bottles")
    public Map<String, Object> gasBottles(@RequestParam(required = false) Long stationId) {
        Long station = Auth.scopedStation(stationId);
        List<Map<String, Object>> byProduct = db.list("""
                \sSELECT p.id AS product_id, p.code, p.name, p.deposit_amount,
                       COALESCE((SELECT sum(l.quantity) FROM stock_level l WHERE l.product_id = p.id AND l.station_id IS NOT NULL
                                 AND (CAST(:s AS bigint) IS NULL OR l.station_id = :s)), 0) AS full_count,
                       COALESCE(sum(g.empty_count), 0) AS empty_count, COALESCE(sum(g.customer_count), 0) AS customer_count,
                       COALESCE(sum(g.damaged_count), 0) AS damaged_count
                FROM product p LEFT JOIN gas_bottle_stock g ON g.product_id = p.id AND (CAST(:s AS bigint) IS NULL OR g.station_id = :s)
                WHERE p.category = 'GAZ' GROUP BY p.id ORDER BY p.gas_size_kg""", Db.p("s", station));
        long full = 0;
        long empty = 0;
        long customers = 0;
        long damaged = 0;
        for (Map<String, Object> r : byProduct) {
            full += ((Number) r.get("fullCount")).longValue();
            empty += ((Number) r.get("emptyCount")).longValue();
            customers += ((Number) r.get("customerCount")).longValue();
            damaged += ((Number) r.get("damagedCount")).longValue();
        }
        return Map.of("full", full, "empty", empty, "withCustomers", customers, "damaged", damaged,
                "total", full + empty + customers + damaged, "byProduct", byProduct);
    }

    public record BottleAdjust(@NotNull Long stationId, @NotBlank String productCode, int emptyDelta, int customerDelta,
                               int damagedDelta, String note) {}

    /** Correction du parc (bouteilles endommagées, retour fournisseur…). */
    @PostMapping("/gas-bottles/adjust")
    @PreAuthorize("hasAuthority('STOCKS')")
    public Map<String, Object> adjustBottles(@Valid @RequestBody BottleAdjust f) {
        Auth.checkStation(f.stationId());
        Long productId = db.value("SELECT id FROM product WHERE code = :c AND category = 'GAZ'", Db.p("c", f.productCode()), Long.class);
        if (productId == null) {
            throw new ApiException("Produit gaz inconnu");
        }
        stock.bottles(f.stationId(), productId, f.emptyDelta(), f.customerDelta(), f.damagedDelta());
        journal.log("PARC_GAZ", Journal.MODIFICATIONS, "Parc de bouteilles ajusté",
                f.productCode() + " · vides " + f.emptyDelta() + ", clients " + f.customerDelta() + ", endommagées " + f.damagedDelta()
                        + (f.note() == null ? "" : " · " + f.note()), null, f.stationId(), null);
        return gasBottles(f.stationId());
    }

    public record Entry(Long stationId, Long pdvId, @NotBlank String productCode, @NotNull @Positive BigDecimal quantity,
                        Long tankId, String note) {}

    /** Entrée de stock (livraison reçue) : cuve pour le carburant, stock d'article sinon. */
    @PostMapping("/entries")
    @PreAuthorize("hasAuthority('STOCKS')")
    @Transactional
    public Map<String, Object> entry(@Valid @RequestBody Entry f) {
        CurrentUser u = Auth.user();
        Map<String, Object> p = product(f.productCode());
        long productId = ((Number) p.get("id")).longValue();
        if ("CARBURANT".equals(p.get("category"))) {
            Long station = Auth.scopedStation(f.stationId());
            if (station == null) {
                throw new ApiException("Station requise pour une livraison de carburant");
            }
            long tank = f.tankId() != null ? f.tankId() : stock.tankFor(station, productId);
            stock.moveTank(tank, f.quantity(), "LIVRAISON", null, u.id(), f.note());
            journal.log("ENTREE_STOCK", Journal.MODIFICATIONS, "Livraison de carburant",
                    p.get("name") + " · " + StockService.qty(f.quantity()) + " L", null, station, null);
        } else {
            Long station = f.pdvId() == null ? Auth.scopedStation(f.stationId()) : null;
            stock.move(station, f.pdvId(), productId, f.quantity(), "ENTREE", null, u.id(), f.note());
            journal.log("ENTREE_STOCK", Journal.MODIFICATIONS, "Entrée de stock",
                    p.get("name") + " · " + StockService.qty(f.quantity()), null, station, f.pdvId());
        }
        return Map.of("ok", true);
    }

    public record CountLine(@NotBlank String productCode, @NotNull @Min(0) BigDecimal counted) {}

    public record Inventory(Long stationId, Long pdvId, @NotEmpty List<@Valid CountLine> lines) {}

    /** Inventaire : l'écart entre stock théorique et stock compté est enregistré comme ajustement. */
    @PostMapping("/inventory")
    @PreAuthorize("hasAuthority('STOCKS')")
    @Transactional
    public List<Map<String, Object>> inventory(@Valid @RequestBody Inventory f) {
        CurrentUser u = Auth.user();
        Long station = f.pdvId() == null ? Auth.scopedStation(f.stationId()) : null;
        if (station == null && f.pdvId() == null) {
            throw new ApiException("Station ou point de vente requis");
        }
        for (CountLine l : f.lines()) {
            Map<String, Object> p = product(l.productCode());
            if ("CARBURANT".equals(p.get("category"))) {
                throw new ApiException("Le carburant est mesuré par les jauges des cuves");
            }
            BigDecimal book = db.value("""
                    \sSELECT quantity FROM stock_level WHERE product_id = :p
                      AND (station_id = :s OR pdv_id = :pv)""", Db.p("p", p.get("id"), "s", station, "pv", f.pdvId()), BigDecimal.class);
            BigDecimal delta = l.counted().subtract(book == null ? BigDecimal.ZERO : book);
            if (delta.signum() != 0) {
                stock.move(station, f.pdvId(), ((Number) p.get("id")).longValue(), delta, "INVENTAIRE", null, u.id(),
                        "Compté " + l.counted().stripTrailingZeros().toPlainString());
            }
        }
        journal.log("INVENTAIRE", Journal.MODIFICATIONS, "Inventaire enregistré", f.lines().size() + " articles", null, station, f.pdvId());
        return levels(station, null, null);
    }

    public record ThresholdForm(BigDecimal thresholdManual, @Min(0) Integer leadDays) {}

    /** Seuil manuel (ou null pour revenir au seuil automatique) d'un article. */
    @PutMapping("/levels/{id}/threshold")
    @PreAuthorize("hasAuthority('STOCKS')")
    public Map<String, Object> threshold(@PathVariable long id, @RequestBody ThresholdForm f) {
        Map<String, Object> l = db.require("SELECT station_id FROM stock_level WHERE id = :id", Db.p("id", id), "Stock introuvable");
        if (l.get("stationId") != null) {
            Auth.checkStation(((Number) l.get("stationId")).longValue());
        }
        db.update("UPDATE stock_level SET threshold_manual = :t, lead_days = COALESCE(:d, lead_days) WHERE id = :id",
                Db.p("id", id, "t", f.thresholdManual(), "d", f.leadDays()));
        stock.checkLevel(id);
        return Map.of("ok", true);
    }

    /** Recalcul immédiat des seuils automatiques (fait aussi chaque nuit). */
    @PostMapping("/recompute-thresholds")
    @PreAuthorize("hasAuthority('STOCKS')")
    public Map<String, Object> recompute() {
        thresholds.recomputeAll();
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ achats

    public record PurchaseLine(@NotBlank String productCode, @NotNull @Positive BigDecimal quantity, @Min(0) Long unitCost) {}

    public record PurchaseForm(@NotBlank String supplier, Long stationId, Long pdvId, @NotEmpty List<@Valid PurchaseLine> lines,
                               String note) {}

    /** Achat (bon de livraison) : alimente les stocks et les cuves. */
    @PostMapping("/purchases")
    @PreAuthorize("hasAuthority('STOCKS')")
    @Transactional
    public Map<String, Object> purchase(@Valid @RequestBody PurchaseForm f) {
        CurrentUser u = Auth.user();
        Long station = f.pdvId() == null ? Auth.scopedStation(f.stationId()) : null;
        String ref = refs.purchase();
        long id = db.insert("""
                \sINSERT INTO purchase (ref, supplier, station_id, pdv_id, note, created_by) VALUES (:r, :s, :st, :pv, :n, :u)""",
                Db.p("r", ref, "s", f.supplier().trim(), "st", station, "pv", f.pdvId(), "n", f.note(), "u", u.id()));
        long total = 0;
        for (PurchaseLine l : f.lines()) {
            Map<String, Object> p = product(l.productCode());
            long productId = ((Number) p.get("id")).longValue();
            long cost = l.unitCost() == null ? 0 : l.unitCost();
            db.update("INSERT INTO purchase_line (purchase_id, product_id, quantity, unit_cost) VALUES (:p, :pr, :q, :c)",
                    Db.p("p", id, "pr", productId, "q", l.quantity(), "c", cost));
            total += l.quantity().multiply(BigDecimal.valueOf(cost)).longValue();
            if ("CARBURANT".equals(p.get("category"))) {
                if (station == null) {
                    throw new ApiException("Station requise pour un achat de carburant");
                }
                stock.moveTank(stock.tankFor(station, productId), l.quantity(), "LIVRAISON", ref, u.id(), f.supplier());
            } else if (!"CARTE".equals(p.get("category"))) {
                stock.move(station, f.pdvId(), productId, l.quantity(), "ENTREE", ref, u.id(), f.supplier());
            }
        }
        db.update("UPDATE purchase SET total = :t WHERE id = :id", Db.p("id", id, "t", total));
        journal.log("ACHAT", Journal.MODIFICATIONS, "Achat enregistré", ref + " · " + f.supplier(), total, station, f.pdvId());
        return db.one("SELECT * FROM purchase WHERE id = :id", Db.p("id", id));
    }

    @GetMapping("/purchases")
    public List<Map<String, Object>> purchases(@RequestParam(required = false) Long stationId) {
        Long station = Auth.scopedStation(stationId);
        return db.list("""
                \sSELECT pu.id, pu.ref, pu.supplier, pu.total, pu.at, pu.note, COALESCE(s.short_name, pv.name) AS place,
                       e.first_name || ' ' || e.last_name AS created_by_name,
                       (SELECT count(*) FROM purchase_line WHERE purchase_id = pu.id) AS lines
                FROM purchase pu LEFT JOIN station s ON s.id = pu.station_id LEFT JOIN pdv pv ON pv.id = pu.pdv_id
                JOIN employee e ON e.id = pu.created_by
                WHERE CAST(:s AS bigint) IS NULL OR pu.station_id = :s ORDER BY pu.at DESC LIMIT 200""", Db.p("s", station));
    }

    private Map<String, Object> product(String code) {
        return db.require("SELECT id, name, category FROM product WHERE code = :c", Db.p("c", code), "Produit inconnu : " + code);
    }
}
