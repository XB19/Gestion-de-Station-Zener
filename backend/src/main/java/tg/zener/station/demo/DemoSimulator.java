package tg.zener.station.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.mobility.DeliveryService;
import tg.zener.station.mobility.Tracking;
import tg.zener.station.pdv.PdvService;
import tg.zener.station.recharges.RechargeService;
import tg.zener.station.sales.SaleService;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.security.UserLoader;
import tg.zener.station.shifts.ShiftService;
import tg.zener.station.stock.StockService;

/**
 * Animation de la démonstration (DEMO_DATA=true). Toutes les quelques secondes, une opération
 * passe par les vrais services (ventes à la pompe, paiements par carte, boutique et gaz, cartes
 * vendues en point de vente, recharges, refus, validations) : le journal, le tableau de bord et le
 * flux temps réel bougent comme en exploitation. Chaque minute, l'entretien garde la démonstration
 * crédible sur plusieurs jours : relève des postes, livraisons de carburant, réassort de la boutique,
 * recharges des cartes flottes et tournées de livraison Zen Mobility.
 * À retirer avec le reste du paquet demo avant la mise en production.
 */
@Component
public class DemoSimulator {

    private static final Logger log = LoggerFactory.getLogger(DemoSimulator.class);
    static final String PIN = DemoSeeder.PIN;

    /** Cartes de démonstration utilisées à la pompe : numéro, carburants permis, codes des stations permises (null = toutes). */
    record DemoCard(String number, List<String> fuels, List<String> stations, boolean daytimeOnly) {}

    static final List<DemoCard> CARDS = List.of(
            new DemoCard("601128470093", List.of("SUPER", "GASOIL"), null, true),          // Translom (06h–20h, lun–sam)
            new DemoCard("601144170028", List.of("SUPER", "GASOIL"), List.of("ST-001", "ST-002", "ST-003"), false), // Lomé Logistique
            new DemoCard("601133027782", List.of("GASOIL"), null, false),                   // Agro-Plus : gasoil seulement
            new DemoCard("601128474410", List.of("SUPER", "GASOIL", "PETROLE"), null, false)); // Komi Mensah (VIP)

    /** Clients livrés par Zen Mobility (adresse, latitude, longitude). */
    static final Object[][] DESTINATIONS = {
            {"Translom SARL", "Dépôt Translom, zone portuaire, Lomé", 6.1372, 1.2861},
            {"Lomé Logistique", "Base Lomé Logistique, Agbalépédogan, Lomé", 6.1780, 1.2150},
            {"Agro-Plus Togo", "Entrepôt Agro-Plus, route de Kpalimé, Lomé", 6.1840, 1.1920},
            {"Société Togolaise des Eaux", "Usine TDE, Cacavéli, Lomé", 6.1950, 1.1890}};

    private final Db db;
    private final SaleService sales;
    private final PdvService pdvs;
    private final RechargeService recharges;
    private final ShiftService shifts;
    private final StockService stock;
    private final DeliveryService deliveries;
    private final Tracking tracking;
    private final Settings settings;
    private final UserLoader users;
    private final Journal journal;
    private final LiveFeed live;
    private final boolean enabled;
    private final Random rnd = new Random();
    private final Map<Long, CurrentUser> userCache = new ConcurrentHashMap<>();
    private volatile boolean started;

    public DemoSimulator(Db db, SaleService sales, PdvService pdvs, RechargeService recharges, ShiftService shifts, StockService stock,
                         DeliveryService deliveries, Tracking tracking, Settings settings, UserLoader users, Journal journal,
                         LiveFeed live, @Value("${app.demo-data}") boolean enabled) {
        this.db = db;
        this.sales = sales;
        this.pdvs = pdvs;
        this.recharges = recharges;
        this.shifts = shifts;
        this.stock = stock;
        this.deliveries = deliveries;
        this.tracking = tracking;
        this.settings = settings;
        this.users = users;
        this.journal = journal;
        this.live = live;
        this.enabled = enabled;
    }

    public void start() {
        started = enabled;
        if (started) {
            log.info("Simulateur de démonstration démarré (une opération toutes les quelques secondes)");
        }
    }

    // ================================================================== toutes les 6 secondes

