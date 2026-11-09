package tg.zener.station.mobility;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;

/** Enregistre une position GPS (livreur ou station mobile) et la pousse dans le flux temps réel (événement location). */
@Service
public class Tracking {

    private final Db db;
    private final LiveFeed live;

    public Tracking(Db db, LiveFeed live) {
        this.db = db;
        this.live = live;
    }

    public void record(String subject, long subjectId, Long deliveryId, BigDecimal latitude, BigDecimal longitude,
                       BigDecimal accuracy, BigDecimal speed, BigDecimal heading, Instant at, String name) {
        Instant when = at == null || at.isAfter(Instant.now()) ? Instant.now() : at;
        db.update("""
                \sINSERT INTO location_ping (subject, subject_id, delivery_id, latitude, longitude, accuracy_m, speed_kmh, heading, at)
                VALUES (:s, :id, :d, :lat, :lon, :acc, :sp, :h, :at)""",
                Db.p("s", subject, "id", subjectId, "d", deliveryId, "lat", latitude, "lon", longitude, "acc", accuracy,
                        "sp", speed, "h", heading, "at", java.sql.Timestamp.from(when)));
        if ("STATION_MOBILE".equals(subject)) {
            db.update("UPDATE station SET latitude = :lat, longitude = :lon WHERE id = :id",
                    Db.p("id", subjectId, "lat", latitude, "lon", longitude));
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("subject", subject);
        e.put("id", subjectId);
        e.put("name", name);
        e.put("deliveryId", deliveryId);
        e.put("latitude", latitude);
        e.put("longitude", longitude);
        e.put("speedKmh", speed);
        e.put("heading", heading);
        e.put("at", when);
        live.publish("location", "STATION_MOBILE".equals(subject) ? subjectId : null, e);
    }
}
