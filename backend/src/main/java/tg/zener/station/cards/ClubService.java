package tg.zener.station.cards;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.notifications.Notifier;
import tg.zener.station.recharges.RechargeService;
import tg.zener.station.security.CurrentUser;

/**
 * Carte Club (carte noire) : adhésion à l'attribution ou à la vente (prix club.price + dépôt initial d'au moins
 * club.initial_deposit crédité sur la carte), valable club.validity_months mois. À l'échéance : renouvellement d'office
 * si la carte a consommé au moins club.auto_renew_spend sur les 12 derniers mois, sinon renouvellement payé
 * (club.renewal_fee). Période de grâce de club.grace_days jours ; ensuite la carte reste utilisable (solde conservé)
 * mais sans les avantages Club (profil de prix « Club », plafonds par défaut plus élevés) jusqu'au renouvellement.
 * Rappels au titulaire et à la comptabilité à J-30, J-7, J-1, au début et à la fin de la grâce.
 */
@Service
public class ClubService {

    public static final String CLUB = "CLUB";

    private final Db db;
    private final Settings settings;
    private final Journal journal;
    private final CardEvents events;
    private final Notifier notifier;
    private final RechargeService recharges;

    public ClubService(Db db, Settings settings, Journal journal, CardEvents events, Notifier notifier, RechargeService recharges) {
        this.db = db;
        this.settings = settings;
        this.journal = journal;
        this.events = events;
        this.notifier = notifier;
        this.recharges = recharges;
    }

    public long initialDeposit() {
        return settings.getLong("club.initial_deposit", 200_000);
    }

    /** ACTIF, GRACE (avantages conservés), EXPIRE (sans avantages) ou null (pas une carte Club). */
    public String status(String typeCode, LocalDate clubUntil) {
        if (!CLUB.equals(typeCode)) {
            return null;
        }
        if (clubUntil == null) {
            return "EXPIRE";
        }
        LocalDate today = LocalDate.now();
        if (!today.isAfter(clubUntil)) {
            return "ACTIF";
        }
        return today.isAfter(clubUntil.plusDays(settings.getLong("club.grace_days", 30))) ? "EXPIRE" : "GRACE";
    }

    /** Les avantages Club s'appliquent (adhésion en cours ou période de grâce). */
    public boolean perks(Map<String, Object> card) {
        String s = status((String) card.get("typeCode"), (LocalDate) card.get("clubUntil"));
        return "ACTIF".equals(s) || "GRACE".equals(s);
    }

    /**
     * Adhésion : dépôt initial crédité (recharge au guichet ou au point de vente), adhésion de 12 mois, plafonds Club.
     * Appelée juste après l'attribution de la carte (carte active).
     */
    @Transactional
    public Map<String, Object> join(long cardId, long deposit, String paymentMethod, String paymentRef, CurrentUser user, Long stationId,
                                    Long pdvId) {
        Map<String, Object> c = card(cardId);
        if (!CLUB.equals(c.get("typeCode"))) {
            throw new ApiException("Ce n'est pas une carte Club");
        }
        if (deposit < initialDeposit()) {
            throw new ApiException("Carte Club : dépôt initial d'au moins " + fmt(initialDeposit()) + " F à créditer sur la carte");
        }
        if (!"ACTIVE".equals(c.get("status"))) {
            throw ApiException.conflict("La carte doit être active pour l'adhésion Club");
        }
        recharges.recharge(new RechargeService.Request((String) c.get("number"), deposit, "UNITAIRE", payment(paymentMethod), paymentRef,
                stationId, pdvId), user);
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusMonths(settings.getLong("club.validity_months", 12));
        db.update("UPDATE card SET club_until = :e WHERE id = :id", Db.p("id", cardId, "e", end));
        db.update("""
                \sINSERT INTO club_membership (card_id, kind, period_start, period_end, fee, deposit, payment_method, payment_ref, employee_id)
                VALUES (:c, 'ADHESION', :s, :e, :f, :d, :pm, :pr, :u)""",
                Db.p("c", cardId, "s", start, "e", end, "f", settings.getLong("club.price", 10_000), "d", deposit,
                        "pm", payment(paymentMethod), "pr", paymentRef, "u", user.id()));
        // avantages : plafonds par défaut plus élevés (sans réduire des plafonds déjà plus hauts)
        db.update("""
                \sUPDATE card_permission SET daily_limit = GREATEST(COALESCE(daily_limit, 0), :d), monthly_limit = GREATEST(COALESCE(monthly_limit, 0), :m),
                       updated_at = now(), updated_by = :u
                WHERE card_id = :c""",
                Db.p("c", cardId, "d", settings.getLong("club.daily_limit", 1_000_000), "m", settings.getLong("club.monthly_limit", 10_000_000),
                        "u", user.id()));
        events.recordAs(user.id(), cardId, "CLUB", "Adhésion Club jusqu'au " + end + " · dépôt " + fmt(deposit) + " F", stationId, pdvId);
        journal.logAs(user.id(), "CLUB_ADHESION", Journal.VENTES_CARTES, "Adhésion Club",
                CardService.mask((String) c.get("number")) + " · " + c.get("holderName") + " · jusqu'au " + end,
                settings.getLong("club.price", 10_000), stationId, pdvId);
        plan(cardId);
        return summary(cardId);
    }

