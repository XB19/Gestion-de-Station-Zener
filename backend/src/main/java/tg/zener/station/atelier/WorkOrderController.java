package tg.zener.station.atelier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
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
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.journal.Journal;
import tg.zener.station.notifications.Notifier;
import tg.zener.station.sales.SaleService;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/**
 * Atelier de la station : ordres de travail sur un véhicule (vidange, graissage, filtres, lavage…). Ouvert → en cours →
 * terminé → payé (ou annulé). Les produits utilisés sortent du stock de la station à l'encaissement, avec la main-d'œuvre,
 * par une vente « atelier » (espèces, mobile money ou carte Zener). Le véhicule garde son historique et son kilométrage.
 */
@RestController
public class WorkOrderController {

    static final List<String> SERVICES = List.of("VIDANGE", "GRAISSAGE", "FILTRES", "LAVAGE", "PNEUS", "BATTERIE", "DIAGNOSTIC", "AUTRE");

    private static final String SELECT = """
            \sSELECT w.id, w.ref, w.station_id, s.short_name AS station_name, w.vehicle_id, v.plate, v.brand, v.model,
                   COALESCE(w.customer_name, v.owner_name, h.name) AS customer_name, COALESCE(w.customer_phone, v.owner_phone) AS customer_phone,
                   w.card_id, c.number AS card_number, w.odometer_km, w.services, w.description, w.status, w.labour_amount, w.next_service_km,
                   w.technician_id, t.first_name || ' ' || t.last_name AS technician_name, w.opened_at, w.started_at, w.finished_at,
                   w.paid_at, w.sale_id, w.cancel_reason, o.first_name || ' ' || o.last_name AS opened_by_name,
                   CASE w.status WHEN 'OUVERT' THEN 'Ouvert' WHEN 'EN_COURS' THEN 'En cours' WHEN 'TERMINE' THEN 'Terminé'
                                 WHEN 'PAYE' THEN 'Payé' ELSE 'Annulé' END AS status_label,
                   (SELECT COALESCE(sum(round(l.quantity * COALESCE((SELECT price FROM product_price pp WHERE pp.product_id = l.product_id
                                                                     AND pp.profile = 'GRAND_PUBLIC'), 0))), 0)
                    FROM work_order_line l WHERE l.work_order_id = w.id) AS products_amount,
                   x.total AS paid_total, x.payment_method
            FROM work_order w JOIN station s ON s.id = w.station_id JOIN vehicle v ON v.id = w.vehicle_id
            LEFT JOIN holder h ON h.id = v.holder_id LEFT JOIN card c ON c.id = w.card_id
            LEFT JOIN employee t ON t.id = w.technician_id JOIN employee o ON o.id = w.opened_by LEFT JOIN sale x ON x.id = w.sale_id""";

    private final Db db;
    private final SaleService sales;
    private final Journal journal;
    private final Notifier notifier;

    public WorkOrderController(Db db, SaleService sales, Journal journal, Notifier notifier) {
        this.db = db;
        this.sales = sales;
        this.journal = journal;
        this.notifier = notifier;
    }

    @GetMapping("/api/work-orders")
    @PreAuthorize("hasAuthority('ATELIER') or hasAuthority('TRANSACTIONS')")
    public Page list(@RequestParam(required = false) Long stationId, @RequestParam(required = false) String status,
                     @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Integer size) {
        Long station = Auth.scopedStation(stationId);
        int sz = Page.size(size);
        int pg = Page.page(page);
        String where = """
                 \sWHERE (CAST(:s AS bigint) IS NULL OR w.station_id = :s)
                   AND (CAST(:st AS text) IS NULL OR w.status = :st OR (:st = 'EN_COURS_TOUS' AND w.status IN ('OUVERT', 'EN_COURS', 'TERMINE')))
                   AND (CAST(:q AS text) IS NULL OR v.plate ILIKE '%' || :q || '%' OR w.ref ILIKE '%' || :q || '%'
                        OR COALESCE(w.customer_name, v.owner_name, '') ILIKE '%' || :q || '%')""";
        Map<String, Object> p = Db.p("s", station, "st", status, "q", q == null || q.isBlank() ? null : q.trim(), "l", sz, "o", pg * sz);
        return new Page(db.list(SELECT + where + " ORDER BY w.status IN ('PAYE', 'ANNULE'), w.opened_at DESC LIMIT :l OFFSET :o", p),
                db.count("SELECT count(*) FROM work_order w JOIN vehicle v ON v.id = w.vehicle_id" + where, p), pg, sz);
    }

