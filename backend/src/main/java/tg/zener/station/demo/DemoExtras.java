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

/**
 * Démonstration des phases 2c à 2g : transferts entre cartes, plafonds en litres ; remplacement d'une carte perdue ;
 * fournisseurs, commandes de carburant reliées aux réceptions, historique et changement de prix planifié.
 * Chaque domaine est créé une seule fois (marqueur : sa table principale vide).
 */
@Component
public class DemoExtras {

    private static final Logger log = LoggerFactory.getLogger(DemoExtras.class);

    private final Db db;
    private final CardOperations cardOps;
    private final CardIssuer issuer;
    private final TransactionTemplate tx;

    public DemoExtras(Db db, CardOperations cardOps, CardIssuer issuer, PlatformTransactionManager tm) {
        this.db = db;
        this.cardOps = cardOps;
        this.issuer = issuer;
        this.tx = new TransactionTemplate(tm);
    }

    /** Avant les cuves. */
    public void ensureBeforeTanks() {
        step("cartes des flottes", "SELECT count(*) FROM card_movement WHERE detail = 'Solde de départ (démonstration)'", this::fleetCards);
        step("cartes d'entreprise", "SELECT count(*) FROM card_transfer", this::companyCards);
        step("remplacement de carte", "SELECT count(*) FROM card_replacement", this::replacement);
    }

    /** Après les cuves (les commandes passées sont reliées aux réceptions). */
    public void ensureAfterTanks() {
        step("fournisseurs", "SELECT count(*) FROM supplier", this::suppliers);
        step("gérance libre", "SELECT count(*) FROM station_concession", this::concessions);
        step("produits des points de vente", "SELECT count(*) FROM pdv_product", this::pdvProducts);
        step("atelier", "SELECT count(*) FROM work_order", this::workshop);
        step("délégation", "SELECT count(*) FROM validation_delegation", this::delegation);
    }

    // ------------------------------------------------------------------ réunion du 17 novembre : gérance, PDV, atelier, délégation

    /** Exploitants et contrats des stations en gérance libre (Kpalimé : contrat qui se termine bientôt). */
    private void concessions() {
        long admin = admin();
        Object[][] rows = {
                {"ST-002", "Komla Amegah", "Ets Amegah & Fils", "+228 90 12 45 78", "RCCM TG-LOM-2015-A-2201", "GL-2024-002", 900, 18, "COMMISSION_LITRE", 15},
                {"ST-004", "Kodjo Agbenyo", "Sté Kodjo Services", "+228 91 22 03 44", "RCCM TG-LOM-2018-B-0777", "GL-2023-004", 1100, 34, "LOYER_FIXE", 0},
                {"ST-006", "Afi Mensah", "Ets Mensah", "+228 92 54 11 09", "RCCM TG-KPA-2019-A-0102", "GL-2022-006", 1400, 1, "MIXTE", 12},
                {"ST-008", "Yawo Agbeko", "Agbeko Pétrole", "+228 93 40 66 21", "RCCM TG-ATK-2020-B-0040", "GL-2024-008", 500, 210, "COMMISSION_LITRE", 14},
                {"ST-011", "Issa Tchakoura", "Sté Savanes Énergie", "+228 97 18 30 52", "RCCM TG-DAP-2021-B-0019", "GL-2025-011", 300, 400, "POURCENTAGE_CA", 0}};
        for (Object[] r : rows) {
            Long station = db.value("SELECT id FROM station WHERE code = :c", Db.p("c", r[0]), Long.class);
            if (station == null) {
                continue;
            }
            int age = (Integer) r[6];
            int left = (Integer) r[7];
            String kind = (String) r[8];
            db.update("""
                    \sINSERT INTO station_concession (station_id, operator_name, operator_company, operator_phone, operator_id_doc, contract_ref,
                                                    contract_start, contract_end, fee_kind, fee_amount, commission_per_liter, revenue_pct,
                                                    deposit_amount, equipment_zener, equipment_operator, staff_by_operator, updated_by)
                    VALUES (:s, :n, :c, :ph, :doc, :ref, current_date - make_interval(days => :age), current_date + make_interval(days => :left),
                            :k, :fee, :cpl, :pct, :dep, 'Cuves, pistolets, hub et lecteur de cartes', 'Boutique, groupe électrogène', true, :u)""",
                    Db.p("s", station, "n", r[1], "c", r[2], "ph", r[3], "doc", r[4], "ref", r[5], "age", age, "left", left, "k", kind,
                            "fee", "LOYER_FIXE".equals(kind) || "MIXTE".equals(kind) ? 450_000L : null,
                            "cpl", "COMMISSION_LITRE".equals(kind) || "MIXTE".equals(kind) ? java.math.BigDecimal.valueOf((Integer) r[9]) : null,
                            "pct", "POURCENTAGE_CA".equals(kind) ? java.math.BigDecimal.valueOf(4.5) : null, "dep", 2_000_000L, "u", admin));
            for (int d : new int[] {60, 30, 7}) {
                db.update("""
                        \sINSERT INTO reminder (kind, ref_type, ref_id, due_on, priority, title, body, link, station_id)
                        SELECT 'CONTRAT', 'CONTRAT', :s, contract_end - :d, CASE WHEN :d <= 7 THEN 'URGENT' ELSE 'ATTENTION' END,
                               'Contrat de gérance : fin dans ' || :d || ' jours · ' || s.name,
                               'Le contrat de gérance libre de ' || sc.operator_name || ' se termine le ' || sc.contract_end || '.',
                               '/stations/' || s.id, s.id
                        FROM station_concession sc JOIN station s ON s.id = sc.station_id
                        WHERE sc.station_id = :s AND sc.contract_end - :d >= current_date - 1
                        ON CONFLICT DO NOTHING""", Db.p("s", station, "d", d));
            }
        }
    }

