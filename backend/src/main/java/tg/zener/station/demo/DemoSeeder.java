package tg.zener.station.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tg.zener.station.cards.CardIssuer;
import tg.zener.station.common.Db;
import tg.zener.station.common.Refs;
import tg.zener.station.common.Settings;
import tg.zener.station.recharges.RechargeService;
import tg.zener.station.reports.ReportService;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.security.UserLoader;
import tg.zener.station.stock.ThresholdService;
import tg.zener.station.validations.ValidationService;

/**
 * Données de démonstration reprenant les maquettes (DEMO_DATA=true) : clients et licences, les
 * 11 stations du frontend avec leurs coordonnées, pistolets P1–P8, cuves, employés de chaque rôle,
 * cartes, points de vente, validations en attente, 14 jours de ventes et les rapports journaliers.
 * Exécuté une seule fois, sur une base vide. Mot de passe de tous les comptes : zener-station ;
 * PIN de toutes les cartes : 1234 ; clé du PC station : demo-st-001, demo-st-002…
 */
@Component
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    public static final String PASSWORD = "zener-station";
    public static final String PIN = "1234";
    static final int DAYS = 14;

    record Spec(String code, String name, String shortName, String type, String zone, String client, double lat, double lon,
                String net, String mode, long revenue, int[] tanks, boolean reader) {}

    static final List<Spec> STATIONS = List.of(
            new Spec("ST-001", "Zener Agoè Assiyéyé", "Agoè Assiyéyé", "SUCCURSALE", "Lomé", "Groupe Zener", 6.2367, 1.2131, "ON",
                    "CONVENTIONNEL", 2_845_600, new int[] {70, 55, 40}, true),
            new Spec("ST-002", "Zener Bè Kpota", "Bè Kpota", "GERANCE_LIBRE", "Lomé", "Ets Amegah & Fils", 6.1431, 1.2440, "ON",
                    "CONVENTIONNEL", 2_310_200, new int[] {75, 15, 65}, true),
            new Spec("ST-003", "Zener Adidogomé", "Adidogomé", "SUCCURSALE", "Lomé", "Groupe Zener", 6.1689, 1.1633, "ON",
                    "AUTONOME", 1_984_000, new int[] {50, 70, 60}, true),
            new Spec("ST-004", "Zener Tsévié Nord", "Tsévié Nord", "GERANCE_LIBRE", "Zio", "Sté Kodjo Services", 6.4300, 1.2133, "ON",
                    "CONVENTIONNEL", 1_205_400, new int[] {60, 50, 45}, true),
            new Spec("ST-005", "Zener Aného Plage", "Aného Plage", "SUCCURSALE", "Lacs", "Groupe Zener", 6.2311, 1.5956, "ON",
                    "CONVENTIONNEL", 986_300, new int[] {55, 60, 50}, true),
            new Spec("ST-006", "Zener Kpalimé Centre", "Kpalimé Centre", "GERANCE_LIBRE", "Kloto", "Ets Mensah", 6.9006, 0.6297,
                    "UNSTABLE", "CONVENTIONNEL", 874_100, new int[] {45, 55, 50}, true),
            new Spec("ST-007", "Zener Notsè", "Notsè", "SUCCURSALE", "Haho", "Groupe Zener", 6.9500, 1.1667, "ON",
                    "AUTONOME", 642_800, new int[] {60, 45, 55}, true),
            new Spec("ST-008", "Zener Atakpamé", "Atakpamé", "GERANCE_LIBRE", "Ogou", "Agbeko Pétrole", 7.5333, 1.1250, "OFF",
                    "CONVENTIONNEL", 900_000, new int[] {55, 40, 50}, false),
            new Spec("ST-009", "Zener Sokodé", "Sokodé", "SUCCURSALE", "Tchaoudjo", "Groupe Zener", 8.9833, 1.1333, "ON",
                    "AUTONOME", 1_120_500, new int[] {60, 55, 50}, true),
            new Spec("ST-010", "Zener Kara Centre", "Kara Centre", "SUCCURSALE", "Kozah", "Groupe Zener", 9.5511, 1.1861, "ON",
                    "AUTONOME", 1_512_900, new int[] {80, 75, 70}, true),
            new Spec("ST-011", "Zener Dapaong", "Dapaong", "GERANCE_LIBRE", "Tône", "Sté Savanes Énergie", 10.8622, 0.2072, "OFF",
                    "CONVENTIONNEL", 700_000, new int[] {50, 45, 40}, true));

    static final String[] FIRST = {"Kossi", "Yao", "Komlan", "Kodjo", "Afi", "Akossiwa", "Edem", "Sena", "Yawo", "Koffi", "Kafui",
            "Elom", "Dela", "Mawuli", "Abla", "Adjo", "Enyonam", "Fafa", "Kokou", "Atsu", "Dzifa", "Eyram", "Kekeli", "Senyo",
            "Yayra", "Komi", "Kwami", "Essowè", "Pyabalo", "Massama", "Akouvi", "Ablavi", "Tchilabalo", "Mawussé"};
    static final String[] LAST = {"Mensah", "Adzoh", "Amouzou", "Akakpo", "Dossou", "Ahadji", "Agbo", "Koudjo", "Ayivi", "Atsou",
            "Ekoué", "Abalo", "Tchalla", "Bawa", "Djobo", "Issifou", "Ouro", "Pali", "Sama", "Wiyao", "Kpatcha", "Afeli", "Amedome",
            "Attiogbé", "Dogbé", "Fiagan", "Gbadoe", "Klutse", "Lawson", "Nyaku", "Sossou", "Tsogbe"};

    /** Poste type de la station (Matin, Soir, Nuit). */
    record Config(long id, String name, LocalTime from, LocalTime to) {}

    static final class St {
        Spec spec;
        long id;
        long manager;
        long[] chiefs = new long[2];
        long[] attendants = new long[3];
        final Map<String, Long> pumps = new LinkedHashMap<>();   // P1 → id
        final Map<String, Long> tanks = new HashMap<>();         // SUPER → id
        final List<Config> configs = new ArrayList<>();
    }

    /** Carte de démonstration pouvant payer dans l'historique. */
    static final class DemoCard {
        long id;
        String key;
        String profile;
        long finalBalance;
        long cap;
        int weight;
        Set<String> fuels;
        Set<String> stationCodes;   // null = toutes
        LocalTime from;
        LocalTime to;
        boolean weekdaysOnly;
        LocalDate until;            // pas de paiement après ce jour
        boolean fleet;
    }

    static final class Line {
        long productId;
        String category;
        String productName;
        BigDecimal quantity;
        long unit;
        long amount;
    }

    static final class Sale {
        long id;
        LocalDateTime at;
        String kind;
        String channel;
        St st;
        Long pdvId;
        Long pumpId;
        String pumpCode;
        String mode;
        Long employee;
        Long shift;
        String payment;
        String paymentRef;
        DemoCard card;
        long cardId;
        String profile = "GRAND_PUBLIC";
        long total;
        final List<Line> lines = new ArrayList<>();
    }

    record Event(LocalDateTime at, boolean sale, long amount, Long saleId, Long stationId) {}

    private final Db db;
    private final PasswordEncoder encoder;
    private final CardIssuer issuer;
    private final Refs refs;
    private final Settings settings;
    private final RechargeService rechargeService;
    private final ValidationService validations;
    private final ThresholdService thresholds;
    private final ReportService reports;
    private final UserLoader users;
    private final DemoSimulator simulator;
    private final DemoAvatars avatars;
    private final TransactionTemplate tx;
    private final boolean enabled;

    private final Random rnd = new Random(20261109);
    private LocalDateTime now;
    private LocalDate today;
    private LocalDate start;
    private String passwordHash;
    private String pinHash;
    private final Map<String, Long> products = new HashMap<>();
    private final Map<String, String> productNames = new HashMap<>();
    private final Map<String, Long> prices = new HashMap<>();
    private final Set<LocalDate> holidays = new HashSet<>();
    private final List<St> stations = new ArrayList<>();
    private final Map<String, Long> clients = new HashMap<>();
    private final Map<String, Long> people = new HashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Set<String> usedEmails = new HashSet<>();
    private final List<DemoCard> demoCards = new ArrayList<>();
    private final Map<String, Long> cardIds = new HashMap<>();
    private final Map<String, Long> pdvs = new HashMap<>();
    private final Map<String, Long> shiftIds = new HashMap<>();
    private final Map<Long, Long> shiftCash = new HashMap<>();
    private int matricule = 0;

    public DemoSeeder(Db db, PasswordEncoder encoder, CardIssuer issuer, Refs refs, Settings settings,
                      RechargeService rechargeService, ValidationService validations, ThresholdService thresholds,
                      ReportService reports, UserLoader users, DemoSimulator simulator, DemoAvatars avatars,
                      PlatformTransactionManager tm,
                      @Value("${app.demo-data}") boolean enabled) {
        this.db = db;
        this.encoder = encoder;
        this.issuer = issuer;
        this.refs = refs;
        this.settings = settings;
        this.rechargeService = rechargeService;
        this.validations = validations;
        this.thresholds = thresholds;
        this.reports = reports;
        this.users = users;
        this.simulator = simulator;
        this.avatars = avatars;
        this.tx = new TransactionTemplate(tm);
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        if (db.count("SELECT count(*) FROM employee", Map.of()) > 0) {
            if (!settings.getBool("demo.seeded", false)) {
                log.warn("Données de démonstration : la base contient déjà des employés mais la démonstration n'a pas été "
                        + "entièrement créée ; recréez une base vide pour la régénérer");
            } else {
                log.info("Données de démonstration : base déjà remplie, rien à faire");
            }
            avatars.ensureAll();
            simulator.start();
            return;
        }
        long t0 = System.currentTimeMillis();
        log.info("Données de démonstration : création en cours…");
        now = LocalDateTime.now().withNano(0);
        today = now.toLocalDate();
        start = today.minusDays(DAYS - 1);
        tx.executeWithoutResult(s -> {
            reference();
            clientsAndLicences();
            stationsAndEquipment();
            employees();
            cards();
            pdv();
            history();
        });
        tx.executeWithoutResult(s -> pendingOperations());
        tx.executeWithoutResult(s -> deliveries());
        tx.executeWithoutResult(s -> dailyReports());
        tx.executeWithoutResult(s -> finish());
        log.info("Données de démonstration créées en {} s · connexion : ama.kpodar@zener.tg / {}",
                (System.currentTimeMillis() - t0) / 1000, PASSWORD);
        avatars.ensureAll();
        simulator.start();
    }

    // ================================================================== référentiel

    private void reference() {
        passwordHash = encoder.encode(PASSWORD);
        pinHash = issuer.hashPin(PIN);
        for (Map<String, Object> p : db.list("SELECT id, code, name FROM product")) {
            products.put((String) p.get("code"), num(p.get("id")));
            productNames.put((String) p.get("code"), (String) p.get("name"));
        }
        for (Map<String, Object> p : db.list("SELECT p.code, pp.profile, pp.price FROM product_price pp JOIN product p ON p.id = pp.product_id")) {
            prices.put(p.get("code") + "|" + p.get("profile"), num(p.get("price")));
        }
        db.list("SELECT day FROM public_holiday").forEach(r -> holidays.add((LocalDate) r.get("day")));
    }

    private void clientsAndLicences() {
        Object[][] list = {
                {"Groupe Zener", "GROUPE", "Togo", "Direction générale", "+228 22 21 40 00", "direction@zener.tg", "2026-12-31", "ACTIVE", 0L},
                {"Ets Amegah & Fils", "GERANT", "Togo", "Koffi Amegah", "+228 90 12 34 56", "contact@amegah-fils.tg", "2027-03-14", "ACTIVE", 350_000L},
                {"Sté Kodjo Services", "GERANT", "Togo", "Kodjo Ayivi", "+228 91 22 33 44", "kodjo.services@gmail.com", "2026-11-25", "ACTIVE", 350_000L},
                {"Agbeko Pétrole", "GERANT", "Togo", "Edoh Agbeko", "+228 92 45 67 89", "agbeko.petrole@yahoo.fr", "2026-11-30", "ACTIVE", 350_000L},
                {"Ets Mensah", "GERANT", "Togo", "Afi Mensah", "+228 93 11 22 33", "ets.mensah@gmail.com", "2027-06-02", "ACTIVE", 350_000L},
                {"Sté Savanes Énergie", "GERANT", "Togo", "Lamboni Yendoubé", "+228 94 55 66 77", "savanes.energie@gmail.com", "2026-10-15", "RESILIEE", 350_000L},
                {"Bénin Carburants SA", "GERANT", "Bénin", "Rodrigue Houngbo", "+229 97 00 11 22", "contact@benincarburants.bj", "2027-02-01", "ACTIVE", 400_000L}};
        for (Object[] c : list) {
            long id = db.insert("""
                    \sINSERT INTO client (name, kind, country, contact_name, phone, email, created_at)
                    VALUES (:n, :k, :c, :cn, :p, :e, now() - interval '2 years')""",
                    Db.p("n", c[0], "k", c[1], "c", c[2], "cn", c[3], "p", c[4], "e", c[5]));
            clients.put((String) c[0], id);
            LocalDate end = LocalDate.parse((String) c[6]);
            // licence précédente remplacée, puis licence en cours
            db.update("""
                    \sINSERT INTO licence (client_id, start_date, end_date, amount_per_station, status)
                    VALUES (:c, :s, :e, :a, 'REMPLACEE')""",
                    Db.p("c", id, "s", end.minusYears(2), "e", end.minusYears(1), "a", c[8]));
            db.update("""
                    \sINSERT INTO licence (client_id, start_date, end_date, amount_per_station, status, terminated_at)
                    VALUES (:c, :s, :e, :a, :st, CASE WHEN :st = 'RESILIEE' THEN CAST(:e AS date) - 20 END)""",
                    Db.p("c", id, "s", end.minusYears(1), "e", end, "a", c[8], "st", c[7]));
        }
    }

    private void stationsAndEquipment() {
        int i = 0;
        for (Spec sp : STATIONS) {
            i++;
            St st = new St();
            st.spec = sp;
            boolean autonomous = "AUTONOME".equals(sp.mode());
            st.id = db.insert("""
                    \sINSERT INTO station (code, name, short_name, type, kind, client_id, country, zone, address, latitude, longitude,
                                         opened_on, mode, auto_switch, hub_ports, reader_port, reader_connected, serial_port,
                                         serial_baud, api_key_hash, last_seen_at, created_at)
                    VALUES (:code, :n, :sn, :t, 'FIXE', :c, 'Togo', :z, :a, :lat, :lon, :o, :m, :auto, 12, 'COM9', :r, 'COM3', 9600,
                            :key, :seen, now() - interval '3 years')""",
                    Db.p("code", sp.code(), "n", sp.name(), "sn", sp.shortName(), "t", sp.type(), "c", clients.get(sp.client()),
                            "z", sp.zone(), "a", sp.shortName() + ", " + ("Lomé".equals(sp.zone()) ? "Lomé" : "préfecture de " + sp.zone()),
                            "lat", BigDecimal.valueOf(sp.lat()), "lon", BigDecimal.valueOf(sp.lon()),
                            "o", LocalDate.of(2019 + i % 5, 1 + i % 12, 1 + i), "m", sp.mode(), "auto", !autonomous,
                            "r", sp.reader(), "key", sha256("demo-" + sp.code().toLowerCase()), "seen", ts(lastSeen(sp))));
            db.update("""
                    \sINSERT INTO station_mode_window (station_id, label, days, holidays, conventional_from, conventional_to) VALUES
                        (:s, 'Lundi – Vendredi', '{1,2,3,4,5}', false, '06:00', '22:00'),
                        (:s, 'Samedi', '{6}', false, '06:00', '23:00'),
                        (:s, 'Dimanche & fériés', '{7}', true, '07:00', '20:00')""", Db.p("s", st.id));
            String[][] pumps = {{"P1", "SUPER"}, {"P2", "SUPER"}, {"P3", "SUPER"}, {"P4", "SUPER"}, {"P5", "GASOIL"}, {"P6", "GASOIL"},
                    {"P7", "GASOIL"}, {"P8", "PETROLE"}};
            for (int p = 0; p < pumps.length; p++) {
                long pid = db.insert("""
                        \sINSERT INTO pump (station_id, code, port, product_id, state, index_total)
                        VALUES (:s, :c, :port, :p, 'LIBRE', :idx)""",
                        Db.p("s", st.id, "c", pumps[p][0], "port", "COM" + (p + 1), "p", products.get(pumps[p][1]),
                                "idx", BigDecimal.valueOf(150_000 + rnd.nextInt(400_000))));
                st.pumps.put(pumps[p][0], pid);
            }
            String[] fuels = {"SUPER", "GASOIL", "PETROLE"};
            boolean big = "Lomé".equals(sp.zone());
            int[] capacity = big ? new int[] {50_000, 50_000, 15_000} : new int[] {30_000, 30_000, 10_000};
            for (int t = 0; t < 3; t++) {
                long tid = db.insert("""
                        \sINSERT INTO tank (station_id, product_id, name, capacity, volume, lead_days)
                        VALUES (:s, :p, :n, :c, :v, :ld)""",
                        Db.p("s", st.id, "p", products.get(fuels[t]), "n", "Cuve " + productNames.get(fuels[t]),
                                "c", BigDecimal.valueOf(capacity[t]), "v", BigDecimal.valueOf(capacity[t] * sp.tanks()[t] / 100),
                                "ld", sp.zone().equals("Lomé") ? 2 : 3));
                st.tanks.put(fuels[t], tid);
            }
            Object[][] cfg = {{"Matin", "06:00", "14:00"}, {"Soir", "14:00", "22:00"}, {"Nuit", "22:00", "06:00"}};
            for (Object[] c : cfg) {
                long cid = db.insert("INSERT INTO shift_config (station_id, name, start_time, end_time) VALUES (:s, :n, :a, :b)",
                        Db.p("s", st.id, "n", c[0], "a", LocalTime.parse((String) c[1]), "b", LocalTime.parse((String) c[2])));
                st.configs.add(new Config(cid, (String) c[0], LocalTime.parse((String) c[1]), LocalTime.parse((String) c[2])));
            }
            // articles de la boutique et parc de bouteilles
            // quantités proportionnelles à l'activité de la station
            double size = 0.8 + sp.revenue() / 700_000.0;
            Object[][] articles = {{"HUILE_5W30_5L", 60}, {"HUILE_2T_1L", 90}, {"BRULEUR_GAZ", 45}, {"GAZ_6", 70}, {"GAZ_12_5", 90}};
            for (Object[] a : articles) {
                int q = (int) Math.round((Integer) a[1] * size) + rnd.nextInt(10);
                int cap = q * 2;
                Integer manual = null;
                if ("ST-001".equals(sp.code()) && "HUILE_2T_1L".equals(a[0])) {
                    q = 6;
                    manual = 15;
                }
                if ("ST-002".equals(sp.code()) && "GAZ_12_5".equals(a[0])) {
                    q = 8;
                    manual = 12;
                }
                db.update("""
                        \sINSERT INTO stock_level (station_id, product_id, quantity, capacity, threshold_manual, lead_days)
                        VALUES (:s, :p, :q, :c, :m, 5)""",
                        Db.p("s", st.id, "p", products.get((String) a[0]), "q", BigDecimal.valueOf(q), "c", BigDecimal.valueOf(cap),
                                "m", manual == null ? null : BigDecimal.valueOf(manual)));
            }
            for (String g : List.of("GAZ_6", "GAZ_12_5")) {
                db.update("""
                        \sINSERT INTO gas_bottle_stock (station_id, product_id, empty_count, customer_count, damaged_count)
                        VALUES (:s, :p, :e, :c, :d)""",
                        Db.p("s", st.id, "p", products.get(g), "e", 10 + rnd.nextInt(20), "c", 120 + rnd.nextInt(200), "d", rnd.nextInt(4)));
            }
            stations.add(st);
        }
        // bascules de mode des deux derniers jours (plages horaires)
        for (St st : stations) {
            for (int d = 1; d >= 0; d--) {
                LocalDate day = today.minusDays(d);
                for (Object[] sw : new Object[][] {{"AUTONOME", "CONVENTIONNEL", day.atTime(6, 0)},
                        {"CONVENTIONNEL", "AUTONOME", day.atTime(22, 0)}}) {
                    LocalDateTime at = (LocalDateTime) sw[2];
                    if (at.isBefore(now)) {
                        db.update("INSERT INTO mode_change (station_id, from_mode, to_mode, trigger, at) VALUES (:s, :f, :t, 'PLAGE', :at)",
                                Db.p("s", st.id, "f", sw[0], "t", sw[1], "at", ts(at)));
                    }
                }
            }
        }
    }

    private LocalDateTime lastSeen(Spec sp) {
        return switch (sp.net()) {
            case "ON" -> now.minusSeconds(20);
            case "UNSTABLE" -> now.minusMinutes(5);
            default -> now.minusHours(3).minusMinutes(12);
        };
    }

    // ================================================================== employés

    private void employees() {
        long admin = person("Ama", "Kpodar", "ADMIN", null);
        person("Mawuli", "Kpodar", "DAF", null);
        person("Abla", "Gbeasor", "COMPTABLE", null);
        person("Kafui", "Ekoué", "COMPTABLE", null);
        person("Essi", "Lawson", "AGENT_PDV", null);
        person("Afi Dzifa", "Adjo", "AGENT_PDV", null);
        person("Délali", "Agbo", "AGENT_PDV", null);
        person("Mawuena", "Kossi", "AGENT_PDV", null);
        person("Sélom", "Ahadji", "AGENT_PDV", null);
        person("Edem", "Akakpo", "LIVREUR", null);
        person("Koffi", "Dossou", "LIVREUR", null);
        for (St st : stations) {
            if ("ST-001".equals(st.spec.code())) {
                st.manager = person("Kossi", "Amegah", "GERANT", st.id);
                st.chiefs[0] = person("Yao", "Agbeko", "CHEF_POSTE", st.id);
                st.chiefs[1] = person("Kodjo", "Agbodjan", "CHEF_POSTE", st.id);
                st.attendants[0] = person("Komlan", "Tetteh", "POMPISTE", st.id);
                st.attendants[1] = person("Afi", "Amouzou", "POMPISTE", st.id);
                st.attendants[2] = person("Sena", "Adzoh", "POMPISTE", st.id);
            } else {
                st.manager = randomPerson("GERANT", st.id);
                st.chiefs[0] = randomPerson("CHEF_POSTE", st.id);
                st.chiefs[1] = randomPerson("CHEF_POSTE", st.id);
                for (int i = 0; i < 3; i++) {
                    st.attendants[i] = randomPerson("POMPISTE", st.id);
                }
            }
            db.update("UPDATE station SET manager_id = :m WHERE id = :s", Db.p("m", st.manager, "s", st.id));
        }
        db.update("UPDATE employee SET created_by = :a WHERE id <> :a", Db.p("a", admin));
    }

    private long randomPerson(String role, long stationId) {
        for (int i = 0; i < 200; i++) {
            String f = FIRST[rnd.nextInt(FIRST.length)];
            String l = LAST[rnd.nextInt(LAST.length)];
            if (!usedNames.contains(f + " " + l)) {
                return person(f, l, role, stationId);
            }
        }
        throw new IllegalStateException("Plus de noms disponibles");
    }

    private long person(String first, String last, String role, Long stationId) {
        usedNames.add(first + " " + last);
        String base = ascii(first).replace(' ', '.') + "." + ascii(last);
        String email = base + "@zener.tg";
        for (int n = 2; usedEmails.contains(email); n++) {
            email = base + n + "@zener.tg";
        }
        usedEmails.add(email);
        matricule++;
        long id = db.insert("""
                \sINSERT INTO employee (matricule, first_name, last_name, email, phone, password_hash, role_code, station_id,
                                      id_doc_type, id_doc_number, created_at)
                VALUES (:m, :f, :l, :e, :p, :pw, :r, :s, 'CNI', :doc, now() - make_interval(days => :age))""",
                Db.p("m", String.format("ZN-%05d", matricule), "f", first, "l", last, "e", email,
                        "p", String.format("+228 9%d %02d %02d %02d", rnd.nextInt(9), rnd.nextInt(100), rnd.nextInt(100), rnd.nextInt(100)),
                        "pw", passwordHash, "r", role, "s", stationId,
                        "doc", String.format("%04d-%03d-%04d", rnd.nextInt(10000), rnd.nextInt(1000), rnd.nextInt(10000)),
                        "age", 60 + rnd.nextInt(1500)));
        people.put(first + " " + last, id);
        return id;
    }

    // ================================================================== cartes

    private void cards() {
        long kossi = people.get("Kossi Amegah");
        long abla = people.get("Abla Gbeasor");
        long ama = people.get("Ama Kpodar");
        St agoe = station("ST-001");

        long translom = holder("ENTREPRISE", "Translom SARL", "GRAND_PUBLIC", "RCCM", "TG-LOM-2014-B-1187", "+228 22 27 45 10",
                "flotte@translom.tg", "Zone portuaire, Lomé", null);
        long komi = holder("PARTICULIER", "Komi Mensah", "GRAND_PUBLIC", "CNI", "0712-554-2201", "+228 90 44 12 87", null, "Bè, Lomé", null);
        long agro = holder("ENTREPRISE", "Agro-Plus Togo", "PARTENAIRE", "RCCM", "TG-LOM-2017-B-0412", "+228 22 51 20 30",
                "logistique@agroplus.tg", "Route de Kpalimé, Lomé", null);
        long akossiwa = holder("PARTICULIER", "Akossiwa Lawson", "GRAND_PUBLIC", "CNI", "1103-882-4410", "+228 91 08 55 23", null,
                "Adidogomé, Lomé", null);
        long btp = holder("ENTREPRISE", "BTP Kara Construction", "GRAND_PUBLIC", "RCCM", "TG-KAR-2019-B-0077", "+228 26 60 11 45",
                "direction@btpkara.tg", "Kara", null);
        long lomelog = holder("ENTREPRISE", "Lomé Logistique", "GRAND_PUBLIC", "RCCM", "TG-LOM-2012-B-0951", "+228 22 26 70 70",
                "finance@lomelogistique.tg", "Boulevard du 13 janvier, Lomé", null);
        long yawo = holder("PARTICULIER", "Yawo Agbodjan", "GRAND_PUBLIC", "CNI", "0905-310-7788", "+228 93 70 21 09", null, "Lomé", null);

        long gNord = group(translom, "Camions Nord");
        group(translom, "Camions Sud");
        long gLiv = group(agro, "Livraisons");
        long gEngins = group(btp, "Engins");
        long gNav = group(lomelog, "Navettes");
        group(lomelog, "Direction");

        DemoCard c;
        c = card("601128470093", "FLOTTE", "PUCE", translom, gNord, kossi, abla, "ACTIVE", "Translom", 1_284_500, 1_800_000, 3, true);
        c.fuels = Set.of("SUPER", "GASOIL");
        c.from = LocalTime.of(6, 0);
        c.to = LocalTime.of(20, 0);
        c.weekdaysOnly = true;
        db.update("""
                \sUPDATE card SET vehicle_plate = 'TG 4521 BK', vehicle_label = 'Camion Renault Kerax', driver_name = 'Kokou Attiogbé'
                WHERE id = :c""", Db.p("c", c.id));
        permissions(c.id, new String[] {"SUPER", "GASOIL"}, 3_000_000L, 25_000_000L, null, new Integer[] {1, 2, 3, 4, 5, 6},
                LocalTime.of(6, 0), LocalTime.of(20, 0), null, true);

        c = card("601128474410", "VIP", "M1", komi, null, kossi, abla, "ACTIVE", "Komi", 46_000, 150_000, 2, false);

        c = card("601133027782", "FLOTTE", "PUCE", agro, gLiv, kossi, abla, "ACTIVE", "AgroPlus", 42_000, 900_000, 2, true);
        c.fuels = Set.of("GASOIL");
        db.update("UPDATE card SET vehicle_plate = 'TG 7710 AH', vehicle_label = 'Isuzu NPR', driver_name = 'Elom Fiagan' WHERE id = :c",
                Db.p("c", c.id));
        permissions(c.id, new String[] {"GASOIL", "LUBRIFIANT"}, null, 15_000_000L, 200_000L, new Integer[] {1, 2, 3, 4, 5, 6, 7},
                null, null, null, true);

        c = card("601133021209", "CLASSIQUE", "M1", akossiwa, null, kossi, abla, "ACTIVE", "Akossiwa", 2_500, 40_000, 1, false);

        c = card("601122905531", "FLOTTE", "PUCE", btp, gEngins, kossi, abla, "ACTIVE", "BTP", 0, 600_000, 1, true);
        c.fuels = Set.of("GASOIL");
        c.stationCodes = Set.of("ST-010");
        c.until = today.minusDays(3);
        permissions(c.id, new String[] {"GASOIL"}, null, 1_500_000L, null, new Integer[] {1, 2, 3, 4, 5, 6, 7}, null, null,
                new Long[] {station("ST-010").id}, false);

        Long[] lome = stations.stream().filter(s -> "Lomé".equals(s.spec.zone())).map(s -> s.id).toArray(Long[]::new);
        c = card("601144170028", "FLOTTE", "PUCE", lomelog, gNav, kossi, abla, "ACTIVE", "LomeLog", 2_015_000, 2_500_000, 3, true);
        c.stationCodes = Set.of("ST-001", "ST-002", "ST-003");
        permissions(c.id, new String[] {"SUPER", "GASOIL", "LUBRIFIANT"}, 3_000_000L, null, null, new Integer[] {1, 2, 3, 4, 5, 6, 7},
                null, null, lome, true);

        // carte vendue à la Foire, désactivée
        long yawoCard = rawCard("601144179930", "CLASSIQUE", "M1");
        issuer.issue("601144179930", "CLASSIQUE", "M1", yawo, null, people.get("Délali Agbo"), PIN);
        db.update("""
                \sUPDATE card SET status = 'INACTIVE', balance = 4000, activated_by = :a, activated_at = now() - interval '4 days',
                       sold_at = now() - interval '4 days', sold_by = :d
                WHERE id = :c""", Db.p("c", yawoCard, "a", abla, "d", people.get("Délali Agbo")));
        db.update("""
                \sINSERT INTO card_movement (card_id, at, kind, amount, balance_after, detail)
                VALUES (:c, now() - interval '4 days', 'RECHARGE', 4000, 4000, 'Au guichet')""", Db.p("c", yawoCard));

        // cartes des employés (créées par le gérant, activées par la comptable)
        employeeCard("Komlan Tetteh", "EMPLOYE_STATION", kossi, abla, true, agoe);
        employeeCard("Yao Agbeko", "EMPLOYE_STATION", kossi, abla, true, agoe);
        employeeCard("Afi Amouzou", "EMPLOYE_STATION", kossi, null, false, agoe);
        employeeCard("Abla Gbeasor", "EMPLOYE_GROUPE", ama, people.get("Mawuli Kpodar"), true, null);

        // carte émise par l'Admin, en attente d'activation par une autre personne
        long koami = holder("PARTICULIER", "Koami Ayivi", "GRAND_PUBLIC", "CNI", "1207-445-9031", "+228 92 18 40 77", null, "Tokoin, Lomé", null);
        issuer.issue(null, "CLASSIQUE", "PUCE", koami, null, ama, PIN);
        long partner = holder("ENTREPRISE", "Société Togolaise des Eaux", "PARTENAIRE", "RCCM", "TG-LOM-2003-B-0001", "+228 22 21 34 81",
                "parc@tde.tg", "Lomé", null);
        issuer.issue(null, "FLOTTE", "PUCE", partner, null, kossi, PIN);

        // stock de cartes vierges
        issuer.createBatch("CLASSIQUE", "M1", 600, ama);
        issuer.createBatch("VIP", "M1", 150, ama);
        issuer.createBatch("CLASSIQUE", "PUCE", 200, ama);
        db.update("UPDATE card_batch SET created_at = now() - interval '20 days'", Map.of());
    }

    private long holder(String kind, String name, String profile, String docType, String doc, String phone, String email,
                        String address, Long employeeId) {
        return db.insert("""
                \sINSERT INTO holder (kind, name, profile, id_doc_type, id_doc_number, phone, email, address, employee_id,
                                    created_at)
                VALUES (:k, :n, :p, :dt, :d, :ph, :e, :a, :emp, now() - make_interval(days => :age))""",
                Db.p("k", kind, "n", name, "p", profile, "dt", docType, "d", doc, "ph", phone, "e", email, "a", address,
                        "emp", employeeId, "age", 30 + rnd.nextInt(700)));
    }

    private long group(long holder, String name) {
        return db.insert("INSERT INTO card_group (holder_id, name) VALUES (:h, :n)", Db.p("h", holder, "n", name));
    }

    private long rawCard(String number, String type, String techno) {
        return db.insert("INSERT INTO card (number, type_code, techno, status, created_at) VALUES (:n, :t, :te, 'STOCK', now() - interval '400 days')",
                Db.p("n", number, "t", type, "te", techno));
    }

    private DemoCard card(String number, String type, String techno, long holder, Long group, long issuedBy, Long activatedBy,
                          String status, String key, long balance, long cap, int weight, boolean fleet) {
        long id = rawCard(number, type, techno);
        issuer.issue(number, type, techno, holder, group, issuedBy, PIN);
        if (activatedBy != null) {
            db.update("""
                    \sUPDATE card SET status = :st, activated_by = :a, activated_at = now() - make_interval(days => :d),
                           created_at = now() - make_interval(days => :d + 2)
                    WHERE id = :c""", Db.p("c", id, "st", status, "a", activatedBy, "d", 60 + rnd.nextInt(300)));
        }
        DemoCard c = new DemoCard();
        c.id = id;
        c.key = key;
        c.profile = db.value("SELECT profile FROM holder WHERE id = :h", Db.p("h", holder), String.class);
        c.finalBalance = balance;
        c.cap = cap;
        c.weight = weight;
        c.fleet = fleet;
        demoCards.add(c);
        cardIds.put(key, id);
        return c;
    }

    private void employeeCard(String name, String profile, long issuedBy, Long activatedBy, boolean pays, St st) {
        long emp = people.get(name);
        long holder = holder("EMPLOYE", name, profile, "CNI", String.format("%04d-%03d-%04d", rnd.nextInt(10000), rnd.nextInt(1000),
                rnd.nextInt(10000)), null, null, null, emp);
        long id = issuer.issue(null, "EMPLOYE", "PUCE", holder, null, issuedBy, PIN);
        if (activatedBy != null) {
            db.update("UPDATE card SET status = 'ACTIVE', activated_by = :a, activated_at = now() - interval '45 days' WHERE id = :c",
                    Db.p("c", id, "a", activatedBy));
        }
        if (pays && st != null) {
            DemoCard c = new DemoCard();
            c.id = id;
            c.key = name;
            c.profile = profile;
            c.finalBalance = 8_000 + rnd.nextInt(20) * 1000;
            c.cap = 30_000;
            c.weight = 1;
            c.stationCodes = Set.of(st.spec.code());
            demoCards.add(c);
        }
        cardIds.put(name, id);
    }

    private void permissions(long cardId, String[] products, Long daily, Long monthly, Long perTx, Integer[] days, LocalTime from,
                             LocalTime to, Long[] stationIds, boolean autonomous) {
        db.update("""
                \sUPDATE card_permission SET products = :p, daily_limit = :d, monthly_limit = :m, per_tx_limit = :t, days = :days,
                       time_from = :f, time_to = :to, station_ids = :s, autonomous_allowed = :a
                WHERE card_id = :c""",
                Db.p("c", cardId, "p", db.array("text", products), "d", daily, "m", monthly, "t", perTx,
                        "days", db.array("smallint", days), "f", from, "to", to,
                        "s", stationIds == null ? null : db.array("bigint", stationIds), "a", autonomous));
    }

    // ================================================================== points de vente

    private void pdv() {
        long ama = people.get("Ama Kpodar");
        long daf = people.get("Mawuli Kpodar");
        db.sum("SELECT setval('ref_validation_seq', 1170)", Map.of());
        long siege = db.insert("""
                \sINSERT INTO pdv (code, name, kind, address, latitude, longitude, status, cards_planned, requested_by, last_sync_at, created_at)
                VALUES (:c, 'Siège Lomé', 'PERMANENT', 'Siège ZENER, boulevard du Mono, Lomé', 6.1300, 1.2200, 'OUVERT', NULL, :u, now(),
                        now() - interval '2 years')""", Db.p("c", refs.pdv(), "u", ama));
        pdvs.put("Siège Lomé", siege);
        agent(siege, "Essi Lawson", "SUPERVISEUR");
        agent(siege, "Afi Dzifa Adjo", "AGENT");

        long foire = temporaryPdv("Foire Internationale de Lomé", "Centre des expositions, Lomé", 6.1374, 1.2512, today.minusDays(5),
                today.plusDays(9), 500, "Foire annuelle : vente de cartes au stand ZENER", "Essi Lawson", daf);
        agent(foire, "Délali Agbo", "SUPERVISEUR");
        agent(foire, "Mawuena Kossi", "AGENT");
        long stand = temporaryPdv("Stand Agro-Plus", "Siège Agro-Plus Togo, Lomé", 6.1712, 1.2058, today.minusDays(2), today.plusDays(1),
                80, "Campagne partenaire : cartes pour les employés d'Agro-Plus", "Essi Lawson", ama);
        agent(stand, "Sélom Ahadji", "AGENT");

        allocate(siege, "CLASSIQUE", 60, 20);
        allocate(siege, "VIP", 20, 20);
        allocate(foire, "CLASSIQUE", 260, 5);
        allocate(foire, "VIP", 40, 5);
        allocate(stand, "CLASSIQUE", 60, 2);
    }

    private long temporaryPdv(String name, String address, double lat, double lon, LocalDate from, LocalDate to, int planned,
                              String reason, String requester, long decider) {
        long req = people.get(requester);
        long id = db.insert("""
                \sINSERT INTO pdv (code, name, kind, address, latitude, longitude, start_date, end_date, status, cards_planned, reason,
                                 requested_by, last_sync_at, created_at)
                VALUES (:c, :n, 'TEMPORAIRE', :a, :lat, :lon, :sd, :ed, 'DEMANDE', :cp, :r, :u, now(), :created)""",
                Db.p("c", refs.pdv(), "n", name, "a", address, "lat", BigDecimal.valueOf(lat), "lon", BigDecimal.valueOf(lon),
                        "sd", from, "ed", to, "cp", planned, "r", reason, "u", req, "created", ts(from.minusDays(6).atTime(10, 15))));
        long v = validations.create(new ValidationService.NewRequest(ValidationService.OUVERTURE_PDV, name,
                "Ouverture d'un point de vente temporaire · " + from + " → " + to, null, false, reason, null, id), users.load(req));
        db.update("UPDATE pdv SET validation_id = :v WHERE id = :id", Db.p("id", id, "v", v));
        validations.decide(v, true, "Accordé", users.load(decider));
        LocalDateTime asked = from.minusDays(6).atTime(10, 15);
        db.update("UPDATE validation_request SET requested_at = :a, decided_at = :d WHERE id = :v",
                Db.p("v", v, "a", ts(asked), "d", ts(asked.plusDays(1))));
        db.update("UPDATE validation_step SET decided_at = :d WHERE request_id = :v", Db.p("v", v, "d", ts(asked.plusDays(1))));
        pdvs.put(name, id);
        return id;
    }

    private void agent(long pdv, String name, String role) {
        db.update("INSERT INTO pdv_agent (pdv_id, employee_id, role) VALUES (:p, :e, :r)", Db.p("p", pdv, "e", people.get(name), "r", role));
    }

    private void allocate(long pdv, String type, int quantity, int daysAgo) {
        List<Map<String, Object>> list = db.list("""
                \sSELECT id FROM card WHERE type_code = :t AND status = 'STOCK' AND batch_id IS NOT NULL ORDER BY number LIMIT :q""",
                Db.p("t", type, "q", quantity));
        Object[] ids = list.stream().map(m -> m.get("id")).toArray();
        db.update("UPDATE card SET status = 'PDV', pdv_id = :p WHERE id = ANY(:ids)", Db.p("p", pdv, "ids", db.array("bigint", ids)));
        db.update("""
                \sINSERT INTO pdv_card (pdv_id, card_id, allocated_at) SELECT :p, unnest(CAST(:ids AS bigint[])), :at""",
                Db.p("p", pdv, "ids", db.array("bigint", ids), "at", ts(today.minusDays(daysAgo).atTime(8, 30))));
    }

    // ================================================================== historique : postes, ventes, recharges

    private void history() {
        List<Sale> sales = new ArrayList<>();
        createShifts();
        for (St st : stations) {
            for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
                if ("OFF".equals(st.spec.net()) && d.equals(today)) {
                    continue; // station hors ligne : rien de remonté aujourd'hui
                }
                sales.addAll(stationDay(st, d));
            }
        }
        sales.addAll(pdvSales());
        sales.sort(Comparator.comparing(s -> s.at));
        List<Map<String, Object>> ids = db.list("SELECT nextval('sale_id_seq') AS id FROM generate_series(1, :n)",
                Db.p("n", sales.size()));
        for (int i = 0; i < sales.size(); i++) {
            sales.get(i).id = num(ids.get(i).get("id"));
        }
        insertSales(sales);
        cardMovements(sales);
        stockMovements(sales);
        closeShifts();
        journal(sales);
        db.update("""
                \sUPDATE pump p SET index_total = p.index_total + COALESCE((SELECT sum(sl.quantity) FROM sale x
                    JOIN sale_line sl ON sl.sale_id = x.id WHERE x.pump_id = p.id), 0)""", Map.of());
        log.info("Données de démonstration : {} ventes", sales.size());
    }

    private void createShifts() {
        for (St st : stations) {
            for (LocalDate d = start.minusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
                for (Config c : st.configs) {
                    LocalDateTime from = d.atTime(c.from());
                    LocalDateTime to = c.to().isAfter(c.from()) ? d.atTime(c.to()) : d.plusDays(1).atTime(c.to());
                    if (!from.isBefore(now)) {
                        continue;
                    }
                    if ("OFF".equals(st.spec.net()) && to.isAfter(now.minusHours(4))) {
                        continue;
                    }
                    boolean closed = !to.isAfter(now);
                    List<Long> team = new ArrayList<>();
                    switch (c.name()) {
                        case "Matin" -> {
                            team.add(st.chiefs[0]);
                            team.add(st.attendants[0]);
                        }
                        case "Soir" -> {
                            team.add(st.chiefs[1]);
                            team.add(st.attendants[1]);
                        }
                        default -> team.add(st.attendants[2]);
                    }
                    for (long emp : team) {
                        boolean chief = emp == st.chiefs[0] || emp == st.chiefs[1];
                        long id = db.insert("""
                                \sINSERT INTO employee_shift (employee_id, station_id, config_id, pumps, status, started_at, ended_at)
                                VALUES (:e, :s, :c, :p, :st, :a, :b)""",
                                Db.p("e", emp, "s", st.id, "c", c.id(), "p", chief ? null : "P1, P2, P3, P4, P5, P6, P7, P8",
                                        "st", closed ? "CLOS" : "EN_POSTE", "a", ts(from.minusMinutes(5 + rnd.nextInt(10))),
                                        "b", closed ? ts(to.plusMinutes(rnd.nextInt(20))) : null));
                        shiftIds.put(st.id + "|" + d + "|" + c.name() + "|" + emp, id);
                    }
                }
            }
        }
    }

    /** Mode prévu par les plages par défaut (lun–ven 06–22, sam 06–23, dim et fériés 07–20). */
    private String modeAt(LocalDateTime t) {
        int h = t.getHour();
        DayOfWeek d = t.getDayOfWeek();
        boolean conv;
        if (d == DayOfWeek.SUNDAY || holidays.contains(t.toLocalDate())) {
            conv = h >= 7 && h < 20;
        } else if (d == DayOfWeek.SATURDAY) {
            conv = h >= 6 && h < 23;
        } else {
            conv = h >= 6 && h < 22;
        }
        return conv ? "CONVENTIONNEL" : "AUTONOME";
    }

    static final double[] HOURS = {0.15, 0.08, 0.06, 0.06, 0.15, 0.6, 1.5, 2.5, 2.6, 2.2, 1.9, 1.9, 2.2, 2.0, 1.8, 1.8, 2.1, 2.6,
            2.8, 2.4, 1.6, 1.1, 0.5, 0.3};
    static final int[] LITERS = {3, 5, 8, 10, 15, 20, 25, 30, 40, 50, 60, 80};
    static final int[] LITERS_W = {6, 9, 7, 12, 12, 13, 10, 9, 7, 5, 3, 2};

    private List<Sale> stationDay(St st, LocalDate d) {
        List<Sale> out = new ArrayList<>();
        int index = (int) java.time.temporal.ChronoUnit.DAYS.between(start, d);
        double factor = (0.92 + rnd.nextDouble() * 0.16) * (1 + 0.006 * index)
                * (d.getDayOfWeek() == DayOfWeek.SUNDAY ? 0.78 : d.getDayOfWeek() == DayOfWeek.SATURDAY ? 1.08 : 1.0);
        if ("ST-002".equals(st.spec.code())) {
            factor *= 1 + 0.02 * index; // consommation de gasoil en hausse : seuil ajusté à la hausse
        }
        long target = Math.round(st.spec.revenue() * 1.7 * factor);
        long sum = 0;
        while (sum < target) {
            LocalDateTime at = d.atTime(pick(HOURS), rnd.nextInt(60), rnd.nextInt(60));
            Sale s = rnd.nextDouble() < 0.9 ? fuelSale(st, at) : shopSale(st, at);
            if (s == null) {
                continue;
            }
            sum += s.total;
            if (at.isBefore(now.minusSeconds(30))) {
                out.add(s);
            }
        }
        return out;
    }

    private Sale fuelSale(St st, LocalDateTime at) {
        Sale s = new Sale();
        s.at = at;
        s.st = st;
        s.kind = "CARBURANT";
        s.channel = "POMPE";
        s.mode = modeAt(at);
        double r = rnd.nextDouble();
        String fuel = r < 0.58 ? "SUPER" : r < 0.95 ? "GASOIL" : "PETROLE";
        DemoCard card = null;
        if ("AUTONOME".equals(s.mode) || rnd.nextDouble() < 0.07) {
            card = eligibleCard(st, fuel, at);
            if (card == null) {
                if ("AUTONOME".equals(s.mode)) {
                    return null; // en autonome, paiement par carte uniquement
                }
            }
        }
        BigDecimal liters;
        if (card != null && card.fleet) {
            liters = BigDecimal.valueOf(40 + rnd.nextInt(9) * 10 + rnd.nextInt(10) / 10.0);
        } else {
            liters = BigDecimal.valueOf(LITERS[pick(LITERS_W)] + rnd.nextInt(10) / 10.0);
        }
        liters = liters.setScale(2, RoundingMode.HALF_UP);
        String pump = switch (fuel) {
            case "SUPER" -> "P" + (1 + rnd.nextInt(4));
            case "GASOIL" -> "P" + (5 + rnd.nextInt(3));
            default -> "P8";
        };
        s.pumpCode = pump;
        s.pumpId = st.pumps.get(pump);
        if (card != null) {
            s.card = card;
            s.cardId = card.id;
            s.profile = card.profile;
            s.payment = "CARTE";
        } else {
            double p = rnd.nextDouble();
            s.payment = p < 0.70 ? "ESPECES" : "MOBILE_MONEY";
            if ("MOBILE_MONEY".equals(s.payment)) {
                s.paymentRef = (rnd.nextBoolean() ? "TMoney " : "Flooz ") + (100_000_000 + rnd.nextInt(899_999_999));
            }
        }
        addLine(s, fuel, liters, false);
        if (card != null && s.total > card.cap / 2) {
            return null;
        }
        assignStaff(s);
        return s;
    }

    private Sale shopSale(St st, LocalDateTime at) {
        if (!"CONVENTIONNEL".equals(modeAt(at))) {
            return null; // boutique fermée la nuit
        }
        Sale s = new Sale();
        s.at = at;
        s.st = st;
        s.mode = "CONVENTIONNEL";
        s.channel = "BOUTIQUE";
        s.payment = rnd.nextDouble() < 0.75 ? "ESPECES" : "MOBILE_MONEY";
        if ("MOBILE_MONEY".equals(s.payment)) {
            s.paymentRef = "TMoney " + (100_000_000 + rnd.nextInt(899_999_999));
        }
        if (rnd.nextDouble() < 0.3) {
            s.kind = "ECHANGE_GAZ";
            addLine(s, rnd.nextBoolean() ? "GAZ_6" : "GAZ_12_5", BigDecimal.ONE, false);
        } else {
            s.kind = "BOUTIQUE";
            String[] items = {"HUILE_5W30_5L", "HUILE_2T_1L", "HUILE_2T_1L", "HUILE_2T_1L", "BRULEUR_GAZ", "GAZ_6", "GAZ_12_5"};
            String code = items[rnd.nextInt(items.length)];
            addLine(s, code, BigDecimal.valueOf(1 + (rnd.nextDouble() < 0.2 ? 1 : 0)), code.startsWith("GAZ"));
        }
        assignStaff(s);
        return s;
    }

    private void addLine(Sale s, String code, BigDecimal q, boolean deposit) {
        Line l = new Line();
        l.productId = products.get(code);
        l.productName = productNames.get(code);
        l.category = code.startsWith("GAZ") ? "GAZ" : code.startsWith("HUILE") ? "LUBRIFIANT" : code.startsWith("CARTE") ? "CARTE"
                : "BRULEUR_GAZ".equals(code) ? "ACCESSOIRE" : "CARBURANT";
        Long price = prices.get(code + "|" + s.profile);
        l.unit = (price == null ? prices.get(code + "|GRAND_PUBLIC") : price)
                + (deposit ? ("GAZ_6".equals(code) ? 10_000 : 15_000) : 0);
        l.quantity = q;
        l.amount = q.multiply(BigDecimal.valueOf(l.unit)).setScale(0, RoundingMode.HALF_UP).longValue();
        s.lines.add(l);
        s.total += l.amount;
    }

    /** Pompiste (ou chef de poste) du poste en cours, en mode conventionnel. */
    private void assignStaff(Sale s) {
        if (!"CONVENTIONNEL".equals(s.mode)) {
            return;
        }
        int h = s.at.getHour();
        LocalDate day = s.at.toLocalDate();
        String cfg;
        long emp;
        if (h >= 6 && h < 14) {
            cfg = "Matin";
            emp = rnd.nextDouble() < 0.9 ? s.st.attendants[0] : s.st.chiefs[0];
        } else if (h >= 14 && h < 22) {
            cfg = "Soir";
            emp = rnd.nextDouble() < 0.65 ? s.st.attendants[1] : s.st.chiefs[1];
        } else {
            cfg = "Nuit";
            emp = s.st.attendants[2];
            if (h < 6) {
                day = day.minusDays(1);
            }
        }
        Long shift = shiftIds.get(s.st.id + "|" + day + "|" + cfg + "|" + emp);
        if (shift != null) {
            s.employee = emp;
            s.shift = shift;
            if ("ESPECES".equals(s.payment)) {
                shiftCash.merge(shift, s.total, Long::sum);
            }
        }
    }

    private DemoCard eligibleCard(St st, String fuel, LocalDateTime at) {
        List<DemoCard> ok = new ArrayList<>();
        int total = 0;
        for (DemoCard c : demoCards) {
            if (c.fuels != null && !c.fuels.contains(fuel)) {
                continue;
            }
            if (c.stationCodes != null && !c.stationCodes.contains(st.spec.code())) {
                continue;
            }
            if (c.from != null && (at.toLocalTime().isBefore(c.from) || !at.toLocalTime().isBefore(c.to))) {
                continue;
            }
            if (c.weekdaysOnly && at.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            if (c.until != null && at.toLocalDate().isAfter(c.until)) {
                continue;
            }
            ok.add(c);
            total += c.weight;
        }
        if (ok.isEmpty()) {
            return null;
        }
        int r = rnd.nextInt(total);
        for (DemoCard c : ok) {
            r -= c.weight;
            if (r < 0) {
                return c;
            }
        }
        return ok.get(0);
    }

    /** Ventes de cartes neuves dans les points de vente (pièce d'identité, espèces ou mobile money). */
    private List<Sale> pdvSales() {
        List<Sale> out = new ArrayList<>();
        Object[][] plan = {{"Siège Lomé", 14, 3, "Essi Lawson", "Afi Dzifa Adjo"}, {"Foire Internationale de Lomé", 6, 28, "Délali Agbo",
                "Mawuena Kossi"}, {"Stand Agro-Plus", 3, 12, "Sélom Ahadji", "Sélom Ahadji"}};
        long abla = people.get("Abla Gbeasor");
        long kafui = people.get("Kafui Ekoué");
        for (Object[] p : plan) {
            long pdv = pdvs.get((String) p[0]);
            List<Map<String, Object>> available = db.list("""
                    \sSELECT c.id, c.number, c.type_code FROM card c WHERE c.pdv_id = :p AND c.status = 'PDV' ORDER BY c.number""",
                    Db.p("p", pdv));
            int next = 0;
            for (int d = (Integer) p[1] - 1; d >= 0 && next < available.size(); d--) {
                LocalDate day = today.minusDays(d);
                int n = Math.max(1, (Integer) p[2] + rnd.nextInt(7) - 3);
                for (int k = 0; k < n && next < available.size(); k++) {
                    LocalDateTime at = day.atTime(9 + rnd.nextInt(9), rnd.nextInt(60), rnd.nextInt(60));
                    if (!at.isBefore(now)) {
                        continue;
                    }
                    Map<String, Object> card = available.get(next++);
                    long agent = people.get((String) p[rnd.nextBoolean() ? 3 : 4]);
                    String name = FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)];
                    long holder = holder("PARTICULIER", name, "GRAND_PUBLIC", "CNI", String.format("%04d-%03d-%04d",
                            rnd.nextInt(10000), rnd.nextInt(1000), rnd.nextInt(10000)),
                            String.format("+228 9%d %02d %02d %02d", rnd.nextInt(9), rnd.nextInt(100), rnd.nextInt(100), rnd.nextInt(100)),
                            null, null, null);
                    boolean recent = at.isAfter(now.minusHours(20));
                    db.update("""
                            \sUPDATE card SET status = :st, holder_id = :h, pdv_id = NULL, issued_by = :a, sold_by = :a, sold_pdv_id = :p,
                                   sold_at = :at, pin_hash = :pin, expires_on = CAST(:at AS date) + interval '3 years',
                                   activated_by = CAST(:act AS bigint),
                                   activated_at = CASE WHEN CAST(:act AS bigint) IS NULL THEN NULL ELSE CAST(:at AS timestamptz) + interval '3 hours' END
                            WHERE id = :id""",
                            Db.p("id", card.get("id"), "st", recent ? "A_ACTIVER" : "ACTIVE", "h", holder, "a", agent, "p", pdv,
                                    "at", ts(at), "pin", pinHash, "act", recent ? null : rnd.nextBoolean() ? abla : kafui));
                    db.update("INSERT INTO card_permission (card_id, updated_by) VALUES (:c, :a) ON CONFLICT DO NOTHING",
                            Db.p("c", card.get("id"), "a", agent));
                    db.update("UPDATE pdv_card SET sold_at = :at WHERE pdv_id = :p AND card_id = :c",
                            Db.p("p", pdv, "c", card.get("id"), "at", ts(at)));
                    Sale s = new Sale();
                    s.at = at;
                    s.kind = "CARTE";
                    s.channel = "PDV";
                    s.pdvId = pdv;
                    s.employee = agent;
                    s.payment = rnd.nextDouble() < 0.6 ? "ESPECES" : "MOBILE_MONEY";
                    if ("MOBILE_MONEY".equals(s.payment)) {
                        s.paymentRef = "TMoney " + (100_000_000 + rnd.nextInt(899_999_999));
                    }
                    addLine(s, "CARTE_" + card.get("typeCode"), BigDecimal.ONE, false);
                    s.cardId = num(card.get("id"));
                    s.pumpCode = (String) card.get("number"); // pour le journal
                    out.add(s);
                }
            }
        }
        return out;
    }

    private void insertSales(List<Sale> sales) {
        List<SqlParameterSource> rows = new ArrayList<>();
        List<SqlParameterSource> lines = new ArrayList<>();
        for (Sale s : sales) {
            boolean cardSale = "CARTE".equals(s.kind);
            rows.add(new MapSqlParameterSource(Db.p("id", s.id, "u", new UUID(rnd.nextLong(), rnd.nextLong()), "at", ts(s.at),
                    "k", s.kind, "ch", s.channel, "s", s.st == null ? null : s.st.id, "pv", s.pdvId, "pump", s.pumpId, "mode", s.mode,
                    "e", s.employee, "sh", s.shift, "pm", s.payment, "pr", s.paymentRef,
                    "card", cardSale || s.card == null ? null : s.cardId, "prof", s.profile, "t", s.total)));
            for (Line l : s.lines) {
                lines.add(new MapSqlParameterSource(Db.p("s", s.id, "p", l.productId, "q", l.quantity, "u", l.unit, "a", l.amount,
                        "c", cardSale ? s.cardId : null)));
            }
        }
        db.jdbc().batchUpdate("""
                \sINSERT INTO sale (id, uuid, at, kind, channel, station_id, pdv_id, pump_id, mode, employee_id, employee_shift_id,
                                  payment_method, payment_ref, card_id, buyer_profile, total)
                VALUES (:id, :u, :at, :k, :ch, :s, :pv, :pump, :mode, :e, :sh, :pm, :pr, :card, :prof, :t)""",
                rows.toArray(SqlParameterSource[]::new));
        db.jdbc().batchUpdate("""
                \sINSERT INTO sale_line (sale_id, product_id, quantity, unit_price, amount, card_id) VALUES (:s, :p, :q, :u, :a, :c)""",
                lines.toArray(SqlParameterSource[]::new));
    }

    /**
     * Mouvements des cartes : on part du solde final voulu et on remonte le temps ; quand le solde
     * dépasserait le plafond habituel de la carte, une recharge a eu lieu juste avant la vente.
     */
    private void cardMovements(List<Sale> sales) {
        Map<Long, List<Sale>> byCard = new HashMap<>();
        for (Sale s : sales) {
            if (s.card != null) {
                byCard.computeIfAbsent(s.card.id, k -> new ArrayList<>()).add(s);
            }
        }
        List<SqlParameterSource> moves = new ArrayList<>();
        for (DemoCard c : demoCards) {
            List<Sale> list = byCard.getOrDefault(c.id, List.of());
            List<Event> events = new ArrayList<>();
            long balance = c.finalBalance;
            for (int i = list.size() - 1; i >= 0; i--) {
                Sale s = list.get(i);
                events.add(new Event(s.at, true, s.total, s.id, s.st.id));
                balance += s.total;
                if (balance > c.cap) {
                    long rest = (long) (rnd.nextDouble() * c.cap * 0.1);
                    long amount = (balance - rest) / 1000 * 1000;
                    events.add(new Event(s.at.minusMinutes(3 + rnd.nextInt(600)), false, amount, null,
                            c.fleet ? null : s.st.id));
                    balance -= amount;
                }
            }
            if (balance > 0) {
                events.add(new Event(start.minusDays(2).atTime(9, 30), false, balance, null, null));
            }
            events.sort(Comparator.comparing(Event::at));
            long b = 0;
            for (Event e : events) {
                Long rechargeId = null;
                if (e.sale()) {
                    b -= e.amount();
                } else {
                    b += e.amount();
                    rechargeId = db.insert("""
                            \sINSERT INTO recharge (ref, card_id, amount, channel, payment_method, status, station_id, requested_by,
                                                  created_at, credited_at)
                            VALUES (:ref, :c, :a, :ch, :pm, 'CREDITEE', :s, :u, :at, :at)""",
                            Db.p("ref", refs.recharge(), "c", c.id, "a", e.amount(), "ch", c.fleet ? "DISTANCE" : "UNITAIRE",
                                    "pm", c.fleet ? "VIREMENT" : rnd.nextBoolean() ? "ESPECES" : "MOBILE_MONEY", "s", e.stationId(),
                                    "u", people.get("Kossi Amegah"), "at", ts(e.at())));
                }
                moves.add(new MapSqlParameterSource(Db.p("c", c.id, "at", ts(e.at()), "k", e.sale() ? "VENTE" : "RECHARGE",
                        "a", e.sale() ? -e.amount() : e.amount(), "b", b, "s", e.stationId(), "sale", e.saleId(), "r", rechargeId,
                        "d", e.sale() ? "Vente carburant" : c.fleet ? "À distance" : "Au guichet")));
            }
            db.update("UPDATE card SET balance = :b WHERE id = :c", Db.p("c", c.id, "b", b));
        }
        db.jdbc().batchUpdate("""
                \sINSERT INTO card_movement (card_id, at, kind, amount, balance_after, station_id, sale_id, recharge_id, detail)
                VALUES (:c, :at, :k, :a, :b, :s, :sale, :r, :d)""", moves.toArray(SqlParameterSource[]::new));
    }

    /** Sorties de stock agrégées par jour (cuves et articles) : base du calcul des seuils automatiques. */
    private void stockMovements(List<Sale> sales) {
        Map<String, BigDecimal> agg = new LinkedHashMap<>();
        for (Sale s : sales) {
            if (s.st == null) {
                continue;
            }
            for (Line l : s.lines) {
                String key = s.st.id + "|" + l.productId + "|" + l.category + "|" + s.at.toLocalDate() + "|" + s.kind;
                agg.merge(key, l.quantity, BigDecimal::add);
            }
        }
        Map<Long, St> byId = new HashMap<>();
        stations.forEach(st -> byId.put(st.id, st));
        Map<Long, String> codeById = new HashMap<>();
        products.forEach((k, v) -> codeById.put(v, k));
        List<SqlParameterSource> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : agg.entrySet()) {
            String[] k = e.getKey().split("\\|");
            St st = byId.get(Long.parseLong(k[0]));
            long product = Long.parseLong(k[1]);
            LocalDate day = LocalDate.parse(k[3]);
            boolean fuel = "CARBURANT".equals(k[2]);
            rows.add(new MapSqlParameterSource(Db.p("s", st.id, "p", product, "t", fuel ? st.tanks.get(codeById.get(product)) : null,
                    "q", e.getValue().negate(), "k", "ECHANGE_GAZ".equals(k[4]) ? "ECHANGE" : "VENTE", "r", "Ventes du " + day,
                    "at", ts(day.equals(today) ? now.minusMinutes(1) : day.atTime(23, 30)))));
        }
        // livraisons de carburant tous les 4 jours environ
        for (St st : stations) {
            for (String fuel : List.of("SUPER", "GASOIL")) {
                for (LocalDate d = start.plusDays(rnd.nextInt(3)); d.isBefore(today); d = d.plusDays(4 + rnd.nextInt(2))) {
                    rows.add(new MapSqlParameterSource(Db.p("s", st.id, "p", products.get(fuel), "t", st.tanks.get(fuel),
                            "q", BigDecimal.valueOf(10_000 + rnd.nextInt(3) * 2_000), "k", "ENTREE", "r", "BL-" + (40_000 + rnd.nextInt(9_999)),
                            "at", ts(d.atTime(7, 15)))));
                }
            }
        }
        db.jdbc().batchUpdate("""
                \sINSERT INTO stock_movement (station_id, product_id, tank_id, quantity, kind, ref, at)
                VALUES (:s, :p, :t, :q, :k, :r, :at)""", rows.toArray(SqlParameterSource[]::new));
    }

    /** Clôture des postes terminés : remises partielles puis solde ; quelques écarts de caisse. */
    private void closeShifts() {
        for (Map<String, Object> sh : db.list("""
                \sSELECT es.id, es.employee_id, es.station_id, es.started_at, es.ended_at, es.status, e.role_code
                FROM employee_shift es JOIN employee e ON e.id = es.employee_id""")) {
            long id = num(sh.get("id"));
            long cash = shiftCash.getOrDefault(id, 0L);
            boolean closed = "CLOS".equals(sh.get("status"));
            St st = stations.stream().filter(s -> s.id == num(sh.get("stationId"))).findFirst().orElseThrow();
            long receiver = "CHEF_POSTE".equals(sh.get("roleCode")) ? st.manager
                    : num(sh.get("employeeId")) == st.attendants[1] ? st.chiefs[1] : st.chiefs[0];
            LocalDateTime from = LocalDateTime.ofInstant((java.time.Instant) sh.get("startedAt"), java.time.ZoneId.systemDefault());
            LocalDateTime to = closed ? LocalDateTime.ofInstant((java.time.Instant) sh.get("endedAt"), java.time.ZoneId.systemDefault())
                    : now;
            long gap = 0;
            if (closed && cash > 0 && rnd.nextDouble() < 0.08) {
                gap = Math.min(cash, (2 + rnd.nextInt(24)) * 500L);
            }
            long remitted = 0;
            if (cash > 0) {
                long partial = cash / 2 / 1000 * 1000;
                if (partial > 0) {
                    remittance(id, partial, receiver, from.plusSeconds(java.time.Duration.between(from, to).getSeconds() / 2),
                            "Remise partielle");
                    remitted += partial;
                }
                if (closed && cash - gap - partial > 0) {
                    remittance(id, cash - gap - partial, receiver, to, "Remise à la clôture");
                    remitted += cash - gap - partial;
                }
            }
            db.update("""
                    \sUPDATE employee_shift SET remitted_cash = :r, expected_cash = CASE WHEN status = 'CLOS' THEN :e END,
                           closed_by = CASE WHEN status = 'CLOS' THEN :u END,
                           note = CASE WHEN :gap > 0 THEN 'Écart constaté à la clôture' END
                    WHERE id = :id""", Db.p("id", id, "r", remitted, "e", cash, "u", receiver, "gap", gap));
        }
    }

    private void remittance(long shift, long amount, long receiver, LocalDateTime at, String note) {
        db.update("INSERT INTO shift_remittance (shift_id, amount, received_by, note, at) VALUES (:s, :a, :u, :n, :at)",
                Db.p("s", shift, "a", amount, "u", receiver, "n", note, "at", ts(at)));
    }

    /** Journal : les opérations des dernières 36 heures (les plus récentes sont dans le flux en direct). */
    private void journal(List<Sale> sales) {
        List<SqlParameterSource> rows = new ArrayList<>();
        LocalDateTime from = now.minusHours(36);
        for (Sale s : sales) {
            if (s.at.isBefore(from)) {
                continue;
            }
            Line first = s.lines.get(0);
            String payment = s.card != null ? "carte ···" + cardSuffix(s.cardId) : "ESPECES".equals(s.payment) ? "espèces" : "mobile money";
            String type;
            String category;
            String title;
            String detail;
            switch (s.kind) {
                case "CARBURANT" -> {
                    type = "VENTE_CARBURANT";
                    category = "VENTES_CARBURANT";
                    title = "Vente carburant · " + first.quantity.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " L";
                    detail = "Pompe " + s.pumpCode.substring(1) + " · " + first.productName + " · " + payment;
                }
                case "CARTE" -> {
                    type = "VENTE_CARTE";
                    category = "VENTES_CARTES";
                    title = "Vente " + first.productName.toLowerCase().replace("carte vip", "carte VIP");
                    detail = "···" + s.pumpCode.substring(8) + " · pièce d'identité vérifiée · " + payment;
                }
                case "ECHANGE_GAZ" -> {
                    type = "ECHANGE_GAZ";
                    category = "VENTES_BOUTIQUE";
                    title = "Échange " + first.productName.replace("Gaz butane ", "gaz ");
                    detail = payment;
                }
                default -> {
                    type = "VENTE_BOUTIQUE";
                    category = "VENTES_BOUTIQUE";
                    title = "Vente boutique · " + first.quantity.stripTrailingZeros().toPlainString() + " × " + first.productName;
                    detail = payment;
                }
            }
            rows.add(new MapSqlParameterSource(Db.p("at", ts(s.at), "a", s.employee, "s", s.st == null ? null : s.st.id, "p", s.pdvId,
                    "t", type, "c", category, "ti", title, "d", detail, "m", s.total)));
        }
        for (St st : stations) {
            rows.add(new MapSqlParameterSource(Db.p("at", ts(lastSeen(st.spec).minusMinutes(1)), "a", null, "s", st.id, "p", null,
                    "t", "CONNEXION_STATION", "c", "SYSTEME", "ti", "OFF".equals(st.spec.net()) ? "Station hors ligne" : "Station en ligne",
                    "d", st.spec.shortName(), "m", null)));
        }
        db.jdbc().batchUpdate("""
                \sINSERT INTO operation_log (at, actor_id, station_id, pdv_id, type, category, title, detail, amount)
                VALUES (:at, :a, :s, :p, :t, :c, :ti, :d, :m)""", rows.toArray(SqlParameterSource[]::new));
        db.update("UPDATE station SET last_status = CASE WHEN code IN ('ST-008', 'ST-011') THEN 'HORS_LIGNE' WHEN code = 'ST-006' THEN 'INSTABLE' ELSE 'EN_LIGNE' END",
                Map.of());
    }

    private String cardSuffix(long cardId) {
        String n = db.value("SELECT number FROM card WHERE id = :c", Db.p("c", cardId), String.class);
        return n == null ? "" : n.substring(8);
    }

    // ================================================================== opérations en cours

    /** Validations en attente (palier dépassé, cartes entreprises, ouverture de PDV) et quelques décisions passées. */
    private void pendingOperations() {
        CurrentUser kossi = users.load(people.get("Kossi Amegah"));
        CurrentUser yao = users.load(people.get("Yao Agbeko"));
        CurrentUser abla = users.load(people.get("Abla Gbeasor"));
        CurrentUser daf = users.load(people.get("Mawuli Kpodar"));
        CurrentUser essi = users.load(people.get("Essi Lawson"));
        long agoe = station("ST-001").id;

        // historique : approuvée (Comptable puis DAF) et refusée
        Map<String, Object> r = rechargeService.recharge(new RechargeService.Request("601133027782", 600_000, "UNITAIRE", "VIREMENT",
                "VIR-AGRO-2210", agoe, null), kossi);
        long v = validationOf(r);
        validations.decide(v, true, "Justificatif de virement reçu", abla);
        validations.decide(v, true, null, daf);
        backdate(v, now.minusDays(2).withHour(9).withMinute(12));
        r = rechargeService.recharge(new RechargeService.Request("601122905531", 900_000, "UNITAIRE", "VIREMENT", "VIR-BTP-0918",
                station("ST-010").id, null), users.load(station("ST-010").manager));
        v = validationOf(r);
        validations.decide(v, false, "Virement non reçu", abla);
        backdate(v, now.minusDays(1).withHour(15).withMinute(40));

        // en attente
        db.sum("SELECT setval('ref_validation_seq', 1183)", Map.of());
        r = rechargeService.recharge(new RechargeService.Request("601128470093", 1_200_000, "UNITAIRE", "VIREMENT", "VIR-TRL-7781",
                agoe, null), kossi);
        v = validationOf(r);
        validations.decide(v, true, "Montant conforme au bon de commande", abla);
        backdate(v, now.minusHours(2));
        r = rechargeService.recharge(new RechargeService.Request("601144170028", 2_500_000, "DISTANCE", "VIREMENT", "VIR-LLG-1102",
                agoe, null), kossi);
        backdate(validationOf(r), now.minusMinutes(40));
        r = rechargeService.recharge(new RechargeService.Request("601128474410", 750_000, "UNITAIRE", "MOBILE_MONEY", "TMoney 228904412",
                agoe, null), yao);
        backdate(validationOf(r), now.minusMinutes(12));

        // demande d'ouverture d'un point de vente temporaire
        LocalDate from = today.plusDays(7);
        long kara = db.insert("""
                \sINSERT INTO pdv (code, name, kind, address, latitude, longitude, start_date, end_date, status, cards_planned, reason,
                                 requested_by)
                VALUES (:c, 'Campagne Université de Kara', 'TEMPORAIRE', 'Campus nord, Université de Kara', 9.5600, 1.1900, :sd, :ed,
                        'DEMANDE', 300, 'Rentrée universitaire : cartes étudiants', :u)""",
                Db.p("c", refs.pdv(), "sd", from, "ed", from.plusDays(14), "u", essi.id()));
        v = validations.create(new ValidationService.NewRequest(ValidationService.OUVERTURE_PDV, "Campagne Université de Kara",
                "Ouverture d'un point de vente temporaire · " + from + " → " + from.plusDays(14), null, false,
                "Rentrée universitaire : cartes étudiants", null, kara), essi);
        db.update("UPDATE pdv SET validation_id = :v WHERE id = :id", Db.p("id", kara, "v", v));
        backdate(v, now.minusHours(1));
        agent(kara, "Mawuena Kossi", "AGENT");

        // recharges sous le palier du jour (créditées immédiatement)
        rechargeService.recharge(new RechargeService.Request("601133021209", 10_000, "UNITAIRE", "ESPECES", null, agoe, null), yao);
        rechargeService.recharge(new RechargeService.Request("601128474410", 50_000, "DISTANCE", "MOBILE_MONEY", "Flooz 99120456",
                null, null), kossi);
    }

    private long validationOf(Map<String, Object> recharge) {
        return num(recharge.get("validationId"));
    }

    private void backdate(long validation, LocalDateTime at) {
        db.update("UPDATE validation_request SET requested_at = :a, decided_at = CASE WHEN decided_at IS NULL THEN NULL ELSE CAST(:d AS timestamptz) END WHERE id = :v",
                Db.p("v", validation, "a", ts(at), "d", ts(at.plusMinutes(35))));
        db.update("UPDATE validation_step SET decided_at = :d WHERE request_id = :v AND decided_at IS NOT NULL",
                Db.p("v", validation, "d", ts(at.plusMinutes(35))));
        db.update("UPDATE recharge SET created_at = :a WHERE validation_id = :v", Db.p("v", validation, "a", ts(at)));
    }

    // ================================================================== Zen Mobility

    private void deliveries() {
        long ama = people.get("Ama Kpodar");
        long edem = people.get("Edem Akakpo");
        long koffi = people.get("Koffi Dossou");
        St agoe = station("ST-001");
        St bk = station("ST-002");
        St kara = station("ST-010");
        long translom = db.value("SELECT holder_id FROM card WHERE number = '601128470093'", Map.of(), Long.class);
        long lomelog = db.value("SELECT holder_id FROM card WHERE number = '601144170028'", Map.of(), Long.class);
        long btp = db.value("SELECT holder_id FROM card WHERE number = '601122905531'", Map.of(), Long.class);
        long agro = db.value("SELECT holder_id FROM card WHERE number = '601133027782'", Map.of(), Long.class);

        // livrée hier (virement)
        long done = order(agro, null, "GASOIL", 1500, "Entrepôt Agro-Plus, route de Kpalimé, Lomé", 6.1840, 1.1920, agoe.id, edem,
                "Citerne TG 9032 AB", "LIVREE", "VIREMENT", now.minusDays(1).withHour(8), ama);
        long saleId = db.insert("""
                \sINSERT INTO sale (uuid, at, kind, channel, station_id, employee_id, payment_method, buyer_profile, total)
                VALUES (:u, :at, 'CARBURANT', 'LIVRAISON', :s, :e, 'VIREMENT', 'PARTENAIRE', :t)""",
                Db.p("u", UUID.randomUUID(), "at", ts(now.minusDays(1).withHour(11)), "s", agoe.id, "e", edem, "t", 1500L * 675));
        db.update("INSERT INTO sale_line (sale_id, product_id, quantity, unit_price, amount) VALUES (:s, :p, 1500, 675, :a)",
                Db.p("s", saleId, "p", products.get("GASOIL"), "a", 1500L * 675));
        db.update("UPDATE delivery_order SET delivered_quantity = 1500, sale_id = :s WHERE id = :d", Db.p("s", saleId, "d", done));
        db.update("""
                \sINSERT INTO stock_movement (station_id, product_id, tank_id, quantity, kind, ref, at)
                VALUES (:s, :p, :t, -1500, 'LIVRAISON', (SELECT ref FROM delivery_order WHERE id = :d), :at)""",
                Db.p("s", agoe.id, "p", products.get("GASOIL"), "t", agoe.tanks.get("GASOIL"), "d", done,
                        "at", ts(now.minusDays(1).withHour(11))));

        // en route, avec son trajet GPS
        long route = order(translom, null, "GASOIL", 5000, "Dépôt Translom, zone portuaire, Lomé", 6.1372, 1.2861,
                agoe.id, edem, "Citerne TG 9032 AB", "EN_ROUTE", "VIREMENT", now.minusMinutes(25), ama);
        double[] from = {agoe.spec.lat(), agoe.spec.lon()};
        double[] to = {6.1372, 1.2861};
        for (int i = 0; i <= 12; i++) {
            double f = i * 0.05;
            db.update("""
                    \sINSERT INTO location_ping (subject, subject_id, delivery_id, latitude, longitude, accuracy_m, speed_kmh, heading, at)
                    VALUES ('LIVREUR', :e, :d, :lat, :lon, 8, :sp, 145, :at)""",
                    Db.p("e", edem, "d", route, "lat", BigDecimal.valueOf(from[0] + (to[0] - from[0]) * f).setScale(6, RoundingMode.HALF_UP),
                            "lon", BigDecimal.valueOf(from[1] + (to[1] - from[1]) * f).setScale(6, RoundingMode.HALF_UP),
                            "sp", BigDecimal.valueOf(25 + rnd.nextInt(20)), "at", ts(now.minusMinutes(26 - 2L * i))));
        }
        order(lomelog, cardIds.get("LomeLog"), "SUPER", 3000, "Base Lomé Logistique, Agbalépédogan, Lomé", 6.1780, 1.2150, bk.id, koffi,
                "Citerne TG 4410 BC", "VALIDEE", "CARTE", now.minusHours(5), ama);
        order(btp, null, "GASOIL", 2000, "Chantier BTP Kara, route de Lama-Kara", 9.5750, 1.2050, kara.id, null, null, "DEMANDEE",
                "VIREMENT", now.minusMinutes(50), ama);
        // pour le simulateur : la livraison en cours avance vers sa destination
        settings.set("demo.route", route + ";" + to[0] + ";" + to[1]);
    }

    private long order(long holder, Long card, String fuel, int quantity, String address, double lat, double lon, long source, Long agent,
                       String vehicle, String status, String payment, LocalDateTime created, long by) {
        long unit = prices.get(fuel + "|GRAND_PUBLIC");
        return db.insert("""
                \sINSERT INTO delivery_order (ref, holder_id, card_id, product_id, quantity, unit_price, address, latitude, longitude,
                                            requested_for, status, source_station_id, agent_id, vehicle, payment_method, created_by,
                                            created_at, departed_at, delivered_at)
                VALUES (:ref, :h, :c, :p, :q, :u, :a, :lat, :lon, :rf, :st, :src, :ag, :v, :pm, :by, :at,
                        CASE WHEN :st = 'EN_ROUTE' THEN CAST(:at AS timestamp) + interval '5 minutes'
                             WHEN :st = 'LIVREE' THEN CAST(:at AS timestamp) + interval '2 hours' END,
                        CASE WHEN :st = 'LIVREE' THEN CAST(:at AS timestamp) + interval '3 hours' END)""",
                Db.p("ref", refs.delivery(), "h", holder, "c", card, "p", products.get(fuel), "q", BigDecimal.valueOf(quantity), "u", unit,
                        "a", address, "lat", BigDecimal.valueOf(lat), "lon", BigDecimal.valueOf(lon), "rf", ts(created.plusHours(4)),
                        "st", status, "src", source, "ag", agent, "v", vehicle, "pm", payment, "by", by, "at", ts(created)));
    }

    // ================================================================== rapports

    private void dailyReports() {
        for (St st : stations) {
            for (LocalDate d = start; d.isBefore(today); d = d.plusDays(1)) {
                long id = reports.generate(st.id, d);
                LocalDateTime at = d.plusDays(1).atTime(8, 5 + rnd.nextInt(50));
                if (d.isBefore(today.minusDays(1))) {
                    String[] comments = {"RAS. Journée normale.", "Affluence en fin de journée, caisse conforme.",
                            "Livraison de carburant reçue le matin. RAS.", "Coupure de courant 20 min, groupe électrogène démarré.",
                            "Bonne journée, hausse des ventes de lubrifiants."};
                    db.update("""
                            \sUPDATE daily_report SET generated_at = :g, status = 'COMMENTE', comment = :c, comment_by = :u,
                                   comment_at = :at, validated_at = :at
                            WHERE id = :id""",
                            Db.p("id", id, "g", ts(d.atTime(23, 59)), "c", comments[rnd.nextInt(comments.length)], "u", st.manager,
                                    "at", ts(at)));
                } else {
                    db.update("UPDATE daily_report SET generated_at = :g WHERE id = :id", Db.p("id", id, "g", ts(d.atTime(23, 59))));
                    if ("ST-001".equals(st.spec.code())) {
                        db.update("""
                                \sUPDATE daily_report SET comment = :c, comment_by = :u, comment_at = :at WHERE id = :id""",
                                Db.p("id", id, "c", "Journée conforme. Écart de caisse du poste du soir en cours de vérification avec Kodjo.",
                                        "u", st.manager, "at", ts(now.withHour(Math.min(now.getHour(), 8)).withMinute(12))));
                    }
                }
            }
        }
    }

    private void finish() {
        settings.set("demo.seeded", "true");
        settings.set("demo.online_codes", "ST-001,ST-002,ST-003,ST-004,ST-005,ST-007,ST-009,ST-010");
        settings.set("demo.unstable_codes", "ST-006");
        settings.set("demo.pdv_online_codes", "PDV-101,PDV-102");
        settings.set("email.summary_last_day", today.minusDays(1).toString());
        db.update("UPDATE station SET last_seen_at = now() - interval '20 seconds' WHERE code = ANY(:c)",
                Db.p("c", db.array("text", settings.get("demo.online_codes", "").split(","))));
        db.update("UPDATE station SET last_seen_at = now() - interval '5 minutes' WHERE code = 'ST-006'", Map.of());
        db.update("UPDATE station SET reader_connected = false WHERE code = 'ST-008'", Map.of());
        db.update("UPDATE card SET status = 'BLOQUEE', blocked_reason = 'Plafond mensuel atteint' WHERE number = '601122905531'", Map.of());
        // Bè Kpota : pistolet en cours de distribution
        db.update("""
                \sUPDATE pump SET state = 'DISTRIBUE', current_volume = 18.40 WHERE code = 'P5'
                  AND station_id = (SELECT id FROM station WHERE code = 'ST-002')""", Map.of());
        db.update("""
                \sUPDATE pump SET state = 'HORS_SERVICE' WHERE code = 'P8'
                  AND station_id = (SELECT id FROM station WHERE code = 'ST-008')""", Map.of());
        // les cuves retrouvent le niveau des maquettes
        for (St st : stations) {
            String[] fuels = {"SUPER", "GASOIL", "PETROLE"};
            for (int t = 0; t < 3; t++) {
                db.update("UPDATE tank SET volume = round(capacity * :pct / 100) WHERE id = :id",
                        Db.p("id", st.tanks.get(fuels[t]), "pct", st.spec.tanks()[t]));
            }
        }
        thresholds.recomputeAll();
    }

    // ================================================================== utilitaires

    private St station(String code) {
        return stations.stream().filter(s -> s.spec.code().equals(code)).findFirst().orElseThrow();
    }

    private int pick(double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double r = rnd.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    private int pick(int[] weights) {
        double[] w = new double[weights.length];
        for (int i = 0; i < w.length; i++) {
            w[i] = weights[i];
        }
        return pick(w);
    }

    static Timestamp ts(LocalDateTime t) {
        return Timestamp.valueOf(t);
    }

    static long num(Object o) {
        return ((Number) o).longValue();
    }

    static String ascii(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