    @Scheduled(fixedDelay = 6_000, initialDelay = 8_000)
    public void tick() {
        if (!started) {
            return;
        }
        userCache.clear();
        safe("signaux de vie", this::heartbeats);
        safe("pistolets", this::pumps);
        safe("livreur", this::moveDriver);
        // activité proportionnelle à l'affluence de l'heure (forte en journée, faible la nuit)
        double load = DemoSeeder.HOURS[LocalTime.now().getHour()] / 1.6;
        if (rnd.nextDouble() < 0.42 * load + 0.05) {
            safe("opération", this::randomOperation);
        }
        if (rnd.nextDouble() < 0.18 * load) {
            safe("vente", this::cashFuelSale);
        }
    }

    private void randomOperation() {
        int r = rnd.nextInt(100);
        if (r < 42) {
            cashFuelSale();
        } else if (r < 58) {
            cardFuelSale();
        } else if (r < 69) {
            shopSale();
        } else if (r < 78) {
            pdvCardSale();
        } else if (r < 88) {
            smallRecharge();
        } else if (r < 92) {
            activateCard();
        } else if (r < 95) {
            refusedPayment();
        } else if (r < 97) {
            largeRecharge();
        } else {
            gasDispenser();
        }
    }

    private void heartbeats() {
        db.update("UPDATE station SET last_seen_at = now() WHERE code = ANY(:c)", Db.p("c", codes("demo.online_codes")));
        db.update("UPDATE station SET last_seen_at = now() - interval '5 minutes' WHERE code = ANY(:c)",
                Db.p("c", codes("demo.unstable_codes")));
        db.update("UPDATE pdv SET last_sync_at = now() WHERE status = 'OUVERT' AND code = ANY(:c)",
                Db.p("c", codes("demo.pdv_online_codes")));
    }

    /** Un pistolet commence à distribuer, ceux qui distribuaient depuis un moment se libèrent. */
    private void pumps() {
        for (Map<String, Object> p : db.list("""
                \sUPDATE pump SET state = 'LIBRE', current_volume = NULL, updated_at = now()
                WHERE state = 'DISTRIBUE' AND updated_at < now() - interval '18 seconds'
                RETURNING id, station_id, code""")) {
            publishPump(p, "LIBRE", null);
        }
        if (rnd.nextDouble() < 0.6) {
            Map<String, Object> p = db.one("""
                    \sSELECT pu.id, pu.station_id, pu.code FROM pump pu JOIN station s ON s.id = pu.station_id
                    WHERE pu.state = 'LIBRE' AND s.code = ANY(:c) ORDER BY random() LIMIT 1""", Db.p("c", codes("demo.online_codes")));
            if (p != null) {
                BigDecimal v = BigDecimal.valueOf(2 + rnd.nextInt(40) + rnd.nextInt(10) / 10.0);
                db.update("UPDATE pump SET state = 'DISTRIBUE', current_volume = :v, updated_at = now() WHERE id = :id",
                        Db.p("id", p.get("id"), "v", v));
                publishPump(p, "DISTRIBUE", v);
            }
        }
    }

    private void publishPump(Map<String, Object> p, String state, BigDecimal volume) {
        Map<String, Object> e = new HashMap<>();
        e.put("stationId", p.get("stationId"));
        e.put("pumpId", p.get("id"));
        e.put("code", p.get("code"));
        e.put("state", state);
        e.put("currentVolume", volume);
        live.publish("pump", ((Number) p.get("stationId")).longValue(), e);
    }

    // ================================================================== opérations

    /** Vente à la pompe en espèces ou mobile money, par le pompiste en poste. */
    private void cashFuelSale() {
        Map<String, Object> st = randomStation("CONVENTIONNEL");
        if (st == null) {
            return;
        }
        long station = id(st);
        Map<String, Object> pump = randomPump(station, null);
        if (pump == null) {
            return;
        }
        Long employee = onDuty(station);
        boolean mm = rnd.nextDouble() < 0.3;
        sales.record(new SaleService.Sale(UUID.randomUUID(), station, null, "CARBURANT", "POMPE", id(pump),
                List.of(new SaleService.Line((String) pump.get("productCode"), liters(false), false)), mm ? "MOBILE_MONEY" : "ESPECES",
                mm ? momo() : null, null, null, false, employee, null, false, null));
    }

