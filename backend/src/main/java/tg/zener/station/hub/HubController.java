package tg.zener.station.hub;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.mobility.Tracking;
import tg.zener.station.sales.SaleService;
import tg.zener.station.stations.StationService;
import tg.zener.station.stock.StockService;

/**
 * API du PC station. Le hub de la station envoie un flux binaire sur un port série ; le programme
 * du PC station le décode et appelle ces points d'entrée, authentifié par les en-têtes
 * X-Station-Code et X-Station-Key (clé générée à la création de la station).
 */
@RestController
@RequestMapping("/api/hub")
public class HubController {

    private final Db db;
    private final StationService stations;
    private final StockService stock;
    private final CardService cards;
    private final SaleService sales;
    private final Tracking tracking;
    private final LiveFeed live;

    public HubController(Db db, StationService stations, StockService stock, CardService cards, SaleService sales,
                         Tracking tracking, LiveFeed live) {
        this.db = db;
        this.stations = stations;
        this.stock = stock;
        this.cards = cards;
        this.sales = sales;
        this.tracking = tracking;
        this.live = live;
    }

    // ------------------------------------------------------------------ signal de vie

    public record PumpState(String code, String port, String state, BigDecimal currentVolume, BigDecimal indexTotal) {}

    public record TankLevel(Long id, String name, @NotNull BigDecimal volume) {}

    public record Heartbeat(Boolean readerConnected, List<@Valid PumpState> pumps, List<@Valid TankLevel> tanks) {}

