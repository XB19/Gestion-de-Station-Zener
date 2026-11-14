package tg.zener.station.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/** Ventes saisies en station (pompiste, caisse boutique) et historique des transactions. */
@RestController
@RequestMapping("/api/sales")
public class SaleController {

    private final Db db;
    private final SaleService sales;

    public SaleController(Db db, SaleService sales) {
        this.db = db;
        this.sales = sales;
    }

    public record FuelSale(UUID uuid, @NotNull Long pumpId, @NotNull @Positive BigDecimal liters,
                           @NotBlank @Pattern(regexp = "ESPECES|MOBILE_MONEY|CARTE") String paymentMethod,
                           String paymentRef, String cardNumber, String pin) {}

    /** Vente à la pompe par le pompiste (mode conventionnel). */
    @PostMapping("/fuel")
    @PreAuthorize("hasAuthority('VENTES_POMPE')")
    public Map<String, Object> fuel(@Valid @RequestBody FuelSale f) {
        CurrentUser u = Auth.user();
        Map<String, Object> pump = db.require("""
                \sSELECT pu.id, pu.station_id, pu.state, p.code FROM pump pu JOIN product p ON p.id = pu.product_id WHERE pu.id = :id""",
                Db.p("id", f.pumpId()), "Pistolet introuvable");
        long stationId = ((Number) pump.get("stationId")).longValue();
        Auth.checkStation(stationId);
        if ("HORS_SERVICE".equals(pump.get("state"))) {
            throw ApiException.conflict("Ce pistolet est hors service");
        }
        return sales.record(new SaleService.Sale(f.uuid(), stationId, null, "CARBURANT", "POMPE", f.pumpId(),
                List.of(new SaleService.Line((String) pump.get("code"), f.liters(), false)), f.paymentMethod(), f.paymentRef(),
                f.cardNumber(), f.pin(), false, u.id(), null, false, null));
    }

    public record ShopLine(@NotBlank String productCode, @NotNull @Positive BigDecimal quantity, Boolean withDeposit) {}

    public record ShopSale(UUID uuid, Long stationId, @NotEmpty List<@Valid ShopLine> lines,
                           @NotBlank @Pattern(regexp = "ESPECES|MOBILE_MONEY|CARTE") String paymentMethod,
                           String paymentRef, String cardNumber, String pin) {}

    /** Vente boutique : gaz (bouteille neuve avec consigne si withDeposit), lubrifiants, accessoires. */
    @PostMapping("/shop")
    @PreAuthorize("hasAuthority('VENTES_BOUTIQUE')")
    public Map<String, Object> shop(@Valid @RequestBody ShopSale f) {
        CurrentUser u = Auth.user();
        Long station = Auth.scopedStation(f.stationId());
        if (station == null) {
            throw new ApiException("Station requise");
        }
        if (f.lines().stream().anyMatch(l -> l.productCode().startsWith("CARTE_"))) {
            throw new ApiException("Les cartes se vendent depuis un point de vente");
        }
        List<SaleService.Line> lines = f.lines().stream()
                .map(l -> new SaleService.Line(l.productCode(), l.quantity(), Boolean.TRUE.equals(l.withDeposit()))).toList();
        return sales.record(new SaleService.Sale(f.uuid(), station, null, "BOUTIQUE", "BOUTIQUE", null, lines,
                f.paymentMethod(), f.paymentRef(), f.cardNumber(), f.pin(), false, u.id(), null, false, null));
    }

    public record GasExchange(UUID uuid, Long stationId, @NotBlank String productCode, @Positive Integer quantity,
                              @NotBlank @Pattern(regexp = "ESPECES|MOBILE_MONEY|CARTE") String paymentMethod,
                              String paymentRef, String cardNumber, String pin) {}

