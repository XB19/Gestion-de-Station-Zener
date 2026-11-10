package tg.zener.station.mobility;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.Mailer;
import tg.zener.station.common.Refs;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.sales.SaleService;
import tg.zener.station.stock.StockService;

/**
 * Zen Mobility : commandes de carburant livrées par un agent (1 000 L minimum).
 * Cycle : DEMANDEE → VALIDEE → EN_ROUTE → LIVREE (ou ANNULEE). À la livraison, le carburant
 * sort de la cuve de la station source et la carte du client est débitée si elle paie.
 */
@Service
public class DeliveryService {

    public record NewOrder(Long holderId, String cardNumber, String productCode, BigDecimal quantity, String address,
                           BigDecimal latitude, BigDecimal longitude, LocalDateTime requestedFor, Long sourceStationId,
                           String paymentMethod, String note) {}

    private final Db db;
    private final Refs refs;
    private final Settings settings;
    private final SaleService sales;
    private final CardService cards;
    private final StockService stock;
    private final Journal journal;
    private final LiveFeed live;
    private final Mailer mailer;

    public DeliveryService(Db db, Refs refs, Settings settings, SaleService sales, CardService cards, StockService stock,
                           Journal journal, LiveFeed live, Mailer mailer) {
        this.db = db;
        this.refs = refs;
        this.settings = settings;
        this.sales = sales;
        this.cards = cards;
        this.stock = stock;
        this.journal = journal;
        this.live = live;
        this.mailer = mailer;
    }

    public static String statusLabel(Object status) {
        return switch (String.valueOf(status)) {
            case "VALIDEE" -> "Validée";
            case "EN_ROUTE" -> "En route";
            case "LIVREE" -> "Livrée";
            case "ANNULEE" -> "Annulée";
            default -> "Demandée";
        };
    }

    @Transactional
    public long create(NewOrder o, long userId) {
        BigDecimal min = BigDecimal.valueOf(settings.getLong("mobility.min_quantity", 1000));
        if (o.quantity() == null || o.quantity().compareTo(min) < 0) {
            throw new ApiException("Commande de " + min.toPlainString() + " L minimum");
        }
        Map<String, Object> product = db.require("SELECT id, code, name FROM product WHERE code = :c AND category = 'CARBURANT' AND active",
                Db.p("c", o.productCode()), "Carburant inconnu : " + o.productCode());
        Long holderId = o.holderId();
        Long cardId = null;
        String payment = o.paymentMethod() == null ? "VIREMENT" : o.paymentMethod();
        if (o.cardNumber() != null && !o.cardNumber().isBlank()) {
            Map<String, Object> card = cards.find(o.cardNumber(), null);
            if (!"ACTIVE".equals(card.get("status"))) {
                throw ApiException.conflict("La carte doit être active");
            }
            if (holderId != null && !holderId.equals(num(card.get("holderId")))) {
                throw new ApiException("Cette carte n'appartient pas au client");
            }
            holderId = num(card.get("holderId"));
            cardId = num(card.get("id"));
            payment = "CARTE";
        } else if ("CARTE".equals(payment)) {
            throw new ApiException("Numéro de carte requis pour un paiement par carte");
        }
        if (holderId == null) {
            throw new ApiException("Client requis (holderId ou carte)");
        }
        String profile = (String) db.require("SELECT profile FROM holder WHERE id = :h", Db.p("h", holderId), "Client introuvable")
                .get("profile");
        long unitPrice = sales.price(num(product.get("id")), profile);
        if (o.sourceStationId() != null) {
            checkSource(o.sourceStationId(), num(product.get("id")), o.quantity());
        }
        String ref = refs.delivery();
        long id = db.insert("""
                \sINSERT INTO delivery_order (ref, holder_id, card_id, product_id, quantity, unit_price, address, latitude, longitude,
                                            requested_for, source_station_id, payment_method, note, created_by)
                VALUES (:ref, :h, :c, :p, :q, :u, :a, :lat, :lon, :rf, :src, :pm, :n, :by)""",
                Db.p("ref", ref, "h", holderId, "c", cardId, "p", product.get("id"), "q", o.quantity(), "u", unitPrice,
                        "a", o.address().trim(), "lat", o.latitude(), "lon", o.longitude(),
                        "rf", o.requestedFor(), "src", o.sourceStationId(), "pm", payment,
                        "n", o.note() == null || o.note().isBlank() ? null : o.note().trim(), "by", userId));
        Map<String, Object> d = get(id);
        journal.logAs(userId, "LIVRAISON_COMMANDEE", Journal.LIVRAISONS, "Commande Zen Mobility",
                ref + " · " + qty(o.quantity()) + " L " + product.get("name") + " · " + d.get("holderName"),
                num(d.get("amount")), o.sourceStationId(), null);
        publish(d);
        return id;
    }

