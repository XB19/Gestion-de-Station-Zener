package tg.zener.station.tanks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.Refs;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.stock.StockService;

/**
 * Cuves : mesures (jauge automatique du PC station, pige manuelle), réceptions de carburant (dépotage),
 * inventaires mesurés et alarmes (niveau haut, eau, température, écart jauge / théorique).
 * Le niveau bas reste géré par StockService (alertes de stock et seuils automatiques).
 */
@Service
public class TankService {

    public static final String JAUGE = "JAUGE";
    public static final String MANUEL = "MANUEL";

    /** Mesure envoyée par la jauge ou relevée à la pige ; les champs autres que le volume sont facultatifs. */
    public record Measure(BigDecimal volume, BigDecimal heightMm, BigDecimal temperature, BigDecimal waterHeightMm,
                          BigDecimal waterVolume, BigDecimal density) {}

    /** Réception de carburant : bon du fournisseur et volume constaté en cuve. */
    public record Reception(BigDecimal ticketVolume, BigDecimal receivedVolume, BigDecimal soldDuring, String supplier,
                            String truckPlate, String driver, String ticketNumber, BigDecimal ticketDensity,
                            BigDecimal temperature, Instant startedAt, Instant endedAt, String note) {}

    private final Db db;
    private final StockService stock;
    private final Settings settings;
    private final Journal journal;
    private final LiveFeed live;
    private final Refs refs;

    public TankService(Db db, StockService stock, Settings settings, Journal journal, LiveFeed live, Refs refs) {
        this.db = db;
        this.stock = stock;
        this.settings = settings;
        this.journal = journal;
        this.live = live;
        this.refs = refs;
    }

    private Map<String, Object> tank(long tankId) {
        return db.require("""
                \sSELECT t.id, t.station_id, t.product_id, t.name, t.capacity, t.volume, p.name AS product_name,
                       s.short_name AS station_name
                FROM tank t JOIN product p ON p.id = t.product_id JOIN station s ON s.id = t.station_id WHERE t.id = :id""",
                Db.p("id", tankId), "Cuve introuvable");
    }

    // ------------------------------------------------------------------ mesures

    /**
     * Mesure de jauge envoyée par le PC station (signal de vie) : le volume mesuré devient le volume de la cuve ;
     * la mesure est historisée au plus toutes les {@code tank.reading_interval_minutes}. Les alarmes sont évaluées
     * à chaque mesure.
     */
    @Transactional
    public void gauge(long tankId, Measure m) {
        Map<String, Object> t = tank(tankId);
        BigDecimal book = (BigDecimal) t.get("volume");
        long interval = settings.getLong("tank.reading_interval_minutes", 15);
        boolean due = db.count("""
                \sSELECT count(*) FROM tank_reading WHERE tank_id = :t AND source = 'JAUGE'
                  AND at > now() - make_interval(mins => CAST(:i AS int))""", Db.p("t", tankId, "i", interval)) == 0;
        if (due) {
            insertReading(t, JAUGE, m, book, null, null, null);
        }
        stock.setTankVolume(tankId, m.volume());
        evaluate(t, m, book);
    }

    /**
     * Mesure manuelle (pige). Avec {@code adjust}, l'écart avec le stock théorique est passé en ajustement de stock
     * (inventaire de la cuve, ligne dans stock_count).
     */
    @Transactional
    public Map<String, Object> manual(long tankId, Measure m, boolean adjust, Long employeeId, String note) {
        Map<String, Object> t = tank(tankId);
        BigDecimal book = (BigDecimal) t.get("volume");
        long readingId = insertReading(t, MANUEL, m, book, employeeId, note, null);
        BigDecimal gap = m.volume().subtract(book);
        long stationId = num(t.get("stationId"));
        if (adjust) {
            db.update("""
                    \sINSERT INTO stock_count (station_id, product_id, tank_id, book_quantity, counted_quantity, gap, adjusted, employee_id, note)
                    VALUES (:s, :p, :t, :b, :c, :g, true, :e, :n)""",
                    Db.p("s", stationId, "p", t.get("productId"), "t", tankId, "b", book, "c", m.volume(), "g", gap,
                            "e", employeeId, "n", note));
            if (gap.signum() != 0) {
                stock.moveTank(tankId, gap, "INVENTAIRE", null, employeeId, "Pige · mesuré " + StockService.qty(m.volume()) + " L");
            }
        }
        evaluate(t, m, book);
        journal.log("MESURE_CUVE", Journal.MODIFICATIONS, (adjust ? "Inventaire de cuve" : "Mesure de cuve") + " · " + t.get("name"),
                t.get("productName") + " · mesuré " + StockService.qty(m.volume()) + " L · théorique " + StockService.qty(book)
                        + " L · écart " + signed(gap) + " L", null, stationId, null);
        return db.one("SELECT * FROM tank_reading WHERE id = :id", Db.p("id", readingId));
    }

