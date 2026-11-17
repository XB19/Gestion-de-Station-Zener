package tg.zener.station.controls;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.notifications.Notifier;

/**
 * Détection des soldes et recharges suspects (toutes les 15 minutes). Rien n'est bloqué : chaque cas ouvre une alerte de
 * contrôle (une seule alerte non close par carte et par règle), notifiée à la comptabilité et au DAF.
 */
@Service
public class SuspicionService {

    private static final Logger log = LoggerFactory.getLogger(SuspicionService.class);

    private final Db db;
    private final Settings settings;
    private final Notifier notifier;
    private final Journal journal;

    public SuspicionService(Db db, Settings settings, Notifier notifier, Journal journal) {
        this.db = db;
        this.settings = settings;
        this.notifier = notifier;
        this.journal = journal;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 90_000)
    public void scan() {
        try {
            int n = run();
            if (n > 0) {
                log.info("Contrôles : {} nouvelle(s) alerte(s)", n);
            }
        } catch (Exception e) {
            log.warn("Contrôles des soldes impossibles : {}", e.getMessage());
        }
    }

    /** Passe toutes les règles ; renvoie le nombre d'alertes ouvertes. */
    public int run() {
        long threshold = settings.getLong("validation.threshold", 500_000);
        int created = 0;
        // contournement du palier : recharges fractionnées juste sous le seuil
        created += open("FRACTIONNEMENT", "ELEVEE", """
                \sSELECT r.card_id, count(*) AS n, sum(r.amount) AS amount FROM recharge r
                WHERE r.created_at >= now() - interval '24 hours' AND r.amount >= :low AND r.amount <= :th
                GROUP BY r.card_id HAVING count(*) >= :cnt""",
                Db.p("low", Math.round(threshold * settings.getDouble("controls.split_ratio", 0.8)), "th", threshold,
                        "cnt", settings.getLong("controls.split_count", 3)),
                r -> r.get("n") + " recharges juste sous le palier de " + fmt(threshold) + " F en 24 h (total " + fmt(r.get("amount"))
                        + " F) : validation contournée ?");
        created += open("RECHARGE_ATYPIQUE", "MOYENNE", """
                \sSELECT r.card_id, max(r.amount) AS amount, avg_prev.avg FROM recharge r
                CROSS JOIN LATERAL (SELECT avg(p.amount) AS avg FROM recharge p WHERE p.card_id = r.card_id
                                    AND p.created_at < r.created_at AND p.created_at >= r.created_at - interval '90 days') avg_prev
                WHERE r.created_at >= now() - interval '24 hours' AND r.amount >= :min AND avg_prev.avg IS NOT NULL
                  AND r.amount >= avg_prev.avg * :f
                GROUP BY r.card_id, avg_prev.avg""",
                Db.p("min", settings.getLong("controls.atypical_min", 300_000), "f", settings.getDouble("controls.atypical_factor", 5)),
                r -> "Recharge de " + fmt(r.get("amount")) + " F, " + Math.round(((Number) r.get("amount")).doubleValue()
                        / ((Number) r.get("avg")).doubleValue()) + " fois la recharge habituelle (" + fmt(((Number) r.get("avg")).longValue()) + " F)");
        created += open("RECHARGES_RAPPROCHEES", "MOYENNE", """
                \sSELECT r.card_id, count(*) AS n, sum(r.amount) AS amount FROM recharge r
                WHERE r.created_at >= now() - interval '24 hours' GROUP BY r.card_id HAVING count(*) >= :cnt""",
                Db.p("cnt", settings.getLong("controls.rapid_count", 5)),
                r -> r.get("n") + " recharges en 24 h (total " + fmt(r.get("amount")) + " F)");
        created += open("HORS_HORAIRES", "MOYENNE", """
                \sSELECT r.card_id, count(*) AS n, sum(r.amount) AS amount FROM recharge r
                WHERE r.created_at >= now() - interval '24 hours' AND r.channel <> 'MASSE'
                  AND (CAST(r.created_at AT TIME ZONE current_setting('TimeZone') AS time) >= CAST(:from AS time)
                       OR CAST(r.created_at AT TIME ZONE current_setting('TimeZone') AS time) < CAST(:to AS time))
                GROUP BY r.card_id""",
                Db.p("from", settings.get("controls.night_from", "22:00"), "to", settings.get("controls.night_to", "05:00")),
                r -> r.get("n") + " recharge(s) de nuit (total " + fmt(r.get("amount")) + " F)");
        created += open("MEME_OPERATEUR", "MOYENNE", """
                \sSELECT r.card_id, count(*) AS n, sum(r.amount) AS amount, e.first_name || ' ' || e.last_name AS operator
                FROM recharge r JOIN employee e ON e.id = r.requested_by JOIN card c ON c.id = r.card_id
                WHERE r.created_at >= now() - interval '7 days' AND r.channel <> 'MASSE' AND c.type_code <> 'FLOTTE'
                GROUP BY r.card_id, e.id HAVING count(*) >= :cnt""",
                Db.p("cnt", settings.getLong("controls.same_operator_count", 5)),
                r -> r.get("operator") + " a rechargé cette carte " + r.get("n") + " fois en 7 jours (total " + fmt(r.get("amount")) + " F)");
        created += open("SOLDE_DORMANT", "MOYENNE", """
                \sSELECT c.id AS card_id, c.balance AS amount,
                       (SELECT max(x.at) FROM sale x WHERE x.card_id = c.id) AS last_sale
                FROM card c WHERE c.status = 'ACTIVE' AND c.balance >= :b
                  AND NOT EXISTS (SELECT 1 FROM sale x WHERE x.card_id = c.id AND x.at >= now() - make_interval(days => CAST(:d AS int)))
                  AND c.activated_at < now() - make_interval(days => CAST(:d AS int))""",
                Db.p("b", settings.getLong("controls.dormant_balance", 1_000_000), "d", settings.getLong("controls.dormant_days", 30)),
                r -> "Solde de " + fmt(r.get("amount")) + " F sans aucun achat depuis "
                        + (r.get("lastSale") == null ? "l'activation" : settings.getLong("controls.dormant_days", 30) + " jours ou plus"));
        created += open("ACCUMULATION", "MOYENNE", """
                \sSELECT c.id AS card_id, rc.total AS amount, COALESCE(sp.total, 0) AS spent FROM card c
                CROSS JOIN LATERAL (SELECT sum(r.amount) AS total FROM recharge r WHERE r.card_id = c.id AND r.status = 'CREDITEE'
                                    AND r.created_at >= now() - interval '90 days') rc
                LEFT JOIN LATERAL (SELECT sum(x.total) AS total FROM sale x WHERE x.card_id = c.id AND x.at >= now() - interval '90 days') sp ON true
                WHERE c.status = 'ACTIVE' AND rc.total >= :min AND rc.total >= COALESCE(sp.total, 0) * :ratio""",
                Db.p("min", settings.getLong("controls.accumulation_min", 1_000_000), "ratio", settings.getDouble("controls.accumulation_ratio", 3)),
                r -> fmt(r.get("amount")) + " F rechargés en 90 jours pour " + fmt(r.get("spent")) + " F dépensés : le solde s'accumule");
        created += open("VA_ET_VIENT", "ELEVEE", """
                \sSELECT t.from_card_id AS card_id, sum(t.amount) AS amount, count(*) AS n FROM card_transfer t
                WHERE t.at >= now() - interval '7 days' AND t.reason = 'TRANSFERT'
                  AND EXISTS (SELECT 1 FROM card_transfer b WHERE b.from_card_id = t.to_card_id AND b.to_card_id = t.from_card_id
                              AND b.at >= now() - interval '7 days' AND b.reason = 'TRANSFERT')
                GROUP BY t.from_card_id""", Map.of(),
                r -> "Transferts dans les deux sens avec une autre carte en 7 jours (" + r.get("n") + " transfert(s), " + fmt(r.get("amount")) + " F)");
        return created;
    }

