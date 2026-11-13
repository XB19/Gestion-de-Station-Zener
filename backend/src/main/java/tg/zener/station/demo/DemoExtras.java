package tg.zener.station.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import tg.zener.station.cards.CardOperations;
import tg.zener.station.common.Db;
import tg.zener.station.fleets.FleetService;
import tg.zener.station.loyalty.LoyaltyService;
import tg.zener.station.sales.SaleService;

/**
 * Démonstration des phases 2c à 2g : transferts entre cartes et entre flottes, plafonds en litres ; points de fidélité
 * repris de l'historique et remises ; un client à crédit avec ses relevés et règlements ; listes de cartes et un
 * remplacement ; fournisseurs, commandes de carburant reliées aux réceptions, historique et changement de prix planifié.
 * Chaque domaine est créé une seule fois (marqueur : sa table principale vide).
 */
@Component
public class DemoExtras {

    private static final Logger log = LoggerFactory.getLogger(DemoExtras.class);

    private final Db db;
    private final CardOperations cardOps;
    private final FleetService fleets;
    private final LoyaltyService loyalty;
    private final CardIssuer issuer;
    private final SaleService sales;
    private final TransactionTemplate tx;

    public DemoExtras(Db db, CardOperations cardOps, FleetService fleets, LoyaltyService loyalty, CardIssuer issuer, SaleService sales,
                      PlatformTransactionManager tm) {
        this.sales = sales;
        this.db = db;
        this.cardOps = cardOps;
        this.fleets = fleets;
        this.loyalty = loyalty;
        this.issuer = issuer;
        this.tx = new TransactionTemplate(tm);
    }

    /** Avant les cuves (les pleins du client à crédit entrent dans les relevés des pistolets). */
    public void ensureBeforeTanks() {
        step("cartes d'entreprise", "SELECT count(*) FROM card_transfer", this::companyCards);
        step("fidélité", "SELECT count(*) FROM discount_rule", this::loyalty);
        step("crédit", "SELECT count(*) FROM credit_bill", this::credit);
        step("listes de cartes", "SELECT count(*) FROM card_replacement", this::lists);
    }

    /** Après les cuves (les commandes passées sont reliées aux réceptions). */
    public void ensureAfterTanks() {
        step("fournisseurs", "SELECT count(*) FROM supplier", this::suppliers);
    }

