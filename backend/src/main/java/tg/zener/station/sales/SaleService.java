package tg.zener.station.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.journal.Journal;
import tg.zener.station.stock.StockService;

/**
 * Ventes : carburant (pompe), boutique (gaz, lubrifiants, accessoires), échange de bouteilles,
 * distributeur de gaz en libre-service. Le prix dépend du profil de l'acheteur, connu par sa carte
 * (sans carte : grand public). Chaque vente porte un identifiant fourni par le poste (uuid) :
 * un renvoi après une coupure réseau ne crée pas de doublon.
 */
@Service
public class SaleService {

    public record Line(String productCode, BigDecimal quantity, boolean withDeposit) {}

    public record Sale(UUID uuid, Long stationId, Long pdvId, String kind, String channel, Long pumpId,
                       List<Line> lines, String paymentMethod, String paymentRef, String cardNumber, String pin,
                       boolean requirePin, Long employeeId, Instant at, boolean synced, String note) {}

    private final Db db;
    private final CardService cards;
    private final StockService stock;
    private final Journal journal;
    private final LiveFeed live;
    private final ZoneId zone;

    public SaleService(Db db, CardService cards, StockService stock, Journal journal, LiveFeed live,
                       @Value("${app.timezone}") String zone) {
        this.db = db;
        this.cards = cards;
        this.stock = stock;
        this.journal = journal;
        this.live = live;
        this.zone = ZoneId.of(zone);
    }

    public long price(long productId, String profile) {
        Long p = db.value("""
                \sSELECT COALESCE((SELECT price FROM product_price WHERE product_id = :p AND profile = :pr),
                                (SELECT price FROM product_price WHERE product_id = :p AND profile = 'GRAND_PUBLIC'))""",
                Db.p("p", productId, "pr", profile), Long.class);
        if (p == null) {
            throw new ApiException("Aucun prix défini pour ce produit");
        }
        return p;
    }

    /** Vente déjà enregistrée avec cet identifiant (renvoi du poste). */
    public Map<String, Object> existing(UUID uuid) {
        return uuid == null ? null : db.one("SELECT * FROM sale WHERE uuid = :u", Db.p("u", uuid));
    }

