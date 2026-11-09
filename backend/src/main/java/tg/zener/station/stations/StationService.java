package tg.zener.station.stations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;

/** État des stations (en ligne / instable / hors ligne), modes de fonctionnement et clés des PC station. */
@Service
public class StationService {

    private final Db db;
    private final Settings settings;
    private final Journal journal;
    private final LiveFeed live;
    private final ZoneId zone;

    public StationService(Db db, Settings settings, Journal journal, LiveFeed live, @Value("${app.timezone}") String zone) {
        this.db = db;
        this.settings = settings;
        this.journal = journal;
        this.live = live;
        this.zone = ZoneId.of(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    /** Expression SQL du statut d'une station (alias s) : EN_LIGNE, INSTABLE ou HORS_LIGNE. */
    public String statusSql() {
        long online = settings.getLong("station.online_seconds", 120);
        long unstable = settings.getLong("station.unstable_seconds", 900);
        return "CASE WHEN s.last_seen_at > now() - interval '" + online + " seconds' THEN 'EN_LIGNE' "
                + "WHEN s.last_seen_at > now() - interval '" + unstable + " seconds' THEN 'INSTABLE' "
                + "ELSE 'HORS_LIGNE' END";
    }

    public static String statusLabel(Object status) {
        return switch (String.valueOf(status)) {
            case "EN_LIGNE" -> "En ligne";
            case "INSTABLE" -> "Instable";
            default -> "Hors ligne";
        };
    }

    public static String modeLabel(Object mode) {
        return "AUTONOME".equals(mode) ? "Autonome" : "Conventionnel";
    }

    public static String typeLabel(Object type) {
        return "GERANCE_LIBRE".equals(type) ? "Gérance libre" : "Succursale";
    }

    // ------------------------------------------------------------------ plages horaires

    public ModeSchedule schedule(long stationId) {
        List<ModeSchedule.Window> windows = db.list("""
                \sSELECT days, holidays, conventional_from, conventional_to FROM station_mode_window
                WHERE station_id = :s ORDER BY id""", Db.p("s", stationId)).stream()
                .map(r -> {
                    Set<Integer> days = new HashSet<>();
                    for (Object d : (List<?>) r.get("days")) {
                        days.add(((Number) d).intValue());
                    }
                    return new ModeSchedule.Window(days, Boolean.TRUE.equals(r.get("holidays")),
                            (LocalTime) r.get("conventionalFrom"), (LocalTime) r.get("conventionalTo"));
                }).toList();
        Set<LocalDate> holidays = new HashSet<>();
        db.list("SELECT day FROM public_holiday WHERE day BETWEEN current_date - 1 AND current_date + 10")
                .forEach(r -> holidays.add((LocalDate) r.get("day")));
        return new ModeSchedule(windows, holidays);
    }

    public LocalDateTime now() {
        return ZonedDateTime.now(zone).toLocalDateTime();
    }

    /**
     * Bascule de mode. Une bascule manuelle reste en vigueur jusqu'au prochain changement
     * prévu par les plages horaires.
     */
    @Transactional
    public void switchMode(long stationId, String to, String trigger, Long userId) {
        Map<String, Object> s = db.require("SELECT id, short_name, mode FROM station WHERE id = :id FOR UPDATE",
                Db.p("id", stationId), "Station introuvable");
        String from = (String) s.get("mode");
        ZonedDateTime overrideUntil = null;
        if ("MANUEL".equals(trigger)) {
            LocalDateTime next = schedule(stationId).nextSwitch(now());
            overrideUntil = next == null ? null : next.atZone(zone);
        }
        db.update("UPDATE station SET mode = :m, mode_override_until = :o WHERE id = :id",
                Db.p("id", stationId, "m", to, "o", overrideUntil == null ? null : overrideUntil.toOffsetDateTime()));
        if (from.equals(to)) {
            return;
        }
        db.update("INSERT INTO mode_change (station_id, from_mode, to_mode, trigger, user_id) VALUES (:s, :f, :t, :tr, :u)",
                Db.p("s", stationId, "f", from, "t", to, "tr", trigger, "u", userId));
        journal.logAs(userId, "BASCULE_MODE", userId == null ? Journal.SYSTEME : Journal.MODIFICATIONS,
                "Bascule en mode " + modeLabel(to).toLowerCase(),
                s.get("shortName") + " · " + modeLabel(from) + " → " + modeLabel(to)
                        + ("PLAGE".equals(trigger) ? " · plage horaire" : " · manuelle"),
                null, stationId, null);
        live.publish("station", stationId, Map.of("stationId", stationId, "mode", to));
    }

    // ------------------------------------------------------------------ activité

    /** Litres distribués par heure et par carburant : { SUPER: [24 valeurs], GASOIL: […] }. */
    public Map<String, Object> litersByHour(long stationId, LocalDate day) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : db.list("""
                \sSELECT p.code, CAST(extract(hour FROM x.at) AS int) AS h, sum(sl.quantity) AS liters
                FROM sale x JOIN sale_line sl ON sl.sale_id = x.id JOIN product p ON p.id = sl.product_id
                WHERE x.station_id = :s AND x.at >= :d AND x.at < CAST(:d AS date) + 1 AND p.category = 'CARBURANT'
                GROUP BY p.code, h""", Db.p("s", stationId, "d", day))) {
            @SuppressWarnings("unchecked")
            List<Object> hours = (List<Object>) out.computeIfAbsent((String) r.get("code"), k -> {
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    l.add(BigDecimal.ZERO);
                }
                return l;
            });
            hours.set(((Number) r.get("h")).intValue(), r.get("liters"));
        }
        return out;
    }

    // ------------------------------------------------------------------ clé du PC station

    /** Génère une nouvelle clé pour le PC station ; seule son empreinte est conservée. */
    public String newApiKey(long stationId) {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        db.update("UPDATE station SET api_key_hash = :h WHERE id = :id", Db.p("id", stationId, "h", sha256(key)));
        return key;
    }

    /** Station correspondant au couple code / clé envoyé par le PC station, sinon erreur 401. */
    public Map<String, Object> authenticateStation(String code, String key) {
        if (code == null || key == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "CLE_STATION", "Clé de station manquante");
        }
        Map<String, Object> s = db.one("SELECT id, code, short_name, mode, api_key_hash, kind FROM station WHERE code = :c AND active",
                Db.p("c", code));
        if (s == null || s.get("apiKeyHash") == null
                || !MessageDigest.isEqual(sha256(key).getBytes(StandardCharsets.US_ASCII),
                        ((String) s.get("apiKeyHash")).getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "CLE_STATION", "Clé de station invalide");
        }
        return s;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