    private void step(String name, String marker, Runnable r) {
        if (db.count(marker, Map.of()) > 0) {
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            tx.executeWithoutResult(s -> r.run());
            log.info("Données de démonstration ({}) créées en {} ms", name, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("Données de démonstration ({}) non créées : {}", name, e.getMessage());
        }
    }

    private Long admin() {
        return db.value("SELECT id FROM employee WHERE role_code = 'ADMIN' AND active ORDER BY id LIMIT 1", Map.of(), Long.class);
    }

    // ------------------------------------------------------------------ 2c : transferts, plafonds en litres

    private void companyCards() {
        long admin = admin();
        // plafonds en litres et pleins par jour sur les camions de Translom ; frais de service et plafond du compte
        db.update("""
                \sUPDATE card_permission cp SET daily_volume_limit = 600, monthly_volume_limit = 9000, daily_fills_limit = 3
                FROM card c JOIN card_group g ON g.id = c.group_id JOIN holder h ON h.id = c.holder_id
                WHERE cp.card_id = c.id AND h.name = 'Translom SARL'""", Map.of());
        db.update("""
                \sUPDATE card_permission cp SET daily_volume_limit = 120, min_balance = 5000
                FROM card c JOIN card_group g ON g.id = c.group_id WHERE cp.card_id = c.id AND g.name = 'Navettes'""", Map.of());
        db.update("""
                \sUPDATE card_group g SET recharge_fee_pct = 1.5, load_up_limit = 25000000 FROM holder h
                WHERE h.id = g.holder_id AND h.name = 'Translom SARL'""", Map.of());
        // transferts entre cartes d'une même flotte
        for (String group : List.of("Camions Nord", "Navettes", "Engins")) {
            List<Map<String, Object>> cards = db.list("""
                    \sSELECT c.id, c.balance FROM card c JOIN card_group g ON g.id = c.group_id
                    WHERE g.name = :g AND c.status = 'ACTIVE' ORDER BY c.balance DESC LIMIT 2""", Db.p("g", group));
            if (cards.size() == 2 && ((Number) cards.get(0).get("balance")).longValue() > 60_000) {
                cardOps.transfer(num(cards.get(0).get("id")), num(cards.get(1).get("id")), 50_000, "Rééquilibrage entre chauffeurs", admin);
            }
        }
        // transfert d'un compte flotte à l'autre (Translom : Camions Nord → Camions Sud)
        Map<String, Object> pair = db.one("""
                \sSELECT n.id AS north, s.id AS south, n.balance FROM card_group n JOIN card_group s ON s.holder_id = n.holder_id
                WHERE n.name = 'Camions Nord' AND s.name = 'Camions Sud'""", Map.of());
        if (pair != null && num(pair.get("balance")) >= 300_000) {
            fleets.transfer(num(pair.get("north")), num(pair.get("south")), 300_000, "Renfort de la flotte Sud", admin);
        }
    }

    // ------------------------------------------------------------------ 2d : fidélité et remises

    private void loyalty() {
        long admin = admin();
        Long agroFleet = db.value("SELECT g.id FROM card_group g JOIN holder h ON h.id = g.holder_id WHERE h.name = 'Agro-Plus Togo' ORDER BY g.id LIMIT 1",
                Map.of(), Long.class);
        db.update("""
                \sINSERT INTO discount_rule (name, scope, category, min_consumption, discount_pct, created_by, created_at) VALUES
                    ('Palier Argent', 'PALIER', 'LUBRIFIANT', 1000000, 3, :a, now() - interval '60 days'),
                    ('Palier Or', 'PALIER', 'LUBRIFIANT', 3000000, 6, :a, now() - interval '60 days'),
                    ('Palier Or · accessoires', 'PALIER', 'ACCESSOIRE', 3000000, 5, :a, now() - interval '60 days')""", Db.p("a", admin));
        db.update("""
                \sINSERT INTO discount_rule (name, scope, category, profile, discount_pct, created_by, created_at)
                VALUES ('Partenaires · gaz', 'PROFIL', 'GAZ', 'PARTENAIRE', 4, :a, now() - interval '45 days')""", Db.p("a", admin));
        if (agroFleet != null) {
            db.update("""
                    \sINSERT INTO discount_rule (name, scope, category, card_group_id, discount_pct, created_by, created_at)
                    VALUES ('Contrat Agro-Plus · lubrifiants', 'GROUPE', 'LUBRIFIANT', :g, 8, :a, now() - interval '30 days')""",
                    Db.p("g", agroFleet, "a", admin));
        }
        // points repris de l'historique : achats payés par carte des clients (hors employés), règle de la catégorie
        db.update("""
                \sINSERT INTO loyalty_movement (holder_id, card_id, sale_id, at, kind, points, balance_after)
                SELECT c.holder_id, c.id, x.id, x.at, 'GAIN', p.pts, 0
                FROM sale x JOIN card c ON c.id = x.card_id JOIN holder h ON h.id = c.holder_id
                CROSS JOIN LATERAL (
                    SELECT floor(sum(l.amount * r.points_per_1000 / 1000) * CASE WHEN c.type_code = 'VIP' THEN 2 ELSE 1 END) AS pts
                    FROM sale_line l JOIN product pr ON pr.id = l.product_id JOIN loyalty_rule r ON r.category = pr.category AND r.active
                    WHERE l.sale_id = x.id) p
                WHERE h.kind <> 'EMPLOYE' AND x.payment_method = 'CARTE' AND p.pts > 0""", Map.of());
        // soldes cumulés dans l'ordre, puis solde du titulaire
        db.update("""
                \sUPDATE loyalty_movement m SET balance_after = s.running FROM (
                    SELECT id, sum(points) OVER (PARTITION BY holder_id ORDER BY at, id) AS running FROM loyalty_movement) s
                WHERE s.id = m.id""", Map.of());
        db.update("""
                \sUPDATE sale x SET points_earned = m.points FROM loyalty_movement m WHERE m.sale_id = x.id AND m.kind = 'GAIN'""", Map.of());
        db.update("""
                \sUPDATE holder h SET loyalty_points = s.total FROM (SELECT holder_id, sum(points) AS total FROM loyalty_movement GROUP BY holder_id) s
                WHERE s.holder_id = h.id""", Map.of());
        // ventes remisées : lubrifiants achetés avec une carte de la flotte Agro-Plus (contrat à 8 %)
        Map<String, Object> agro = db.one("""
                \sSELECT c.number, s.id AS station_id, s.manager_id FROM card c JOIN card_group g ON g.id = c.group_id
                JOIN station s ON s.code = 'ST-004' WHERE g.name = 'Livraisons' AND c.status = 'ACTIVE' ORDER BY c.balance DESC LIMIT 1""", Map.of());
        String lube = db.value("SELECT code FROM product WHERE category = 'LUBRIFIANT' AND active ORDER BY id LIMIT 1", Map.of(), String.class);
        if (agro != null && lube != null) {
            for (int i = 0; i < 3; i++) {
                try {
                    sales.record(new SaleService.Sale(null, num(agro.get("stationId")), null, "BOUTIQUE", "BOUTIQUE", null,
                            List.of(new SaleService.Line(lube, BigDecimal.valueOf(2 + i), false)), "CARTE", null, (String) agro.get("number"),
                            null, false, agro.get("managerId") == null ? null : num(agro.get("managerId")), null, false, null));
                } catch (Exception e) {
                    log.debug("Vente remisée de démonstration non enregistrée : {}", e.getMessage());
                }
            }
        }
        // quelques clients utilisent leurs points (crédit sur la carte)
        for (Map<String, Object> h : db.list("""
                \sSELECT h.id, h.loyalty_points, (SELECT c.id FROM card c WHERE c.holder_id = h.id AND c.status = 'ACTIVE' ORDER BY c.id LIMIT 1) AS card_id
                FROM holder h WHERE h.kind = 'PARTICULIER' AND h.loyalty_points >= 300 ORDER BY h.loyalty_points DESC LIMIT 4""")) {
            if (h.get("cardId") != null) {
                long pts = num(h.get("loyaltyPoints")) / 200 * 100;
                loyalty.redeem(num(h.get("cardId")), pts, "Conversion à la caisse", admin);
            }
        }
    }

    // ------------------------------------------------------------------ 2e : client à crédit, relevés et règlements

    private void credit() {
        long admin = admin();
        Long accountant = db.value("SELECT id FROM employee WHERE role_code = 'COMPTABLE' AND active ORDER BY id LIMIT 1", Map.of(), Long.class);
        long holder = db.insert("""
                \sINSERT INTO holder (kind, name, profile, id_doc_type, id_doc_number, phone, email, address, created_at)
                VALUES ('ENTREPRISE', 'Kéka Transport et Logistique', 'PARTENAIRE', 'RCCM', 'TG-LOM-2020-B-0815', '+228 22 61 44 90',
                        'comptabilite@keka-transport.tg', 'Zone portuaire, Lomé', now() - interval '120 days')""", Map.of());
        long group = db.insert("INSERT INTO card_group (holder_id, name, code, contact_name, phone) VALUES (:h, 'Flotte crédit', 'KEK-CR', 'Afi Kéka', '+228 90 12 34 56')",
                Db.p("h", holder));
        Random r = new Random(7);
        List<Long> cards = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long id = issuer.issue(null, "FLOTTE", "PUCE", holder, group, admin, "1234");
            db.update("""
                    \sUPDATE card SET status = 'ACTIVE', activated_by = :a, activated_at = now() - interval '100 days', created_at = now() - interval '101 days',
                           credit_limit = 3000000, vehicle_plate = :p, vehicle_label = 'Camion citerne', driver_name = :d WHERE id = :id""",
                    Db.p("id", id, "a", accountant, "p", "TG " + (5100 + i * 37) + " KT",
                            "d", DemoSeeder.FIRST[r.nextInt(DemoSeeder.FIRST.length)] + " " + DemoSeeder.LAST[r.nextInt(DemoSeeder.LAST.length)]));
            cards.add(id);
        }
        // grand livre : pleins à crédit sur 2,5 mois, relevés du 1er du mois, règlements
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now();
        YearMonth m2 = YearMonth.from(today).minusMonths(2);
        YearMonth m1 = YearMonth.from(today).minusMonths(1);
        long price = db.value("""
                \sSELECT pp.price FROM product_price pp JOIN product p ON p.id = pp.product_id WHERE p.code = 'GASOIL' AND pp.profile = 'PARTENAIRE'""",
                Map.of(), Long.class);
        long productId = db.value("SELECT id FROM product WHERE code = 'GASOIL'", Map.of(), Long.class);
        long[] balance = new long[cards.size()];
        // paiements : relevé M-2 soldé le 12 du mois M-1, relevé M-1 réglé à 40 % le 8 du mois en cours
        Instant pay1 = m1.atDay(12).atTime(6, 30).atZone(zone).toInstant();
        Instant pay2 = YearMonth.from(today).atDay(Math.min(8, today.getDayOfMonth())).atTime(6, 30).atZone(zone).toInstant();
        long[] bill2Total = {0};
        long[] bill1Total = {0};
        Instant end = Instant.now().minus(2, ChronoUnit.HOURS);
        for (Instant t = m2.atDay(1).atTime(7, 0).atZone(zone).toInstant(); t.isBefore(end); t = t.plus(1, ChronoUnit.DAYS)) {
            if (!pay1.isAfter(t) && pay1.isAfter(t.minus(1, ChronoUnit.DAYS))) {
                // règlement du relevé M-2 (après le grand livre de M-2) : rétablit les soldes
                settle(cards, balance, bill2Total[0], pay1, accountant);
            }
            if (!pay2.isAfter(t) && pay2.isAfter(t.minus(1, ChronoUnit.DAYS))) {
                settle(cards, balance, bill1Total[0] * 4 / 10, pay2, accountant);
            }
            for (int i = 0; i < cards.size(); i++) {
                if (r.nextInt(3) != 0) {
                    continue;
                }
                double liters = 180 + r.nextInt(220);
                long amount = Math.round(liters * price);
                if (balance[i] - amount < -3_000_000) {
                    continue;
                }
                Instant at = t.plus(r.nextInt(12), ChronoUnit.HOURS).plus(r.nextInt(60), ChronoUnit.MINUTES);
                if (at.isAfter(end)) {
                    continue;
                }
                balance[i] -= amount;
                fill(cards.get(i), at, productId, liters, price, amount, balance[i]);
                YearMonth ym = YearMonth.from(at.atZone(zone));
                if (ym.equals(m2)) {
                    bill2Total[0] += amount;
                } else if (ym.equals(m1)) {
                    bill1Total[0] += amount;
                }
            }
        }
        for (int i = 0; i < cards.size(); i++) {
            db.update("UPDATE card SET balance = :b WHERE id = :id", Db.p("id", cards.get(i), "b", balance[i]));
        }
        // relevés (établis le 1er) puis règlements
        bill(holder, m2, m1.atDay(1), bill2Total[0], 0, "PAYEE", bill2Total[0], pay1, accountant);
        long paid2 = bill1Total[0] * 4 / 10;
        bill(holder, m1, YearMonth.from(today).atDay(1), bill1Total[0], 0, paid2 > 0 ? "PARTIELLE" : "A_PAYER", paid2, pay2, accountant);
    }

