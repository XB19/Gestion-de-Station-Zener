package tg.zener.station.journal;

import java.util.Map;
import org.springframework.stereotype.Service;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.common.RequestInfo;
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
    public static final String EXTRACTIONS = "EXTRACTIONS";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    public static final String SECURITE = "SECURITE";

    private final Db db;
    private final LiveFeed live;

    public static String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

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
        write(actorId, type, category, title, detail, amount, stationId, pdvId, null, null, null);
    }

    /**
     * Modification sensible : en plus de l'auteur, de l'adresse IP, de l'appareil et de la session (toujours relevés),
     * on garde l'état avant / après et le motif donné.
     */
    public void audit(String type, String category, String title, String detail, Object before, Object after, String reason,
                      Long stationId) {
        write(Auth.userIdOrNull(), type, category, title, detail, null, stationId, null, before, after, reason);
    }

    private void write(Long actorId, String type, String category, String title, String detail, Long amount, Long stationId,
                       Long pdvId, Object before, Object after, String reason) {
        long id = db.insert("""
                \sINSERT INTO operation_log (actor_id, station_id, pdv_id, type, category, title, detail, amount, ip, user_agent, session_id,
                                           before_value, after_value, reason)
                VALUES (:a, :s, :p, :t, :c, :ti, :d, :m, :ip, :ua, :sid, CAST(:bv AS jsonb), CAST(:av AS jsonb), :r)""",
                Db.p("a", actorId, "s", stationId, "p", pdvId, "t", type, "c", category, "ti", title, "d", detail,
                        "m", amount, "ip", RequestInfo.ip(), "ua", RequestInfo.userAgent(), "sid", RequestInfo.sessionId(),
                        "bv", toJson(before), "av", toJson(after), "r", reason == null || reason.isBlank() ? null : reason.trim()));
        Map<String, Object> entry = db.one(JournalController.SELECT + " WHERE o.id = :id", Db.p("id", id));
        live.publish("operation", stationId, entry);
    }
}
