package tg.zener.station.demo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tg.zener.station.cards.CardIssuer;
import tg.zener.station.common.Db;
import tg.zener.station.common.Refs;
import tg.zener.station.fleets.FleetService;
import tg.zener.station.security.UserLoader;

/**
 * Démonstration des flottes : chaque carte flotte d'entreprise reçoit un véhicule et un chauffeur
 * (affectations datées, quelques changements de chauffeur), les ventes passées sont rattachées au véhicule
 * avec un kilométrage cohérent, et chaque flotte a un compte rechargé puis réparti sur ses cartes.
 * Idempotent : ne fait rien si un mouvement de compte flotte existe déjà.
 */
@Component
public class DemoFleets {

    private static final Logger log = LoggerFactory.getLogger(DemoFleets.class);

    record Model(String type, String brand, String model, String fuel, int tank) {}

    private static final Map<String, List<Model>> MODELS = Map.of(
            "CAMION", List.of(new Model("CAMION", "Renault", "Kerax", "GASOIL", 400), new Model("CAMION", "Mercedes-Benz", "Actros", "GASOIL", 600),
                    new Model("CAMION", "MAN", "TGS", "GASOIL", 500), new Model("CAMION", "Volvo", "FMX", "GASOIL", 450)),
            "ENGIN", List.of(new Model("ENGIN", "Caterpillar", "320D", "GASOIL", 410), new Model("ENGIN", "Komatsu", "WA320", "GASOIL", 300),
                    new Model("CAMION", "Sinotruk", "Howo benne", "GASOIL", 400)),
            "CAMIONNETTE", List.of(new Model("CAMIONNETTE", "Toyota", "Hilux", "GASOIL", 80), new Model("CAMIONNETTE", "Isuzu", "NPR", "GASOIL", 100),
                    new Model("CAMIONNETTE", "Mitsubishi", "L200", "GASOIL", 75), new Model("BUS", "Toyota", "Hiace", "GASOIL", 70)),
            "VOITURE", List.of(new Model("VOITURE", "Toyota", "Corolla", "SUPER", 50), new Model("VOITURE", "Hyundai", "Accent", "SUPER", 45),
                    new Model("CAMIONNETTE", "Toyota", "Land Cruiser", "GASOIL", 90), new Model("MOTO", "Haojue", "HJ125", "SUPER", 12)));
    private static final String[] COLORS = {"Blanc", "Blanc", "Gris", "Bleu", "Rouge", "Noir", "Jaune"};

    private final Db db;
    private final FleetService fleets;
    private final Refs refs;
    private final UserLoader users;
    private final TransactionTemplate tx;
    private final CardIssuer issuer;

    public DemoFleets(Db db, FleetService fleets, Refs refs, UserLoader users, CardIssuer issuer, PlatformTransactionManager tm) {
        this.issuer = issuer;
        this.db = db;
        this.fleets = fleets;
        this.refs = refs;
        this.users = users;
        this.tx = new TransactionTemplate(tm);
    }