    private long insertReading(Map<String, Object> t, String source, Measure m, BigDecimal book, Long employeeId, String note,
                               Instant at) {
        if (m.volume() == null || m.volume().signum() < 0) {
            throw new ApiException("Volume mesuré invalide");
        }
        if (m.volume().compareTo((BigDecimal) t.get("capacity")) > 0) {
            throw new ApiException("Volume mesuré supérieur à la capacité de la cuve");
        }
        return db.insert("""
                \sINSERT INTO tank_reading (tank_id, station_id, product_id, at, source, volume, book_volume, gap, height_mm,
                                          temperature, water_height_mm, water_volume, density, employee_id, note)
                VALUES (:t, :s, :p, COALESCE(CAST(:at AS timestamptz), now()), :src, :v, :b, :g, :h, :tp, :wh, :wv, :d, :e, :n)""",
                Db.p("t", t.get("id"), "s", t.get("stationId"), "p", t.get("productId"),
                        "at", at == null ? null : java.sql.Timestamp.from(at), "src", source, "v", m.volume(), "b", book,
                        "g", book == null ? null : m.volume().subtract(book), "h", m.heightMm(), "tp", m.temperature(),
                        "wh", m.waterHeightMm(), "wv", m.waterVolume(), "d", m.density(), "e", employeeId, "n", note));
    }

    // ------------------------------------------------------------------ réceptions

    /**
     * Réception de carburant (dépotage) : la cuve est créditée du volume reçu (à défaut, du volume du bon),
     * l'écart reçu − bon est conservé. Le volume avant / après est le stock théorique de la cuve.
     */
    @Transactional
    public Map<String, Object> receive(long tankId, Reception r, Long employeeId) {
        if (r.ticketVolume() == null || r.ticketVolume().signum() <= 0) {
            throw new ApiException("Volume du bon de livraison requis");
        }
        Map<String, Object> t = tank(tankId);
        BigDecimal received = r.receivedVolume() != null ? r.receivedVolume() : r.ticketVolume();
        if (received.signum() <= 0) {
            throw new ApiException("Volume reçu invalide");
        }
        BigDecimal before = (BigDecimal) t.get("volume");
        BigDecimal capacity = (BigDecimal) t.get("capacity");
        if (before.add(received).compareTo(capacity.multiply(new BigDecimal("1.02"))) > 0) {
            throw new ApiException("La cuve ne peut pas recevoir " + StockService.qty(received) + " L (capacité "
                    + StockService.qty(capacity) + " L, " + StockService.qty(before) + " L déjà en cuve)");
        }
        String ref = refs.reception();
        BigDecimal sold = r.soldDuring() == null ? BigDecimal.ZERO : r.soldDuring();
        stock.moveTank(tankId, received, "LIVRAISON", ref, employeeId,
                "Réception" + (r.supplier() == null ? "" : " · " + r.supplier()) + (r.ticketNumber() == null ? "" : " · bon " + r.ticketNumber()));
        long id = db.insert("""
                \sINSERT INTO tank_delivery (ref, tank_id, station_id, product_id, supplier, truck_plate, driver, ticket_number,
                                           ticket_volume, ticket_density, temperature, volume_before, volume_after, sold_during,
                                           received_volume, gap, started_at, ended_at, employee_id, note)
                VALUES (:ref, :t, :s, :p, :sup, :tr, :dr, :tn, :tv, :td, :tp, :vb, :va, :sd, :rv, :g,
                        COALESCE(CAST(:sa AS timestamptz), now()), COALESCE(CAST(:ea AS timestamptz), now()), :e, :n)""",
                Db.p("ref", ref, "t", tankId, "s", t.get("stationId"), "p", t.get("productId"), "sup", r.supplier(),
                        "tr", r.truckPlate(), "dr", r.driver(), "tn", r.ticketNumber(), "tv", r.ticketVolume(),
                        "td", r.ticketDensity(), "tp", r.temperature(), "vb", before, "va", before.add(received),
                        "sd", sold, "rv", received, "g", received.subtract(r.ticketVolume()),
                        "sa", r.startedAt() == null ? null : java.sql.Timestamp.from(r.startedAt()),
                        "ea", r.endedAt() == null ? null : java.sql.Timestamp.from(r.endedAt()), "e", employeeId, "n", r.note()));
        BigDecimal gap = received.subtract(r.ticketVolume());
        journal.logAs(employeeId, "RECEPTION_CARBURANT", Journal.LIVRAISONS, "Réception de carburant · " + t.get("productName"),
                t.get("name") + " · " + StockService.qty(received) + " L reçus · bon " + StockService.qty(r.ticketVolume()) + " L"
                        + (gap.signum() == 0 ? "" : " · écart " + signed(gap) + " L") + (r.supplier() == null ? "" : " · " + r.supplier()),
                null, num(t.get("stationId")), null);
        return db.one("SELECT * FROM tank_delivery WHERE id = :id", Db.p("id", id));
    }

    // ------------------------------------------------------------------ alarmes