    @Transactional
    public Map<String, Object> record(Sale s) {
        Map<String, Object> done = existing(s.uuid());
        if (done != null) {
            done.put("duplicate", true);
            return done;
        }
        if (s.lines().isEmpty()) {
            throw new ApiException("Vente sans article");
        }
        String mode = s.stationId() == null ? null
                : db.value("SELECT mode FROM station WHERE id = :s AND active", Db.p("s", s.stationId()), String.class);
        if (s.stationId() != null && mode == null) {
            throw ApiException.notFound("Station introuvable");
        }
        boolean autonomous = "AUTONOME".equals(mode) && ("POMPE".equals(s.channel()) || "DISTRIBUTEUR_GAZ".equals(s.channel()));
        boolean byCard = s.cardNumber() != null && !s.cardNumber().isBlank();
        if ((autonomous || "DISTRIBUTEUR_GAZ".equals(s.channel())) && !byCard) {
            throw ApiException.refused("CARTE_REQUISE", "En libre-service, le paiement se fait par carte");
        }
        if (byCard != "CARTE".equals(s.paymentMethod())) {
            throw new ApiException(byCard ? "Mode de paiement « CARTE » attendu avec une carte" : "Numéro de carte requis");
        }

        // profil de l'acheteur : celui du titulaire de la carte, sinon grand public
        String profile = "GRAND_PUBLIC";
        Map<String, Object> card = null;
        if (byCard) {
            card = cards.find(s.cardNumber(), null);
            profile = (String) card.get("holderProfile");
            if (profile == null) {
                profile = "GRAND_PUBLIC";
            }
        }

        List<Object[]> priced = new ArrayList<>(); // productId, code, category, quantity, unitPrice, amount, name
        List<CardService.Item> items = new ArrayList<>();
        long total = 0;
        for (Line l : s.lines()) {
            if (l.quantity() == null || l.quantity().signum() <= 0) {
                throw new ApiException("Quantité invalide");
            }
            Map<String, Object> p = db.require("SELECT id, code, name, category, deposit_amount FROM product WHERE code = :c AND active",
                    Db.p("c", l.productCode()), "Produit inconnu : " + l.productCode());
            long productId = ((Number) p.get("id")).longValue();
            long unit = price(productId, profile);
            if (l.withDeposit() && p.get("depositAmount") != null) {
                unit += ((Number) p.get("depositAmount")).longValue(); // bouteille neuve : gaz + consigne
            }
            long amount = l.quantity().multiply(BigDecimal.valueOf(unit)).setScale(0, RoundingMode.HALF_UP).longValue();
            total += amount;
            priced.add(new Object[] {productId, p.get("code"), p.get("category"), l.quantity(), unit, amount, p.get("name")});
            items.add(new CardService.Item((String) p.get("code"), (String) p.get("category"), amount));
        }

        Instant at = s.at() == null ? Instant.now() : s.at();
        Long shift = s.employeeId() == null || s.stationId() == null ? null : openShift(s.employeeId(), s.stationId());
        long saleId = db.insert("""
                \sINSERT INTO sale (uuid, at, kind, channel, station_id, pdv_id, pump_id, mode, employee_id, employee_shift_id,
                                  payment_method, payment_ref, card_id, buyer_profile, total, synced_at)
                VALUES (:uuid, :at, :k, :ch, :s, :pv, :pump, :mode, :e, :sh, :pm, :pr, :card, :prof, :t, :sync)""",
                Db.p("uuid", s.uuid() == null ? UUID.randomUUID() : s.uuid(), "at", java.sql.Timestamp.from(at), "k", s.kind(),
                        "ch", s.channel(), "s", s.stationId(), "pv", s.pdvId(), "pump", s.pumpId(), "mode", mode, "e", s.employeeId(),
                        "sh", shift, "pm", s.paymentMethod(), "pr", s.paymentRef(), "card", card == null ? null : card.get("id"),
                        "prof", profile, "t", total, "sync", s.synced() ? java.sql.Timestamp.from(Instant.now()) : null));
        for (Object[] p : priced) {
            db.update("""
                    \sINSERT INTO sale_line (sale_id, product_id, quantity, unit_price, amount)
                    VALUES (:s, :p, :q, :u, :a)""", Db.p("s", saleId, "p", p[0], "q", p[3], "u", p[4], "a", p[5]));
        }

        if (card != null) {
            cards.charge(new CardService.Charge(null, ((Number) card.get("id")).longValue(), s.pin(), s.requirePin() || autonomous,
                    s.stationId(), mode, items, LocalDateTime.ofInstant(at, zone)), describe(priced), saleId);
        }
        updateStock(s, priced, saleId);
        if (s.pumpId() != null) {
            db.update("UPDATE pump SET index_total = index_total + :q, updated_at = now() WHERE id = :id",
                    Db.p("id", s.pumpId(), "q", priced.get(0)[3]));
        }
        log(s, priced, total, card, saleId);
        return db.one("SELECT * FROM sale WHERE id = :id", Db.p("id", saleId));
    }

    private void updateStock(Sale s, List<Object[]> priced, long saleId) {
        String ref = "VENTE-" + saleId;
        for (Object[] p : priced) {
            long productId = (Long) p[0];
            BigDecimal q = (BigDecimal) p[3];
            String category = (String) p[2];
            if ("CARBURANT".equals(category)) {
                stock.moveTank(stock.tankFor(s.stationId(), productId), q.negate(), "VENTE", ref, s.employeeId(), null);
            } else if ("CARTE".equals(category)) {
                continue; // les cartes sont suivies une par une (stock de cartes numérotées)
            } else {
                stock.move(s.stationId(), s.pdvId(), productId, q.negate(), "ECHANGE_GAZ".equals(s.kind()) ? "ECHANGE" : "VENTE",
                        ref, s.employeeId(), null);
                if ("GAZ".equals(category) && s.stationId() != null) {
                    int n = q.intValue();
                    boolean newBottle = s.lines().stream().anyMatch(l -> l.productCode().equals(p[1]) && l.withDeposit());
                    if ("ECHANGE_GAZ".equals(s.kind())) {
                        stock.bottles(s.stationId(), productId, n, 0, 0);   // la bouteille vide rendue rentre en stock
                    } else if (newBottle) {
                        stock.bottles(s.stationId(), productId, 0, n, 0);   // nouvelle bouteille consignée chez le client
                    }
                }
            }
        }
    }