    /** Paiement par carte (flotte ou VIP), y compris dans les stations en mode autonome (PIN obligatoire). */
    private void cardFuelSale() {
        DemoCard card = CARDS.get(rnd.nextInt(CARDS.size()));
        LocalDateTime now = LocalDateTime.now();
        if (card.daytimeOnly() && (now.getHour() < 6 || now.getHour() >= 20 || now.getDayOfWeek().getValue() == 7)) {
            card = CARDS.get(3);
        }
        Map<String, Object> st = card.stations() == null ? randomStation(null) : db.one("""
                \sSELECT id, mode FROM station WHERE code = ANY(:c) AND code = ANY(:on) ORDER BY random() LIMIT 1""",
                Db.p("c", db.array("text", card.stations().toArray()), "on", codes("demo.online_codes")));
        if (st == null) {
            return;
        }
        long station = id(st);
        String fuel = card.fuels().get(rnd.nextInt(card.fuels().size()));
        Map<String, Object> pump = randomPump(station, fuel);
        if (pump == null) {
            return;
        }
        Long employee = "AUTONOME".equals(st.get("mode")) ? null : onDuty(station);
        sales.record(new SaleService.Sale(UUID.randomUUID(), station, null, "CARBURANT", "POMPE", id(pump),
                List.of(new SaleService.Line(fuel, liters(true), false)), "CARTE", null, card.number(), PIN, false, employee, null,
                false, null));
    }

    /** Boutique : lubrifiants, accessoires, bouteille neuve ou échange vide ⇄ pleine. */
    private void shopSale() {
        Map<String, Object> st = randomStation("CONVENTIONNEL");
        if (st == null) {
            return;
        }
        long station = id(st);
        Long employee = onDuty(station);
        boolean mm = rnd.nextDouble() < 0.25;
        if (rnd.nextDouble() < 0.35) {
            sales.record(new SaleService.Sale(UUID.randomUUID(), station, null, "ECHANGE_GAZ", "BOUTIQUE", null,
                    List.of(new SaleService.Line(rnd.nextBoolean() ? "GAZ_6" : "GAZ_12_5", BigDecimal.ONE, false)),
                    mm ? "MOBILE_MONEY" : "ESPECES", mm ? momo() : null, null, null, false, employee, null, false, null));
            return;
        }
        String[] items = {"HUILE_5W30_5L", "HUILE_2T_1L", "HUILE_2T_1L", "BRULEUR_GAZ", "GAZ_6", "GAZ_12_5"};
        String code = items[rnd.nextInt(items.length)];
        sales.record(new SaleService.Sale(UUID.randomUUID(), station, null, "BOUTIQUE", "BOUTIQUE", null,
                List.of(new SaleService.Line(code, BigDecimal.ONE, code.startsWith("GAZ"))), mm ? "MOBILE_MONEY" : "ESPECES",
                mm ? momo() : null, null, null, false, employee, null, false, null));
    }

    /** Distributeur de gaz en libre-service : la carte VIP de Komi Mensah sert une bouteille. */
    private void gasDispenser() {
        Map<String, Object> st = randomStation(null);
        if (st == null) {
            return;
        }
        sales.record(new SaleService.Sale(UUID.randomUUID(), id(st), null, "ECHANGE_GAZ", "DISTRIBUTEUR_GAZ", null,
                List.of(new SaleService.Line("GAZ_6", BigDecimal.ONE, false)), "CARTE", null, "601128474410", PIN, true, null, null,
                false, null));
    }