    /** Évalue les alarmes d'une cuve d'après sa dernière mesure (reprise de données, démonstration). */
    public void evaluateLatest(long tankId) {
        Map<String, Object> r = db.one("""
                \sSELECT volume, book_volume, height_mm, temperature, water_height_mm, water_volume, density
                FROM tank_reading WHERE tank_id = :t ORDER BY at DESC LIMIT 1""", Db.p("t", tankId));
        if (r == null) {
            return;
        }
        evaluate(tank(tankId), new Measure((BigDecimal) r.get("volume"), (BigDecimal) r.get("heightMm"),
                (BigDecimal) r.get("temperature"), (BigDecimal) r.get("waterHeightMm"), (BigDecimal) r.get("waterVolume"),
                (BigDecimal) r.get("density")), (BigDecimal) r.get("bookVolume"));
    }

    /** Ouvre ou ferme les alarmes d'une cuve d'après une mesure. */
    void evaluate(Map<String, Object> t, Measure m, BigDecimal book) {
        long tankId = num(t.get("id"));
        BigDecimal capacity = (BigDecimal) t.get("capacity");
        BigDecimal highPct = BigDecimal.valueOf(settings.getDouble("tank.high_level_pct", 95));
        BigDecimal high = capacity.multiply(highPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        alarm(t, "NIVEAU_HAUT", m.volume(), high, m.volume().compareTo(high) > 0);
        if (m.waterHeightMm() != null) {
            BigDecimal limit = BigDecimal.valueOf(settings.getDouble("tank.water_alarm_mm", 30));
            alarm(t, "EAU", m.waterHeightMm(), limit, m.waterHeightMm().compareTo(limit) > 0);
        }
        if (m.temperature() != null) {
            BigDecimal limit = BigDecimal.valueOf(settings.getDouble("tank.temperature_alarm", 40));
            alarm(t, "TEMPERATURE", m.temperature(), limit, m.temperature().compareTo(limit) > 0);
        }
        if (book != null) {
            BigDecimal gap = m.volume().subtract(book).abs();
            BigDecimal limit = capacity.multiply(BigDecimal.valueOf(settings.getDouble("tank.gauge_gap_pct", 1.5)))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            alarm(t, "ECART_JAUGE", m.volume().subtract(book), limit, gap.compareTo(limit) > 0);
        }
    }

    private void alarm(Map<String, Object> t, String kind, BigDecimal value, BigDecimal threshold, boolean on) {
        long tankId = num(t.get("id"));
        Long open = db.value("SELECT id FROM tank_alarm WHERE tank_id = :t AND kind = :k AND ended_at IS NULL",
                Db.p("t", tankId, "k", kind), Long.class);
        if (on && open == null) {
            db.update("""
                    \sINSERT INTO tank_alarm (tank_id, station_id, kind, value, threshold) VALUES (:t, :s, :k, :v, :th)
                    ON CONFLICT DO NOTHING""",
                    Db.p("t", tankId, "s", t.get("stationId"), "k", kind, "v", value, "th", threshold));
            String label = label(kind);
            journal.logAs(null, "ALARME_CUVE", Journal.SYSTEME, "Alarme cuve · " + label,
                    t.get("stationName") + " · " + t.get("name") + " (" + t.get("productName") + ") · " + value.stripTrailingZeros().toPlainString()
                            + " pour un seuil de " + threshold.stripTrailingZeros().toPlainString(), null, num(t.get("stationId")), null);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("tankId", tankId);
            e.put("kind", kind);
            e.put("label", label);
            e.put("open", true);
            live.publish("alert", num(t.get("stationId")), e);
        } else if (on && open != null) {
            db.update("UPDATE tank_alarm SET value = :v WHERE id = :id", Db.p("id", open, "v", value));
        } else if (!on && open != null) {
            db.update("UPDATE tank_alarm SET ended_at = now(), value = :v WHERE id = :id", Db.p("id", open, "v", value));
        }
    }

    /** Acquittement d'une alarme par un opérateur (elle reste ouverte tant que la cause persiste). */
    public Map<String, Object> acknowledge(long alarmId, long employeeId, String note) {
        Map<String, Object> a = db.require("""
                \sUPDATE tank_alarm SET acknowledged_by = :e, acknowledged_at = now(), note = COALESCE(:n, note)
                WHERE id = :id RETURNING id, tank_id, station_id, kind""",
                Db.p("id", alarmId, "e", employeeId, "n", note), "Alarme introuvable");
        journal.log("ALARME_ACQUITTEE", Journal.MODIFICATIONS, "Alarme acquittée · " + label((String) a.get("kind")),
                note, null, num(a.get("stationId")), null);
        return a;
    }

    public static String label(String kind) {
        return switch (kind) {
            case "NIVEAU_HAUT" -> "niveau haut";
            case "EAU" -> "eau en fond de cuve";
            case "TEMPERATURE" -> "température élevée";
            case "ECART_JAUGE" -> "écart jauge / théorique";
            default -> kind;
        };
    }

    static String signed(BigDecimal v) {
        String s = StockService.qty(v.abs());
        return (v.signum() < 0 ? "−" : "+") + s;
    }

    static long num(Object o) {
        return ((Number) o).longValue();
    }
}
