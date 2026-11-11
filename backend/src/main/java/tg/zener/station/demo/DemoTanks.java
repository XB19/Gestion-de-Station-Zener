package tg.zener.station.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tg.zener.station.common.Db;
import tg.zener.station.tanks.PumpReportService;
import tg.zener.station.tanks.TankService;

/**
 * Démonstration du domaine pistolets et cuves : historique des jauges (14 jours), mesures à la pige,
 * réceptions de carburant reconstituées depuis les entrées en cuve, inventaires d'articles, alarmes,
 * relevés d'index des pistolets. Idempotent : ne fait rien si l'historique des jauges existe déjà.
 */
@Component
public class DemoTanks {

    private static final Logger log = LoggerFactory.getLogger(DemoTanks.class);

    static final String[] SUPPLIERS = {"Dépôt pétrolier de Lomé", "Terminal du Port autonome", "Dépôt de Kara", "Dépôt de Sokodé"};
    static final String[] DRIVERS = {"Kokou Attiogbé", "Messan Afanou", "Yawo Kpelly", "Komi Ahiadzro", "Dodji Kouma", "Folly Agbéko"};

    static String supplier(Random r) {
        return SUPPLIERS[r.nextInt(SUPPLIERS.length)];
    }

    static String driver(Random r) {
        return DRIVERS[r.nextInt(DRIVERS.length)];
    }

