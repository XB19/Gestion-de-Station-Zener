package tg.zener.station.mobility;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.common.Settings;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/** Zen Mobility : commandes de carburant (1 000 L minimum), affectation, départ, livraison. */
@RestController
@RequestMapping("/api/mobility")
public class DeliveryController {

    private final Db db;
    private final DeliveryService deliveries;
    private final Settings settings;

    public DeliveryController(Db db, DeliveryService deliveries, Settings settings) {
        this.db = db;
        this.deliveries = deliveries;
        this.settings = settings;
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('LIVRAISONS')")
    public Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                     @RequestParam(required = false) Long agentId, @RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Integer size) {
        Long scope = Auth.scopedStation(null);
        String where = """
                 \sWHERE (CAST(:st AS text) IS NULL OR d.status = :st)
                   AND (CAST(:q AS text) IS NULL OR (d.ref || ' ' || h.name || ' ' || d.address) ILIKE '%' || :q || '%')
                   AND (CAST(:a AS bigint) IS NULL OR d.agent_id = :a)
                   AND (CAST(:scope AS bigint) IS NULL OR d.source_station_id = :scope)""";
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> p = Db.p("st", blank(status), "q", blank(q), "a", agentId, "scope", scope, "lim", sz, "off", pg * sz);
        List<Map<String, Object>> items = db.list(DeliveryService.SELECT + where + """
                 \sORDER BY CASE d.status WHEN 'EN_ROUTE' THEN 0 WHEN 'VALIDEE' THEN 1 WHEN 'DEMANDEE' THEN 2 ELSE 3 END,
                          d.created_at DESC LIMIT :lim OFFSET :off""", p);
        items.forEach(DeliveryService::decorate);
        return new Page(items, db.count("SELECT count(*) FROM delivery_order d JOIN holder h ON h.id = d.holder_id" + where, p), pg, sz);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('LIVRAISONS')")
    public Map<String, Object> summary() {
        Map<String, Object> m = db.one("""
                \sSELECT count(*) FILTER (WHERE status = 'DEMANDEE') AS requested,
                       count(*) FILTER (WHERE status = 'VALIDEE') AS validated,
                       count(*) FILTER (WHERE status = 'EN_ROUTE') AS on_the_way,
                       count(*) FILTER (WHERE status = 'LIVREE' AND delivered_at >= date_trunc('month', now())) AS delivered_month,
                       COALESCE(sum(delivered_quantity) FILTER (WHERE status = 'LIVREE'
                                AND delivered_at >= date_trunc('month', now())), 0) AS liters_month,
                       COALESCE(sum(round(delivered_quantity * unit_price)) FILTER (WHERE status = 'LIVREE'
                                AND delivered_at >= date_trunc('month', now())), 0) AS amount_month
                FROM delivery_order""", Map.of());
        m.put("minQuantity", settings.getLong("mobility.min_quantity", 1000));
        m.put("agents", db.list("""
                \sSELECT e.id, e.first_name || ' ' || e.last_name AS name, e.phone,
                       (SELECT d.ref FROM delivery_order d WHERE d.agent_id = e.id AND d.status = 'EN_ROUTE' LIMIT 1) AS current_delivery
                FROM employee e WHERE e.active AND e.role_code = 'LIVREUR' ORDER BY e.last_name"""));
        return m;
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('LIVRAISONS') or hasAuthority('LIVRER')")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> d = deliveries.get(id);
        CurrentUser u = Auth.user();
        if (!u.can("LIVRAISONS") && (d.get("agentId") == null || ((Number) d.get("agentId")).longValue() != u.id())) {
            throw tg.zener.station.common.ApiException.forbidden("Cette livraison est affectée à un autre livreur");
        }
        d.put("track", db.list("""
                \sSELECT latitude, longitude, speed_kmh, heading, at FROM location_ping
                WHERE delivery_id = :id ORDER BY at""", Db.p("id", id)));
        return d;
    }

    /** Livraisons du livreur connecté (application mobile). */
    @GetMapping("/my-deliveries")
    @PreAuthorize("hasAuthority('LIVRER')")
    public List<Map<String, Object>> mine() {
        List<Map<String, Object>> rows = db.list(DeliveryService.SELECT + """
                 \sWHERE d.agent_id = :a AND (d.status IN ('VALIDEE', 'EN_ROUTE') OR d.delivered_at > now() - interval '2 days')
                ORDER BY CASE d.status WHEN 'EN_ROUTE' THEN 0 WHEN 'VALIDEE' THEN 1 ELSE 2 END, d.requested_for NULLS LAST""",
                Db.p("a", Auth.user().id()));
        rows.forEach(DeliveryService::decorate);
        return rows;
    }

    public record OrderForm(Long holderId, String cardNumber, @NotBlank String productCode, @NotNull @Positive BigDecimal quantity,
                            @NotBlank String address, BigDecimal latitude, BigDecimal longitude, LocalDateTime requestedFor,
                            Long sourceStationId, @Pattern(regexp = "CARTE|ESPECES|MOBILE_MONEY|VIREMENT") String paymentMethod,
                            String note) {}

    @PostMapping("/orders")
    @PreAuthorize("hasAuthority('LIVRAISONS')")
    public Map<String, Object> create(@Valid @RequestBody OrderForm f) {
        long id = deliveries.create(new DeliveryService.NewOrder(f.holderId(), f.cardNumber(), f.productCode(), f.quantity(),
                f.address(), f.latitude(), f.longitude(), f.requestedFor(), f.sourceStationId(), f.paymentMethod(), f.note()),
                Auth.user().id());
        return deliveries.get(id);
    }

    @PostMapping("/orders/{id}/validate")
    @PreAuthorize("hasAuthority('LIVRAISONS')")
    public Map<String, Object> validate(@PathVariable long id) {
        deliveries.validate(id, Auth.user().id());
        return deliveries.get(id);
    }

    public record AssignForm(@NotNull Long agentId, String vehicle, Long sourceStationId) {}

    @PostMapping("/orders/{id}/assign")
    @PreAuthorize("hasAuthority('LIVRAISONS')")
    public Map<String, Object> assign(@PathVariable long id, @Valid @RequestBody AssignForm f) {
        deliveries.assign(id, f.agentId(), f.vehicle(), f.sourceStationId(), Auth.user().id());
        return deliveries.get(id);
    }

    @PostMapping("/orders/{id}/depart")
    @PreAuthorize("hasAuthority('LIVRAISONS') or hasAuthority('LIVRER')")
    public Map<String, Object> depart(@PathVariable long id) {
        deliveries.depart(id, Auth.user().id());
        return deliveries.get(id);
    }

    public record DeliverForm(@Positive BigDecimal deliveredQuantity) {}

    @PostMapping("/orders/{id}/deliver")
    @PreAuthorize("hasAuthority('LIVRAISONS') or hasAuthority('LIVRER')")
    public Map<String, Object> deliver(@PathVariable long id, @Valid @RequestBody(required = false) DeliverForm f) {
        deliveries.deliver(id, f == null ? null : f.deliveredQuantity(), Auth.user().id());
        return deliveries.get(id);
    }

    public record CancelForm(String reason) {}

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize("hasAuthority('LIVRAISONS')")
    public Map<String, Object> cancel(@PathVariable long id, @RequestBody CancelForm f) {
        deliveries.cancel(id, f.reason(), Auth.user().id());
        return deliveries.get(id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
