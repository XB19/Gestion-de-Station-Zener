package tg.zener.station.credit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.YearMonth;
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
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.security.Auth;

/** Écran Crédit clients : encours par client, relevés mensuels, règlements. */
@RestController
@RequestMapping("/api/credit")
@PreAuthorize("hasAuthority('CREDIT')")
public class CreditController {

    private final Db db;
    private final CreditService credit;

    public CreditController(Db db, CreditService credit) {
        this.db = db;
        this.credit = credit;
    }

    /** Clients à crédit : plafond total, utilisé, relevés dus et en retard. */
    @GetMapping("/clients")
    public List<Map<String, Object>> clients() {
        return db.list("""
                \sSELECT h.id, h.name, h.kind, h.phone, h.email, count(c.id) AS cards, sum(c.credit_limit) AS credit_limit,
                       COALESCE(-sum(c.balance) FILTER (WHERE c.balance < 0), 0) AS used,
                       (SELECT COALESCE(sum(b.amount_due - b.paid), 0) FROM credit_bill b WHERE b.holder_id = h.id
                        AND b.status IN ('A_PAYER', 'PARTIELLE', 'EN_RETARD')) AS billed_due,
                       (SELECT count(*) FROM credit_bill b WHERE b.holder_id = h.id AND b.status = 'EN_RETARD') AS overdue,
                       (SELECT COALESCE(-sum(m.amount), 0) FROM card_movement m JOIN card c2 ON c2.id = m.card_id
                        WHERE c2.holder_id = h.id AND c2.credit_limit > 0 AND m.kind = 'VENTE' AND m.at >= date_trunc('month', now())) AS month_to_date
                FROM holder h JOIN card c ON c.holder_id = h.id AND c.credit_limit > 0
                GROUP BY h.id ORDER BY h.name""");
    }

    /** Cartes de crédit d'un client. */
    @GetMapping("/clients/{holderId}/cards")
    public List<Map<String, Object>> cards(@PathVariable long holderId) {
        List<Map<String, Object>> rows = db.list("""
                \sSELECT c.id, c.number, c.status, c.balance, c.credit_limit, g.name AS group_name, c.vehicle_plate, c.driver_name
                FROM card c LEFT JOIN card_group g ON g.id = c.group_id
                WHERE c.holder_id = :h AND c.credit_limit > 0 ORDER BY c.number""", Db.p("h", holderId));
        rows.forEach(r -> r.put("numberFormatted", CardService.format((String) r.get("number"))));
        return rows;
    }

    @GetMapping("/bills")
    public Page bills(@RequestParam(required = false) Long holderId, @RequestParam(required = false) String status,
                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        int sz = Page.size(size);
        int pg = Page.page(page);
        String where = """
                 \sWHERE (CAST(:h AS bigint) IS NULL OR b.holder_id = :h)
                   AND (CAST(:s AS text) IS NULL OR b.status = :s OR (:s = 'OUVERTS' AND b.status IN ('A_PAYER', 'PARTIELLE', 'EN_RETARD')))""";
        Map<String, Object> p = Db.p("h", holderId, "s", status, "l", sz, "o", pg * sz);
        return new Page(db.list("""
                \sSELECT b.id, b.ref, b.holder_id, h.name AS holder_name, b.period_start, b.period_end, b.previous_due, b.consumption,
                       b.amount_due, b.paid, b.amount_due - b.paid AS remaining, b.due_date, b.status, b.generated_at, b.paid_at,
                       (SELECT count(*) FROM credit_bill_line l WHERE l.bill_id = b.id) AS lines
                FROM credit_bill b JOIN holder h ON h.id = b.holder_id""" + where
                + " ORDER BY b.period_start DESC, h.name LIMIT :l OFFSET :o", p),
                db.count("SELECT count(*) FROM credit_bill b" + where, p), pg, sz);
    }

    @GetMapping("/bills/{id}")
    public Map<String, Object> bill(@PathVariable long id) {
        Map<String, Object> b = db.require("""
                \sSELECT b.*, h.name AS holder_name, h.address AS holder_address, h.phone AS holder_phone, b.amount_due - b.paid AS remaining
                FROM credit_bill b JOIN holder h ON h.id = b.holder_id WHERE b.id = :id""", Db.p("id", id), "Relevé introuvable");
        List<Map<String, Object>> lines = db.list("""
                \sSELECT l.at, l.amount, c.number, c.vehicle_plate, c.driver_name, s.short_name AS station_name,
                       (SELECT string_agg(sl.quantity || ' ' || p.unit || ' ' || p.name, ', ') FROM sale_line sl JOIN product p ON p.id = sl.product_id
                        WHERE sl.sale_id = l.sale_id) AS detail
                FROM credit_bill_line l JOIN card c ON c.id = l.card_id LEFT JOIN sale x ON x.id = l.sale_id LEFT JOIN station s ON s.id = x.station_id
                WHERE l.bill_id = :id ORDER BY l.at""", Db.p("id", id));
        lines.forEach(l -> l.put("numberFormatted", CardService.format((String) l.get("number"))));
        b.put("lines", lines);
        b.put("byCard", db.list("""
                \sSELECT c.number, c.vehicle_plate, count(*) AS fills, sum(l.amount) AS amount
                FROM credit_bill_line l JOIN card c ON c.id = l.card_id WHERE l.bill_id = :id GROUP BY c.number, c.vehicle_plate ORDER BY c.number""",
                Db.p("id", id)));
        b.put("payments", db.list("""
                \sSELECT p.at, p.amount, p.payment_method, p.payment_ref, p.note, e.first_name || ' ' || e.last_name AS employee_name
                FROM credit_payment p LEFT JOIN employee e ON e.id = p.employee_id WHERE p.bill_id = :id ORDER BY p.at""", Db.p("id", id)));
        return b;
    }

    public record GenerateForm(@Pattern(regexp = "\\d{4}-\\d{2}") String month) {}

    /** Établit les relevés d'un mois (par défaut, le mois écoulé). */
    @PostMapping("/bills/generate")
    public Map<String, Object> generate(@RequestBody(required = false) GenerateForm f) {
        YearMonth m = f == null || f.month() == null ? YearMonth.now().minusMonths(1) : YearMonth.parse(f.month());
        if (!m.isBefore(YearMonth.now())) {
            throw new ApiException("Seul un mois terminé peut être facturé");
        }
        int n = credit.generate(m, LocalDate.now(), Auth.user().id());
        credit.overdue();
        return Map.of("created", n, "month", m.toString());
    }

    public record PaymentForm(@Positive long amount,
                              @NotBlank @Pattern(regexp = "ESPECES|MOBILE_MONEY|VIREMENT|CHEQUE|CARTE_BANCAIRE") String paymentMethod,
                              String paymentRef, String note) {}

    @PostMapping("/bills/{id}/payments")
    public Map<String, Object> pay(@PathVariable long id, @Valid @RequestBody PaymentForm f) {
        credit.pay(id, f.amount(), f.paymentMethod(), blank(f.paymentRef()), blank(f.note()), Auth.user().id());
        return bill(id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