    static final String SELECT = """
            \sSELECT d.id, d.ref, d.status, d.holder_id, h.name AS holder_name, h.phone AS holder_phone, d.card_id,
                   c.number AS card_number, d.product_id, p.code AS product_code, p.name AS product_name,
                   d.quantity, d.unit_price, round(d.quantity * d.unit_price) AS amount, d.payment_method,
                   d.address, d.latitude, d.longitude, d.requested_for,
                   d.source_station_id, s.short_name AS source_station_name, s.latitude AS source_latitude,
                   s.longitude AS source_longitude,
                   d.agent_id, a.first_name || ' ' || a.last_name AS agent_name, a.phone AS agent_phone, d.vehicle,
                   CASE WHEN a.photo_path IS NULL THEN NULL ELSE '/files/' || a.photo_path END AS agent_photo_url,
                   d.delivered_quantity, d.note, d.cancel_reason, d.created_at, d.departed_at, d.delivered_at, d.sale_id,
                   cb.first_name || ' ' || cb.last_name AS created_by_name,
                   lp.latitude AS last_latitude, lp.longitude AS last_longitude, lp.at AS last_ping_at,
                   CAST(extract(epoch FROM now() - lp.at) AS int) AS last_ping_age_seconds
            FROM delivery_order d
            JOIN holder h ON h.id = d.holder_id JOIN product p ON p.id = d.product_id
            LEFT JOIN card c ON c.id = d.card_id LEFT JOIN station s ON s.id = d.source_station_id
            LEFT JOIN employee a ON a.id = d.agent_id LEFT JOIN employee cb ON cb.id = d.created_by
            LEFT JOIN LATERAL (SELECT latitude, longitude, at FROM location_ping
                               WHERE subject = 'LIVREUR' AND subject_id = d.agent_id
                                 AND (d.status = 'EN_ROUTE' OR delivery_id = d.id)
                               ORDER BY at DESC LIMIT 1) lp ON d.agent_id IS NOT NULL""";

    public Map<String, Object> get(long id) {
        Map<String, Object> d = db.require(SELECT + " WHERE d.id = :id", Db.p("id", id), "Commande introuvable");
        return decorate(d);
    }

    static Map<String, Object> decorate(Map<String, Object> d) {
        d.put("statusLabel", statusLabel(d.get("status")));
        if (d.get("cardNumber") != null) {
            d.put("cardMasked", CardService.mask((String) d.get("cardNumber")));
        }
        return d;
    }

    @Transactional
    public void validate(long id, long userId) {
        Map<String, Object> d = lock(id);
        expect(d, "DEMANDEE", "Seule une commande demandée peut être validée");
        db.update("UPDATE delivery_order SET status = 'VALIDEE' WHERE id = :id", Db.p("id", id));
        journal.logAs(userId, "LIVRAISON_VALIDEE", Journal.LIVRAISONS, "Commande validée", (String) d.get("ref"),
                null, num(d.get("sourceStationId")), null);
        publish(get(id));
    }