    /** Échange d'une bouteille vide contre une pleine : seul le gaz est payé. */
    @PostMapping("/gas-exchange")
    @PreAuthorize("hasAuthority('VENTES_BOUTIQUE')")
    public Map<String, Object> gasExchange(@Valid @RequestBody GasExchange f) {
        CurrentUser u = Auth.user();
        Long station = Auth.scopedStation(f.stationId());
        if (station == null) {
            throw new ApiException("Station requise");
        }
        String category = db.value("SELECT category FROM product WHERE code = :c", Db.p("c", f.productCode()), String.class);
        if (!"GAZ".equals(category)) {
            throw new ApiException("Seules les bouteilles de gaz s'échangent");
        }
        int q = f.quantity() == null ? 1 : f.quantity();
        return sales.record(new SaleService.Sale(f.uuid(), station, null, "ECHANGE_GAZ", "BOUTIQUE", null,
                List.of(new SaleService.Line(f.productCode(), BigDecimal.valueOf(q), false)), f.paymentMethod(), f.paymentRef(),
                f.cardNumber(), f.pin(), false, u.id(), null, false, null));
    }

    // ------------------------------------------------------------------ historique

    static final String SELECT = """
            \sSELECT x.id, x.uuid, x.at, x.kind, x.channel, x.mode, x.payment_method, x.payment_ref, x.buyer_profile, x.total,
                   x.station_id, s.short_name AS station_name, x.pdv_id, pv.name AS pdv_name, pu.code AS pump_code,
                   x.employee_id, e.first_name || ' ' || e.last_name AS employee_name, c.number AS card_number,
                   CASE WHEN e.photo_path IS NULL THEN NULL ELSE '/files/' || e.photo_path END AS employee_photo_url,
                   x.synced_at,
                   (SELECT string_agg(CASE WHEN p.category = 'CARBURANT'
                                           THEN replace(trim(to_char(sl.quantity, 'FM999999990.0')), '.', ',') || ' L ' || p.name
                                           ELSE trim(to_char(sl.quantity, 'FM999999990')) || ' × ' || p.name END, ', ')
                    FROM sale_line sl JOIN product p ON p.id = sl.product_id WHERE sl.sale_id = x.id) AS description,
                   (SELECT sum(sl.quantity) FROM sale_line sl JOIN product p ON p.id = sl.product_id
                    WHERE sl.sale_id = x.id AND p.category = 'CARBURANT') AS liters
            FROM sale x
            LEFT JOIN station s ON s.id = x.station_id
            LEFT JOIN pdv pv ON pv.id = x.pdv_id
            LEFT JOIN pump pu ON pu.id = x.pump_id
            LEFT JOIN employee e ON e.id = x.employee_id
            LEFT JOIN card c ON c.id = x.card_id""";

    /** Historique des transactions (profil Comptable : consultation seule). */
    @GetMapping
    @PreAuthorize("hasAuthority('TRANSACTIONS') or hasAuthority('VENTES_POMPE')")
    public Page list(@RequestParam(required = false) Long stationId, @RequestParam(required = false) Long pdvId,
                     @RequestParam(required = false) Long employeeId, @RequestParam(required = false) String kind,
                     @RequestParam(required = false) String paymentMethod, @RequestParam(required = false) String cardNumber,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        CurrentUser u = Auth.user();
        Long station = Auth.scopedStation(stationId);
        // sans le droit TRANSACTIONS, un pompiste ne voit que ses propres ventes
        Long employee = u.can("TRANSACTIONS") ? employeeId : Long.valueOf(u.id());
        String where = """
                 \sWHERE (CAST(:s AS bigint) IS NULL OR x.station_id = :s) AND (CAST(:pv AS bigint) IS NULL OR x.pdv_id = :pv)
                   AND (CAST(:e AS bigint) IS NULL OR x.employee_id = :e) AND (CAST(:k AS text) IS NULL OR x.kind = :k)
                   AND (CAST(:pm AS text) IS NULL OR x.payment_method = :pm)
                   AND (CAST(:c AS text) IS NULL OR x.card_id = (SELECT id FROM card WHERE number = :c))
                   AND (CAST(:from AS date) IS NULL OR x.at >= CAST(:from AS date))
                   AND (CAST(:to AS date) IS NULL OR x.at < CAST(:to AS date) + 1)""";
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> p = Db.p("s", station, "pv", pdvId, "e", employee, "k", kind, "pm", paymentMethod,
                "c", cardNumber == null || cardNumber.isBlank() ? null : CardService.normalize(cardNumber),
                "from", from, "to", to, "lim", sz, "off", pg * sz);
        List<Map<String, Object>> items = db.list(SELECT + where + " ORDER BY x.at DESC, x.id DESC LIMIT :lim OFFSET :off", p);
        return new Page(items, db.count("SELECT count(*) FROM sale x" + where, p), pg, sz);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TRANSACTIONS') or hasAuthority('VENTES_POMPE')")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> s = db.require(SELECT + " WHERE x.id = :id", Db.p("id", id), "Vente introuvable");
        if (s.get("stationId") != null) {
            Auth.checkStation(((Number) s.get("stationId")).longValue());
        }
        s.put("lines", db.list("""
                \sSELECT sl.id, p.code, p.name, p.category, sl.quantity, sl.unit_price, sl.amount, c.number AS card_number
                FROM sale_line sl JOIN product p ON p.id = sl.product_id LEFT JOIN card c ON c.id = sl.card_id
                WHERE sl.sale_id = :id ORDER BY sl.id""", Db.p("id", id)));
        return s;
    }

