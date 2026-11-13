package tg.zener.station.credit;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Mailer;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;

/**
 * Crédit clients (phase 2e). Une carte de crédit (credit_limit &gt; 0) peut payer jusqu'à son plafond ; chaque mois,
 * un relevé par client reprend ses achats à crédit de la période et l'impayé reporté des relevés précédents.
 * Un règlement (partiel ou total) rétablit le solde des cartes du client, dans l'ordre de leur dette.
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);
    static final List<String> OPEN = List.of("A_PAYER", "PARTIELLE", "EN_RETARD");

    private final Db db;
    private final Settings settings;
    private final Journal journal;
    private final Mailer mailer;
    private final TransactionTemplate tx;

    public CreditService(Db db, Settings settings, Journal journal, Mailer mailer, PlatformTransactionManager tm) {
        this.db = db;
        this.settings = settings;
        this.journal = journal;
        this.mailer = mailer;
        this.tx = new TransactionTemplate(tm);
    }

    /** Chaque jour : relevés du mois écoulé (à partir du jour de facturation) et échéances dépassées. */
    @Scheduled(cron = "0 40 0 * * *", zone = "${app.timezone}")
    public void job() {
        try {
            LocalDate today = LocalDate.now();
            if (today.getDayOfMonth() >= settings.getLong("credit.bill_day", 1)) {
                generate(YearMonth.from(today).minusMonths(1), today, null);
            }
            overdue();
        } catch (Exception e) {
            log.warn("Relevés de crédit : {}", e.getMessage());
        }
    }

    /** Relevés en retard : échéance passée et solde dû. */
    public int overdue() {
        return db.update("""
                \sUPDATE credit_bill SET status = 'EN_RETARD' WHERE status IN ('A_PAYER', 'PARTIELLE') AND due_date < current_date""", Map.of());
    }

    /**
     * Relevés d'un mois pour les clients qui ont des cartes de crédit (ou un client donné). Idempotent (un relevé par
     * client et par mois). {@code issuedOn} : date d'établissement (échéance = date + credit.payment_days).
     */
    public int generate(YearMonth month, LocalDate issuedOn, Long employeeId) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        int n = 0;
        for (Map<String, Object> h : db.list("""
                \sSELECT DISTINCT c.holder_id FROM card c WHERE c.credit_limit > 0 AND c.holder_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM credit_bill b WHERE b.holder_id = c.holder_id AND b.period_start = :f)""",
                Db.p("f", from))) {
            long holderId = ((Number) h.get("holderId")).longValue();
            Integer made = tx.execute(s -> bill(holderId, from, to, issuedOn, employeeId) ? 1 : 0);
            n += made == null ? 0 : made;
        }
        return n;
    }

    private boolean bill(long holderId, LocalDate from, LocalDate to, LocalDate issuedOn, Long employeeId) {
        long consumption = db.sum("""
                \sSELECT COALESCE(-sum(m.amount), 0) FROM card_movement m JOIN card c ON c.id = m.card_id
                WHERE c.holder_id = :h AND c.credit_limit > 0 AND m.kind = 'VENTE' AND m.at >= :f AND m.at < :t""",
                Db.p("h", holderId, "f", from, "t", to.plusDays(1)));
        long previous = db.sum("""
                \sSELECT COALESCE(sum(amount_due - paid), 0) FROM credit_bill WHERE holder_id = :h AND status IN ('A_PAYER', 'PARTIELLE', 'EN_RETARD')""",
                Db.p("h", holderId));
        if (consumption == 0 && previous == 0) {
            return false;
        }
        db.update("UPDATE credit_bill SET status = 'REPORTEE' WHERE holder_id = :h AND status IN ('A_PAYER', 'PARTIELLE', 'EN_RETARD')",
                Db.p("h", holderId));
        String ref = "FAC-" + Year.from(from) + "-" + db.sum("SELECT nextval('ref_bill_seq')", Map.of());
        LocalDate due = issuedOn.plusDays(settings.getLong("credit.payment_days", 15));
        long id = db.insert("""
                \sINSERT INTO credit_bill (ref, holder_id, period_start, period_end, previous_due, consumption, amount_due, due_date, generated_at)
                VALUES (:ref, :h, :f, :t, :p, :c, :a, :d, CAST(:g AS date) + time '00:40')""",
                Db.p("ref", ref, "h", holderId, "f", from, "t", to, "p", previous, "c", consumption, "a", previous + consumption, "d", due,
                        "g", issuedOn));
        db.update("""
                \sINSERT INTO credit_bill_line (bill_id, card_id, sale_id, at, amount)
                SELECT :b, m.card_id, m.sale_id, m.at, -m.amount FROM card_movement m JOIN card c ON c.id = m.card_id
                WHERE c.holder_id = :h AND c.credit_limit > 0 AND m.kind = 'VENTE' AND m.at >= :f AND m.at < :t""",
                Db.p("b", id, "h", holderId, "f", from, "t", to.plusDays(1)));
        Map<String, Object> holder = db.one("SELECT name, email FROM holder WHERE id = :h", Db.p("h", holderId));
        journal.logAs(employeeId, "RELEVE_CREDIT", Journal.RECHARGES, "Relevé de crédit établi",
                holder.get("name") + " · " + ref + " · échéance " + due, previous + consumption, null, null);
        if (holder.get("email") != null) {
            mailer.queue(List.of((String) holder.get("email")), "Relevé ZENER " + ref,
                    "Bonjour,\n\nVotre relevé " + ref + " du " + from + " au " + to + " s'élève à " + fmt(previous + consumption)
                            + " F (dont " + fmt(previous) + " F reportés). Échéance : " + due + ".\n\nZENER", "RELEVE_CREDIT");
        }
        return true;
    }

    /** Règlement d'un relevé ; il rétablit le solde des cartes de crédit du client, les plus endettées d'abord. */
    @Transactional
    public Map<String, Object> pay(long billId, long amount, String method, String ref, String note, long employeeId) {
        Map<String, Object> b = db.require("SELECT * FROM credit_bill WHERE id = :id FOR UPDATE", Db.p("id", billId), "Relevé introuvable");
        if (!OPEN.contains(b.get("status"))) {
            throw ApiException.conflict("Ce relevé n'est pas à régler (" + b.get("status") + ")");
        }
        long remaining = num(b.get("amountDue")) - num(b.get("paid"));
        if (amount <= 0 || amount > remaining) {
            throw new ApiException("Montant à régler entre 1 et " + fmt(remaining) + " F");
        }
        long paid = num(b.get("paid")) + amount;
        String status = paid >= num(b.get("amountDue")) ? "PAYEE" : "PARTIELLE";
        db.update("""
                \sUPDATE credit_bill SET paid = :p, status = :s, paid_at = CASE WHEN :s = 'PAYEE' THEN now() ELSE paid_at END WHERE id = :id""",
                Db.p("id", billId, "p", paid, "s", status));
        db.update("""
                \sINSERT INTO credit_payment (bill_id, amount, payment_method, payment_ref, employee_id, note) VALUES (:b, :a, :m, :r, :e, :n)""",
                Db.p("b", billId, "a", amount, "m", method, "r", ref, "e", employeeId, "n", note));
        long left = amount;
        for (Map<String, Object> c : db.list("""
                \sSELECT id, balance FROM card WHERE holder_id = :h AND credit_limit > 0 AND balance < 0 ORDER BY balance, id FOR UPDATE""",
                Db.p("h", b.get("holderId")))) {
            if (left <= 0) {
                break;
            }
            long credit = Math.min(left, -num(c.get("balance")));
            long balance = db.value("UPDATE card SET balance = balance + :a WHERE id = :id RETURNING balance",
                    Db.p("id", c.get("id"), "a", credit), Long.class);
            db.update("""
                    \sINSERT INTO card_movement (card_id, kind, amount, balance_after, detail, employee_id)
                    VALUES (:c, 'REGLEMENT', :a, :b, :d, :e)""",
                    Db.p("c", c.get("id"), "a", credit, "b", balance, "d", "Règlement " + b.get("ref"), "e", employeeId));
            left -= credit;
        }
        Map<String, Object> holder = db.one("SELECT name FROM holder WHERE id = :h", Db.p("h", b.get("holderId")));
        journal.logAs(employeeId, "REGLEMENT_CREDIT", Journal.RECHARGES, "Règlement de relevé" + ("PAYEE".equals(status) ? " (soldé)" : ""),
                holder.get("name") + " · " + b.get("ref"), amount, null, null);
        return db.one("SELECT * FROM credit_bill WHERE id = :id", Db.p("id", billId));
    }

    static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }

    static long num(Object o) {
        return ((Number) o).longValue();
    }
}