    private void settle(List<Long> cards, long[] balance, long amount, Instant at, Long employee) {
        long left = amount;
        for (int i = 0; i < cards.size() && left > 0; i++) {
            if (balance[i] >= 0) {
                continue;
            }
            long credit = Math.min(left, -balance[i]);
            balance[i] += credit;
            left -= credit;
            db.update("""
                    \sINSERT INTO card_movement (card_id, at, kind, amount, balance_after, detail, employee_id)
                    VALUES (:c, :at, 'REGLEMENT', :a, :b, 'Règlement de relevé', :e)""",
                    Db.p("c", cards.get(i), "at", java.sql.Timestamp.from(at), "a", credit, "b", balance[i], "e", employee));
        }
    }

    private void fill(long cardId, Instant at, long productId, double liters, long price, long amount, long balanceAfter) {
        Map<String, Object> pump = db.one("""
                \sSELECT pu.id, pu.station_id FROM pump pu JOIN station s ON s.id = pu.station_id JOIN product p ON p.id = pu.product_id
                WHERE s.code IN ('ST-001', 'ST-003') AND p.code = 'GASOIL' ORDER BY random() LIMIT 1""", Map.of());
        Map<String, Object> shift = db.one("""
                \sSELECT es.id, es.employee_id FROM employee_shift es WHERE es.station_id = :s AND es.started_at <= :at
                  AND (es.ended_at IS NULL OR es.ended_at > :at) ORDER BY es.started_at DESC LIMIT 1""",
                Db.p("s", pump.get("stationId"), "at", java.sql.Timestamp.from(at)));
        BigDecimal q = BigDecimal.valueOf(liters).setScale(2, RoundingMode.HALF_UP);
        long saleId = db.insert("""
                \sINSERT INTO sale (uuid, at, kind, channel, station_id, pump_id, mode, employee_id, employee_shift_id, payment_method, card_id,
                                  buyer_profile, total)
                VALUES (gen_random_uuid(), :at, 'CARBURANT', 'POMPE', :s, :p, 'CONVENTIONNEL', :e, :sh, 'CARTE', :c, 'PARTENAIRE', :t)""",
                Db.p("at", java.sql.Timestamp.from(at), "s", pump.get("stationId"), "p", pump.get("id"), "e", shift == null ? null : shift.get("employeeId"),
                        "sh", shift == null ? null : shift.get("id"), "c", cardId, "t", amount));
        db.update("INSERT INTO sale_line (sale_id, product_id, quantity, unit_price, amount) VALUES (:x, :p, :q, :u, :a)",
                Db.p("x", saleId, "p", productId, "q", q, "u", price, "a", amount));
        db.update("""
                \sINSERT INTO card_movement (card_id, at, kind, amount, balance_after, station_id, sale_id, detail, employee_id)
                VALUES (:c, :at, 'VENTE', :a, :b, :s, :x, :d, :e)""",
                Db.p("c", cardId, "at", java.sql.Timestamp.from(at), "a", -amount, "b", balanceAfter, "s", pump.get("stationId"), "x", saleId,
                        "d", q.stripTrailingZeros().toPlainString() + " L Gasoil", "e", shift == null ? null : shift.get("employeeId")));
    }