    /** Le PDV du siège vend aussi des lubrifiants et du gaz ; le stand Agro-Plus est un point promotionnel. */
    private void pdvProducts() {
        long admin = admin();
        Long siege = db.value("SELECT id FROM pdv WHERE kind = 'PERMANENT' ORDER BY id LIMIT 1", Map.of(), Long.class);
        Long hq = db.value("SELECT id FROM station WHERE code = 'ST-001'", Map.of(), Long.class);
        if (siege != null) {
            for (String code : List.of("HUILE_15W40_1L", "HUILE_15W40_5L", "HUILE_5W30_5L", "HUILE_2T_1L", "LIQUIDE_FREIN", "GRAISSE_1KG", "BRULEUR_GAZ")) {
                db.update("""
                        \sINSERT INTO pdv_product (pdv_id, product_id) SELECT :p, id FROM product WHERE code = :c ON CONFLICT DO NOTHING""",
                        Db.p("p", siege, "c", code));
                db.update("""
                        \sINSERT INTO stock_level (pdv_id, product_id, quantity) SELECT :p, id, 24 FROM product WHERE code = :c
                        ON CONFLICT (pdv_id, product_id) WHERE pdv_id IS NOT NULL DO NOTHING""", Db.p("p", siege, "c", code));
            }
        }
        // stock des nouveaux lubrifiants dans les stations
        db.update("""
                \sINSERT INTO stock_level (station_id, product_id, quantity, threshold_manual)
                SELECT s.id, p.id, CASE WHEN p.pack_size >= 20 THEN 6 ELSE 30 END, CASE WHEN p.pack_size >= 20 THEN 2 ELSE 8 END
                FROM station s CROSS JOIN product p
                WHERE s.active AND p.category = 'LUBRIFIANT' AND p.code NOT IN ('HUILE_DIESEL_208L')
                ON CONFLICT (station_id, product_id) WHERE station_id IS NOT NULL DO NOTHING""", Map.of());
        Long stand = db.value("SELECT id FROM pdv WHERE name = 'Stand Agro-Plus'", Map.of(), Long.class);
        if (stand != null) {
            db.update("UPDATE pdv SET kind = 'PROMOTIONNEL', campaign = 'Campagne partenaire Agro-Plus', offer = 'Carte classique à 500 F' WHERE id = :p",
                    Db.p("p", stand));
            db.update("""
                    \sINSERT INTO pdv_product (pdv_id, product_id, promo_price) SELECT :p, id, 500 FROM product WHERE code = 'CARTE_CLASSIQUE'
                    ON CONFLICT DO NOTHING""", Db.p("p", stand));
        }
        if (hq != null && admin > 0) {
            db.update("UPDATE pdv SET session_started_at = COALESCE(session_started_at, created_at)", Map.of());
        }
        // ce que vend chaque point de vente : le siège a toute la gamme, le stand Agro-Plus des cartes et des lubrifiants
        db.update("UPDATE pdv SET sells = '{CARTES,LUBRIFIANTS,GAZ,ACCESSOIRES,ZEN_MOBILITY}' WHERE kind = 'PERMANENT'", Map.of());
        db.update("UPDATE pdv SET sells = '{CARTES,LUBRIFIANTS}' WHERE name = 'Stand Agro-Plus'", Map.of());
    }