    /** Vente d'une carte neuve dans un point de vente ouvert, sur pièce d'identité. */
    private void pdvCardSale() {
        Map<String, Object> pdv = db.one("""
                \sSELECT p.id FROM pdv p WHERE p.status = 'OUVERT' AND p.code = ANY(:c)
                  AND EXISTS (SELECT 1 FROM card c WHERE c.pdv_id = p.id AND c.status = 'PDV')
                ORDER BY random() LIMIT 1""", Db.p("c", codes("demo.pdv_online_codes")));
        if (pdv == null) {
            return;
        }
        long pdvId = id(pdv);
        Map<String, Object> card = db.one("""
                \sSELECT number FROM card WHERE pdv_id = :p AND status = 'PDV' ORDER BY random() LIMIT 1""", Db.p("p", pdvId));
        Long agent = db.value("SELECT employee_id FROM pdv_agent WHERE pdv_id = :p ORDER BY random() LIMIT 1", Db.p("p", pdvId), Long.class);
        if (card == null || agent == null) {
            return;
        }
        String name = DemoSeeder.FIRST[rnd.nextInt(DemoSeeder.FIRST.length)] + " " + DemoSeeder.LAST[rnd.nextInt(DemoSeeder.LAST.length)];
        boolean mm = rnd.nextDouble() < 0.4;
        pdvs.sellCard(pdvId, new PdvService.CardSale(UUID.randomUUID(), (String) card.get("number"),
                new PdvService.Holder(name, "CNI", String.format("%04d-%03d-%04d", rnd.nextInt(10000), rnd.nextInt(1000), rnd.nextInt(10000)),
                        String.format("+228 9%d %02d %02d %02d", rnd.nextInt(9), rnd.nextInt(100), rnd.nextInt(100), rnd.nextInt(100)), null),
                mm ? "MOBILE_MONEY" : "ESPECES", mm ? momo() : null, null), user(agent), false);
    }

    /** Recharge au guichet ou à distance d'une carte de particulier (sous le palier). */
    private void smallRecharge() {
        Map<String, Object> card = db.one("""
                \sSELECT c.number FROM card c JOIN holder h ON h.id = c.holder_id
                WHERE c.status = 'ACTIVE' AND h.kind = 'PARTICULIER' ORDER BY random() LIMIT 1""", Map.of());
        Map<String, Object> st = randomStation(null);
        if (card == null || st == null) {
            return;
        }
        Long cashier = db.value("""
                \sSELECT id FROM employee WHERE station_id = :s AND active AND role_code IN ('GERANT', 'CHEF_POSTE')
                ORDER BY random() LIMIT 1""", Db.p("s", id(st)), Long.class);
        if (cashier == null) {
            return;
        }
        long[] amounts = {5_000, 10_000, 10_000, 20_000, 25_000, 50_000};
        boolean remote = rnd.nextDouble() < 0.3;
        recharges.recharge(new RechargeService.Request((String) card.get("number"), amounts[rnd.nextInt(amounts.length)],
                remote ? "DISTANCE" : "UNITAIRE", remote ? "MOBILE_MONEY" : "ESPECES", remote ? momo() : null,
                remote ? null : id(st), null), user(cashier));
    }

    /** Recharge d'une carte flotte au-dessus du palier : elle part en validation (Comptable puis DAF). */
    private void largeRecharge() {
        if (db.count("SELECT count(*) FROM validation_request WHERE status = 'EN_ATTENTE'", Map.of()) >= 6) {
            return;
        }
        String[] fleets = {"601128470093", "601144170028", "601133027782"};
        Long manager = db.value("SELECT manager_id FROM station WHERE code = 'ST-001'", Map.of(), Long.class);
        if (manager == null) {
            return;
        }
        long amount = (6 + rnd.nextInt(20)) * 100_000L;
        recharges.recharge(new RechargeService.Request(fleets[rnd.nextInt(fleets.length)], amount, rnd.nextBoolean() ? "DISTANCE" : "UNITAIRE",
                "VIREMENT", "VIR-" + (10_000 + rnd.nextInt(89_999)), null, null), user(manager));
    }

    /** La comptabilité active les cartes vendues (une autre personne que le vendeur). */
    private void activateCard() {
        if (db.count("SELECT count(*) FROM card WHERE status = 'A_ACTIVER'", Map.of()) < 10) {
            return;
        }
        Map<String, Object> card = db.one("""
                \sSELECT c.id, c.number, c.issued_by FROM card c WHERE c.status = 'A_ACTIVER' AND c.holder_id IS NOT NULL
                ORDER BY c.sold_at NULLS LAST, c.id LIMIT 1""", Map.of());
        Long accountant = db.value("""
                \sSELECT id FROM employee WHERE role_code = 'COMPTABLE' AND active AND id <> COALESCE(CAST(:i AS bigint), 0)
                ORDER BY random() LIMIT 1""", Db.p("i", card == null ? null : card.get("issuedBy")), Long.class);
        if (card == null || accountant == null) {
            return;
        }
        db.update("UPDATE card SET status = 'ACTIVE', activated_by = :u, activated_at = now() WHERE id = :id",
                Db.p("id", card.get("id"), "u", accountant));
        journal.logAs(accountant, "CARTE_ACTIVEE", Journal.VENTES_CARTES, "Carte activée",
                "···" + ((String) card.get("number")).substring(8), null, null, null);
    }

