package tg.zener.station.stations;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Plages horaires du mode conventionnel d'une station : en dehors des plages, la station
 * passe en autonome. Un jour férié utilise les plages marquées « fériés » s'il y en a.
 */
public final class ModeSchedule {

    public static final String CONVENTIONNEL = "CONVENTIONNEL";
    public static final String AUTONOME = "AUTONOME";

    public record Window(Set<Integer> days, boolean holidays, LocalTime from, LocalTime to) {

        boolean covers(LocalTime t) {
            if (from.equals(to)) {
                return true; // 24 h sur 24
            }
            return from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
        }
    }

    private final List<Window> windows;
    private final Set<LocalDate> holidays;

    public ModeSchedule(List<Window> windows, Set<LocalDate> holidays) {
        this.windows = windows;
        this.holidays = holidays;
    }

    public boolean isEmpty() {
        return windows.isEmpty();
    }

    /** Plages qui s'appliquent ce jour-là. */
    List<Window> windowsFor(LocalDate day) {
        if (holidays.contains(day)) {
            List<Window> h = windows.stream().filter(Window::holidays).toList();
            if (!h.isEmpty()) {
                return h;
            }
        }
        int dow = day.getDayOfWeek().getValue();
        return windows.stream().filter(w -> w.days().contains(dow)).toList();
    }

    /** Mode voulu à cet instant, ou null si aucune plage n'est définie. */
    public String modeAt(LocalDateTime at) {
        if (windows.isEmpty()) {
            return null;
        }
        LocalTime t = at.toLocalTime();
        // une plage qui passe minuit (ex. 22:00 → 02:00) déborde sur le lendemain
        for (Window w : windowsFor(at.toLocalDate())) {
            if (w.from().isBefore(w.to()) || w.from().equals(w.to()) ? w.covers(t) : !t.isBefore(w.from())) {
                return CONVENTIONNEL;
            }
        }
        for (Window w : windowsFor(at.toLocalDate().minusDays(1))) {
            if (w.from().isAfter(w.to()) && t.isBefore(w.to())) {
                return CONVENTIONNEL;
            }
        }
        return AUTONOME;
    }

    /** Prochain changement de mode après « from » (recherche sur 8 jours), ou null. */
    public LocalDateTime nextSwitch(LocalDateTime from) {
        String current = modeAt(from);
        if (current == null) {
            return null;
        }
        LocalDateTime best = null;
        for (int d = 0; d <= 8; d++) {
            LocalDate day = from.toLocalDate().plusDays(d);
            for (Window w : windowsFor(day)) {
                LocalDateTime end = w.from().isAfter(w.to()) ? day.plusDays(1).atTime(w.to()) : day.atTime(w.to());
                for (LocalDateTime candidate : List.of(day.atTime(w.from()), end)) {
                    if (candidate.isAfter(from) && (best == null || candidate.isBefore(best))
                            && !current.equals(modeAt(candidate))) {
                        best = candidate;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }
}