    private void log(Sale s, List<Object[]> priced, long total, Map<String, Object> card, long saleId) {
        Object[] first = priced.get(0);
        String cardInfo = card == null ? paymentLabel(s.paymentMethod()) : "carte " + CardService.mask((String) card.get("number"));
        String type;
        String category;
        String title;
        String detail;
        if ("CARBURANT".equals(s.kind())) {
            String pump = s.pumpId() == null ? "" : db.value("SELECT code FROM pump WHERE id = :p", Db.p("p", s.pumpId()), String.class);
            type = "VENTE_CARBURANT";
            category = Journal.VENTES_CARBURANT;
            title = "Vente carburant · " + liters((BigDecimal) first[3]) + " L";
            detail = "Pompe " + pump.replace("P", "") + " · " + first[6] + " · " + cardInfo;
        } else if ("ECHANGE_GAZ".equals(s.kind())) {
            type = "ECHANGE_GAZ";
            category = Journal.VENTES_BOUTIQUE;
            title = "Échange " + first[6].toString().replace("Gaz butane ", "gaz ");
            detail = cardInfo;
        } else if ("CARTE".equals(s.kind())) {
            type = "VENTE_CARTE";
            category = Journal.VENTES_CARTES;
            title = "Vente " + first[6].toString().toLowerCase().replace("carte vip", "carte VIP");
            detail = cardInfo;
        } else {
            type = "DISTRIBUTEUR_GAZ".equals(s.channel()) ? "DISTRIBUTEUR_GAZ" : "VENTE_BOUTIQUE";
            category = Journal.VENTES_BOUTIQUE;
            title = ("DISTRIBUTEUR_GAZ".equals(s.channel()) ? "Distributeur de gaz · " : "Vente boutique · ") + describe(priced);
            detail = cardInfo;
        }
        if (s.note() != null) {
            detail = s.note();
        }
        journal.logAs(s.employeeId(), type, category, title, detail, total, s.stationId(), s.pdvId());
        live.publish("sale", s.stationId(), Map.of("saleId", saleId, "stationId", s.stationId() == null ? 0 : s.stationId(),
                "total", total));
    }

    static String describe(List<Object[]> priced) {
        List<String> parts = new ArrayList<>();
        for (Object[] p : priced) {
            String q = ((BigDecimal) p[3]).stripTrailingZeros().toPlainString().replace('.', ',');
            parts.add(("CARBURANT".equals(p[2]) ? q + " L " : q + " × ") + p[6]);
        }
        return String.join(", ", parts);
    }

    static String liters(BigDecimal l) {
        return l.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    static String paymentLabel(String m) {
        return switch (m) {
            case "MOBILE_MONEY" -> "mobile money";
            case "VIREMENT" -> "virement";
            case "CARTE" -> "carte";
            default -> "espèces";
        };
    }

    /** Poste en cours de l'employé ; ouvert automatiquement pour un pompiste qui vend sans l'avoir ouvert. */
    Long openShift(long employeeId, long stationId) {
        Long id = db.value("SELECT id FROM employee_shift WHERE employee_id = :e AND status <> 'CLOS'", Db.p("e", employeeId), Long.class);
        if (id != null) {
            return id;
        }
        String role = db.value("SELECT role_code FROM employee WHERE id = :e", Db.p("e", employeeId), String.class);
        if (!"POMPISTE".equals(role) && !"CHEF_POSTE".equals(role)) {
            return null;
        }
        return db.insert("""
                \sINSERT INTO employee_shift (employee_id, station_id, config_id)
                VALUES (:e, :s, (SELECT id FROM shift_config WHERE station_id = :s
                                 AND ((start_time <= end_time AND localtime >= start_time AND localtime < end_time)
                                      OR (start_time > end_time AND (localtime >= start_time OR localtime < end_time)))
                                 LIMIT 1))""", Db.p("e", employeeId, "s", stationId));
    }
}