    /** Renouvellement payé (frais club.renewal_fee) : 12 mois de plus à partir de l'échéance (ou d'aujourd'hui si elle est passée). */
    @Transactional
    public Map<String, Object> renew(long cardId, String paymentMethod, String paymentRef, CurrentUser user) {
        Map<String, Object> c = card(cardId);
        if (!CLUB.equals(c.get("typeCode"))) {
            throw new ApiException("Ce n'est pas une carte Club");
        }
        if ("INACTIVE".equals(c.get("status"))) {
            throw ApiException.conflict("Carte retirée");
        }
        long fee = settings.getLong("club.renewal_fee", 10_000);
        extend(c, "RENOUVELLEMENT_PAYE", fee, null, payment(paymentMethod), paymentRef, user.id());
        journal.logAs(user.id(), "CLUB_RENOUVELLEMENT", Journal.VENTES_CARTES, "Carte Club renouvelée",
                CardService.mask((String) c.get("number")) + " · " + c.get("holderName") + " · frais", fee, null, null);
        return summary(cardId);
    }

    private LocalDate extend(Map<String, Object> c, String kind, long fee, Long spent, String paymentMethod, String paymentRef, Long employeeId) {
        long cardId = ((Number) c.get("id")).longValue();
        LocalDate until = (LocalDate) c.get("clubUntil");
        LocalDate start = until == null || until.isBefore(LocalDate.now()) ? LocalDate.now() : until.plusDays(1);
        LocalDate end = start.plusMonths(settings.getLong("club.validity_months", 12)).minusDays(until == null || until.isBefore(LocalDate.now()) ? 0 : 1);
        db.update("UPDATE card SET club_until = :e WHERE id = :id", Db.p("id", cardId, "e", end));
        db.update("""
                \sINSERT INTO club_membership (card_id, kind, period_start, period_end, fee, spent_12m, payment_method, payment_ref, employee_id)
                VALUES (:c, :k, :s, :e, :f, :sp, :pm, :pr, :u)""",
                Db.p("c", cardId, "k", kind, "s", start, "e", end, "f", fee, "sp", spent, "pm", paymentMethod, "pr", paymentRef, "u", employeeId));
        events.recordAs(employeeId, cardId, "CLUB", ("RENOUVELLEMENT_AUTO".equals(kind) ? "Renouvellement d'office (consommation " + fmt(spent) + " F)"
                : "Renouvellement payé · " + fmt(fee) + " F") + " · jusqu'au " + end, null, null);
        notifier.cancel("CARTE", cardId, "CLUB");
        plan(cardId);
        return end;
    }