    private void bill(long holder, YearMonth month, LocalDate issued, long consumption, long previous, String status, long paid, Instant paidAt,
                      Long employee) {
        long id = db.insert("""
                \sINSERT INTO credit_bill (ref, holder_id, period_start, period_end, previous_due, consumption, amount_due, paid, due_date, status,
                                         generated_at, paid_at)
                VALUES ('FAC-' || :y || '-' || nextval('ref_bill_seq'), :h, :f, :t, :p, :c, :a, :paid, :due, :st, CAST(:g AS date) + time '00:40',
                        CASE WHEN :st = 'PAYEE' THEN CAST(:pa AS timestamptz) END)""",
                Db.p("y", month.getYear(), "h", holder, "f", month.atDay(1), "t", month.atEndOfMonth(), "p", previous, "c", consumption,
                        "a", previous + consumption, "paid", paid, "due", issued.plusDays(15), "st", status, "g", issued,
                        "pa", java.sql.Timestamp.from(paidAt)));
        db.update("""
                \sINSERT INTO credit_bill_line (bill_id, card_id, sale_id, at, amount)
                SELECT :b, m.card_id, m.sale_id, m.at, -m.amount FROM card_movement m JOIN card c ON c.id = m.card_id
                WHERE c.holder_id = :h AND m.kind = 'VENTE' AND m.at >= :f AND m.at < :t""",
                Db.p("b", id, "h", holder, "f", month.atDay(1), "t", month.plusMonths(1).atDay(1)));
        if (paid > 0) {
            db.update("""
                    \sINSERT INTO credit_payment (bill_id, amount, payment_method, payment_ref, at, employee_id, note)
                    VALUES (:b, :a, 'VIREMENT', :r, :at, :e, :n)""",
                    Db.p("b", id, "a", paid, "r", "VIR-KEKA-" + month, "at", java.sql.Timestamp.from(paidAt), "e", employee,
                            "n", "PAYEE".equals(status) ? "Règlement intégral" : "Acompte"));
        }
    }

