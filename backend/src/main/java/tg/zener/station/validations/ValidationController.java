package tg.zener.station.validations;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.security.Auth;

/** Validations (écran 08) : demandes en attente, détail, décision, historique. */
@RestController
@RequestMapping("/api/validations")
@PreAuthorize("hasAuthority('VALIDER_MONTANTS') or hasAuthority('PDV_VALIDER') or hasAuthority('RECHARGER') or hasAuthority('TRANSACTIONS')")
public class ValidationController {

    private static final String SELECT = """
            \sSELECT v.id, v.ref, v.kind, v.beneficiary, v.summary, v.amount, v.company_card, v.status, v.source,
                   v.payment_info, v.target_id, v.requested_at, v.decided_at,
                   e.first_name || ' ' || e.last_name AS requested_by_name, COALESCE(s.short_name, 'Siège') AS requested_from,
                   cur.roles AS awaiting_roles,
                   CASE v.status WHEN 'APPROUVEE' THEN 'Approuvée' WHEN 'REFUSEE' THEN 'Refusée'
                        ELSE 'Attente ' || CASE WHEN 'DAF' = ANY(cur.roles) AND NOT 'ADMIN' = ANY(cur.roles) THEN 'DAF'
                                                WHEN 'COMPTABLE' = ANY(cur.roles) THEN 'Comptable' ELSE 'Admin / DAF' END
                   END AS status_label,
                   (SELECT string_agg(de.first_name || ' ' || left(de.last_name, 1) || '.', ' · ' ORDER BY st.step_no)
                    FROM validation_step st JOIN employee de ON de.id = st.decided_by WHERE st.request_id = v.id) AS deciders
            FROM validation_request v
            JOIN employee e ON e.id = v.requested_by
            LEFT JOIN station s ON s.id = e.station_id
            LEFT JOIN validation_step cur ON cur.request_id = v.id AND cur.status = 'EN_ATTENTE'""";

    private final Db db;
    private final ValidationService validations;

    public ValidationController(Db db, ValidationService validations) {
        this.db = db;
        this.validations = validations;
    }

    /** status=EN_ATTENTE (par défaut), HISTORIQUE (approuvées et refusées), ou un statut précis. */
    @GetMapping
    public Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String kind,
                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        String st = status == null ? "EN_ATTENTE" : status;
        String where = """
                 \sWHERE (CASE :st WHEN 'HISTORIQUE' THEN v.status <> 'EN_ATTENTE' WHEN 'TOUS' THEN true ELSE v.status = :st END)
                   AND (CAST(:k AS text) IS NULL OR v.kind = :k)""";
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> p = Db.p("st", st, "k", kind, "lim", sz, "off", pg * sz);
        String order = "EN_ATTENTE".equals(st) ? " ORDER BY v.requested_at" : " ORDER BY COALESCE(v.decided_at, v.requested_at) DESC";
        return new Page(db.list(SELECT + where + order + " LIMIT :lim OFFSET :off", p),
                db.count("SELECT count(*) FROM validation_request v" + where, p), pg, sz);
    }

    /** Indicateurs : en attente, approuvées ce mois, refusées, délai moyen (vs mois précédent). */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> m = db.one("""
                \sSELECT count(*) FILTER (WHERE status = 'EN_ATTENTE') AS pending,
                       COALESCE(sum(amount) FILTER (WHERE status = 'EN_ATTENTE'), 0) AS pending_amount,
                       count(*) FILTER (WHERE status = 'APPROUVEE' AND decided_at >= date_trunc('month', now())) AS approved_month,
                       COALESCE(sum(amount) FILTER (WHERE status = 'APPROUVEE' AND decided_at >= date_trunc('month', now())), 0) AS approved_month_amount,
                       count(*) FILTER (WHERE status = 'REFUSEE' AND decided_at >= date_trunc('month', now())) AS refused_month,
                       max(decided_at) FILTER (WHERE status = 'REFUSEE') AS last_refused_at,
                       round(avg(extract(epoch FROM decided_at - requested_at) / 60)
                             FILTER (WHERE decided_at >= date_trunc('month', now()))) AS avg_delay_minutes,
                       round(avg(extract(epoch FROM decided_at - requested_at) / 60)
                             FILTER (WHERE decided_at >= date_trunc('month', now()) - interval '1 month'
                                     AND decided_at < date_trunc('month', now()))) AS avg_delay_minutes_prev
                FROM validation_request""", Map.of());
        m.put("threshold", validations.threshold());
        return m;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> v = db.require(SELECT + " WHERE v.id = :id", Db.p("id", id), "Demande introuvable");
        v.put("steps", db.list("""
                \sSELECT st.step_no, st.roles, st.status, st.decided_at, st.comment,
                       e.first_name || ' ' || e.last_name AS decided_by_name,
                       upper(left(e.first_name, 1) || left(e.last_name, 1)) AS initials,
                       CASE WHEN e.photo_path IS NULL THEN NULL ELSE '/files/' || e.photo_path END AS photo_url,
                       (SELECT string_agg(a.first_name || ' ' || a.last_name, ', ')
                        FROM employee a WHERE a.active AND a.role_code = ANY(st.roles)) AS approvers
                FROM validation_step st LEFT JOIN employee e ON e.id = st.decided_by
                WHERE st.request_id = :id ORDER BY st.step_no""", Db.p("id", id)));
        List<Map<String, Object>> cards = db.list("""
                \sSELECT c.id, c.number, r.amount, h.name AS holder_name
                FROM recharge r JOIN card c ON c.id = r.card_id LEFT JOIN holder h ON h.id = c.holder_id
                WHERE r.validation_id = :id ORDER BY r.id""", Db.p("id", id));
        cards.forEach(c -> c.put("masked", CardService.mask((String) c.get("number"))));
        v.put("cards", cards);
        if (ValidationService.OUVERTURE_PDV.equals(v.get("kind"))) {
            v.put("pdv", db.one("""
                    \sSELECT id, name, address, start_date, end_date, cards_planned, reason FROM pdv WHERE validation_id = :id""",
                    Db.p("id", id)));
        }
        v.put("aboveThreshold", v.get("amount") != null && ((Number) v.get("amount")).longValue() > validations.threshold());
        v.put("canDecide", canDecide(v));
        return v;
    }

    private boolean canDecide(Map<String, Object> v) {
        var u = Auth.user();
        if (!"EN_ATTENTE".equals(v.get("status")) || v.get("awaitingRoles") == null) {
            return false;
        }
        Long requester = db.value("SELECT requested_by FROM validation_request WHERE id = :id", Db.p("id", v.get("id")), Long.class);
        return requester != null && requester != u.id() && ((List<?>) v.get("awaitingRoles")).contains(u.role());
    }

    public record Decision(String comment) {}

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id, @RequestBody(required = false) Decision d) {
        validations.decide(id, true, d == null ? null : d.comment(), Auth.user());
        return get(id);
    }

    @PostMapping("/{id}/refuse")
    public Map<String, Object> refuse(@PathVariable long id, @RequestBody(required = false) Decision d) {
        validations.decide(id, false, d == null ? null : d.comment(), Auth.user());
        return get(id);
    }
}