    /** Paiement refusé par les permissions de la carte (tracé dans le journal et la liste des refus). */
    private void refusedPayment() {
        Map<String, Object> st = randomStation(null);
        if (st == null) {
            return;
        }
        Map<String, Object> pump = randomPump(id(st), "SUPER");
        if (pump == null) {
            return;
        }
        try { // Agro-Plus n'a droit qu'au gasoil
            sales.record(new SaleService.Sale(UUID.randomUUID(), id(st), null, "CARBURANT", "POMPE", id(pump),
                    List.of(new SaleService.Line("SUPER", liters(true), false)), "CARTE", null, "601133027782", PIN, false, null, null,
                    false, null));
        } catch (RuntimeException expected) {
            // refus attendu, déjà enregistré par CardRefusals
        }
    }

    // ================================================================== entretien, chaque minute

    @Scheduled(cron = "30 * * * * *", zone = "${app.timezone}")
    public void upkeep() {
        if (!started) {
            return;
        }
        userCache.clear();
        safe("postes", this::rotateShifts);
        safe("cuves", this::refillTanks);
        safe("boutique", this::restockShops);
        safe("cartes flottes", this::topUpFleetCards);
        safe("cartes PDV", this::restockPdvCards);
        safe("livraisons", this::deliveryCycle);
    }

    /**
     * Relève des postes : l'équipe du poste en cours est en poste ; les postes terminés sont clôturés
     * par le gérant avec la remise des espèces (parfois un petit écart).
     */
    private void rotateShifts() {
        LocalTime now = LocalTime.now();
        for (Map<String, Object> st : db.list("SELECT id, manager_id FROM station WHERE active AND code = ANY(:c)",
                Db.p("c", codes("demo.online_codes")))) {
            long station = id(st);
            List<Map<String, Object>> configs = db.list("""
                    \sSELECT id, name, start_time, end_time FROM shift_config WHERE station_id = :s ORDER BY start_time""", Db.p("s", station));
            Map<String, Object> current = null;
            for (Map<String, Object> c : configs) {
                LocalTime from = (LocalTime) c.get("startTime");
                LocalTime to = (LocalTime) c.get("endTime");
                boolean in = from.isBefore(to) ? !now.isBefore(from) && now.isBefore(to) : !now.isBefore(from) || now.isBefore(to);
                if (in) {
                    current = c;
                }
            }
            if (current == null) {
                continue;
            }
            List<Long> chiefs = ids("SELECT id FROM employee WHERE station_id = :s AND active AND role_code = 'CHEF_POSTE' ORDER BY id", station);
            List<Long> attendants = ids("SELECT id FROM employee WHERE station_id = :s AND active AND role_code = 'POMPISTE' ORDER BY id", station);
            List<Long> team = new ArrayList<>();
            switch (String.valueOf(current.get("name"))) {
                case "Matin" -> {
                    addIf(team, chiefs, 0);
                    addIf(team, attendants, 0);
                }
                case "Soir" -> {
                    addIf(team, chiefs, 1);
                    addIf(team, attendants, 1);
                }
                default -> addIf(team, attendants, 2);
            }
            long currentConfig = id(current);
            Long manager = st.get("managerId") == null ? null : ((Number) st.get("managerId")).longValue();
            // clôture des postes d'un autre poste type ou commencés depuis plus de 9 heures
            for (Map<String, Object> open : db.list("""
                    \sSELECT id, employee_id FROM employee_shift
                    WHERE station_id = :s AND status <> 'CLOS'
                      AND (config_id IS DISTINCT FROM :c OR started_at < now() - interval '9 hours')""",
                    Db.p("s", station, "c", currentConfig))) {
                if (manager == null) {
                    continue;
                }
                long shiftId = id(open);
                long remaining = db.sum("""
                        \sSELECT COALESCE(sum(x.total), 0) - COALESCE((SELECT sum(amount) FROM shift_remittance WHERE shift_id = :id), 0)
                        FROM sale x WHERE x.employee_shift_id = :id AND x.payment_method = 'ESPECES'""", Db.p("id", shiftId));
                long gap = rnd.nextDouble() < 0.1 ? Math.min(Math.max(remaining, 0), (1 + rnd.nextInt(10)) * 500L) : 0;
                shifts.close(shiftId, Math.max(remaining - gap, 0), gap > 0 ? "Écart constaté à la relève" : null, manager);
            }
            for (long emp : team) {
                if (shifts.currentShiftId(emp) == null) {
                    boolean chief = chiefs.contains(emp);
                    shifts.start(emp, station, currentConfig, chief ? null : "P1, P2, P3, P4, P5, P6, P7, P8");
                }
            }
        }
    }