    // ------------------------------------------------------------------ 2f : listes et remplacement

    private void lists() {
        long admin = admin();
        // remplacement d'une carte perdue (solde reporté, frais, opposition)
        Long lost = db.value("""
                \sSELECT c.id FROM card c JOIN holder h ON h.id = c.holder_id
                WHERE h.kind = 'PARTICULIER' AND c.status = 'ACTIVE' AND c.balance > 10000 ORDER BY c.id DESC LIMIT 1""", Map.of(), Long.class);
        if (lost != null) {
            cardOps.replace(lost, "PERTE", null, "Déclarée perdue par le titulaire au guichet", admin);
        }
        // liste blanche : cartes des camions acceptées hors connexion à Dapaong et Kara
        for (Map<String, Object> c : db.list("""
                \sSELECT c.id FROM card c JOIN card_group g ON g.id = c.group_id WHERE g.name = 'Camions Nord' AND c.status = 'ACTIVE' ORDER BY c.id LIMIT 4""")) {
            for (String code : List.of("ST-011", "ST-010")) {
                Long station = db.value("SELECT id FROM station WHERE code = :c", Db.p("c", code), Long.class);
                cardOps.list(num(c.get("id")), "BLANCHE", station, "Tournées du Nord : acceptée hors connexion", null, admin);
            }
        }
        // liste grise : codes PIN erronés répétés
        Long gray = db.value("""
                \sSELECT c.id FROM card c JOIN holder h ON h.id = c.holder_id WHERE h.kind = 'PARTICULIER' AND c.status = 'ACTIVE'
                ORDER BY c.id LIMIT 1 OFFSET 3""", Map.of(), Long.class);
        if (gray != null) {
            Long station = db.value("SELECT id FROM station WHERE code = 'ST-002'", Map.of(), Long.class);
            for (int i = 0; i < 3; i++) {
                db.update("""
                        \sINSERT INTO card_refusal (card_id, station_id, at, amount, reason, detail)
                        VALUES (:c, :s, now() - make_interval(mins => :m), 15000, 'PIN_INCORRECT', 'Code PIN incorrect')""",
                        Db.p("c", gray, "s", station, "m", 40 + i * 3));
            }
            cardOps.list(gray, "GRISE", null, "3 codes PIN erronés en 15 minutes", null, null);
        }
    }