    /** Indicateurs de l'écran Ventes & stocks. */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) Long stationId) {
        Long station = Auth.scopedStation(stationId);
        Map<String, Object> p = Db.p("s", station);
        Map<String, Object> m = db.one("""
                \sSELECT COALESCE(sum(x.total) FILTER (WHERE x.kind IN ('BOUTIQUE', 'ECHANGE_GAZ') AND x.at >= current_date), 0) AS shop_today,
                       COALESCE(sum(x.total) FILTER (WHERE x.kind IN ('BOUTIQUE', 'ECHANGE_GAZ')
                                AND x.at >= current_date - 1 AND x.at < now() - interval '1 day'), 0) AS shop_yesterday_same_time,
                       count(*) FILTER (WHERE x.kind = 'ECHANGE_GAZ' AND x.at >= current_date) AS exchanges_today
                FROM sale x WHERE (CAST(:s AS bigint) IS NULL OR x.station_id = :s) AND x.at >= current_date - 1""", p);
        m.put("bottlesExchangedToday", db.sum("""
                \sSELECT COALESCE(sum(sl.quantity), 0) FROM sale x JOIN sale_line sl ON sl.sale_id = x.id
                WHERE x.kind = 'ECHANGE_GAZ' AND x.at >= current_date AND (CAST(:s AS bigint) IS NULL OR x.station_id = :s)""", p));
        m.putAll(db.one("""
                \sSELECT COALESCE(sum(total), 0) AS purchases_month, count(*) AS purchases_month_count FROM purchase
                WHERE at >= date_trunc('month', now()) AND (CAST(:s AS bigint) IS NULL OR station_id = :s)""", p));
        m.putAll(db.one("""
                \sSELECT count(*) AS alerts, count(*) FILTER (WHERE severity = 'CRITIQUE') AS alerts_critical
                FROM stock_alert WHERE resolved_at IS NULL AND (CAST(:s AS bigint) IS NULL OR station_id = :s)""", p));
        return m;
    }

    /** Prix calculé pour un produit et un profil (aperçu en caisse). */
    @GetMapping("/quote")
    public Map<String, Object> quote(@RequestParam String productCode, @RequestParam(required = false) String cardNumber) {
        Map<String, Object> p = db.require("SELECT id, name, deposit_amount FROM product WHERE code = :c", Db.p("c", productCode),
                "Produit inconnu");
        String profile = "GRAND_PUBLIC";
        if (cardNumber != null && !cardNumber.isBlank()) {
            String pr = db.value("""
                    \sSELECT h.profile FROM card c JOIN holder h ON h.id = c.holder_id WHERE c.number = :n""",
                    Db.p("n", CardService.normalize(cardNumber)), String.class);
            profile = pr == null ? profile : pr;
        }
        return Map.of("productCode", productCode, "name", p.get("name"), "profile", profile,
                "unitPrice", sales.price(((Number) p.get("id")).longValue(), profile),
                "deposit", p.get("depositAmount") == null ? 0 : p.get("depositAmount"), "at", Instant.now());
    }
}