    /** Livraison du dépôt quand une cuve descend sous 10 % (Bè Kpota garde ainsi sa cuve de gasoil basse un moment). */
    private void refillTanks() {
        for (Map<String, Object> t : db.list("""
                \sSELECT t.id, t.capacity, t.volume, t.station_id, t.name, s.short_name FROM tank t JOIN station s ON s.id = t.station_id
                WHERE t.volume < t.capacity * 0.10 AND s.code = ANY(:c)""", Db.p("c", codes("demo.online_codes")))) {
            BigDecimal capacity = (BigDecimal) t.get("capacity");
            BigDecimal q = capacity.multiply(BigDecimal.valueOf(0.6)).setScale(0, RoundingMode.HALF_UP);
            String ref = "BL-" + (40_000 + rnd.nextInt(9_999));
            stock.moveTank(id(t), q, "ENTREE", ref, null, "Livraison du dépôt");
            journal.logAs(null, "LIVRAISON_CARBURANT", Journal.LIVRAISONS, "Livraison de carburant reçue",
                    t.get("shortName") + " · " + t.get("name") + " · " + q.toPlainString() + " L · " + ref, null, id(t.get("stationId")), null);
        }
    }

    /** Réassort de la boutique quand un article est presque épuisé. */
    private void restockShops() {
        for (Map<String, Object> l : db.list("""
                \sSELECT l.station_id, l.product_id FROM stock_level l JOIN station s ON s.id = l.station_id
                WHERE l.quantity < 4 AND s.code = ANY(:c)""", Db.p("c", codes("demo.online_codes")))) {
            stock.move(id(l.get("stationId")), null, id(l.get("productId")), BigDecimal.valueOf(30), "ENTREE",
                    "BL-" + (40_000 + rnd.nextInt(9_999)), null, "Réassort");
        }
    }

    /** Les entreprises rechargent leurs cartes flottes avant qu'elles soient vides (sous le palier). */
    private void topUpFleetCards() {
        Long manager = db.value("SELECT manager_id FROM station WHERE code = 'ST-001'", Map.of(), Long.class);
        if (manager == null) {
            return;
        }
        for (Map<String, Object> c : db.list("""
                \sSELECT number, balance FROM card WHERE status = 'ACTIVE' AND number = ANY(:n) AND balance < 250000""",
                Db.p("n", db.array("text", CARDS.stream().map(DemoCard::number).toArray())))) {
            recharges.recharge(new RechargeService.Request((String) c.get("number"), 450_000, "DISTANCE", "VIREMENT",
                    "VIR-" + (10_000 + rnd.nextInt(89_999)), null, null), user(manager));
        }
    }

