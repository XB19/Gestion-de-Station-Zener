package tg.zener.station.common;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Paramètres modifiables de l'application (table setting). */
@Service
public class Settings {

    private final Db db;

    public Settings(Db db) {
        this.db = db;
    }

    public String get(String key, String fallback) {
        String v = db.value("SELECT value FROM setting WHERE key = :k", Db.p("k", key), String.class);
        return v == null ? fallback : v;
    }

    public long getLong(String key, long fallback) {
        try {
            return Long.parseLong(get(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(get(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        return Boolean.parseBoolean(get(key, String.valueOf(fallback)).trim());
    }

    public LocalTime getTime(String key, LocalTime fallback) {
        try {
            return LocalTime.parse(get(key, fallback.toString()).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public List<String> getList(String key) {
        return Arrays.stream(get(key, "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public Map<String, Object> all() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : db.list("SELECT key, value FROM setting ORDER BY key")) {
            m.put((String) r.get("key"), r.get("value"));
        }
        return m;
    }

    public void set(String key, String value) {
        db.update("""
                \sINSERT INTO setting (key, value, updated_at) VALUES (:k, :v, now())
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", Db.p("k", key, "v", value));
    }
}