    /** Rappels de l'adhésion en cours (titulaire par email, comptabilité dans l'application). */
    public void plan(long cardId) {
        Map<String, Object> c = card(cardId);
        LocalDate until = (LocalDate) c.get("clubUntil");
        if (until == null) {
            return;
        }
        String name = CardService.mask((String) c.get("number")) + " · " + c.get("holderName");
        List<String> holder = c.get("holderEmail") == null ? List.of() : List.of((String) c.get("holderEmail"));
        String link = "/cartes/" + cardId;
        for (String d : settings.getList("club.reminder_days")) {
            int days;
            try {
                days = Integer.parseInt(d.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            LocalDate due = until.minusDays(days);
            if (due.isBefore(LocalDate.now())) {
                continue;
            }
            notifier.schedule("CLUB", "CARTE", cardId, due, days <= 7 ? Notifier.ATTENTION : Notifier.INFO,
                    "Carte Club : échéance dans " + days + " jour" + (days > 1 ? "s" : "") + " · " + name,
                    "L'adhésion Club se termine le " + until + ". Renouvellement d'office si la carte a consommé au moins "
                            + fmt(settings.getLong("club.auto_renew_spend", 1_200_000)) + " F sur 12 mois, sinon frais de "
                            + fmt(settings.getLong("club.renewal_fee", 10_000)) + " F.", link, null, List.of(), holder);
        }
        long grace = settings.getLong("club.grace_days", 30);
        notifier.schedule("CLUB", "CARTE", cardId, until.plusDays(1), Notifier.ATTENTION, "Carte Club échue · période de grâce · " + name,
                "L'adhésion est arrivée à échéance le " + until + ". Les avantages restent actifs " + grace + " jours ; renouvelez la carte pour les garder.",
                link, null, List.of(), holder);
        notifier.schedule("CLUB", "CARTE", cardId, until.plusDays(grace + 1), Notifier.ATTENTION, "Carte Club : avantages suspendus · " + name,
                "La période de grâce est terminée : la carte fonctionne au tarif grand public jusqu'au renouvellement (le solde est conservé).",
                link, null, List.of(), holder);
    }

    /** Chaque nuit : renouvellement d'office des adhésions échues dont la carte a assez consommé. */
    @Scheduled(cron = "0 20 2 * * *", zone = "${app.timezone}")
    @Transactional
    public void nightly() {
        long threshold = settings.getLong("club.auto_renew_spend", 1_200_000);
        for (Map<String, Object> c : db.list("""
                \sSELECT c.id, (SELECT COALESCE(sum(x.total), 0) FROM sale x WHERE x.card_id = c.id AND x.at >= now() - interval '12 months') AS spent
                FROM card c WHERE c.type_code = 'CLUB' AND c.status = 'ACTIVE' AND c.club_until IS NOT NULL AND c.club_until < current_date""")) {
            long spent = ((Number) c.get("spent")).longValue();
            if (spent >= threshold) {
                Map<String, Object> card = card(((Number) c.get("id")).longValue());
                LocalDate end = extend(card, "RENOUVELLEMENT_AUTO", 0, spent, null, null, null);
                journal.logAs(null, "CLUB_RENOUVELLEMENT", Journal.VENTES_CARTES, "Carte Club renouvelée d'office",
                        CardService.mask((String) card.get("number")) + " · " + card.get("holderName") + " · consommation " + fmt(spent)
                                + " F · jusqu'au " + end, null, null, null);
            }
        }
    }

    /** État de l'adhésion pour la fiche de la carte. */
    public Map<String, Object> summary(long cardId) {
        Map<String, Object> c = card(cardId);
        Map<String, Object> out = new LinkedHashMap<>();
        String s = status((String) c.get("typeCode"), (LocalDate) c.get("clubUntil"));
        out.put("status", s);
        out.put("statusLabel", s == null ? null : switch (s) {
            case "ACTIF" -> "Club actif";
            case "GRACE" -> "Club · période de grâce";
            default -> "Club échu (sans avantages)";
        });
        out.put("until", c.get("clubUntil"));
        out.put("graceUntil", c.get("clubUntil") == null ? null : ((LocalDate) c.get("clubUntil")).plusDays(settings.getLong("club.grace_days", 30)));
        long spent = db.sum("SELECT COALESCE(sum(total), 0) FROM sale WHERE card_id = :c AND at >= now() - interval '12 months'", Db.p("c", cardId));
        out.put("spent12m", spent);
        out.put("autoRenewSpend", settings.getLong("club.auto_renew_spend", 1_200_000));
        out.put("autoRenewReached", spent >= settings.getLong("club.auto_renew_spend", 1_200_000));
        out.put("renewalFee", settings.getLong("club.renewal_fee", 10_000));
        out.put("memberships", db.list("""
                \sSELECT m.kind, m.period_start, m.period_end, m.fee, m.deposit, m.spent_12m, m.payment_method, m.created_at,
                       e.first_name || ' ' || e.last_name AS employee_name
                FROM club_membership m LEFT JOIN employee e ON e.id = m.employee_id WHERE m.card_id = :c ORDER BY m.period_start DESC""",
                Db.p("c", cardId)));
        return out;
    }

    private Map<String, Object> card(long id) {
        return db.require("""
                \sSELECT c.id, c.number, c.type_code, c.status, c.club_until, h.name AS holder_name, h.email AS holder_email
                FROM card c LEFT JOIN holder h ON h.id = c.holder_id WHERE c.id = :id""", Db.p("id", id), "Carte introuvable");
    }

    private static String payment(String m) {
        return m == null || m.isBlank() ? "ESPECES" : m;
    }

    public static String fmt(Long v) {
        return v == null ? "0" : String.format("%,d", v).replace(',', ' ');
    }

    static List<String> list() {
        return new ArrayList<>();
    }
}