    /** Les points de vente reçoivent de nouvelles cartes vierges quand leur stock s'épuise. */
    private void restockPdvCards() {
        for (Map<String, Object> p : db.list("""
                \sSELECT p.id FROM pdv p WHERE p.status = 'OUVERT'
                  AND (SELECT count(*) FROM card c WHERE c.pdv_id = p.id AND c.status = 'PDV') < 15""")) {
            List<Map<String, Object>> cards = db.list("""
                    \sSELECT id FROM card WHERE status = 'STOCK' AND type_code = 'CLASSIQUE' ORDER BY number LIMIT 40""");
            if (cards.isEmpty()) {
                continue;
            }
            Object[] ids = cards.stream().map(c -> c.get("id")).toArray();
            db.update("UPDATE card SET status = 'PDV', pdv_id = :p WHERE id = ANY(:ids)", Db.p("p", p.get("id"), "ids", db.array("bigint", ids)));
            db.update("INSERT INTO pdv_card (pdv_id, card_id) SELECT :p, unnest(CAST(:ids AS bigint[])) ON CONFLICT DO NOTHING",
                    Db.p("p", p.get("id"), "ids", db.array("bigint", ids)));
        }
    }

    /**
     * Tournée Zen Mobility : une livraison arrivée à destination est livrée, puis une nouvelle
     * commande est créée, validée, affectée et part de la station d'Agoè.
     */
    private void deliveryCycle() {
        String[] route = settings.get("demo.route", "").split(";");
        if (route.length == 3) {
            long delivery = Long.parseLong(route[0]);
            Map<String, Object> d = db.one("SELECT status, agent_id FROM delivery_order WHERE id = :d", Db.p("d", delivery));
            if (d != null && "EN_ROUTE".equals(d.get("status"))) {
                if (distanceToDestination(delivery, Double.parseDouble(route[1]), Double.parseDouble(route[2])) > 0.002) {
                    return; // toujours en route
                }
                deliveries.deliver(delivery, null, id(d.get("agentId")));
            }
        }
        Long admin = db.value("SELECT id FROM employee WHERE role_code = 'ADMIN' AND active ORDER BY id LIMIT 1", Map.of(), Long.class);
        Long agent = db.value("""
                \sSELECT e.id FROM employee e WHERE e.role_code = 'LIVREUR' AND e.active
                  AND NOT EXISTS (SELECT 1 FROM delivery_order d WHERE d.agent_id = e.id AND d.status = 'EN_ROUTE')
                ORDER BY random() LIMIT 1""", Map.of(), Long.class);
        Map<String, Object> source = db.one("SELECT id, latitude, longitude FROM station WHERE code = 'ST-001'", Map.of());
        if (admin == null || agent == null || source == null) {
            return;
        }
        Object[] dest = DESTINATIONS[rnd.nextInt(DESTINATIONS.length)];
        Long holder = db.value("SELECT id FROM holder WHERE name = :n ORDER BY id LIMIT 1", Db.p("n", dest[0]), Long.class);
        if (holder == null) {
            return;
        }
        long order = deliveries.create(new DeliveryService.NewOrder(holder, null, rnd.nextBoolean() ? "GASOIL" : "SUPER",
                BigDecimal.valueOf(1000 + rnd.nextInt(5) * 500L), (String) dest[1], BigDecimal.valueOf((Double) dest[2]),
                BigDecimal.valueOf((Double) dest[3]), LocalDateTime.now().plusHours(2), id(source), "VIREMENT", null), admin);
        deliveries.validate(order, admin);
        deliveries.assign(order, agent, "Citerne TG " + (1000 + rnd.nextInt(8999)) + " AB", id(source), admin);
        deliveries.depart(order, agent);
        tracking.record("LIVREUR", agent, order, (BigDecimal) source.get("latitude"), (BigDecimal) source.get("longitude"),
                BigDecimal.valueOf(8), BigDecimal.ZERO, BigDecimal.valueOf(150), null, null);
        settings.set("demo.route", order + ";" + dest[2] + ";" + dest[3]);
    }

    private double distanceToDestination(long delivery, double toLat, double toLon) {
        Map<String, Object> last = db.one("""
                \sSELECT latitude, longitude FROM location_ping WHERE delivery_id = :d ORDER BY at DESC LIMIT 1""", Db.p("d", delivery));
        if (last == null) {
            return 1;
        }
        return Math.hypot(toLat - ((BigDecimal) last.get("latitude")).doubleValue(), toLon - ((BigDecimal) last.get("longitude")).doubleValue());
    }