    private int open(String rule, String severity, String sql, Map<String, Object> params,
                     java.util.function.Function<Map<String, Object>, String> detail) {
        int n = 0;
        for (Map<String, Object> r : db.list(sql, params)) {
            long cardId = ((Number) r.get("cardId")).longValue();
            String text = detail.apply(r);
            Long id = db.value("""
                    \sINSERT INTO suspicion (card_id, rule, severity, title, detail, amount) VALUES (:c, :r, :s, :t, :d, :a)
                    ON CONFLICT (card_id, rule) WHERE status <> 'CLOSE' DO NOTHING RETURNING id""",
                    Db.p("c", cardId, "r", rule, "s", severity, "t", label(rule), "d", text,
                            "a", r.get("amount") == null ? null : ((Number) r.get("amount")).longValue()), Long.class);
            if (id == null) {
                continue;
            }
            n++;
            Map<String, Object> c = db.one("""
                    \sSELECT c.number, h.name FROM card c LEFT JOIN holder h ON h.id = c.holder_id WHERE c.id = :c""", Db.p("c", cardId));
            String who = CardService.mask((String) c.get("number")) + (c.get("name") == null ? "" : " · " + c.get("name"));
            notifier.send(new Notifier.Message("SUSPICION", "ELEVEE".equals(severity) ? Notifier.URGENT : Notifier.ATTENTION,
                    label(rule) + " · " + who, text + "\nÀ examiner dans l'écran Contrôles (aucun blocage automatique).", "/controles?id=" + id, null));
            journal.logAs(null, "CONTROLE", Journal.SECURITE, "Alerte de contrôle · " + label(rule), who + " · " + text, null, null, null);
        }
        return n;
    }

    public static String label(String rule) {
        return switch (rule) {
            case "FRACTIONNEMENT" -> "Recharges fractionnées sous le palier";
            case "RECHARGE_ATYPIQUE" -> "Recharge atypique";
            case "RECHARGES_RAPPROCHEES" -> "Recharges rapprochées";
            case "HORS_HORAIRES" -> "Recharge de nuit";
            case "MEME_OPERATEUR" -> "Même opérateur, même carte";
            case "SOLDE_DORMANT" -> "Solde dormant";
            case "ACCUMULATION" -> "Solde qui s'accumule";
            case "VA_ET_VIENT" -> "Transferts aller-retour";
            default -> rule;
        };
    }

    static final List<String> RULES = List.of("FRACTIONNEMENT", "RECHARGE_ATYPIQUE", "RECHARGES_RAPPROCHEES", "HORS_HORAIRES",
            "MEME_OPERATEUR", "SOLDE_DORMANT", "ACCUMULATION", "VA_ET_VIENT");

    static String fmt(Object v) {
        return v == null ? "0" : String.format("%,d", ((Number) v).longValue()).replace(',', ' ');
    }
}