    /** Quelques ordres de travail à l'atelier d'Agoè : un payé, un en cours, un terminé à encaisser. */
    private void workshop() {
        Long station = db.value("SELECT id FROM station WHERE code = 'ST-001'", Map.of(), Long.class);
        Long chef = db.value("SELECT id FROM employee WHERE role_code = 'CHEF_POSTE' AND station_id = :s AND active ORDER BY id LIMIT 1",
                Db.p("s", station), Long.class);
        if (station == null || chef == null) {
            return;
        }
        Object[][] orders = {
                {"TG 3321 AB", "Toyota", "Corolla", "Adjoa Kodjo", "+228 90 44 12 33", 84_250, "VIDANGE,FILTRES", "PAYE", 5000, "HUILE_15W40_5L", 1},
                {"TG 7710 BC", "Isuzu", "NPR", "Lomé Logistique", "+228 22 26 70 70", 212_400, "VIDANGE,GRAISSAGE", "EN_COURS", 8000, "HUILE_DIESEL_20L", 1},
                {"TG 1288 AZ", "Hyundai", "Accent", "Kossi Dogbe", "+228 91 07 58 64", 56_900, "LAVAGE,DIAGNOSTIC", "TERMINE", 4000, "LIQUIDE_REFROID", 2}};
        int n = 0;
        for (Object[] o : orders) {
            long vehicle = db.insert("""
                    \sINSERT INTO vehicle (plate, brand, model, owner_name, owner_phone, odometer_km) VALUES (:p, :b, :m, :on, :op, :km)""",
                    Db.p("p", o[0], "b", o[1], "m", o[2], "on", o[3], "op", o[4], "km", o[5]));
            String status = (String) o[7];
            long id = db.insert("""
                    \sINSERT INTO work_order (ref, station_id, vehicle_id, customer_name, customer_phone, odometer_km, services, status, technician_id,
                                            labour_amount, next_service_km, opened_by, opened_at, started_at, finished_at)
                    VALUES ('OT-' || extract(year FROM now()) || '-' || nextval('ref_work_order_seq'), :s, :v, :cn, :cp, :km, :sv, 'OUVERT', :t,
                            :la, :km + 5000, :t, now() - make_interval(hours => :h), now() - make_interval(hours => :h - 1),
                            CASE WHEN :st IN ('TERMINE', 'PAYE') THEN now() - make_interval(hours => GREATEST(:h - 2, 0)) END)""",
                    Db.p("s", station, "v", vehicle, "cn", o[3], "cp", o[4], "km", o[5], "sv", db.array("text", ((String) o[6]).split(",")),
                            "t", chef, "la", ((Integer) o[8]).longValue(), "h", 30 - n * 10, "st", status));
            db.update("""
                    \sINSERT INTO work_order_line (work_order_id, product_id, quantity) SELECT :w, id, :q FROM product WHERE code = :c""",
                    Db.p("w", id, "c", o[9], "q", o[10]));
            db.update("UPDATE work_order SET status = :st WHERE id = :id", Db.p("id", id, "st", "PAYE".equals(status) ? "TERMINE" : status));
            n++;
        }
    }

