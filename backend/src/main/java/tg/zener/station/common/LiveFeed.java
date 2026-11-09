package tg.zener.station.common;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Flux temps réel (Server-Sent Events) : le tableau de bord s'abonne à /api/live
 * et reçoit les opérations, les changements d'état des stations et les positions GPS.
 * Chaque abonné ne reçoit que les événements de sa station s'il est rattaché à une station.
 */
@Component
public class LiveFeed {

    private record Subscriber(SseEmitter emitter, Long stationScope) {}

    private final Map<SseEmitter, Subscriber> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long stationScope) {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.put(emitter, new Subscriber(emitter, stationScope));
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        send(emitter, "hello", Map.of("ok", true));
        return emitter;
    }

    /** Publie un événement ; stationId null = visible par tous. */
    public void publish(String event, Long stationId, Object data) {
        for (Subscriber s : subscribers.values()) {
            if (s.stationScope() == null || stationId == null || s.stationScope().equals(stationId)) {
                send(s.emitter(), event, data);
            }
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(emitter);
        }
    }

    /** Commentaire périodique pour garder les connexions ouvertes derrière un proxy. */
    @Scheduled(fixedDelay = 25_000)
    void keepAlive() {
        for (Subscriber s : subscribers.values()) {
            try {
                s.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(s.emitter());
            }
        }
    }

    public int size() {
        return subscribers.size();
    }
}
