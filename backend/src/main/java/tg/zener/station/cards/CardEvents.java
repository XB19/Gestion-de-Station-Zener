package tg.zener.station.cards;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tg.zener.station.common.Db;
import tg.zener.station.security.Auth;

/**
 * Journal des opérations de service sur les cartes (équivalent de card_service_logs de LTStation) :
 * émission, activation, blocage, déblocage, PIN, permissions, attribution / vente en PDV…
 * Chaque événement porte l'opérateur (l'employé connecté, ou celui indiqué hors requête).
 */
@Component
public class CardEvents {

    public static final String EMISSION = "EMISSION";
    public static final String MODIFICATION = "MODIFICATION";
    public static final String PERMISSIONS = "PERMISSIONS";
    public static final String ACTIVATION = "ACTIVATION";
    public static final String BLOCAGE = "BLOCAGE";
    public static final String DEBLOCAGE = "DEBLOCAGE";
    public static final String PIN = "PIN";
    public static final String ATTRIBUTION_PDV = "ATTRIBUTION_PDV";
    public static final String RETOUR_STOCK = "RETOUR_STOCK";
    public static final String VENTE_PDV = "VENTE_PDV";
    public static final String DESACTIVATION = "DESACTIVATION";

    /** Libellés français (écrans et extractions). */
    public static final String LABEL_SQL = """
            \sCASE ce.kind WHEN 'EMISSION' THEN 'Émission' WHEN 'MODIFICATION' THEN 'Modification' WHEN 'PERMISSIONS' THEN 'Permissions'
                         WHEN 'ACTIVATION' THEN 'Activation' WHEN 'BLOCAGE' THEN 'Blocage' WHEN 'DEBLOCAGE' THEN 'Déblocage'
                         WHEN 'PIN' THEN 'Code PIN' WHEN 'ATTRIBUTION_PDV' THEN 'Attribution à un PDV'
                         WHEN 'RETOUR_STOCK' THEN 'Retour au stock' WHEN 'VENTE_PDV' THEN 'Vente en PDV'
                         WHEN 'DESACTIVATION' THEN 'Désactivation' WHEN 'REMPLACEMENT' THEN 'Remplacement' ELSE ce.kind END""";

    private final Db db;

    public CardEvents(Db db) {
        this.db = db;
    }

    /** Événement attribué à l'utilisateur connecté (ou au système hors requête). */
    public void record(long cardId, String kind, String detail, Long stationId, Long pdvId) {
        recordAs(Auth.userIdOrNull(), cardId, kind, detail, stationId, pdvId);
    }

    public void recordAs(Long employeeId, long cardId, String kind, String detail, Long stationId, Long pdvId) {
        db.update("""
                \sINSERT INTO card_event (card_id, kind, employee_id, station_id, pdv_id, detail)
                VALUES (:c, :k, :e, :s, :p, :d)""",
                Db.p("c", cardId, "k", kind, "e", employeeId, "s", stationId, "p", pdvId, "d", detail));
    }

    /** Même événement pour plusieurs cartes (attribution d'un lot à un point de vente, retour au stock). */
    public void recordMany(List<Long> cardIds, String kind, String detail, Long stationId, Long pdvId) {
        if (cardIds.isEmpty()) {
            return;
        }
        db.update("""
                \sINSERT INTO card_event (card_id, kind, employee_id, station_id, pdv_id, detail)
                SELECT unnest(:ids), :k, :e, :s, :p, :d""",
                Db.p("ids", db.array("bigint", cardIds.toArray()), "k", kind, "e", Auth.userIdOrNull(), "s", stationId,
                        "p", pdvId, "d", detail));
    }

    /** Événements d'une carte, les plus récents d'abord. */
    public List<Map<String, Object>> of(long cardId, int limit) {
        return db.list("""
                \sSELECT ce.at, ce.kind,""" + LABEL_SQL + """
                 \sAS label, ce.detail, ce.employee_id, e.first_name || ' ' || e.last_name AS employee_name,
                       COALESCE(s.short_name, pv.name) AS place
                FROM card_event ce LEFT JOIN employee e ON e.id = ce.employee_id
                LEFT JOIN station s ON s.id = ce.station_id LEFT JOIN pdv pv ON pv.id = ce.pdv_id
                WHERE ce.card_id = :c ORDER BY ce.at DESC, ce.id DESC LIMIT :l""", Db.p("c", cardId, "l", limit));
    }

    /**
     * Données de démonstration créées directement en SQL : reprend l'historique connu par les colonnes de la carte
     * (émission, vente en PDV, activation, blocage) si aucun événement n'existe encore.
     */
    public int backfill() {
        if (db.count("SELECT count(*) FROM card_event", Map.of()) > 0) {
            return 0;
        }
        int n = db.update("""
                \sINSERT INTO card_event (card_id, at, kind, employee_id, detail)
                SELECT id, COALESCE(sold_at, created_at), 'EMISSION', issued_by, NULL
                FROM card WHERE issued_by IS NOT NULL AND sold_pdv_id IS NULL""", Map.of());
        n += db.update("""
                \sINSERT INTO card_event (card_id, at, kind, employee_id, pdv_id)
                SELECT id, sold_at, 'VENTE_PDV', sold_by, sold_pdv_id FROM card WHERE sold_pdv_id IS NOT NULL AND sold_at IS NOT NULL""",
                Map.of());
        n += db.update("""
                \sINSERT INTO card_event (card_id, at, kind, employee_id)
                SELECT id, activated_at, 'ACTIVATION', activated_by FROM card WHERE activated_at IS NOT NULL""", Map.of());
        n += db.update("""
                \sINSERT INTO card_event (card_id, at, kind, detail)
                SELECT id, now() - interval '3 days', 'BLOCAGE', blocked_reason FROM card WHERE status = 'BLOQUEE'""", Map.of());
        return n;
    }
}
