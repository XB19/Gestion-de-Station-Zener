package tg.zener.station.controls;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;

/** Écran Contrôles : alertes de soldes et recharges suspects, revue et clôture (droit CONTROLES). */
@RestController
@PreAuthorize("hasAuthority('CONTROLES')")
public class SuspicionController {

    private static final String SELECT = """
            \sSELECT s.id, s.card_id, c.number, c.balance, c.status AS card_status, h.name AS holder_name, h.kind AS holder_kind,
                   s.rule, s.severity, s.title, s.detail, s.amount, s.status, s.created_at, s.updated_at, s.closed_at, s.justified,
                   s.resolution, a.first_name || ' ' || a.last_name AS assigned_name, cb.first_name || ' ' || cb.last_name AS closed_by_name,
                   CASE s.status WHEN 'OUVERTE' THEN 'Ouverte' WHEN 'EN_REVUE' THEN 'En revue' ELSE 'Close' END AS status_label
            FROM suspicion s JOIN card c ON c.id = s.card_id LEFT JOIN holder h ON h.id = c.holder_id
            LEFT JOIN employee a ON a.id = s.assigned_to LEFT JOIN employee cb ON cb.id = s.closed_by""";

    private final Db db;
    private final SuspicionService service;
    private final Journal journal;

    public SuspicionController(Db db, SuspicionService service, Journal journal) {
        this.db = db;
        this.service = service;
        this.journal = journal;
    }

    @GetMapping("/api/controls")
    public Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String rule,
                     @RequestParam(required = false) Long cardId, @RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Integer size) {
        int sz = Page.size(size);
        int pg = Page.page(page);
        String st = status == null ? "A_TRAITER" : status;
        String where = """
                 \sWHERE (CASE :st WHEN 'A_TRAITER' THEN s.status <> 'CLOSE' WHEN 'TOUTES' THEN true ELSE s.status = :st END)
                   AND (CAST(:r AS text) IS NULL OR s.rule = :r) AND (CAST(:c AS bigint) IS NULL OR s.card_id = :c)""";
        Map<String, Object> p = Db.p("st", st, "r", rule, "c", cardId, "l", sz, "o", pg * sz);
        List<Map<String, Object>> items = db.list(SELECT + where
                + " ORDER BY s.status = 'CLOSE', s.severity = 'MOYENNE', s.created_at DESC LIMIT :l OFFSET :o", p);
        items.forEach(i -> i.put("numberFormatted", CardService.format((String) i.get("number"))));
        return new Page(items, db.count("SELECT count(*) FROM suspicion s" + where, p), pg, sz);
    }

    @GetMapping("/api/controls/summary")
    public Map<String, Object> summary() {
        Map<String, Object> s = db.one("""
                \sSELECT count(*) FILTER (WHERE status = 'OUVERTE') AS open, count(*) FILTER (WHERE status = 'EN_REVUE') AS in_review,
                       count(*) FILTER (WHERE status <> 'CLOSE' AND severity = 'ELEVEE') AS high,
                       count(*) FILTER (WHERE status = 'CLOSE' AND closed_at >= date_trunc('month', now())) AS closed_month,
                       count(*) FILTER (WHERE status = 'CLOSE' AND justified = false AND closed_at >= date_trunc('month', now())) AS confirmed_month
                FROM suspicion""", Map.of());
        s.put("byRule", db.list("""
                \sSELECT rule, count(*) AS n FROM suspicion WHERE status <> 'CLOSE' GROUP BY rule ORDER BY count(*) DESC"""));
        s.put("rules", SuspicionService.RULES.stream().map(r -> Map.of("code", r, "label", SuspicionService.label(r))).toList());
        return s;
    }

    /** Détail : l'alerte, les dernières recharges, ventes et transferts de la carte. */
    @GetMapping("/api/controls/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> s = db.require(SELECT + " WHERE s.id = :id", Db.p("id", id), "Alerte introuvable");
        s.put("numberFormatted", CardService.format((String) s.get("number")));
        s.put("recharges", db.list("""
                \sSELECT r.ref, r.created_at, r.amount, r.channel, r.payment_method, r.status, st.short_name AS station_name,
                       e.first_name || ' ' || e.last_name AS operator
                FROM recharge r LEFT JOIN station st ON st.id = r.station_id LEFT JOIN employee e ON e.id = r.requested_by
                WHERE r.card_id = :c ORDER BY r.created_at DESC LIMIT 15""", Db.p("c", s.get("cardId"))));
        s.put("sales", db.list("""
                \sSELECT x.at, x.total, st.short_name AS station_name FROM sale x LEFT JOIN station st ON st.id = x.station_id
                WHERE x.card_id = :c ORDER BY x.at DESC LIMIT 10""", Db.p("c", s.get("cardId"))));
        s.put("history", db.list("""
                \sSELECT rule, status, created_at, closed_at, justified, resolution FROM suspicion WHERE card_id = :c AND id <> :id
                ORDER BY created_at DESC LIMIT 10""", Db.p("c", s.get("cardId"), "id", id)));
        return s;
    }

    @PostMapping("/api/controls/{id}/review")
    public Map<String, Object> review(@PathVariable long id) {
        db.require("""
                \sUPDATE suspicion SET status = 'EN_REVUE', assigned_to = :u, updated_at = now() WHERE id = :id AND status <> 'CLOSE'
                RETURNING id""", Db.p("id", id, "u", Auth.user().id()), "Alerte introuvable ou déjà close");
        return get(id);
    }

    public record CloseForm(Boolean justified, String resolution) {}

    @PostMapping("/api/controls/{id}/close")
    public Map<String, Object> close(@PathVariable long id, @RequestBody CloseForm f) {
        if (f.resolution() == null || f.resolution().isBlank()) {
            throw new ApiException("Note de clôture requise (ce qui a été vérifié)");
        }
        if (f.justified() == null) {
            throw new ApiException("Indiquez si l'activité est justifiée ou non");
        }
        Map<String, Object> s = db.require("""
                \sUPDATE suspicion SET status = 'CLOSE', closed_by = :u, closed_at = now(), updated_at = now(), justified = :j, resolution = :r
                WHERE id = :id AND status <> 'CLOSE' RETURNING card_id, title""",
                Db.p("id", id, "u", Auth.user().id(), "j", f.justified(), "r", f.resolution().trim()), "Alerte introuvable ou déjà close");
        journal.audit("CONTROLE_CLOS", Journal.SECURITE, "Alerte de contrôle close · " + (f.justified() ? "justifiée" : "anomalie confirmée"),
                s.get("title") + "", null, Map.of("justified", f.justified()), f.resolution(), null);
        return get(id);
    }

    /** Lance les contrôles tout de suite (sinon toutes les 15 minutes). */
    @PostMapping("/api/controls/scan")
    public Map<String, Object> scan() {
        return Map.of("created", service.run());
    }
}
