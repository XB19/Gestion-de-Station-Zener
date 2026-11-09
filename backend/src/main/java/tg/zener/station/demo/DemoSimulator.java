package tg.zener.station.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import tg.zener.station.common.Settings;
import tg.zener.station.mobility.Tracking;
import tg.zener.station.sales.SaleService;

/**
 * Animation de la démonstration (DEMO_DATA=true) : signaux de vie des PC station « en ligne »,
 * quelques ventes à la pompe et le livreur en route qui avance, pour que le tableau de bord,
 * le flux en direct et la carte bougent sans matériel.
 */
@Component
public class DemoSimulator {

    private static final Logger log = LoggerFactory.getLogger(DemoSimulator.class);

    private final Db db;
    private final SaleService sales;
    private final Tracking tracking;
    private final Settings settings;
    private final boolean enabled;
    private final Random rnd = new Random();
    private volatile boolean started;

    public DemoSimulator(Db db, SaleService sales, Tracking tracking, Settings settings, @Value("${app.demo-data}") boolean enabled) {
        this.db = db;
        this.sales = sales;
        this.tracking = tracking;
        this.settings = settings;
        this.enabled = enabled;
    }

    public void start() {
        started = enabled;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void tick() {
        if (!started) {
            return;
        }
        heartbeats();
        for (int i = 0; i < 2; i++) {
            if (rnd.nextDouble() < 0.6) {
                try {
                    sale();
                } catch (Exception e) {
                    log.debug("Vente simulée refusée : {}", e.getMessage());
                }
            }
        }
        try {
            moveDriver();
        } catch (Exception e) {
            log.debug("Position simulée ignorée : {}", e.getMessage());
        }
    }

    private void heartbeats() {
        String[] online = settings.get("demo.online_codes", "").split(",");
        String[] unstable = settings.get("demo.unstable_codes", "").split(",");
        db.update("UPDATE station SET last_seen_at = now() WHERE code = ANY(:c)", Db.p("c", db.array("text", online)));
        db.update("UPDATE station SET last_seen_at = now() - interval '5 minutes' WHERE code = ANY(:c)",
                Db.p("c", db.array("text", unstable)));
    }

    /** Vente en espèces ou mobile money dans une station en ligne et en mode conventionnel. */
    private void sale() {
        List<Map<String, Object>> stations = db.list("""
                \sSELECT id FROM station WHERE active AND mode = 'CONVENTIONNEL' AND code = ANY(:c)""",
                Db.p("c", db.array("text", settings.get("demo.online_codes", "").split(","))));
        if (stations.isEmpty()) {
            return;
        }
        long station = ((Number) stations.get(rnd.nextInt(stations.size())).get("id")).longValue();
        Map<String, Object> pump = db.one("""
                \sSELECT pu.id, p.code FROM pump pu JOIN product p ON p.id = pu.product_id
                WHERE pu.station_id = :s AND pu.state <> 'HORS_SERVICE' ORDER BY random() LIMIT 1""", Db.p("s", station));
        if (pump == null) {
            return;
        }
        Long employee = db.value("""
                \sSELECT es.employee_id FROM employee_shift es JOIN employee e ON e.id = es.employee_id
                WHERE es.station_id = :s AND es.status = 'EN_POSTE' ORDER BY (e.role_code = 'POMPISTE') DESC, random() LIMIT 1""",
                Db.p("s", station), Long.class);
        int[] liters = {3, 5, 10, 10, 15, 20, 20, 25, 30, 40, 50};
        BigDecimal q = BigDecimal.valueOf(liters[rnd.nextInt(liters.length)] + rnd.nextInt(10) / 10.0).setScale(2, RoundingMode.HALF_UP);
        boolean mm = rnd.nextDouble() < 0.3;
        sales.record(new SaleService.Sale(UUID.randomUUID(), station, null, "CARBURANT", "POMPE", ((Number) pump.get("id")).longValue(),
                List.of(new SaleService.Line((String) pump.get("code"), q, false)), mm ? "MOBILE_MONEY" : "ESPECES",
                mm ? "TMoney " + (100_000_000 + rnd.nextInt(899_999_999)) : null, null, null, false, employee, null, false, null));
    }

    /** Le livreur en route avance vers la destination de sa livraison. */
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
        long agent = ((Number) d.get("agentId")).longValue();
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
        double step = dist < 0.0015 ? 0 : Math.min(1, 0.0012 / dist);
        tracking.record("LIVREUR", agent, delivery,
                BigDecimal.valueOf(lat + (toLat - lat) * step).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(lon + (toLon - lon) * step).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(6 + rnd.nextInt(10)), BigDecimal.valueOf(step == 0 ? 0 : 20 + rnd.nextInt(25)),
                BigDecimal.valueOf(140 + rnd.nextInt(20)), null, (String) d.get("name"));
    }
}