    /** Le livreur en route avance vers la destination de sa livraison (environ 40 km/h). */
    private void moveDriver() {
        String[] route = settings.get("demo.route", "").split(";");
        if (route.length != 3) {
            return;
        }
        long delivery = Long.parseLong(route[0]);
        Map<String, Object> d = db.one("""
                \sSELECT d.agent_id, e.first_name || ' ' || e.last_name AS name FROM delivery_order d JOIN employee e ON e.id = d.agent_id
                WHERE d.id = :d AND d.status = 'EN_ROUTE'""", Db.p("d", delivery));
        if (d == null) {
            return;
        }
        long agent = id(d.get("agentId"));
        Map<String, Object> last = db.one("""
                \sSELECT latitude, longitude FROM location_ping WHERE subject = 'LIVREUR' AND subject_id = :a ORDER BY at DESC LIMIT 1""",
                Db.p("a", agent));
        if (last == null) {
            return;
        }
        double lat = ((BigDecimal) last.get("latitude")).doubleValue();
        double lon = ((BigDecimal) last.get("longitude")).doubleValue();
        double toLat = Double.parseDouble(route[1]);
        double toLon = Double.parseDouble(route[2]);
        double dist = Math.hypot(toLat - lat, toLon - lon);
        double step = dist < 0.0015 ? 0 : Math.min(1, 0.0006 / dist);
        tracking.record("LIVREUR", agent, delivery,
                BigDecimal.valueOf(lat + (toLat - lat) * step).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(lon + (toLon - lon) * step).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(6 + rnd.nextInt(10)), BigDecimal.valueOf(step == 0 ? 0 : 30 + rnd.nextInt(20)),
                BigDecimal.valueOf(140 + rnd.nextInt(20)), null, (String) d.get("name"));
    }

    // ================================================================== utilitaires

    private Map<String, Object> randomStation(String mode) {
        return db.one("""
                \sSELECT id, mode FROM station WHERE active AND code = ANY(:c) AND (CAST(:m AS text) IS NULL OR mode = :m)
                ORDER BY random() LIMIT 1""", Db.p("c", codes("demo.online_codes"), "m", mode));
    }

    private Map<String, Object> randomPump(long station, String fuel) {
        return db.one("""
                \sSELECT pu.id, p.code AS product_code FROM pump pu JOIN product p ON p.id = pu.product_id
                WHERE pu.station_id = :s AND pu.state <> 'HORS_SERVICE' AND (CAST(:f AS text) IS NULL OR p.code = :f)
                ORDER BY random() LIMIT 1""", Db.p("s", station, "f", fuel));
    }

    /** Pompiste (ou chef de poste) en poste dans la station. */
    private Long onDuty(long station) {
        return db.value("""
                \sSELECT es.employee_id FROM employee_shift es JOIN employee e ON e.id = es.employee_id
                WHERE es.station_id = :s AND es.status = 'EN_POSTE'
                ORDER BY (e.role_code = 'POMPISTE') DESC, random() LIMIT 1""", Db.p("s", station), Long.class);
    }

    private BigDecimal liters(boolean fleet) {
        int[] small = {3, 5, 5, 8, 10, 10, 15, 15, 20, 25, 30, 40, 50};
        int base = fleet ? 30 + rnd.nextInt(8) * 10 : small[rnd.nextInt(small.length)];
        return BigDecimal.valueOf(base + rnd.nextInt(10) / 10.0).setScale(2, RoundingMode.HALF_UP);
    }

    private String momo() {
        return (rnd.nextBoolean() ? "TMoney " : "Flooz ") + (100_000_000 + rnd.nextInt(899_999_999));
    }

    private java.sql.Array codes(String key) {
        return db.array("text", settings.get(key, "").split(","));
    }

    private CurrentUser user(long id) {
        return userCache.computeIfAbsent(id, users::load);
    }

    private List<Long> ids(String sql, long station) {
        return db.list(sql, Db.p("s", station)).stream().map(r -> id(r)).toList();
    }

    private static void addIf(List<Long> team, List<Long> list, int i) {
        if (i < list.size()) {
            team.add(list.get(i));
        }
    }

    private static long id(Object o) {
        if (o instanceof Map<?, ?> m) {
            return ((Number) m.get("id")).longValue();
        }
        return ((Number) o).longValue();
    }

    private void safe(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.debug("Simulation ({}) : {}", what, e.getMessage());
        }
    }
}
