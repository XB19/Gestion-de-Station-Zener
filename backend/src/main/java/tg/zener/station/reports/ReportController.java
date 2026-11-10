package tg.zener.station.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/** Rapports journaliers (écran Rapports) : générés automatiquement, commentés puis validés par le gérant. */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAuthority('RAPPORTS')")
public class ReportController {

    private final Db db;
    private final ReportService reports;
    private final Journal journal;

    public ReportController(Db db, ReportService reports, Journal journal) {
        this.db = db;
        this.reports = reports;
        this.journal = journal;
    }

    static final String SELECT = """
            \sSELECT r.id, r.station_id, s.name AS station_name, s.short_name AS station_short_name, r.day, r.generated_at,
                   r.status, r.comment, r.comment_at, r.validated_at, r.comment_by,
                   e.first_name || ' ' || e.last_name AS comment_by_name, CASE WHEN e.photo_path IS NULL THEN NULL ELSE '/files/' || e.photo_path END AS comment_by_photo_url,
                   CAST(r.data ->> 'revenue' AS bigint) AS revenue, CAST(r.data ->> 'volume' AS numeric) AS volume,
                   CAST(r.data ->> 'transactions' AS bigint) AS transactions, CAST(r.data ->> 'cashGap' AS bigint) AS cash_gap
            FROM daily_report r JOIN station s ON s.id = r.station_id LEFT JOIN employee e ON e.id = r.comment_by""";

    private static Map<String, Object> decorate(Map<String, Object> r) {
        r.put("statusLabel", switch (String.valueOf(r.get("status"))) {
            case "COMMENTE" -> "Commenté";
            case "INCOMPLET" -> "Incomplet";
            default -> r.get("comment") != null ? "Brouillon" : "À commenter";
        });
        return r;
    }

    /** Rapports d'un jour (veille par défaut si celui du jour n'est pas encore généré). */
    @GetMapping("/daily")
    public Map<String, Object> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                    @RequestParam(required = false) Long stationId,
                                    @RequestParam(required = false) String status) {
        Long station = Auth.scopedStation(stationId);
        LocalDate d = day;
        if (d == null) {
            d = db.value("""
                    \sSELECT max(day) FROM daily_report WHERE (CAST(:s AS bigint) IS NULL OR station_id = :s)""",
                    Db.p("s", station), LocalDate.class);
            if (d == null) {
                d = LocalDate.now().minusDays(1);
            }
        }
        List<Map<String, Object>> items = db.list(SELECT + """
                 \sWHERE r.day = :d AND (CAST(:s AS bigint) IS NULL OR r.station_id = :s)
                   AND (CAST(:st AS text) IS NULL OR r.status = :st)
                ORDER BY revenue DESC NULLS LAST, s.name""",
                Db.p("d", d, "s", station, "st", status == null || status.isBlank() ? null : status));
        items.forEach(ReportController::decorate);
        Map<String, Object> totals = db.one("""
                \sSELECT count(*) AS reports, count(*) FILTER (WHERE status = 'COMMENTE') AS commented,
                       count(*) FILTER (WHERE status = 'INCOMPLET') AS incomplete,
                       COALESCE(sum(CAST(data ->> 'revenue' AS bigint)), 0) AS revenue,
                       COALESCE(sum(CAST(data ->> 'volume' AS numeric)), 0) AS volume
                FROM daily_report WHERE day = :d AND (CAST(:s AS bigint) IS NULL OR station_id = :s)""", Db.p("d", d, "s", station));
        return Map.of("day", d, "items", items, "totals", totals);
    }

    /** Historique des rapports d'une station. */
    @GetMapping("/daily/history")
    public List<Map<String, Object>> history(@RequestParam(required = false) Long stationId,
                                             @RequestParam(required = false) Integer limit) {
        Long station = Auth.scopedStation(stationId);
        List<Map<String, Object>> rows = db.list(SELECT + """
                 \sWHERE (CAST(:s AS bigint) IS NULL OR r.station_id = :s) ORDER BY r.day DESC, s.name LIMIT :l""",
                Db.p("s", station, "l", limit == null ? 60 : Math.max(1, Math.min(limit, 400))));
        rows.forEach(ReportController::decorate);
        return rows;
    }

    @GetMapping("/daily/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> r = db.require(SELECT.replace("SELECT r.id,", "SELECT r.data, r.id,") + " WHERE r.id = :id",
                Db.p("id", id), "Rapport introuvable");
        Auth.checkStation(((Number) r.get("stationId")).longValue());
        return decorate(r);
    }

    public record CommentForm(String comment) {}

    /** Commentaire du gérant (brouillon). */
    @PutMapping("/daily/{id}/comment")
    public Map<String, Object> comment(@PathVariable long id, @RequestBody CommentForm f) {
        Map<String, Object> r = get(id);
        if ("COMMENTE".equals(r.get("status"))) {
            throw ApiException.conflict("Ce rapport est déjà validé");
        }
        db.update("UPDATE daily_report SET comment = :c, comment_by = :u, comment_at = now() WHERE id = :id",
                Db.p("id", id, "c", blank(f.comment()), "u", Auth.user().id()));
        return get(id);
    }

    /** Validation du rapport commenté : il passe « Commenté ». */
    @PostMapping("/daily/{id}/validate")
    @Transactional
    public Map<String, Object> validate(@PathVariable long id, @RequestBody(required = false) CommentForm f) {
        CurrentUser u = Auth.user();
        Map<String, Object> r = get(id);
        if ("COMMENTE".equals(r.get("status"))) {
            throw ApiException.conflict("Ce rapport est déjà validé");
        }
        String comment = f != null && blank(f.comment()) != null ? blank(f.comment()) : (String) r.get("comment");
        if (comment == null) {
            throw new ApiException("Ajoutez un commentaire avant de valider le rapport");
        }
        db.update("""
                \sUPDATE daily_report SET comment = :c, comment_by = :u, comment_at = now(), status = 'COMMENTE', validated_at = now()
                WHERE id = :id""", Db.p("id", id, "c", comment, "u", u.id()));
        long station = ((Number) r.get("stationId")).longValue();
        journal.log("RAPPORT_VALIDE", Journal.MODIFICATIONS, "Rapport journalier validé",
                r.get("stationShortName") + " · " + ReportService.DAY.format((LocalDate) r.get("day")), null, station, null);
        return get(id);
    }

    /** Génération à la demande (rapport du jour en cours, ou régénération). */
    @PostMapping("/daily/generate")
    public Map<String, Object> generate(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                        @RequestParam(required = false) Long stationId) {
        Long station = Auth.scopedStation(stationId);
        LocalDate d = day == null ? LocalDate.now() : day;
        if (d.isAfter(LocalDate.now())) {
            throw new ApiException("Impossible de générer le rapport d'un jour à venir");
        }
        List<Long> ids = station != null ? List.of(station)
                : db.list("SELECT id FROM station WHERE active ORDER BY id").stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        ids.forEach(s -> reports.generate(s, d));
        return list(d, station, null);
    }

    // ------------------------------------------------------------------ résumé email

    @GetMapping("/email-summary/preview")
    public ReportService.Summary preview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        if (Auth.user().scope() != null) {
            throw ApiException.forbidden("Le résumé quotidien couvre tout le réseau");
        }
        return reports.summary(day == null ? LocalDate.now() : day);
    }

    @PostMapping("/email-summary/send")
    @PreAuthorize("hasAuthority('PARAMETRES')")
    public Map<String, Object> send(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        long id = reports.sendSummary(day == null ? LocalDate.now() : day);
        return Map.of("emailId", id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