    @GetMapping("/api/work-orders/summary")
    @PreAuthorize("hasAuthority('ATELIER') or hasAuthority('TRANSACTIONS')")
    public Map<String, Object> summary(@RequestParam(required = false) Long stationId) {
        Long station = Auth.scopedStation(stationId);
        return db.one("""
                \sSELECT count(*) FILTER (WHERE w.status = 'OUVERT') AS open, count(*) FILTER (WHERE w.status = 'EN_COURS') AS in_progress,
                       count(*) FILTER (WHERE w.status = 'TERMINE') AS to_pay,
                       count(*) FILTER (WHERE w.status = 'PAYE' AND w.paid_at >= current_date) AS paid_today,
                       COALESCE(sum(x.total) FILTER (WHERE w.paid_at >= date_trunc('month', now())), 0) AS revenue_month,
                       count(*) FILTER (WHERE w.status = 'PAYE' AND w.paid_at >= date_trunc('month', now())) AS paid_month
                FROM work_order w LEFT JOIN sale x ON x.id = w.sale_id WHERE CAST(:s AS bigint) IS NULL OR w.station_id = :s""",
                Db.p("s", station));
    }

    @GetMapping("/api/work-orders/{id}")
    @PreAuthorize("hasAuthority('ATELIER') or hasAuthority('TRANSACTIONS')")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> w = db.require(SELECT + " WHERE w.id = :id", Db.p("id", id), "Ordre de travail introuvable");
        Auth.checkStation(((Number) w.get("stationId")).longValue());
        w.put("lines", db.list("""
                \sSELECT l.id, l.product_id, p.code, p.name, p.unit, p.category, l.quantity,
                       (SELECT price FROM product_price pp WHERE pp.product_id = l.product_id AND pp.profile = 'GRAND_PUBLIC') AS unit_price,
                       COALESCE((SELECT quantity FROM stock_level sl WHERE sl.station_id = :s AND sl.product_id = l.product_id), 0) AS stock
                FROM work_order_line l JOIN product p ON p.id = l.product_id WHERE l.work_order_id = :id ORDER BY l.id""",
                Db.p("id", id, "s", w.get("stationId"))));
        w.put("history", db.list("""
                \sSELECT w2.id, w2.ref, w2.opened_at, w2.odometer_km, w2.services, w2.status, s.short_name AS station_name
                FROM work_order w2 JOIN station s ON s.id = w2.station_id
                WHERE w2.vehicle_id = :v AND w2.id <> :id ORDER BY w2.opened_at DESC LIMIT 10""", Db.p("v", w.get("vehicleId"), "id", id)));
        if (w.get("cardNumber") != null) {
            w.put("cardNumberFormatted", CardService.format((String) w.get("cardNumber")));
        }
        return w;
    }

    /** Véhicule connu (par immatriculation) et ses derniers passages, pour préremplir un nouvel ordre. */
    @GetMapping("/api/vehicles/lookup")
    @PreAuthorize("hasAuthority('ATELIER')")
    public Map<String, Object> vehicle(@RequestParam String plate) {
        Map<String, Object> v = db.one("SELECT * FROM vehicle WHERE plate = :p", Db.p("p", normalize(plate)));
        if (v == null) {
            return Map.of("known", false);
        }
        v.put("known", true);
        v.put("orders", db.list("""
                \sSELECT w.ref, w.opened_at, w.odometer_km, w.services, w.next_service_km, s.short_name AS station_name
                FROM work_order w JOIN station s ON s.id = w.station_id WHERE w.vehicle_id = :v ORDER BY w.opened_at DESC LIMIT 5""",
                Db.p("v", v.get("id"))));
        return v;
    }

    public record OrderForm(@NotNull Long stationId, @NotBlank String plate, String brand, String model, String customerName,
                            String customerPhone, String cardNumber, Integer odometerKm, List<String> services, String description,
                            Long technicianId, Long labourAmount, Integer nextServiceKm) {}

    @PostMapping("/api/work-orders")
    @PreAuthorize("hasAuthority('ATELIER')")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody OrderForm f) {
        CurrentUser u = Auth.user();
        Auth.checkStation(f.stationId());
        checkServices(f.services());
        String plate = normalize(f.plate());
        Long vehicleId = db.value("""
                \sINSERT INTO vehicle (plate, brand, model, owner_name, owner_phone, odometer_km) VALUES (:p, :b, :m, :on, :op, :km)
                ON CONFLICT (plate) DO UPDATE SET brand = COALESCE(EXCLUDED.brand, vehicle.brand), model = COALESCE(EXCLUDED.model, vehicle.model),
                    owner_name = COALESCE(EXCLUDED.owner_name, vehicle.owner_name), owner_phone = COALESCE(EXCLUDED.owner_phone, vehicle.owner_phone),
                    odometer_km = GREATEST(COALESCE(EXCLUDED.odometer_km, 0), COALESCE(vehicle.odometer_km, 0))
                RETURNING id""",
                Db.p("p", plate, "b", blank(f.brand()), "m", blank(f.model()), "on", blank(f.customerName()), "op", blank(f.customerPhone()),
                        "km", f.odometerKm()), Long.class);
        Long cardId = null;
        if (f.cardNumber() != null && !f.cardNumber().isBlank()) {
            cardId = db.value("SELECT id FROM card WHERE number = :n", Db.p("n", CardService.normalize(f.cardNumber())), Long.class);
            if (cardId == null) {
                throw ApiException.notFound("Carte introuvable");
            }
        }
        String ref = "OT-" + Year.now() + "-" + db.sum("SELECT nextval('ref_work_order_seq')", Map.of());
        long id = db.insert("""
                \sINSERT INTO work_order (ref, station_id, vehicle_id, card_id, customer_name, customer_phone, odometer_km, services, description,
                                        technician_id, labour_amount, next_service_km, opened_by)
                VALUES (:ref, :s, :v, :c, :cn, :cp, :km, :sv, :d, :t, COALESCE(:la, 0), :nk, :u)""",
                Db.p("ref", ref, "s", f.stationId(), "v", vehicleId, "c", cardId, "cn", blank(f.customerName()), "cp", blank(f.customerPhone()),
                        "km", f.odometerKm(), "sv", db.array("text", (f.services() == null ? List.<String>of() : f.services()).toArray()),
                        "d", blank(f.description()), "t", f.technicianId(), "la", f.labourAmount(), "nk", f.nextServiceKm(), "u", u.id()));
        journal.log("ORDRE_TRAVAIL", Journal.VENTES_BOUTIQUE, "Ordre de travail ouvert",
                ref + " · " + plate + (f.services() == null || f.services().isEmpty() ? "" : " · " + String.join(", ", f.services()).toLowerCase()),
                null, f.stationId(), null);
        return get(id);
    }

    @PutMapping("/api/work-orders/{id}")
    @PreAuthorize("hasAuthority('ATELIER')")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @RequestBody OrderForm f) {
        Map<String, Object> w = editable(id);
        checkServices(f.services());
        db.update("""
                \sUPDATE work_order SET customer_name = :cn, customer_phone = :cp, odometer_km = :km, services = :sv, description = :d,
                       technician_id = :t, labour_amount = COALESCE(:la, labour_amount), next_service_km = :nk
                WHERE id = :id""",
                Db.p("id", id, "cn", blank(f.customerName()), "cp", blank(f.customerPhone()), "km", f.odometerKm(),
                        "sv", db.array("text", (f.services() == null ? List.<String>of() : f.services()).toArray()), "d", blank(f.description()),
                        "t", f.technicianId(), "la", f.labourAmount(), "nk", f.nextServiceKm()));
        if (f.odometerKm() != null) {
            db.update("UPDATE vehicle SET odometer_km = GREATEST(COALESCE(odometer_km, 0), :km) WHERE id = :v",
                    Db.p("v", w.get("vehicleId"), "km", f.odometerKm()));
        }
        return get(id);
    }

    public record LineItem(@NotBlank String productCode, @NotNull @Positive BigDecimal quantity) {}

    public record LinesForm(@NotNull List<@Valid LineItem> items) {}

    /** Produits utilisés (lubrifiants, filtres, accessoires) : sortis du stock à l'encaissement. */
    @PutMapping("/api/work-orders/{id}/lines")
    @PreAuthorize("hasAuthority('ATELIER')")
    @Transactional
    public Map<String, Object> lines(@PathVariable long id, @Valid @RequestBody LinesForm f) {
        editable(id);
        db.update("DELETE FROM work_order_line WHERE work_order_id = :id", Db.p("id", id));
        for (LineItem l : f.items()) {
            Long productId = db.value("""
                    \sSELECT id FROM product WHERE code = :c AND active AND category IN ('LUBRIFIANT', 'ACCESSOIRE', 'GAZ')""",
                    Db.p("c", l.productCode()), Long.class);
            if (productId == null) {
                throw new ApiException("Produit non utilisable à l'atelier : " + l.productCode());
            }
            db.update("INSERT INTO work_order_line (work_order_id, product_id, quantity) VALUES (:w, :p, :q)",
                    Db.p("w", id, "p", productId, "q", l.quantity()));
        }
        return get(id);
    }

    @PostMapping("/api/work-orders/{id}/start")
    @PreAuthorize("hasAuthority('ATELIER')")
    public Map<String, Object> start(@PathVariable long id) {
        Map<String, Object> w = editable(id);
        if (!"OUVERT".equals(w.get("status"))) {
            throw ApiException.conflict("L'ordre doit être ouvert pour démarrer");
        }
        db.update("""
                \sUPDATE work_order SET status = 'EN_COURS', started_at = now(), technician_id = COALESCE(technician_id, :u) WHERE id = :id""",
                Db.p("id", id, "u", Auth.user().id()));
        return get(id);
    }

    @PostMapping("/api/work-orders/{id}/finish")
    @PreAuthorize("hasAuthority('ATELIER')")
    public Map<String, Object> finish(@PathVariable long id) {
        Map<String, Object> w = editable(id);
        if (!"EN_COURS".equals(w.get("status")) && !"OUVERT".equals(w.get("status"))) {
            throw ApiException.conflict("Seul un ordre ouvert ou en cours se termine");
        }
        db.update("UPDATE work_order SET status = 'TERMINE', finished_at = now(), started_at = COALESCE(started_at, now()) WHERE id = :id",
                Db.p("id", id));
        notifier.send(new Notifier.Message("ORDRE_TRAVAIL", Notifier.INFO, "Véhicule prêt · " + w.get("plate") + " · " + w.get("ref"),
                "Travaux terminés : à encaisser.", "/atelier?id=" + id, ((Number) w.get("stationId")).longValue()));
        return get(id);
    }

    public record CancelForm(@NotBlank String reason) {}

    @PostMapping("/api/work-orders/{id}/cancel")
    @PreAuthorize("hasAuthority('ATELIER')")
    public Map<String, Object> cancel(@PathVariable long id, @Valid @RequestBody CancelForm f) {
        Map<String, Object> w = editable(id);
        db.update("UPDATE work_order SET status = 'ANNULE', cancel_reason = :r WHERE id = :id", Db.p("id", id, "r", f.reason().trim()));
        journal.log("ORDRE_TRAVAIL", Journal.VENTES_BOUTIQUE, "Ordre de travail annulé", w.get("ref") + " · " + f.reason().trim(), null,
                ((Number) w.get("stationId")).longValue(), null);
        return get(id);
    }

    public record PayForm(@NotBlank @Pattern(regexp = "ESPECES|MOBILE_MONEY|CARTE") String paymentMethod, String paymentRef,
                          String cardNumber, String pin) {}

    /** Encaissement : vente « atelier » (produits + main-d'œuvre), sortie de stock des produits, ordre payé. */
    @PostMapping("/api/work-orders/{id}/pay")
    @PreAuthorize("hasAuthority('ATELIER')")
    @Transactional
    public Map<String, Object> pay(@PathVariable long id, @Valid @RequestBody PayForm f) {
        Map<String, Object> w = get(id);
        if (!"TERMINE".equals(w.get("status")) && !"EN_COURS".equals(w.get("status"))) {
            throw ApiException.conflict("L'ordre doit être terminé (ou en cours) pour être encaissé");
        }
        List<SaleService.Line> lines = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) w.get("lines");
        items.forEach(l -> lines.add(new SaleService.Line((String) l.get("code"), (BigDecimal) l.get("quantity"), false)));
        long labour = ((Number) w.get("labourAmount")).longValue();
        if (labour > 0) {
            lines.add(new SaleService.Line("MAIN_OEUVRE", BigDecimal.valueOf(labour), false));
        }
        if (lines.isEmpty()) {
            throw new ApiException("Rien à encaisser : ajoutez des produits ou la main-d'œuvre");
        }
        boolean card = "CARTE".equals(f.paymentMethod());
        String cardNumber = card ? (f.cardNumber() != null && !f.cardNumber().isBlank() ? f.cardNumber() : (String) w.get("cardNumber")) : null;
        if (card && cardNumber == null) {
            throw new ApiException("Numéro de carte requis");
        }
        if ("MOBILE_MONEY".equals(f.paymentMethod()) && (f.paymentRef() == null || f.paymentRef().isBlank())) {
            throw new ApiException("Référence de la transaction mobile money requise");
        }
        long station = ((Number) w.get("stationId")).longValue();
        Map<String, Object> sale = sales.record(new SaleService.Sale(java.util.UUID.randomUUID(), station, null, "ATELIER", "ATELIER", null,
                lines, f.paymentMethod(), f.paymentRef(), cardNumber, f.pin(), false, Auth.user().id(), null, false,
                "Ordre de travail " + w.get("ref") + " · " + w.get("plate")));
        db.update("UPDATE work_order SET status = 'PAYE', paid_at = now(), sale_id = :s, finished_at = COALESCE(finished_at, now()) WHERE id = :id",
                Db.p("id", id, "s", sale.get("id")));
        return get(id);
    }

    private Map<String, Object> editable(long id) {
        Map<String, Object> w = db.require("""
                \sSELECT w.id, w.ref, w.status, w.station_id, w.vehicle_id, v.plate FROM work_order w JOIN vehicle v ON v.id = w.vehicle_id
                WHERE w.id = :id FOR UPDATE OF w""", Db.p("id", id), "Ordre de travail introuvable");
        Auth.checkStation(((Number) w.get("stationId")).longValue());
        if ("PAYE".equals(w.get("status")) || "ANNULE".equals(w.get("status"))) {
            throw ApiException.conflict("Cet ordre de travail est clos");
        }
        return w;
    }

    private static void checkServices(List<String> services) {
        if (services != null && services.stream().anyMatch(s -> !SERVICES.contains(s))) {
            throw new ApiException("Prestation inconnue");
        }
    }

    static String normalize(String plate) {
        return plate.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