    public void ensure() {
        if (db.count("SELECT count(*) FROM fleet_movement", Map.of()) > 0) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> create());
        } catch (Exception e) {
            log.warn("Données de démonstration (flottes) non créées : {}", e.getMessage());
        }
    }

    private static String category(String holder, String group) {
        if (group != null && group.toLowerCase().contains("direction")) return "VOITURE";
        String h = holder.toLowerCase();
        if (h.contains("translom")) return "CAMION";
        if (h.contains("btp")) return "ENGIN";
        if (h.contains("logistique") || h.contains("agro")) return "CAMIONNETTE";
        return "VOITURE";
    }

    private void create() {
        long t0 = System.currentTimeMillis();
        Random r = new Random(42);
        Long admin = db.value("SELECT id FROM employee WHERE role_code = 'ADMIN' AND active ORDER BY id LIMIT 1", Map.of(), Long.class);

        // ---------------------------------------------------------- toutes les cartes flotte dans une flotte
        for (Map<String, Object> h : db.list("""
                \sSELECT DISTINCT h.id, h.name FROM holder h JOIN card c ON c.holder_id = h.id
                WHERE h.kind = 'ENTREPRISE' AND c.group_id IS NULL AND c.status <> 'STOCK'""")) {
            Long g = db.value("SELECT id FROM card_group WHERE holder_id = :h ORDER BY id LIMIT 1", Db.p("h", h.get("id")), Long.class);
            if (g == null) {
                g = db.insert("INSERT INTO card_group (holder_id, name) VALUES (:h, 'Flotte principale')", Db.p("h", h.get("id")));
            }
            db.update("UPDATE card SET group_id = :g WHERE holder_id = :h AND group_id IS NULL", Db.p("g", g, "h", h.get("id")));
        }
        db.update("""
                \sUPDATE card_group g SET code = upper(left(regexp_replace(h.name, '[^A-Za-z]', '', 'g'), 3)) || '-' || g.id,
                       contact_name = COALESCE(g.contact_name, 'Responsable parc ' || split_part(h.name, ' ', 1)),
                       phone = COALESCE(g.phone, h.phone), address = COALESCE(g.address, h.address)
                FROM holder h WHERE h.id = g.holder_id AND h.kind = 'ENTREPRISE'""", Map.of());
        List<Long> fresh = extraCards(admin);

        // ---------------------------------------------------------- un véhicule et un chauffeur par carte
        int vehicles = 0;
        int drivers = 0;
        List<Long> changed = new ArrayList<>();
        for (Map<String, Object> c : db.list("""
                \sSELECT c.id, c.holder_id, c.group_id, c.vehicle_id, c.driver_id, c.created_at, h.name AS holder_name, g.name AS group_name
                FROM card c JOIN holder h ON h.id = c.holder_id LEFT JOIN card_group g ON g.id = c.group_id
                WHERE h.kind = 'ENTREPRISE' AND c.type_code = 'FLOTTE' AND c.status NOT IN ('STOCK', 'PDV') ORDER BY c.id""")) {
            long cardId = num(c.get("id"));
            Long vehicleId = c.get("vehicleId") == null ? null : num(c.get("vehicleId"));
            if (vehicleId == null) {
                List<Model> models = MODELS.get(category((String) c.get("holderName"), (String) c.get("groupName")));
                Model m = models.get(r.nextInt(models.size()));
                String plate;
                do {
                    plate = "TG " + (1000 + r.nextInt(8999)) + " " + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26));
                } while (db.count("SELECT count(*) FROM vehicle WHERE plate = :p", Db.p("p", plate)) > 0);
                vehicleId = db.insert("""
                        \sINSERT INTO vehicle (holder_id, group_id, plate, type_code, brand, model, color, product_id, tank_capacity, created_at)
                        VALUES (:h, :g, :p, :t, :b, :m, :c, (SELECT id FROM product WHERE code = :f), :tk, :at)""",
                        Db.p("h", c.get("holderId"), "g", c.get("groupId"), "p", plate, "t", m.type(), "b", m.brand(), "m", m.model(),
                                "c", COLORS[r.nextInt(COLORS.length)], "f", m.fuel(), "tk", m.tank(), "at", ts(c.get("createdAt"))));
                vehicles++;
            } else {
                db.update("UPDATE vehicle SET group_id = COALESCE(group_id, :g), product_id = COALESCE(product_id, (SELECT id FROM product WHERE code = 'GASOIL')), tank_capacity = COALESCE(tank_capacity, 400) WHERE id = :v",
                        Db.p("v", vehicleId, "g", c.get("groupId")));
            }
            Long driverId = c.get("driverId") == null ? null : num(c.get("driverId"));
            if (driverId == null) {
                driverId = newDriver(r, c.get("holderId"), c.get("groupId"));
                drivers++;
            }
            int days = 90 + r.nextInt(120);
            boolean changedDriver = r.nextInt(5) == 0;
            // historique d'affectation reconstruit pour la démonstration
            db.update("DELETE FROM vehicle_assignment WHERE vehicle_id = :v OR driver_id = :d", Db.p("v", vehicleId, "d", driverId));
            if (changedDriver) {
                long previous = newDriver(r, c.get("holderId"), c.get("groupId"));
                drivers++;
                int switchDays = 15 + r.nextInt(30);
                db.update("""
                        \sINSERT INTO vehicle_assignment (vehicle_id, driver_id, started_at, ended_at, assigned_by, note)
                        VALUES (:v, :d, now() - make_interval(days => :s), now() - make_interval(days => :e), :a, 'Mutation sur un autre site')""",
                        Db.p("v", vehicleId, "d", previous, "s", days, "e", switchDays, "a", admin));
                days = switchDays;
                changed.add(vehicleId);
            }
            db.update("""
                    \sINSERT INTO vehicle_assignment (vehicle_id, driver_id, started_at, assigned_by)
                    VALUES (:v, :d, now() - make_interval(days => :s), :a) ON CONFLICT DO NOTHING""",
                    Db.p("v", vehicleId, "d", driverId, "s", days, "a", admin));
            db.update("""
                    \sUPDATE card c SET vehicle_id = v.id, driver_id = d.id, vehicle_plate = v.plate,
                           vehicle_label = NULLIF(concat_ws(' ', v.brand, v.model), ''), driver_name = d.name
                    FROM vehicle v, driver d WHERE c.id = :c AND v.id = :v AND d.id = :d""", Db.p("c", cardId, "v", vehicleId, "d", driverId));
            db.update("""
                    \sINSERT INTO card_event (card_id, at, kind, employee_id, detail)
                    SELECT :c, now() - make_interval(days => :s), 'AFFECTATION', :a, v.plate || ' · ' || d.name
                    FROM vehicle v, driver d WHERE v.id = :v AND d.id = :d""",
                    Db.p("c", cardId, "s", days, "a", admin, "v", vehicleId, "d", driverId));
        }

        // ---------------------------------------------------------- comptes flotte : 8 semaines de recharges, dotations et pleins
        int[] counts = ledger(r, fresh, admin);

        // ---------------------------------------------------------- ventes passées : véhicule, chauffeur, kilométrage
        db.update("""
                \sUPDATE sale x SET vehicle_id = c.vehicle_id, driver_id = c.driver_id
                FROM card c WHERE c.id = x.card_id AND c.vehicle_id IS NOT NULL AND x.vehicle_id IS NULL""", Map.of());
        db.update("""
                \sUPDATE sale x SET driver_id = a.driver_id FROM vehicle_assignment a
                WHERE a.vehicle_id = x.vehicle_id AND a.ended_at IS NOT NULL AND x.at >= a.started_at AND x.at < a.ended_at""", Map.of());
        // kilométrage : point de départ propre au véhicule + litres déjà consommés / consommation du type (85 % des pleins saisis)
        db.update("""
                \sWITH f AS (
                    SELECT x.id, x.vehicle_id,
                           sum(l.quantity) OVER (PARTITION BY x.vehicle_id ORDER BY x.at, x.id) - l.quantity AS before_liters
                    FROM sale x JOIN sale_line l ON l.sale_id = x.id JOIN product p ON p.id = l.product_id
                    WHERE x.vehicle_id IS NOT NULL AND p.category = 'CARBURANT')
                UPDATE sale s SET odometer_km = CAST(40000 + (v.id * 7919) % 180000
                       + f.before_liters * 100 / CASE v.type_code WHEN 'CAMION' THEN 34 WHEN 'ENGIN' THEN 48 WHEN 'BUS' THEN 14
                                                                  WHEN 'CAMIONNETTE' THEN 12 WHEN 'MOTO' THEN 3 ELSE 8 END
                       * (0.97 + random() * 0.06) AS int)
                FROM f JOIN vehicle v ON v.id = f.vehicle_id
                WHERE s.id = f.id AND random() < 0.85""", Map.of());
        db.update("""
                \sUPDATE vehicle v SET odometer_km = m.km
                FROM (SELECT vehicle_id, max(odometer_km) AS km FROM sale WHERE vehicle_id IS NOT NULL GROUP BY vehicle_id) m
                WHERE m.vehicle_id = v.id""", Map.of());

        // ---------------------------------------------------------- dotation du mois par le service (toutes les cartes actives)
        for (Map<String, Object> g : db.list("""
                \sSELECT g.id, g.balance FROM card_group g JOIN holder h ON h.id = g.holder_id
                WHERE h.kind = 'ENTREPRISE' AND EXISTS (SELECT 1 FROM card c WHERE c.group_id = g.id AND c.status = 'ACTIVE')
                ORDER BY g.id""")) {
            long group = num(g.get("id"));
            List<FleetService.Line> lines = new ArrayList<>();
            for (Map<String, Object> c : db.list("SELECT id FROM card WHERE group_id = :g AND status = 'ACTIVE' ORDER BY id", Db.p("g", group))) {
                lines.add(new FleetService.Line(num(c.get("id")), (5 + r.nextInt(16)) * 10_000L));
            }
            long need = lines.stream().mapToLong(FleetService.Line::amount).sum();
            if (num(g.get("balance")) < need) {
                rechargeAt(group, roundUp(need - num(g.get("balance")) + 1_500_000, 100_000), Instant.now().minusSeconds(3600), admin);
                counts[0]++;
            }
            if (!lines.isEmpty() && admin != null) {
                fleets.allocate(group, lines, "Dotation mensuelle", admin);
            }
        }
        log.info("Données de démonstration (flottes) : {} cartes ajoutées, {} véhicules, {} chauffeurs, {} changements de chauffeur, "
                + "{} recharges de compte, {} dotations, {} pleins en {} s", fresh.size(), vehicles, drivers, changed.size(), counts[0], counts[1],
                counts[2], (System.currentTimeMillis() - t0) / 1000);
    }

    /** Taille des flottes de démonstration : entreprise, flotte, nombre de cartes, stations fréquentées. */
    record Target(String holder, String group, int cards, List<String> stations) {}

    private static final List<Target> TARGETS = List.of(
            new Target("Translom SARL", "Camions Nord", 6, List.of("ST-004", "ST-009", "ST-010", "ST-011")),
            new Target("Translom SARL", "Camions Sud", 5, List.of("ST-001", "ST-003", "ST-005", "ST-007")),
            new Target("Lomé Logistique", "Navettes", 5, List.of("ST-001", "ST-002", "ST-003")),
            new Target("Lomé Logistique", "Direction", 2, List.of("ST-001", "ST-002")),
            new Target("BTP Kara Construction", "Engins", 5, List.of("ST-010", "ST-009")),
            new Target("Agro-Plus Togo", "Livraisons", 4, List.of("ST-004", "ST-007", "ST-006")));

    /** Cartes flotte supplémentaires (émises, puis activées par la comptabilité il y a deux mois). */
    private List<Long> extraCards(Long admin) {
        List<Long> fresh = new ArrayList<>();
        Long accountant = db.value("SELECT id FROM employee WHERE role_code = 'COMPTABLE' AND active ORDER BY id LIMIT 1", Map.of(), Long.class);
        for (Target t : TARGETS) {
            Map<String, Object> g = db.one("""
                    \sSELECT g.id, g.holder_id, (SELECT count(*) FROM card c WHERE c.group_id = g.id AND c.type_code = 'FLOTTE') AS n
                    FROM card_group g JOIN holder h ON h.id = g.holder_id WHERE h.name = :h AND g.name = :g""",
                    Db.p("h", t.holder(), "g", t.group()));
            if (g == null || admin == null) {
                continue;
            }
            for (long i = num(g.get("n")); i < t.cards(); i++) {
                long id = issuer.issue(null, "FLOTTE", "PUCE", num(g.get("holderId")), num(g.get("id")), admin, "1234");
                db.update("""
                        \sUPDATE card SET status = 'ACTIVE', activated_by = :a, activated_at = now() - interval '62 days',
                               created_at = now() - interval '63 days' WHERE id = :id""", Db.p("id", id, "a", accountant));
                db.update("""
                        \sINSERT INTO card_event (card_id, at, kind, employee_id, detail) VALUES
                            (:id, now() - interval '63 days', 'EMISSION', :u, 'Flotte · ' || :g),
                            (:id, now() - interval '62 days', 'ACTIVATION', :a, NULL)""",
                        Db.p("id", id, "u", admin, "a", accountant, "g", t.group()));
                fresh.add(id);
            }
        }
        return fresh;
    }

    /** Litres d'un plein selon le type de véhicule. */
    private static double fill(Random r, String type) {
        return switch (type) {
            case "CAMION" -> 150 + r.nextInt(220);
            case "ENGIN" -> 120 + r.nextInt(180);
            case "BUS" -> 40 + r.nextInt(30);
            case "CAMIONNETTE" -> 35 + r.nextInt(45);
            case "MOTO" -> 4 + r.nextInt(7);
            default -> 22 + r.nextInt(23);
        } + r.nextInt(10) / 10.0;
    }

    /**
     * Grand livre des 8 dernières semaines pour les nouvelles cartes : chaque lundi, dotation de la carte par le compte
     * flotte (recharge du compte par virement quand il ne suffit plus), puis 2 à 4 pleins dans les stations de la flotte.
     * Les soldes (compte, carte) sont tenus dans l'ordre chronologique. Renvoie {recharges, dotations, pleins}.
     */
    private int[] ledger(Random r, List<Long> fresh, Long admin) {
        int[] counts = new int[3];
        if (fresh.isEmpty() || admin == null) {
            return counts;
        }
        List<Map<String, Object>> cards = db.list("""
                \sSELECT c.id, c.group_id, c.vehicle_id, c.driver_id, c.holder_id, h.profile, v.type_code, v.product_id, p.code AS product_code,
                       p.name AS product_name, g.name AS group_name, h.name AS holder_name
                FROM card c JOIN holder h ON h.id = c.holder_id JOIN card_group g ON g.id = c.group_id
                JOIN vehicle v ON v.id = c.vehicle_id JOIN product p ON p.id = v.product_id
                WHERE c.id = ANY(:ids) ORDER BY c.group_id, c.id""", Db.p("ids", db.array("bigint", fresh.toArray())));
        Map<Long, Long> account = new java.util.HashMap<>();
        Map<Long, Long> cardBalance = new java.util.HashMap<>();
        Instant now = Instant.now();
        for (int week = 8; week >= 1; week--) {
            Instant monday = now.minus(java.time.Duration.ofDays(7L * week)).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                    .plus(java.time.Duration.ofHours(7));
            for (Map<String, Object> c : cards) {
                long cardId = num(c.get("id"));
                long group = num(c.get("groupId"));
                String type = (String) c.get("typeCode");
                Target t = TARGETS.stream().filter(x -> x.group().equals(c.get("groupName")) && x.holder().equals(c.get("holderName")))
                        .findFirst().orElse(TARGETS.get(0));
                long price = db.value("SELECT price FROM product_price WHERE product_id = :p AND profile = :pr",
                        Db.p("p", c.get("productId"), "pr", c.get("profile")), Long.class);
                int n = 2 + r.nextInt(3);
                double[] liters = new double[n];
                long cost = 0;
                for (int i = 0; i < n; i++) {
                    liters[i] = fill(r, type);
                    cost += Math.round(liters[i] * price);
                }
                // dotation de la semaine (un peu plus que la consommation prévue)
                long dotation = roundUp((long) (cost * 1.12), 10_000);
                long acc = account.getOrDefault(group, db.value("SELECT balance FROM card_group WHERE id = :g", Db.p("g", group), Long.class));
                Instant at = monday.plusSeconds(60L * cards.indexOf(c));
                if (acc < dotation) {
                    long amount = roundUp(Math.max(dotation * 6, 1_500_000), 100_000);
                    rechargeAt(group, amount, at.minusSeconds(3600), admin);
                    acc += amount;
                    counts[0]++;
                }
                long before = cardBalance.getOrDefault(cardId, 0L);
                acc -= dotation;
                long fm = db.insert("""
                        \sINSERT INTO fleet_movement (group_id, at, kind, amount, balance_after, card_id, card_balance_before, card_balance_after,
                                                    employee_id, note)
                        VALUES (:g, :at, 'DOTATION', :a, :b, :c, :cb, :ca, :e, 'Dotation hebdomadaire')""",
                        Db.p("g", group, "at", java.sql.Timestamp.from(at), "a", -dotation, "b", acc, "c", cardId, "cb", before,
                                "ca", before + dotation, "e", admin));
                db.update("""
                        \sINSERT INTO card_movement (card_id, at, kind, amount, balance_after, detail, employee_id, fleet_movement_id)
                        VALUES (:c, :at, 'DOTATION', :a, :b, :d, :e, :fm)""",
                        Db.p("c", cardId, "at", java.sql.Timestamp.from(at), "a", dotation, "b", before + dotation,
                                "d", "Dotation · " + c.get("groupName"), "e", admin, "fm", fm));
                counts[1]++;
                long balance = before + dotation;
                account.put(group, acc);
                // pleins de la semaine, dans l'ordre
                long[] offsets = new long[n];
                for (int i = 0; i < n; i++) {
                    offsets[i] = 3600L * (2 + r.nextInt(6 * 24 - 4));
                }
                java.util.Arrays.sort(offsets);
                for (int i = 0; i < n; i++) {
                    Instant when = at.plusSeconds(offsets[i]);
                    if (when.isAfter(now.minusSeconds(600))) {
                        continue;
                    }
                    String station = t.stations().get(r.nextInt(t.stations().size()));
                    Map<String, Object> pump = db.one("""
                            \sSELECT pu.id, pu.station_id FROM pump pu JOIN station s ON s.id = pu.station_id
                            WHERE s.code = :s AND pu.product_id = :p ORDER BY random() LIMIT 1""", Db.p("s", station, "p", c.get("productId")));
                    if (pump == null) {
                        continue;
                    }
                    java.math.BigDecimal q = java.math.BigDecimal.valueOf(liters[i]).setScale(2, java.math.RoundingMode.HALF_UP);
                    long amount = Math.round(liters[i] * price);
                    if (amount > balance) {
                        continue;
                    }
                    balance -= amount;
                    Map<String, Object> shift = db.one("""
                            \sSELECT es.id, es.employee_id FROM employee_shift es
                            WHERE es.station_id = :s AND es.started_at <= :at AND (es.ended_at IS NULL OR es.ended_at > :at)
                            ORDER BY es.started_at DESC LIMIT 1""", Db.p("s", pump.get("stationId"), "at", java.sql.Timestamp.from(when)));
                    long saleId = db.insert("""
                            \sINSERT INTO sale (uuid, at, kind, channel, station_id, pump_id, mode, employee_id, employee_shift_id, payment_method,
                                              card_id, buyer_profile, total, vehicle_id, driver_id)
                            VALUES (gen_random_uuid(), :at, 'CARBURANT', 'POMPE', :s, :pu, 'CONVENTIONNEL', :e, :sh, 'CARTE', :c, :pr, :t, :v, :d)""",
                            Db.p("at", java.sql.Timestamp.from(when), "s", pump.get("stationId"), "pu", pump.get("id"),
                                    "e", shift == null ? null : shift.get("employeeId"), "sh", shift == null ? null : shift.get("id"), "c", cardId,
                                    "pr", c.get("profile"), "t", amount, "v", c.get("vehicleId"), "d", c.get("driverId")));
                    db.update("""
                            \sINSERT INTO sale_line (sale_id, product_id, quantity, unit_price, amount) VALUES (:x, :p, :q, :u, :a)""",
                            Db.p("x", saleId, "p", c.get("productId"), "q", q, "u", price, "a", amount));
                    db.update("""
                            \sINSERT INTO card_movement (card_id, at, kind, amount, balance_after, station_id, sale_id, detail, employee_id)
                            VALUES (:c, :at, 'VENTE', :a, :b, :s, :x, :d, :e)""",
                            Db.p("c", cardId, "at", java.sql.Timestamp.from(when), "a", -amount, "b", balance, "s", pump.get("stationId"),
                                    "x", saleId, "d", q.stripTrailingZeros().toPlainString() + " L " + c.get("productName"),
                                    "e", shift == null ? null : shift.get("employeeId")));
                    counts[2]++;
                }
                cardBalance.put(cardId, balance);
            }
        }
        cardBalance.forEach((id, b) -> db.update("UPDATE card SET balance = :b WHERE id = :id", Db.p("id", id, "b", b)));
        account.forEach((g, b) -> db.update("UPDATE card_group SET balance = :b WHERE id = :g", Db.p("g", g, "b", b)));
        return counts;
    }

    /** Recharge passée du compte flotte (virement, créditée), avec son mouvement. */
    private void rechargeAt(long group, long amount, Instant at, Long admin) {
        long balance = db.value("UPDATE card_group SET balance = balance + :a WHERE id = :g RETURNING balance", Db.p("g", group, "a", amount),
                Long.class);
        String ref = refs.fleetRecharge();
        String vir = "VIR-" + (10_000 + Math.floorMod(ref.hashCode(), 89_999));
        long rid = db.insert("""
                \sINSERT INTO fleet_recharge (ref, group_id, amount, payment_method, payment_ref, status, requested_by, created_at, credited_at)
                VALUES (:ref, :g, :a, 'VIREMENT', :pr, 'CREDITEE', :u, :at, :at)""",
                Db.p("ref", ref, "g", group, "a", amount, "pr", vir, "u", admin, "at", java.sql.Timestamp.from(at)));
        db.update("""
                \sINSERT INTO fleet_movement (group_id, at, kind, amount, balance_after, fleet_recharge_id, employee_id, note)
                VALUES (:g, :at, 'RECHARGE', :a, :b, :r, :u, :n)""",
                Db.p("g", group, "at", java.sql.Timestamp.from(at), "a", amount, "b", balance, "r", rid, "u", admin, "n", ref + " · virement " + vir));
    }

    private static long roundUp(long v, long step) {
        return (v + step - 1) / step * step;
    }

    private long newDriver(Random r, Object holderId, Object groupId) {
        String name = DemoSeeder.FIRST[r.nextInt(DemoSeeder.FIRST.length)] + " " + DemoSeeder.LAST[r.nextInt(DemoSeeder.LAST.length)];
        int validity = r.nextInt(12) == 0 ? -20 - r.nextInt(60) : r.nextInt(10) == 0 ? 5 + r.nextInt(20) : 120 + r.nextInt(1200);
        return db.insert("""
                \sINSERT INTO driver (holder_id, group_id, name, phone, licence_number, licence_expires, created_at)
                VALUES (:h, :g, :n, :ph, :ln, :le, now() - interval '200 days')""",
                Db.p("h", holderId, "g", groupId, "n", name, "ph", "+228 9" + r.nextInt(10) + " " + (10 + r.nextInt(89)) + " "
                        + (10 + r.nextInt(89)) + " " + (10 + r.nextInt(89)), "ln", "TG-" + (2008 + r.nextInt(16)) + "-" + (100_000 + r.nextInt(899_999)),
                        "le", LocalDate.now().plusDays(validity)));
    }

    /**
     * Simulateur, chaque minute : une carte de flotte qui s'épuise est dotée par son compte flotte ;
     * si le compte ne suffit plus, une recharge du compte est demandée (validation Comptable puis DAF à faire à la main).
     * Renvoie false quand la carte n'appartient à aucune flotte dotée (le simulateur la recharge alors directement).
     */
    boolean topUp(String number, long amount, long operatorId) {
        Map<String, Object> c = db.one("""
                \sSELECT c.id, c.group_id, g.balance FROM card c JOIN card_group g ON g.id = c.group_id
                WHERE c.number = :n AND g.active""", Db.p("n", number));
        if (c == null) {
            return false;
        }
        long group = num(c.get("groupId"));
        if (num(c.get("balance")) >= amount) {
            fleets.allocate(group, List.of(new FleetService.Line(num(c.get("id")), amount)), "Dotation automatique", operatorId);
            return true;
        }
        if (db.count("SELECT count(*) FROM fleet_recharge WHERE group_id = :g AND status = 'EN_VALIDATION'", Db.p("g", group)) == 0) {
            fleets.recharge(group, 3_000_000, "VIREMENT", "VIR-" + (10_000 + new Random().nextInt(89_999)), "Réapprovisionnement du compte",
                    users.load(operatorId));
        }
        return false;
    }

    private static Object ts(Object instant) {
        return instant instanceof java.time.Instant i ? java.sql.Timestamp.from(i) : instant;
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }
}
