package tg.zener.station.journal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.Page;
import tg.zener.station.security.Auth;

/** Journal d'opérations (écran 13) et flux temps réel. */
@RestController
@RequestMapping("/api")
public class JournalController {

    public static final String SELECT = """
            \sSELECT o.id, o.at, o.type, o.category, o.title, o.detail, o.amount,
                   o.station_id, o.pdv_id, o.actor_id,
                   CASE WHEN e.id IS NULL THEN 'Système' ELSE e.first_name || ' ' || e.last_name END AS actor_name,
                   CASE WHEN e.id IS NULL THEN NULL
                        ELSE upper(left(e.first_name, 1) || left(e.last_name, 1)) END AS actor_initials,
                   r.label AS actor_role,
                   COALESCE(pv.name, s.short_name, es.short_name) AS place
            FROM operation_log o
            LEFT JOIN employee e ON e.id = o.actor_id
            LEFT JOIN role r ON r.code = e.role_code
            LEFT JOIN station s ON s.id = o.station_id
            LEFT JOIN station es ON es.id = e.station_id
            LEFT JOIN pdv pv ON pv.id = o.pdv_id""";

    private final Db db;
    private final LiveFeed live;

    public JournalController(Db db, LiveFeed live) {
        this.db = db;
        this.live = live;
    }

    @GetMapping("/journal")
    @PreAuthorize("hasAuthority('JOURNAL')")
    public Page list(@RequestParam(required = false) String q,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(required = false) Long userId,
                     @RequestParam(required = false) String category,
                     @RequestParam(required = false) Long stationId,
                     @RequestParam(required = false) Long pdvId,
                     @RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Integer size) {
        Long station = Auth.scopedStation(stationId);
        String where = """
                 \sWHERE (CAST(:q AS text) IS NULL OR o.title ILIKE '%' || :q || '%' OR o.detail ILIKE '%' || :q || '%'
                        OR (e.first_name || ' ' || e.last_name) ILIKE '%' || :q || '%')
                   AND (CAST(:from AS date) IS NULL OR o.at >= CAST(:from AS date))
                   AND (CAST(:to AS date) IS NULL OR o.at < CAST(:to AS date) + 1)
                   AND (CAST(:u AS bigint) IS NULL OR o.actor_id = :u)
                   AND (CAST(:c AS text) IS NULL OR o.category = :c)
                   AND (CAST(:s AS bigint) IS NULL OR o.station_id = :s)
                   AND (CAST(:p AS bigint) IS NULL OR o.pdv_id = :p)""";
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> params = Db.p("q", blank(q), "from", from, "to", to, "u", userId, "c", blank(category),
                "s", station, "p", pdvId, "lim", sz, "off", pg * sz);
        List<Map<String, Object>> items = db.list(SELECT + where + " ORDER BY o.at DESC, o.id DESC LIMIT :lim OFFSET :off", params);
        long total = db.count("SELECT count(*) FROM operation_log o LEFT JOIN employee e ON e.id = o.actor_id" + where, params);
        return new Page(items, total, pg, sz);
    }

    /** Répartition des opérations d'une journée par catégorie (anneau de l'écran Journal). */
    @GetMapping("/journal/breakdown")
    @PreAuthorize("hasAuthority('JOURNAL')")
    public Map<String, Object> breakdown(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                         @RequestParam(required = false) Long stationId) {
        Long station = Auth.scopedStation(stationId);
        LocalDate d = day == null ? LocalDate.now() : day;
        List<Map<String, Object>> rows = db.list("""
                \sSELECT category, count(*) AS count FROM operation_log
                WHERE at >= :d AND at < CAST(:d AS date) + 1 AND (CAST(:s AS bigint) IS NULL OR station_id = :s)
                GROUP BY category ORDER BY count DESC""", Db.p("d", d, "s", station));
        long total = rows.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        return Map.of("day", d, "total", total, "categories", rows);
    }

    /** Dernières opérations, pour le « Flux en direct » au chargement de la page. */
    @GetMapping("/feed")
    public List<Map<String, Object>> feed(@RequestParam(required = false) Integer limit) {
        Long station = Auth.scopedStation(null);
        return db.list(SELECT + " WHERE (CAST(:s AS bigint) IS NULL OR o.station_id = :s) ORDER BY o.at DESC, o.id DESC LIMIT :l",
                Db.p("s", station, "l", limit == null ? 10 : Math.min(limit, 100)));
    }

    /**
     * Flux temps réel (EventSource). Événements : operation, station, pump, location.
     * Le jeton est passé dans ?access_token=… car EventSource n'envoie pas d'en-tête.
     */
    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live() {
        return live.subscribe(Auth.user().scope());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
