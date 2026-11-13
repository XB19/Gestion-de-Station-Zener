package tg.zener.station.suppliers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Year;
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
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.stock.StockService;
import tg.zener.station.tanks.TankService;

/**
 * Approvisionnement (phase 2g) : fournisseurs (dépôts pétroliers, grossistes) et commandes de carburant
 * (commandée → expédiée → livrée ; la livraison crée la réception en cuve avec l'écart reçu / commandé).
 * Un gérant ne voit et ne commande que pour sa station.
 */
@RestController
public class SupplierController {

    private final Db db;
    private final TankService tanks;
    private final StockService stock;
    private final Journal journal;

    public SupplierController(Db db, TankService tanks, StockService stock, Journal journal) {
        this.db = db;
        this.tanks = tanks;
        this.stock = stock;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ fournisseurs

    @GetMapping("/api/suppliers")
    @PreAuthorize("hasAuthority('APPROVISIONNEMENT') or hasAuthority('STOCKS')")
    public List<Map<String, Object>> suppliers(@RequestParam(required = false) Boolean all) {
        return db.list("""
                \sSELECT s.*, (SELECT count(*) FROM supplier_order o WHERE o.supplier_id = s.id) AS orders,
                       (SELECT COALESCE(sum(o.amount), 0) FROM supplier_order o WHERE o.supplier_id = s.id AND o.status = 'LIVREE'
                        AND o.delivered_at >= now() - interval '12 months') AS purchased_12m,
                       (SELECT max(o.ordered_at) FROM supplier_order o WHERE o.supplier_id = s.id) AS last_order_at
                FROM supplier s WHERE CAST(:all AS boolean) OR s.active ORDER BY s.active DESC, s.name""", Db.p("all", Boolean.TRUE.equals(all)));
    }

    public record SupplierForm(@NotBlank String name, @Pattern(regexp = "CARBURANT|BOUTIQUE|GAZ|AUTRE") String kind, String contactName,
                               String phone, String email, String address, String note, Boolean active) {}

    @PostMapping("/api/suppliers")
    @PreAuthorize("hasAuthority('APPROVISIONNEMENT')")
    public List<Map<String, Object>> create(@Valid @RequestBody SupplierForm f) {
        if (db.count("SELECT count(*) FROM supplier WHERE lower(name) = lower(:n)", Db.p("n", f.name().trim())) > 0) {
            throw ApiException.conflict("Ce fournisseur existe déjà");
        }
        db.update("""
                \sINSERT INTO supplier (name, kind, contact_name, phone, email, address, note) VALUES (:n, COALESCE(:k, 'CARBURANT'), :c, :p, :e, :a, :note)""",
                params(f));
        journal.log("FOURNISSEUR", Journal.MODIFICATIONS, "Fournisseur ajouté", f.name().trim(), null, null, null);
        return suppliers(true);
    }

    @PutMapping("/api/suppliers/{id}")
    @PreAuthorize("hasAuthority('APPROVISIONNEMENT')")
    public List<Map<String, Object>> update(@PathVariable long id, @Valid @RequestBody SupplierForm f) {
        Map<String, Object> p = params(f);
        p.put("id", id);
        p.put("ac", f.active());
        db.update("""
                \sUPDATE supplier SET name = :n, kind = COALESCE(:k, kind), contact_name = :c, phone = :p, email = :e, address = :a, note = :note,
                       active = COALESCE(:ac, active) WHERE id = :id""", p);
        journal.log("FOURNISSEUR", Journal.MODIFICATIONS, "Fournisseur modifié", f.name().trim(), null, null, null);
        return suppliers(true);
    }

    private static Map<String, Object> params(SupplierForm f) {
        return Db.p("n", f.name().trim(), "k", f.kind(), "c", blank(f.contactName()), "p", blank(f.phone()), "e", blank(f.email()),
                "a", blank(f.address()), "note", blank(f.note()));
    }

    // ------------------------------------------------------------------ commandes

    static final String ORDER_SELECT = """
            \sSELECT o.id, o.ref, o.supplier_id, s.name AS supplier_name, o.station_id, st.short_name AS station_name, o.product_id,
                   p.code AS product_code, p.name AS product_name, o.tank_id, t.name AS tank_name, o.quantity, o.unit_price, o.amount, o.status,
                   o.ordered_at, o.expected_at, o.seller_name, o.seller_phone, o.driver_name, o.driver_phone, o.truck_plate, o.shipped_at,
                   o.delivered_at, o.received_volume, o.received_volume - o.quantity AS gap, o.note, o.cancel_reason,
                   e.first_name || ' ' || e.last_name AS created_by_name, d.ref AS delivery_ref
            FROM supplier_order o JOIN supplier s ON s.id = o.supplier_id JOIN station st ON st.id = o.station_id
            JOIN product p ON p.id = o.product_id LEFT JOIN tank t ON t.id = o.tank_id LEFT JOIN employee e ON e.id = o.created_by
            LEFT JOIN tank_delivery d ON d.supplier_order_id = o.id""";

    @GetMapping("/api/supplier-orders")
    @PreAuthorize("hasAuthority('APPROVISIONNEMENT') or hasAuthority('STOCKS')")
    public Page orders(@RequestParam(required = false) String status, @RequestParam(required = false) Long stationId,
                       @RequestParam(required = false) Long supplierId, @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size) {
        Long station = Auth.scopedStation(stationId);
        int sz = Page.size(size);
        int pg = Page.page(page);
        String where = """
                 \sWHERE (CAST(:st AS text) IS NULL OR o.status = :st OR (:st = 'EN_COURS' AND o.status IN ('BROUILLON', 'COMMANDEE', 'EXPEDIEE')))
                   AND (CAST(:s AS bigint) IS NULL OR o.station_id = :s) AND (CAST(:sup AS bigint) IS NULL OR o.supplier_id = :sup)""";
        Map<String, Object> p = Db.p("st", status, "s", station, "sup", supplierId, "l", sz, "o", pg * sz);
        return new Page(db.list(ORDER_SELECT + where + " ORDER BY o.ordered_at DESC, o.id DESC LIMIT :l OFFSET :o", p),
                db.count("SELECT count(*) FROM supplier_order o" + where, p), pg, sz);
    }

    public record OrderForm(@NotNull Long supplierId, @NotNull Long stationId, @NotBlank String productCode, Long tankId,
                            @NotNull @Positive BigDecimal quantity, @NotNull @Min(0) Long unitPrice, Instant expectedAt, String sellerName,
                            String sellerPhone, String note) {}

    /** Commande de carburant au fournisseur, pour une cuve de la station. */
    @PostMapping("/api/supplier-orders")
    @PreAuthorize("hasAuthority('APPROVISIONNEMENT')")
    @Transactional
    public Map<String, Object> order(@Valid @RequestBody OrderForm f) {
        Auth.checkStation(f.stationId());
        Map<String, Object> p = db.require("SELECT id, name, category FROM product WHERE code = :c", Db.p("c", f.productCode()),
                "Produit inconnu");
        if (!"CARBURANT".equals(p.get("category"))) {
            throw new ApiException("Les commandes fournisseur concernent le carburant (les achats de la boutique passent par Ventes & stocks)");
        }
        long productId = ((Number) p.get("id")).longValue();
        long tank = f.tankId() != null ? f.tankId() : stock.tankFor(f.stationId(), productId);
        if (db.count("SELECT count(*) FROM tank WHERE id = :t AND station_id = :s AND product_id = :p",
                Db.p("t", tank, "s", f.stationId(), "p", productId)) == 0) {
            throw new ApiException("Cette cuve ne reçoit pas ce carburant dans cette station");
        }
        String ref = "CMD-" + Year.now() + "-" + db.sum("SELECT nextval('ref_supplier_order_seq')", Map.of());
        long amount = f.quantity().multiply(BigDecimal.valueOf(f.unitPrice())).setScale(0, RoundingMode.HALF_UP).longValue();
        long id = db.insert("""
                \sINSERT INTO supplier_order (ref, supplier_id, station_id, product_id, tank_id, quantity, unit_price, amount, status, expected_at,
                                            seller_name, seller_phone, created_by, note)
                VALUES (:ref, :sup, :s, :p, :t, :q, :u, :a, 'COMMANDEE', CAST(:exp AS timestamptz), :sn, :sp, :e, :n)""",
                Db.p("ref", ref, "sup", f.supplierId(), "s", f.stationId(), "p", productId, "t", tank, "q", f.quantity(), "u", f.unitPrice(),
                        "a", amount, "exp", f.expectedAt() == null ? null : java.sql.Timestamp.from(f.expectedAt()), "sn", blank(f.sellerName()),
                        "sp", blank(f.sellerPhone()), "e", Auth.user().id(), "n", blank(f.note())));
        String supplier = db.value("SELECT name FROM supplier WHERE id = :s", Db.p("s", f.supplierId()), String.class);
        journal.log("COMMANDE_FOURNISSEUR", Journal.LIVRAISONS, "Commande de carburant",
                ref + " · " + supplier + " · " + StockService.qty(f.quantity()) + " L " + p.get("name"), amount, f.stationId(), null);
        return order(id);
    }

    private Map<String, Object> order(long id) {
        return db.require(ORDER_SELECT + " WHERE o.id = :id", Db.p("id", id), "Commande introuvable");
    }

    private Map<String, Object> lock(long id) {
        Map<String, Object> o = db.require("SELECT * FROM supplier_order WHERE id = :id FOR UPDATE", Db.p("id", id), "Commande introuvable");
        Auth.checkStation(((Number) o.get("stationId")).longValue());
        return o;
    }

    public record ShipForm(String driverName, String driverPhone, String truckPlate, Instant expectedAt) {}

    /** Le fournisseur annonce l'expédition (camion, chauffeur, arrivée prévue). */
    @PostMapping("/api/supplier-orders/{id}/ship")
    @PreAuthorize("hasAuthority('APPROVISIONNEMENT')")
    @Transactional
    public Map<String, Object> ship(@PathVariable long id, @RequestBody ShipForm f) {
        Map<String, Object> o = lock(id);
        if (!"COMMANDEE".equals(o.get("status"))) {
            throw ApiException.conflict("Seule une commande passée peut être expédiée");
        }
        db.update("""
                \sUPDATE supplier_order SET status = 'EXPEDIEE', shipped_at = now(), driver_name = :dn, driver_phone = :dp, truck_plate = :tp,
                       expected_at = COALESCE(CAST(:exp AS timestamptz), expected_at) WHERE id = :id""",
                Db.p("id", id, "dn", blank(f.driverName()), "dp", blank(f.driverPhone()), "tp", blank(f.truckPlate()),
                        "exp", f.expectedAt() == null ? null : java.sql.Timestamp.from(f.expectedAt())));
        journal.log("COMMANDE_FOURNISSEUR", Journal.LIVRAISONS, "Commande expédiée", o.get("ref") + (f.truckPlate() == null ? "" : " · " + f.truckPlate()),
                null, ((Number) o.get("stationId")).longValue(), null);
        return order(id);
    }

    public record ReceiveForm(@NotNull @Positive BigDecimal receivedVolume, String ticketNumber, BigDecimal ticketDensity,
                              BigDecimal temperature, String note) {}

    /** Livraison : réception en cuve (volume du bon = quantité commandée), commande livrée. */
    @PostMapping("/api/supplier-orders/{id}/receive")
    @PreAuthorize("hasAuthority('APPROVISIONNEMENT') or hasAuthority('STOCKS')")
    @Transactional
    public Map<String, Object> receive(@PathVariable long id, @Valid @RequestBody ReceiveForm f) {
        Map<String, Object> o = lock(id);
        if (!"COMMANDEE".equals(o.get("status")) && !"EXPEDIEE".equals(o.get("status"))) {
            throw ApiException.conflict("Cette commande n'est pas en attente de livraison");
        }
        String supplier = db.value("SELECT name FROM supplier WHERE id = :s", Db.p("s", o.get("supplierId")), String.class);
        Map<String, Object> d = tanks.receive(((Number) o.get("tankId")).longValue(), new TankService.Reception((BigDecimal) o.get("quantity"),
                f.receivedVolume(), null, supplier, (String) o.get("truckPlate"), (String) o.get("driverName"),
                f.ticketNumber() == null ? (String) o.get("ref") : f.ticketNumber(), f.ticketDensity(), f.temperature(), null, null,
                "Commande " + o.get("ref") + (f.note() == null ? "" : " · " + f.note())), Auth.user().id());
        db.update("UPDATE tank_delivery SET supplier_order_id = :o WHERE id = :d", Db.p("o", id, "d", d.get("id")));
        db.update("UPDATE supplier_order SET status = 'LIVREE', delivered_at = now(), received_volume = :v WHERE id = :id",
                Db.p("id", id, "v", f.receivedVolume()));
        return order(id);
    }

    public record CancelForm(@NotBlank String reason) {}

    @PostMapping("/api/supplier-orders/{id}/cancel")
    @PreAuthorize("hasAuthority('APPROVISIONNEMENT')")
    @Transactional
    public Map<String, Object> cancel(@PathVariable long id, @Valid @RequestBody CancelForm f) {
        Map<String, Object> o = lock(id);
        if ("LIVREE".equals(o.get("status")) || "ANNULEE".equals(o.get("status"))) {
            throw ApiException.conflict("Cette commande ne peut plus être annulée");
        }
        db.update("UPDATE supplier_order SET status = 'ANNULEE', cancel_reason = :r WHERE id = :id", Db.p("id", id, "r", f.reason().trim()));
        journal.log("COMMANDE_FOURNISSEUR", Journal.LIVRAISONS, "Commande annulée", o.get("ref") + " · " + f.reason().trim(), null,
                ((Number) o.get("stationId")).longValue(), null);
        return order(id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