    @Transactional
    public void assign(long id, long agentId, String vehicle, Long sourceStationId, long userId) {
        Map<String, Object> d = lock(id);
        String status = (String) d.get("status");
        if (!"VALIDEE".equals(status) && !"DEMANDEE".equals(status)) {
            throw ApiException.conflict("La commande n'est plus modifiable");
        }
        Map<String, Object> agent = db.require("""
                \sSELECT id, first_name || ' ' || last_name AS name, email FROM employee
                WHERE id = :a AND active AND role_code = 'LIVREUR'""", Db.p("a", agentId), "Livreur introuvable");
        Long source = sourceStationId != null ? sourceStationId : num(d.get("sourceStationId"));
        if (source == null) {
            throw new ApiException("Station source requise");
        }
        checkSource(source, num(d.get("productId")), (BigDecimal) d.get("quantity"));
        db.update("""
                \sUPDATE delivery_order SET agent_id = :a, vehicle = :v, source_station_id = :s,
                       status = CASE WHEN status = 'DEMANDEE' THEN 'VALIDEE' ELSE status END
                WHERE id = :id""",
                Db.p("id", id, "a", agentId, "v", vehicle == null || vehicle.isBlank() ? null : vehicle.trim(), "s", source));
        journal.logAs(userId, "LIVRAISON_AFFECTEE", Journal.LIVRAISONS, "Livreur affecté",
                d.get("ref") + " · " + agent.get("name") + (vehicle == null || vehicle.isBlank() ? "" : " · " + vehicle.trim()),
                null, source, null);
        if (agent.get("email") != null) {
            mailer.queue(List.of((String) agent.get("email")), "Livraison " + d.get("ref") + " affectée",
                    "Livraison de " + qty((BigDecimal) d.get("quantity")) + " L · " + d.get("address"), "LIVRAISON");
        }
        publish(get(id));
    }

    @Transactional
    public void depart(long id, long userId) {
        Map<String, Object> d = lock(id);
        expect(d, "VALIDEE", "La commande doit être validée et affectée avant le départ");
        if (d.get("agentId") == null) {
            throw ApiException.conflict("Aucun livreur affecté");
        }
        checkAgent(d, userId);
        if (db.count("SELECT count(*) FROM delivery_order WHERE agent_id = :a AND status = 'EN_ROUTE'",
                Db.p("a", d.get("agentId"))) > 0) {
            throw ApiException.conflict("Ce livreur a déjà une livraison en cours");
        }
        db.update("UPDATE delivery_order SET status = 'EN_ROUTE', departed_at = now() WHERE id = :id", Db.p("id", id));
        journal.logAs(userId, "LIVRAISON_DEPART", Journal.LIVRAISONS, "Livraison en route", (String) d.get("ref"),
                null, num(d.get("sourceStationId")), null);
        publish(get(id));
    }

    /**
     * Livraison effectuée : sortie de cuve de la station source, vente enregistrée (canal LIVRAISON)
     * et, si la commande est payée par carte, débit de la carte avec contrôle de ses permissions.
     */
    @Transactional
    public void deliver(long id, BigDecimal deliveredQuantity, long userId) {
        Map<String, Object> d = lock(id);
        expect(d, "EN_ROUTE", "La livraison n'est pas en route");
        checkAgent(d, userId);
        BigDecimal q = deliveredQuantity == null ? (BigDecimal) d.get("quantity") : deliveredQuantity;
        if (q.signum() <= 0 || q.compareTo(((BigDecimal) d.get("quantity")).multiply(BigDecimal.valueOf(1.05))) > 0) {
            throw new ApiException("Quantité livrée invalide");
        }
        long station = num(d.get("sourceStationId"));
        long productId = num(d.get("productId"));
        long unit = num(d.get("unitPrice"));
        long amount = q.multiply(BigDecimal.valueOf(unit)).setScale(0, RoundingMode.HALF_UP).longValue();
        String profile = db.value("SELECT profile FROM holder WHERE id = :h", Db.p("h", d.get("holderId")), String.class);
        long saleId = db.insert("""
                \sINSERT INTO sale (uuid, kind, channel, station_id, employee_id, payment_method, card_id, buyer_profile, total)
                VALUES (:u, 'CARBURANT', 'LIVRAISON', :s, :e, :pm, :c, :pr, :t)""",
                Db.p("u", UUID.randomUUID(), "s", station, "e", d.get("agentId"), "pm", d.get("paymentMethod"),
                        "c", d.get("cardId"), "pr", profile == null ? "GRAND_PUBLIC" : profile, "t", amount));
        db.update("INSERT INTO sale_line (sale_id, product_id, quantity, unit_price, amount) VALUES (:s, :p, :q, :u, :a)",
                Db.p("s", saleId, "p", productId, "q", q, "u", unit, "a", amount));
        if (d.get("cardId") != null) {
            cards.charge(new CardService.Charge(null, num(d.get("cardId")), null, false, station, null,
                    List.of(new CardService.Item((String) d.get("productCode"), "CARBURANT", amount)), null),
                    "Livraison " + d.get("ref") + " · " + qty(q) + " L", saleId);
        }
        stock.moveTank(stock.tankFor(station, productId), q.negate(), "LIVRAISON", (String) d.get("ref"), userId,
                "Zen Mobility · " + d.get("holderName"));
        db.update("""
                \sUPDATE delivery_order SET status = 'LIVREE', delivered_at = now(), delivered_quantity = :q, sale_id = :sale
                WHERE id = :id""", Db.p("id", id, "q", q, "sale", saleId));
        journal.logAs(userId, "LIVRAISON_EFFECTUEE", Journal.LIVRAISONS, "Livraison effectuée · " + qty(q) + " L",
                d.get("ref") + " · " + d.get("productName") + " · " + d.get("holderName"), amount, station, null);
        publish(get(id));
    }