    static String plate(Random r) {
        return "TG " + (1000 + r.nextInt(8999)) + " " + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26));
    }

    private final Db db;
    private final TankService tanks;
    private final PumpReportService reports;
    private final TransactionTemplate tx;

    public DemoTanks(Db db, TankService tanks, PumpReportService reports, PlatformTransactionManager tm) {
        this.tx = new TransactionTemplate(tm);
        this.db = db;
        this.tanks = tanks;
        this.reports = reports;
    }

    /** Crée l'historique de démonstration des cuves s'il n'existe pas (tout ou rien ; une erreur n'arrête pas l'API). */
    public void ensure() {
        if (db.count("SELECT count(*) FROM tank_reading", Map.of()) > 0) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> create());
        } catch (Exception e) {
            log.warn("Données de démonstration (cuves) non créées : {}", e.getMessage());
        }
    }

    private void create() {
        long t0 = System.currentTimeMillis();
        // ---------------------------------------------------------- jauges : une mesure toutes les 2 h sur 14 jours
        int readings = db.update("""
                \sINSERT INTO tank_reading (tank_id, station_id, product_id, at, source, volume, book_volume, gap, height_mm,
                                          temperature, water_height_mm, water_volume, density)
                SELECT t.id, t.station_id, t.product_id, g.at, 'JAUGE', v.measured, b.book, v.measured - b.book,
                       round(v.measured / t.capacity * 2500, 1),
                       round(CAST(28.5 + 3.5 * sin(pi() * (extract(hour FROM g.at) - 9) / 12) + random() AS numeric), 1),
                       w.mm, round(w.mm * t.capacity / 2500 * 0.6, 2),
                       CASE p.code WHEN 'SUPER' THEN 0.7420 WHEN 'GASOIL' THEN 0.8350 ELSE 0.7980 END
                FROM tank t JOIN product p ON p.id = t.product_id JOIN station s ON s.id = t.station_id
                CROSS JOIN generate_series(date_trunc('hour', now()) - interval '14 days', now() - interval '20 minutes',
                                           interval '2 hours') g(at)
                CROSS JOIN LATERAL (
                    SELECT greatest(t.volume - COALESCE((SELECT sum(m.quantity) FROM stock_movement m
                                                         WHERE m.tank_id = t.id AND m.at > g.at), 0), 0) AS book) b
                CROSS JOIN LATERAL (
                    SELECT least(t.capacity, round(CAST(b.book * (1 + (random() - 0.5) * 0.003) AS numeric), 2)) AS measured) v
                CROSS JOIN LATERAL (
                    SELECT round(CAST(CASE WHEN s.short_name = 'Bè Kpota' AND p.code = 'GASOIL'
                                           THEN 9 + greatest(0, 26 - extract(epoch FROM now() - g.at) / 10800)
                                           ELSE 2 + random() * 9 END AS numeric), 1) AS mm) w""", Map.of());

        // ---------------------------------------------------------- pige du lundi matin par le gérant (sans ajustement)
        int manual = db.update("""
                \sINSERT INTO tank_reading (tank_id, station_id, product_id, at, source, volume, book_volume, gap, height_mm,
                                          temperature, water_height_mm, employee_id, note)
                SELECT r.tank_id, r.station_id, r.product_id, r.at + interval '10 minutes', 'MANUEL', x.v, r.book_volume,
                       x.v - r.book_volume, r.height_mm, r.temperature, r.water_height_mm, s.manager_id, 'Pige hebdomadaire'
                FROM tank_reading r JOIN station s ON s.id = r.station_id
                CROSS JOIN LATERAL (SELECT round(CAST(r.book_volume * (1 + (random() - 0.55) * 0.004) AS numeric), 2) AS v) x
                WHERE r.source = 'JAUGE' AND extract(isodow FROM r.at) = 1 AND extract(hour FROM r.at) BETWEEN 6 AND 7""", Map.of());
        db.update("""
                \sINSERT INTO stock_count (station_id, product_id, tank_id, book_quantity, counted_quantity, gap, adjusted,
                                         employee_id, at, note)
                SELECT station_id, product_id, tank_id, book_volume, volume, gap, false, employee_id, at, 'Pige hebdomadaire'
                FROM tank_reading WHERE source = 'MANUEL'""", Map.of());

        // ---------------------------------------------------------- réceptions reconstituées depuis les entrées en cuve
        int deliveries = db.update("""
                \sINSERT INTO tank_delivery (ref, tank_id, station_id, product_id, supplier, truck_plate, driver, ticket_number,
                                           ticket_volume, ticket_density, temperature, volume_before, volume_after, sold_during,
                                           received_volume, gap, started_at, ended_at, employee_id, note)
                SELECT 'DEP-' || extract(year FROM m.at) || '-' || nextval('ref_reception_seq'), m.tank_id, m.station_id,
                       m.product_id, (CAST(:sup AS text[]))[1 + floor(random() * 4)::int], 'TG ' || (1000 + floor(random() * 8999)) || ' '
                       || chr(65 + floor(random() * 26)::int) || chr(65 + floor(random() * 26)::int),
                       (CAST(:drv AS text[]))[1 + floor(random() * 6)::int], COALESCE(m.ref, 'BL-' || (40000 + floor(random() * 9999))),
                       tv.ticket, CASE p.code WHEN 'SUPER' THEN 0.7420 WHEN 'GASOIL' THEN 0.8350 ELSE 0.7980 END,
                       round(CAST(26 + random() * 5 AS numeric), 1), b.before, b.before + m.quantity, 0,
                       m.quantity, m.quantity - tv.ticket, m.at - interval '45 minutes', m.at, s.manager_id, 'Livraison du dépôt'
                FROM stock_movement m JOIN tank t ON t.id = m.tank_id JOIN product p ON p.id = m.product_id
                JOIN station s ON s.id = m.station_id
                CROSS JOIN LATERAL (SELECT CASE WHEN random() < 0.12 THEN m.quantity + 40 + floor(random() * 80)
                                                ELSE m.quantity + floor(random() * 21) - 10 END AS ticket) tv
                CROSS JOIN LATERAL (SELECT greatest(t.volume - COALESCE((SELECT sum(m2.quantity) FROM stock_movement m2
                                    WHERE m2.tank_id = t.id AND m2.at >= m.at), 0), 0) AS before) b
                WHERE m.tank_id IS NOT NULL AND m.quantity > 0 AND m.kind IN ('ENTREE', 'LIVRAISON')
                  AND NOT EXISTS (SELECT 1 FROM tank_delivery d WHERE d.tank_id = m.tank_id AND d.ended_at = m.at)""",
                Db.p("sup", db.array("text", SUPPLIERS), "drv", db.array("text", DRIVERS)));

        // ---------------------------------------------------------- inventaires d'articles (deux comptages par station)
        int counts = db.update("""
                \sINSERT INTO stock_count (station_id, product_id, book_quantity, counted_quantity, gap, adjusted, employee_id, at, note)
                SELECT l.station_id, l.product_id, l.quantity + d.sold, l.quantity + d.sold + d.diff, d.diff, true, s.manager_id,
                       d.at, 'Inventaire mensuel'
                FROM stock_level l JOIN station s ON s.id = l.station_id
                CROSS JOIN LATERAL (VALUES (date_trunc('month', now()) - interval '1 day' + time '20:30', 6),
                                           (now() - interval '9 days', 3)) AS w(at, k)
                CROSS JOIN LATERAL (SELECT w.at, CASE WHEN random() + 0 * l.id < 0.2 THEN -1 - floor(random() * 2) ELSE 0 END AS diff,
                                           floor(random() * w.k + 0 * l.id) AS sold) d
                WHERE l.station_id IS NOT NULL""", Map.of());

        // ---------------------------------------------------------- alarmes : dernières mesures + une alarme passée acquittée
        for (Map<String, Object> t : db.list("SELECT id FROM tank ORDER BY id")) {
            tanks.evaluateLatest(((Number) t.get("id")).longValue());
        }
        db.update("""
                \sINSERT INTO tank_alarm (tank_id, station_id, kind, started_at, ended_at, value, threshold, acknowledged_by,
                                        acknowledged_at, note)
                SELECT t.id, t.station_id, 'ECART_JAUGE', now() - interval '5 days 3 hours', now() - interval '5 days 1 hour',
                       -820, round(t.capacity * 0.015, 2), s.manager_id, now() - interval '5 days 2 hours',
                       'Jauge recalibrée par le technicien'
                FROM tank t JOIN station s ON s.id = t.station_id WHERE s.short_name = 'Kpalimé Centre' ORDER BY t.id LIMIT 1""",
                Map.of());

        int pumpRows = reports.generate();
        // essais de pompe non enregistrés dans l'historique (~3 % des pistolets-jours) : le compteur avance sans vente,
        // l'écart apparaît le jour même et les index suivants sont décalés d'autant (index actuel compris)
        db.update("""
                \sCREATE TEMP TABLE demo_pump_gap ON COMMIT DROP AS
                SELECT r.pump_id, r.period_start, CAST(2 + floor(random() * 10) AS numeric) AS x
                FROM pump_report r WHERE r.kind = 'JOUR' AND random() < 0.03""", Map.of());
        db.update("""
                \sUPDATE pump_report r SET
                    start_index = r.start_index + COALESCE((SELECT sum(g.x) FROM demo_pump_gap g
                                                           WHERE g.pump_id = r.pump_id AND g.period_start < r.period_start), 0),
                    end_index = r.end_index + COALESCE((SELECT sum(g.x) FROM demo_pump_gap g
                                                       WHERE g.pump_id = r.pump_id AND g.period_start <= r.period_start), 0),
                    meter_volume = r.meter_volume + COALESCE((SELECT sum(g.x) FROM demo_pump_gap g
                                                             WHERE g.pump_id = r.pump_id AND g.period_start = r.period_start), 0),
                    gap = r.gap + COALESCE((SELECT sum(g.x) FROM demo_pump_gap g
                                           WHERE g.pump_id = r.pump_id AND g.period_start = r.period_start), 0)
                WHERE r.kind = 'JOUR'""", Map.of());
        db.update("""
                \sUPDATE pump p SET index_total = p.index_total + g.total
                FROM (SELECT pump_id, sum(x) AS total FROM demo_pump_gap GROUP BY pump_id) g WHERE g.pump_id = p.id""", Map.of());
        db.update("DELETE FROM pump_report WHERE kind = 'MOIS'", Map.of());
        pumpRows += reports.generate();
        log.info("Données de démonstration (cuves) : {} jauges, {} piges, {} réceptions, {} inventaires, {} relevés de pistolets en {} s",
                readings, manual, deliveries, counts, pumpRows, (System.currentTimeMillis() - t0) / 1000);
    }

    /**
     * Simulateur, chaque minute : mesure de jauge des cuves dont la dernière mesure est trop ancienne
     * (léger bruit de sonde, température selon l'heure, eau en fond de cuve), et de temps en temps un essai
     * de pompe non enregistré qui fait avancer le compteur d'un pistolet (écart dans les relevés).
     */
    void tick(List<String> onlineCodes, Random rnd) {
        long interval = 15;
        double hour = LocalTime.now().getHour() + LocalTime.now().getMinute() / 60.0;
        for (Map<String, Object> t : db.list("""
                \sSELECT t.id, t.volume, t.capacity, p.code AS product_code, s.short_name,
                       (SELECT r.water_height_mm FROM tank_reading r WHERE r.tank_id = t.id ORDER BY r.at DESC LIMIT 1) AS water
                FROM tank t JOIN station s ON s.id = t.station_id JOIN product p ON p.id = t.product_id
                WHERE s.code = ANY(:c)
                  AND NOT EXISTS (SELECT 1 FROM tank_reading r WHERE r.tank_id = t.id AND r.source = 'JAUGE'
                                  AND r.at > now() - make_interval(mins => CAST(:i AS int)))""",
                Db.p("c", db.array("text", onlineCodes.toArray()), "i", interval))) {
            BigDecimal book = (BigDecimal) t.get("volume");
            BigDecimal capacity = (BigDecimal) t.get("capacity");
            BigDecimal measured = book.add(BigDecimal.valueOf(rnd.nextInt(9) - 4)).max(BigDecimal.ZERO).min(capacity);
            double temp = 28.5 + 3.5 * Math.sin(Math.PI * (hour - 9) / 12) + rnd.nextDouble();
            BigDecimal water = t.get("water") == null ? BigDecimal.valueOf(4) : (BigDecimal) t.get("water");
            boolean beKpota = "Bè Kpota".equals(t.get("shortName")) && "GASOIL".equals(t.get("productCode"));
            water = beKpota ? water.max(BigDecimal.valueOf(34)) : water.add(BigDecimal.valueOf((rnd.nextInt(3) - 1) * 0.2))
                    .max(BigDecimal.valueOf(1)).min(BigDecimal.valueOf(14));
            tanks.gauge(((Number) t.get("id")).longValue(), new TankService.Measure(measured,
                    measured.multiply(BigDecimal.valueOf(2500)).divide(capacity, 1, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(temp).setScale(1, RoundingMode.HALF_UP), water.setScale(1, RoundingMode.HALF_UP),
                    null, null));
        }
        if (rnd.nextInt(40) == 0) {
            db.update("""
                    \sUPDATE pump SET index_total = index_total + :q, updated_at = now()
                    WHERE id = (SELECT p.id FROM pump p JOIN station s ON s.id = p.station_id
                                WHERE s.code = ANY(:c) AND p.state <> 'HORS_SERVICE' ORDER BY random() LIMIT 1)""",
                    Db.p("q", BigDecimal.valueOf(2 + rnd.nextInt(9)), "c", db.array("text", onlineCodes.toArray())));
        }
    }
}
