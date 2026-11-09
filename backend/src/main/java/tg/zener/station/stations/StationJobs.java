package tg.zener.station.stations;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tg.zener.station.common.Db;
import tg.zener.station.common.LiveFeed;
import tg.zener.station.journal.Journal;

/**
 * Tâches de chaque minute : bascule automatique du mode selon les plages horaires et détection
 * des passages en ligne / instable / hors ligne des PC station.
 */
@Component
public class StationJobs {

    private static final Logger log = LoggerFactory.getLogger(StationJobs.class);

    private final Db db;
    private final StationService stations;
    private final Journal journal;
    private final LiveFeed live;

    public StationJobs(Db db, StationService stations, Journal journal, LiveFeed live) {
        this.db = db;
        this.stations = stations;
        this.journal = journal;
        this.live = live;
    }

    /**
     * Applique les plages horaires : ignore les stations sans bascule automatique ou dont la bascule
     * manuelle est encore en vigueur ; sinon met la station dans le mode prévu.
     */
    @Scheduled(cron = "5 * * * * *", zone = "${app.timezone}")
    public void applySchedules() {
        LocalDateTime now = stations.now();
        for (Map<String, Object> s : db.list("""
                \sSELECT id, mode, mode_override_until FROM station
                WHERE active AND auto_switch AND EXISTS (SELECT 1 FROM station_mode_window w WHERE w.station_id = station.id)""")) {
            long id = ((Number) s.get("id")).longValue();
            try {
                java.time.Instant until = (java.time.Instant) s.get("modeOverrideUntil");
                if (until != null && until.isAfter(java.time.Instant.now())) {
                    continue; // bascule manuelle prioritaire jusqu'au prochain changement de plage
                }
                String wanted = stations.schedule(id).modeAt(now);
                if (wanted == null) {
                    continue;
                }
                if (!wanted.equals(s.get("mode"))) {
                    stations.switchMode(id, wanted, "PLAGE", null);
                } else if (until != null) {
                    db.update("UPDATE station SET mode_override_until = NULL WHERE id = :id", Db.p("id", id));
                }
            } catch (Exception e) {
                log.warn("Bascule automatique impossible pour la station {} : {}", id, e.getMessage());
            }
        }
    }

    /** Compare l'état de chaque station à son dernier état connu et signale les changements. */
    @Scheduled(cron = "20 * * * * *", zone = "${app.timezone}")
    public void detectConnectivity() {
        for (Map<String, Object> s : db.list("""
                \sSELECT s.id, s.short_name, s.last_status, s.last_seen_at, """ + stations.statusSql() + """
                 \sAS status FROM station s WHERE s.active""")) {
            String status = (String) s.get("status");
            String before = (String) s.get("lastStatus");
            if (status.equals(before)) {
                continue;
            }
            long id = ((Number) s.get("id")).longValue();
            db.update("UPDATE station SET last_status = :st WHERE id = :id", Db.p("id", id, "st", status));
            if (before == null) {
                continue; // premier relevé : pas d'alerte
            }
            String title = switch (status) {
                case "HORS_LIGNE" -> "Station hors ligne";
                case "INSTABLE" -> "Connexion instable";
                default -> "Station de nouveau en ligne";
            };
            String detail = s.get("shortName") + (s.get("lastSeenAt") == null ? " · aucun signal reçu"
                    : " · dernier signal " + ZonedDateTime.ofInstant((java.time.Instant) s.get("lastSeenAt"), stations.zone())
                            .toLocalTime().withNano(0));
            journal.logAs(null, "CONNEXION_STATION", Journal.SYSTEME, title, detail, null, id, null);
            live.publish("station", id, Map.of("stationId", id, "status", status, "statusLabel", StationService.statusLabel(status)));
        }
    }
}