    /**
     * Signal de vie (toutes les 30 s environ) : état du lecteur, des pistolets et jauges des cuves.
     * Réponse : mode de fonctionnement, prix et configuration des pistolets.
     */
    @PostMapping("/heartbeat")
    @Transactional
    public Map<String, Object> heartbeat(@RequestHeader(value = "X-Station-Code", required = false) String code,
                                         @RequestHeader(value = "X-Station-Key", required = false) String key,
                                         @Valid @RequestBody(required = false) Heartbeat h) {
        Map<String, Object> s = stations.authenticateStation(code, key);
        long stationId = num(s.get("id"));
        db.update("UPDATE station SET last_seen_at = now(), reader_connected = COALESCE(:r, reader_connected) WHERE id = :id",
                Db.p("id", stationId, "r", h == null ? null : h.readerConnected()));
        if (h != null && h.pumps() != null) {
            for (PumpState p : h.pumps()) {
                if (p.state() != null && !List.of("LIBRE", "DISTRIBUE", "HORS_SERVICE").contains(p.state())) {
                    throw new ApiException("État de pistolet inconnu : " + p.state());
                }
                Map<String, Object> before = findPump(stationId, p.code(), p.port());
                db.update("""
                        \sUPDATE pump SET state = COALESCE(:st, state), current_volume = :cv,
                               index_total = greatest(index_total, COALESCE(:it, index_total)), updated_at = now()
                        WHERE id = :id""",
                        Db.p("id", before.get("id"), "st", p.state(), "cv", "DISTRIBUE".equals(p.state()) ? p.currentVolume() : null,
                                "it", p.indexTotal()));
                if (p.state() != null && !p.state().equals(before.get("state"))) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("stationId", stationId);
                    e.put("pumpId", before.get("id"));
                    e.put("code", before.get("code"));
                    e.put("state", p.state());
                    e.put("currentVolume", p.currentVolume());
                    live.publish("pump", stationId, e);
                }
            }
        }
        if (h != null && h.tanks() != null) {
            for (TankLevel t : h.tanks()) {
                Long tankId = db.value("""
                        \sSELECT id FROM tank WHERE station_id = :s AND (id = :id OR (CAST(:id AS bigint) IS NULL AND name = :n))""",
                        Db.p("s", stationId, "id", t.id(), "n", t.name()), Long.class);
                if (tankId == null) {
                    throw ApiException.notFound("Cuve inconnue : " + (t.id() != null ? t.id() : t.name()));
                }
                if (t.volume().signum() < 0) {
                    throw new ApiException("Volume de cuve invalide");
                }
                stock.setTankVolume(tankId, t.volume());
            }
        }
        return config(stationId);
    }

    /** Mode, prix grand public et configuration de la station, renvoyés au PC station. */
    private Map<String, Object> config(long stationId) {
        Map<String, Object> s = db.one("""
                \sSELECT id AS station_id, code, short_name, mode, auto_switch, mode_override_until, reader_port, serial_port,
                       serial_baud, hub_ports, kind
                FROM station WHERE id = :id""", Db.p("id", stationId));
        s.put("modeLabel", StationService.modeLabel(s.get("mode")));
        s.put("serverTime", Instant.now());
        s.put("pumps", db.list("""
                \sSELECT pu.id, pu.code, pu.port, pu.state, p.code AS product_code
                FROM pump pu JOIN product p ON p.id = pu.product_id WHERE pu.station_id = :s ORDER BY pu.port""",
                Db.p("s", stationId)));
        s.put("tanks", db.list("""
                \sSELECT t.id, t.name, p.code AS product_code, t.capacity, t.volume FROM tank t
                JOIN product p ON p.id = t.product_id WHERE t.station_id = :s ORDER BY t.id""", Db.p("s", stationId)));
        s.put("prices", db.list("""
                \sSELECT p.code AS product_code, p.name, p.category, pp.price, p.deposit_amount
                FROM product p JOIN product_price pp ON pp.product_id = p.id AND pp.profile = 'GRAND_PUBLIC'
                WHERE p.active AND p.category IN ('CARBURANT', 'GAZ') ORDER BY p.category, p.code"""));
        return s;
    }

    // ------------------------------------------------------------------ pré-autorisation d'une carte

    public record AuthorizeForm(@NotBlank String cardNumber, String pin, String pumpCode, String port, String productCode) {}

    /**
     * Pré-autorisation (mode autonome surtout) : la carte est-elle acceptée pour ce carburant,
     * ici et maintenant ? Renvoie le montant et le volume maximum que la pompe peut délivrer.
     */
    @PostMapping("/authorize")
    public Map<String, Object> authorize(@RequestHeader(value = "X-Station-Code", required = false) String code,
                                         @RequestHeader(value = "X-Station-Key", required = false) String key,
                                         @Valid @RequestBody AuthorizeForm f) {
        Map<String, Object> s = stations.authenticateStation(code, key);
        long stationId = num(s.get("id"));
        touch(stationId);
        String product = f.productCode();
        if (f.pumpCode() != null || f.port() != null) {
            Map<String, Object> pump = findPump(stationId, f.pumpCode(), f.port());
            if ("HORS_SERVICE".equals(pump.get("state"))) {
                throw ApiException.conflict("Ce pistolet est hors service");
            }
            product = (String) pump.get("productCode");
        }
        if (product == null) {
            throw new ApiException("Pistolet ou produit requis");
        }
        Map<String, Object> p = db.require("SELECT id, category FROM product WHERE code = :c", Db.p("c", product), "Produit inconnu");
        String mode = (String) s.get("mode");
        boolean autonomous = "AUTONOME".equals(mode);
        Map<String, Object> res = cards.authorize(new CardService.Charge(f.cardNumber(), null, f.pin(), autonomous, stationId, mode,
                List.of(new CardService.Item(product, (String) p.get("category"), 0)), stations.now()));
        String profile = db.value("""
                \sSELECT h.profile FROM card c JOIN holder h ON h.id = c.holder_id WHERE c.id = :c""",
                Db.p("c", res.get("cardId")), String.class);
        long unit = sales.price(num(p.get("id")), profile == null ? "GRAND_PUBLIC" : profile);
        long max = num(res.get("maxAmount"));
        res.put("productCode", product);
        res.put("unitPrice", unit);
        res.put("maxLiters", unit == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(max).divide(BigDecimal.valueOf(unit), 2, java.math.RoundingMode.DOWN));
        res.put("mode", mode);
        res.put("authorized", max > 0);
        res.remove("number");
        return res;
    }

    // ------------------------------------------------------------------ ventes

    public record HubSale(@NotNull UUID uuid, String pumpCode, String port, @NotNull @Positive BigDecimal liters,
                          String paymentMethod, String paymentRef, String cardNumber, String pin, Instant at) {}

    /**
     * Vente carburant relevée par le hub. Idempotente (uuid) : un renvoi après une coupure ne crée
     * pas de doublon. En mode autonome, carte et PIN sont obligatoires.
     */
    @PostMapping("/sales")
    public Map<String, Object> sale(@RequestHeader(value = "X-Station-Code", required = false) String code,
                                    @RequestHeader(value = "X-Station-Key", required = false) String key,
                                    @Valid @RequestBody HubSale f) {
        Map<String, Object> s = stations.authenticateStation(code, key);
        long stationId = num(s.get("id"));
        touch(stationId);
        Map<String, Object> done = sales.existing(f.uuid());
        if (done != null) {
            done.put("duplicate", true);
            return done;
        }
        Map<String, Object> pump = findPump(stationId, f.pumpCode(), f.port());
        boolean byCard = f.cardNumber() != null && !f.cardNumber().isBlank();
        String payment = f.paymentMethod() != null ? f.paymentMethod() : byCard ? "CARTE" : "ESPECES";
        if (!List.of("ESPECES", "MOBILE_MONEY", "CARTE").contains(payment)) {
            throw new ApiException("Mode de paiement inconnu : " + payment);
        }
        Instant at = f.at() == null || f.at().isAfter(Instant.now()) ? Instant.now() : f.at();
        boolean late = Duration.between(at, Instant.now()).toSeconds() > 120;
        Long employee = db.value("""
                \sSELECT es.employee_id FROM employee_shift es
                WHERE es.station_id = :s AND es.status = 'EN_POSTE'
                  AND es.pumps ~ ('(^|[^A-Za-z0-9])' || :code || '([^A-Za-z0-9]|$)')
                ORDER BY es.started_at DESC LIMIT 1""", Db.p("s", stationId, "code", pump.get("code")), Long.class);
        Map<String, Object> sale = sales.record(new SaleService.Sale(f.uuid(), stationId, null, "CARBURANT", "POMPE",
                num(pump.get("id")), List.of(new SaleService.Line((String) pump.get("productCode"), f.liters(), false)),
                payment, f.paymentRef(), byCard ? f.cardNumber() : null, f.pin(), false,
                "AUTONOME".equals(s.get("mode")) ? null : employee, at, late, null));
        db.update("UPDATE pump SET state = 'LIBRE', current_volume = NULL WHERE id = :id AND state = 'DISTRIBUE'",
                Db.p("id", pump.get("id")));
        return sale;
    }

    public record GasForm(@NotNull UUID uuid, @NotBlank String productCode, @NotBlank String cardNumber, String pin,
                          Integer quantity, Boolean exchange, Instant at) {}

    /**
     * Distributeur de gaz en libre-service : le client introduit sa carte et se sert une bouteille.
     * Par défaut il rend une bouteille vide (échange) ; sinon il paie aussi la consigne.
     */
    @PostMapping("/gas")
    public Map<String, Object> gas(@RequestHeader(value = "X-Station-Code", required = false) String code,
                                   @RequestHeader(value = "X-Station-Key", required = false) String key,
                                   @Valid @RequestBody GasForm f) {
        Map<String, Object> s = stations.authenticateStation(code, key);
        long stationId = num(s.get("id"));
        touch(stationId);
        String category = db.value("SELECT category FROM product WHERE code = :c", Db.p("c", f.productCode()), String.class);
        if (!"GAZ".equals(category)) {
            throw new ApiException("Le distributeur ne délivre que des bouteilles de gaz");
        }
        int q = f.quantity() == null ? 1 : f.quantity();
        if (q < 1 || q > 5) {
            throw new ApiException("Quantité entre 1 et 5 bouteilles");
        }
        boolean exchange = f.exchange() == null || f.exchange();
        Instant at = f.at() == null || f.at().isAfter(Instant.now()) ? Instant.now() : f.at();
        return sales.record(new SaleService.Sale(f.uuid(), stationId, null, exchange ? "ECHANGE_GAZ" : "BOUTIQUE", "DISTRIBUTEUR_GAZ",
                null, List.of(new SaleService.Line(f.productCode(), BigDecimal.valueOf(q), !exchange)), "CARTE", null,
                f.cardNumber(), f.pin(), true, null, at, Duration.between(at, Instant.now()).toSeconds() > 120, null));
    }

    // ------------------------------------------------------------------ station mobile

    public record LocationForm(@NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                               @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                               BigDecimal accuracyM, BigDecimal speedKmh, BigDecimal heading, Instant at) {}

    /** Position d'une station mobile (camion-citerne), affichée sur la carte en direct. */
    @PostMapping("/location")
    public Map<String, Object> location(@RequestHeader(value = "X-Station-Code", required = false) String code,
                                        @RequestHeader(value = "X-Station-Key", required = false) String key,
                                        @Valid @RequestBody LocationForm f) {
        Map<String, Object> s = stations.authenticateStation(code, key);
        if (!"MOBILE".equals(s.get("kind"))) {
            throw new ApiException("Seule une station mobile envoie sa position");
        }
        long stationId = num(s.get("id"));
        touch(stationId);
        tracking.record("STATION_MOBILE", stationId, null, f.latitude(), f.longitude(), f.accuracyM(), f.speedKmh(), f.heading(),
                f.at(), (String) s.get("shortName"));
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------

    private void touch(long stationId) {
        db.update("UPDATE station SET last_seen_at = now() WHERE id = :id", Db.p("id", stationId));
    }

    private Map<String, Object> findPump(long stationId, String code, String port) {
        if ((code == null || code.isBlank()) && (port == null || port.isBlank())) {
            throw new ApiException("Pistolet requis (code ou port)");
        }
        return db.require("""
                \sSELECT pu.id, pu.code, pu.port, pu.state, p.code AS product_code
                FROM pump pu JOIN product p ON p.id = pu.product_id
                WHERE pu.station_id = :s AND (pu.code = CAST(:c AS text) OR upper(pu.port) = upper(CAST(:p AS text)))""",
                Db.p("s", stationId, "c", code, "p", port), "Pistolet inconnu : " + (code != null ? code : port));
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }
}