    @Transactional
    public void cancel(long id, String reason, long userId) {
        Map<String, Object> d = lock(id);
        String status = (String) d.get("status");
        if ("LIVREE".equals(status) || "ANNULEE".equals(status)) {
            throw ApiException.conflict("Cette commande ne peut plus être annulée");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException("Motif d'annulation requis");
        }
        db.update("UPDATE delivery_order SET status = 'ANNULEE', cancel_reason = :r WHERE id = :id",
                Db.p("id", id, "r", reason.trim()));
        journal.logAs(userId, "LIVRAISON_ANNULEE", Journal.LIVRAISONS, "Commande annulée", d.get("ref") + " · " + reason.trim(),
                null, num(d.get("sourceStationId")), null);
        publish(get(id));
    }

    // ------------------------------------------------------------------ contrôles

    private Map<String, Object> lock(long id) {
        db.require("SELECT id FROM delivery_order WHERE id = :id FOR UPDATE", Db.p("id", id), "Commande introuvable");
        return get(id);
    }

    private static void expect(Map<String, Object> d, String status, String message) {
        if (!status.equals(d.get("status"))) {
            throw ApiException.conflict(message);
        }
    }

    /** Un livreur n'agit que sur ses propres livraisons ; le responsable Zen Mobility sur toutes. */
    private void checkAgent(Map<String, Object> d, long userId) {
        String role = db.value("SELECT role_code FROM employee WHERE id = :u", Db.p("u", userId), String.class);
        if ("LIVREUR".equals(role) && (d.get("agentId") == null || num(d.get("agentId")) != userId)) {
            throw ApiException.forbidden("Cette livraison est affectée à un autre livreur");
        }
    }

    private void checkSource(long stationId, long productId, BigDecimal quantity) {
        BigDecimal available = db.value("""
                \sSELECT COALESCE(max(volume), 0) FROM tank WHERE station_id = :s AND product_id = :p""",
                Db.p("s", stationId, "p", productId), BigDecimal.class);
        if (available == null || available.signum() == 0) {
            throw new ApiException("La station source n'a pas de cuve de ce carburant");
        }
        if (available.compareTo(quantity) < 0) {
            throw ApiException.conflict("Stock insuffisant dans la station source (" + qty(available) + " L disponibles)");
        }
    }

    private void publish(Map<String, Object> d) {
        live.publish("delivery", null, Map.of("id", d.get("id"), "ref", d.get("ref"), "status", d.get("status"),
                "statusLabel", d.get("statusLabel")));
    }

    static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    static String qty(BigDecimal q) {
        return String.format(java.util.Locale.ROOT, "%,d", q.setScale(0, RoundingMode.HALF_UP).longValue()).replace(',', ' ');
    }
}
