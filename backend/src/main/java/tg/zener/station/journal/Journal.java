package tg.zener.station.journal;

import java.util.Map;
import org.springframework.stereotype.Service;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.security.Auth;

/**
 * Journal d'opérations : qui a fait quoi, où et quand. Chaque entrée est aussi poussée
 * dans le flux temps réel du tableau de bord.
 */
@Service
public class Journal {

    public static final String VENTES_CARBURANT = "VENTES_CARBURANT";
    public static final String RECHARGES = "RECHARGES";
    public static final String VENTES_CARTES = "VENTES_CARTES";
    public static final String VENTES_BOUTIQUE = "VENTES_BOUTIQUE";
    public static final String VALIDATIONS = "VALIDATIONS";
    public static final String MODIFICATIONS = "MODIFICATIONS";
    public static final String SYSTEME = "SYSTEME";
    public static final String LIVRAISONS = "LIVRAISONS";

    private final Db db;
    private final LiveFeed live;

    public Journal(Db db, LiveFeed live) {
        this.db = db;
        this.live = live;
    }

    /** Entrée attribuée à l'utilisateur connecté (ou au système hors requête). */
    public void log(String type, String category, String title, String detail, Long amount, Long stationId, Long pdvId) {
        logAs(Auth.userIdOrNull(), type, category, title, detail, amount, stationId, pdvId);
    }

    public void logAs(Long actorId, String type, String category, String title, String detail, Long amount,
                      Long stationId, Long pdvId) {
        long id = db.insert("""
                \sINSERT INTO operation_log (actor_id, station_id, pdv_id, type, category, title, detail, amount)
                VALUES (:a, :s, :p, :t, :c, :ti, :d, :m)""",
                Db.p("a", actorId, "s", stationId, "p", pdvId, "t", type, "c", category, "ti", title, "d", detail,
                        "m", amount));
        Map<String, Object> entry = db.one(JournalController.SELECT + " WHERE o.id = :id", Db.p("id", id));
        live.publish("operation", stationId, entry);
    }
}