    /** Le DAF a délégué la validation des recharges jusqu'à 2 000 000 F à une comptable pendant son absence. */
    private void delegation() {
        Long daf = db.value("SELECT id FROM employee WHERE role_code = 'DAF' AND active ORDER BY id LIMIT 1", Map.of(), Long.class);
        Long accountant = db.value("SELECT id FROM employee WHERE email = 'kafui.ekoue@zener.tg'", Map.of(), Long.class);
        if (daf == null || accountant == null) {
            return;
        }
        db.update("""
                \sINSERT INTO validation_delegation (from_employee, to_employee, kinds, max_amount, starts_at, ends_at, note, created_by)
                VALUES (:d, :a, '{RECHARGE,RECHARGE_MASSE}', 2000000, now() - interval '2 days', now() + interval '12 days',
                        'Congés du DAF : recharges jusqu''à 2 000 000 F', :d)""", Db.p("d", daf, "a", accountant));
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

    // ------------------------------------------------------------------ 2b : cartes des flottes

    /** Entreprise, flotte (nulle : carte de l'entreprise sans flotte, à attribuer) et nombre de cartes flotte voulues. */
    private record FleetTarget(String holder, String group, int cards, String plate) {}

    private static final List<FleetTarget> FLEET_TARGETS = List.of(
            new FleetTarget("Translom SARL", "Camions Nord", 4, "TR"), new FleetTarget("Translom SARL", "Camions Sud", 3, "TR"),
            new FleetTarget("Translom SARL", null, 2, "TR"), new FleetTarget("Lomé Logistique", "Navettes", 4, "LL"),
            new FleetTarget("Lomé Logistique", "Direction", 2, "LL"), new FleetTarget("BTP Kara Construction", "Engins", 3, "BK"),
            new FleetTarget("Agro-Plus Togo", "Livraisons", 3, "AP"));

    /** Cartes flotte supplémentaires (émises, activées par la comptabilité, solde de départ) et leur attribution aux flottes. */
    private void fleetCards() {
        long admin = admin();
        Long accountant = db.value("SELECT id FROM employee WHERE role_code = 'COMPTABLE' AND active ORDER BY id LIMIT 1", Map.of(), Long.class);
        Random rnd = new Random(42);
        for (FleetTarget t : FLEET_TARGETS) {
            Map<String, Object> g = db.one("""
                    \sSELECT h.id AS holder_id, g.id AS group_id,
                           (SELECT count(*) FROM card c WHERE c.holder_id = h.id AND c.type_code = 'FLOTTE'
                              AND c.group_id IS NOT DISTINCT FROM g.id) AS n
                    FROM holder h LEFT JOIN card_group g ON g.holder_id = h.id AND g.name = CAST(:g AS text)
                    WHERE h.name = :h""", Db.p("h", t.holder(), "g", t.group()));
            if (g == null || (t.group() != null && g.get("groupId") == null)) {
                continue;
            }
            Long group = g.get("groupId") == null ? null : num(g.get("groupId"));
            for (long i = num(g.get("n")); i < t.cards(); i++) {
                long id = issuer.issue(null, "FLOTTE", "PUCE", num(g.get("holderId")), group, admin, "1234");
                long balance = 50_000L * (3 + rnd.nextInt(10));
                String plate = "TG " + (1000 + rnd.nextInt(9000)) + " " + t.plate();
                db.update("""
                        \sUPDATE card SET status = 'ACTIVE', activated_by = :a, activated_at = now() - interval '40 days',
                               created_at = now() - interval '41 days', balance = :b, vehicle_plate = :p WHERE id = :id""",
                        Db.p("id", id, "a", accountant, "b", balance, "p", plate));
                db.update("""
                        \sINSERT INTO card_movement (card_id, at, kind, amount, balance_after, detail, employee_id)
                        VALUES (:id, now() - interval '40 days', 'AJUSTEMENT', :b, :b, 'Solde de départ (démonstration)', :u)""",
                        Db.p("id", id, "b", balance, "u", admin));
                db.update("""
                        \sINSERT INTO card_event (card_id, at, kind, employee_id, detail) VALUES
                            (:id, now() - interval '41 days', 'EMISSION', :u, NULL),
                            (:id, now() - interval '40 days', 'ACTIVATION', :a, NULL)""", Db.p("id", id, "u", admin, "a", accountant));
                if (t.group() != null) {
                    db.update("""
                            \sINSERT INTO card_event (card_id, at, kind, employee_id, detail)
                            VALUES (:id, now() - interval '40 days', 'AFFECTATION', :u, 'Flotte ' || :g)""",
                            Db.p("id", id, "u", admin, "g", t.group()));
                }
            }
        }
    }

    // ------------------------------------------------------------------ 2c : transferts, plafonds en litres

    private void companyCards() {
        long admin = admin();
        // plafonds en litres et pleins par jour sur les camions de Translom
        db.update("""
                \sUPDATE card_permission cp SET daily_volume_limit = 600, monthly_volume_limit = 9000, daily_fills_limit = 3
                FROM card c JOIN card_group g ON g.id = c.group_id JOIN holder h ON h.id = c.holder_id
                WHERE cp.card_id = c.id AND h.name = 'Translom SARL'""", Map.of());
        db.update("""
                \sUPDATE card_permission cp SET daily_volume_limit = 120, min_balance = 5000
                FROM card c JOIN card_group g ON g.id = c.group_id WHERE cp.card_id = c.id AND g.name = 'Navettes'""", Map.of());
        // transferts entre cartes d'une même flotte
        for (String group : List.of("Camions Nord", "Navettes", "Engins")) {
            List<Map<String, Object>> cards = db.list("""
                    \sSELECT c.id, c.balance FROM card c JOIN card_group g ON g.id = c.group_id
                    WHERE g.name = :g AND c.status = 'ACTIVE' ORDER BY c.balance DESC LIMIT 2""", Db.p("g", group));
            if (cards.size() == 2 && ((Number) cards.get(0).get("balance")).longValue() > 60_000) {
                cardOps.transfer(num(cards.get(0).get("id")), num(cards.get(1).get("id")), 50_000, "Rééquilibrage entre chauffeurs", admin);
            }
        }
    }

    // ------------------------------------------------------------------ 2f : remplacement

    private void replacement() {
        long admin = admin();
        // remplacement d'une carte perdue (solde reporté, frais, ancienne carte bloquée)
        Long lost = db.value("""
                \sSELECT c.id FROM card c JOIN holder h ON h.id = c.holder_id
                WHERE h.kind = 'PARTICULIER' AND c.status = 'ACTIVE' AND c.balance > 10000 ORDER BY c.id DESC LIMIT 1""", Map.of(), Long.class);
        if (lost != null) {
            cardOps.replace(lost, "PERTE", null, "Déclarée perdue par le titulaire au guichet", admin);
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