    // ------------------------------------------------------------------ 2g : fournisseurs, commandes, prix

    private void suppliers() {
        long admin = admin();
        String[][] list = {
                {"Dépôt pétrolier de Lomé", "CARBURANT", "Kodjo Amedzro", "+228 22 27 11 00", "commandes@depot-lome.tg"},
                {"Terminal du Port autonome", "CARBURANT", "Yawa Sossou", "+228 22 23 40 40", "ventes@terminal-pal.tg"},
                {"Dépôt de Kara", "CARBURANT", "Essowè Pali", "+228 26 60 05 05", "kara@depot-nord.tg"},
                {"Dépôt de Sokodé", "CARBURANT", "Abdou Ouro", "+228 25 50 12 12", "sokode@depot-nord.tg"},
                {"Grossiste Lubrifiants du Golfe", "BOUTIQUE", "Mawuena Adjo", "+228 90 44 55 66", "contact@lubgolfe.tg"},
                {"Togo Gaz Distribution", "GAZ", "Komi Dzogbé", "+228 91 22 33 44", "commandes@tgd.tg"}};
        for (String[] s : list) {
            db.update("INSERT INTO supplier (name, kind, contact_name, phone, email, created_at) VALUES (:n, :k, :c, :p, :e, now() - interval '200 days')",
                    Db.p("n", s[0], "k", s[1], "c", s[2], "p", s[3], "e", s[4]));
        }
        // commandes passées : une par réception de carburant, livrée
        db.update("""
                \sWITH d AS (
                    SELECT d.id, d.tank_id, d.station_id, d.product_id, d.ticket_volume, d.received_volume, d.started_at, d.ended_at, d.truck_plate,
                           d.driver, d.employee_id, COALESCE(s.id, (SELECT id FROM supplier WHERE name = 'Dépôt pétrolier de Lomé')) AS supplier_id
                    FROM tank_delivery d LEFT JOIN supplier s ON s.name = d.supplier WHERE d.supplier_order_id IS NULL)
                INSERT INTO supplier_order (ref, supplier_id, station_id, product_id, tank_id, quantity, unit_price, amount, status, ordered_at,
                                            expected_at, driver_name, truck_plate, shipped_at, delivered_at, received_volume, created_by, note)
                SELECT 'CMD-' || extract(year FROM d.started_at) || '-' || nextval('ref_supplier_order_seq'), d.supplier_id, d.station_id, d.product_id,
                       d.tank_id, d.ticket_volume, pr.cost, round(d.ticket_volume * pr.cost), 'LIVREE', d.started_at - interval '2 days',
                       d.started_at, d.driver, d.truck_plate, d.started_at - interval '6 hours', d.ended_at, d.received_volume, d.employee_id, 'tank:' || d.id
                FROM d JOIN product p ON p.id = d.product_id
                CROSS JOIN LATERAL (SELECT CASE p.code WHEN 'SUPER' THEN 612 WHEN 'GASOIL' THEN 598 ELSE 560 END AS cost) pr""", Map.of());
        db.update("""
                \sUPDATE tank_delivery d SET supplier_order_id = o.id FROM supplier_order o WHERE o.note = 'tank:' || d.id""", Map.of());
        db.update("UPDATE supplier_order SET note = NULL WHERE note LIKE 'tank:%'", Map.of());
        // commandes en cours : Bè Kpota (gasoil bas) expédiée, deux autres passées
        Map<String, Object> low = db.one("""
                \sSELECT t.id AS tank_id, t.station_id, t.product_id, t.capacity FROM tank t JOIN station s ON s.id = t.station_id
                JOIN product p ON p.id = t.product_id WHERE s.short_name = 'Bè Kpota' AND p.code = 'GASOIL' LIMIT 1""", Map.of());
        if (low != null) {
            order(1, low, 18000, 598, "EXPEDIEE", admin, "TG 7781 AP", "Yao Kpelly");
        }
        for (Map<String, Object> t : db.list("""
                \sSELECT t.id AS tank_id, t.station_id, t.product_id, t.capacity FROM tank t JOIN station s ON s.id = t.station_id
                WHERE s.code IN ('ST-004', 'ST-009') AND t.volume < t.capacity * 0.6 ORDER BY t.volume / t.capacity LIMIT 2""")) {
            order("ST-009".equals(db.value("SELECT code FROM station WHERE id = :s", Db.p("s", t.get("stationId")), String.class)) ? 4 : 2, t,
                    15000, 605, "COMMANDEE", admin, null, null);
        }
        // historique des prix : ancien prix il y a 95 jours, hausse il y a 40 jours ; une hausse du gasoil planifiée dans 5 jours
        db.update("""
                \sUPDATE price_change SET effective_at = now() - interval '40 days', applied_at = now() - interval '40 days',
                       old_price = new_price - CASE WHEN new_price >= 600 THEN 25 ELSE 0 END, note = 'Révision du prix à la pompe'
                WHERE status = 'APPLIQUE' AND note = 'Prix en vigueur'
                  AND product_id IN (SELECT id FROM product WHERE category = 'CARBURANT')""", Map.of());
        db.update("""
                \sINSERT INTO price_change (product_id, profile, old_price, new_price, effective_at, status, created_by, applied_at, note, created_at)
                SELECT product_id, profile, NULL, old_price, now() - interval '95 days', 'APPLIQUE', :a, now() - interval '95 days', 'Prix de début de trimestre',
                       now() - interval '95 days'
                FROM price_change WHERE note = 'Révision du prix à la pompe'""", Db.p("a", admin));
        db.update("""
                \sINSERT INTO price_change (product_id, profile, old_price, new_price, effective_at, status, created_by, note)
                SELECT pp.product_id, pp.profile, pp.price, pp.price + 10, date_trunc('day', now()) + interval '5 days 6 hours', 'PLANIFIE', :a,
                       'Hausse du prix du gasoil annoncée'
                FROM product_price pp JOIN product p ON p.id = pp.product_id WHERE p.code = 'GASOIL'""", Db.p("a", admin));
    }

    private void order(int supplierIndex, Map<String, Object> t, int quantity, long unitPrice, String status, long admin, String plate, String driver) {
        Long supplier = db.value("SELECT id FROM supplier ORDER BY id OFFSET :o LIMIT 1", Db.p("o", supplierIndex - 1), Long.class);
        db.update("""
                \sINSERT INTO supplier_order (ref, supplier_id, station_id, product_id, tank_id, quantity, unit_price, amount, status, ordered_at,
                                            expected_at, truck_plate, driver_name, shipped_at, seller_name, created_by)
                VALUES ('CMD-' || extract(year FROM now()) || '-' || nextval('ref_supplier_order_seq'), :s, :st, :p, :t, :q, :u, :a, :status,
                        now() - interval '20 hours', now() + interval '6 hours', :plate, :driver,
                        CASE WHEN :status = 'EXPEDIEE' THEN now() - interval '2 hours' END, 'Service commercial', :e)""",
                Db.p("s", supplier, "st", t.get("stationId"), "p", t.get("productId"), "t", t.get("tankId"), "q", quantity, "u", unitPrice,
                        "a", (long) quantity * unitPrice, "status", status, "plate", plate, "driver", driver, "e", admin));
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }
}
