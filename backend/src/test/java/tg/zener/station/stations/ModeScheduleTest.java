package tg.zener.station.stations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModeScheduleTest {

    // lundi 9 novembre 2026
    static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    static ModeSchedule.Window w(Set<Integer> days, boolean holidays, String from, String to) {
        return new ModeSchedule.Window(days, holidays, LocalTime.parse(from), LocalTime.parse(to));
    }

    /** Plages par défaut d'une nouvelle station. */
    static ModeSchedule defaults(Set<LocalDate> holidays) {
        return new ModeSchedule(List.of(
                w(Set.of(1, 2, 3, 4, 5), false, "06:00", "22:00"),
                w(Set.of(6), false, "06:00", "23:00"),
                w(Set.of(7), true, "07:00", "20:00")), holidays);
    }

    @Test
    void conventionnelDansLaPlageAutonomeEnDehors() {
        ModeSchedule s = defaults(Set.of());
        assertEquals("CONVENTIONNEL", s.modeAt(MONDAY.atTime(10, 0)));
        assertEquals("CONVENTIONNEL", s.modeAt(MONDAY.atTime(6, 0)));
        assertEquals("AUTONOME", s.modeAt(MONDAY.atTime(5, 59)));
        assertEquals("AUTONOME", s.modeAt(MONDAY.atTime(22, 0)));
        assertEquals("CONVENTIONNEL", s.modeAt(MONDAY.plusDays(5).atTime(22, 30))); // samedi jusqu'à 23 h
        assertEquals("AUTONOME", s.modeAt(MONDAY.plusDays(6).atTime(20, 30)));      // dimanche jusqu'à 20 h
    }

    @Test
    void unJourFerieUtiliseLesPlagesFeriees() {
        ModeSchedule s = defaults(Set.of(MONDAY));
        assertEquals("AUTONOME", s.modeAt(MONDAY.atTime(6, 30)));      // la plage fériée ouvre à 7 h
        assertEquals("CONVENTIONNEL", s.modeAt(MONDAY.atTime(7, 30)));
        assertEquals("AUTONOME", s.modeAt(MONDAY.atTime(21, 0)));      // et ferme à 20 h
        assertEquals("CONVENTIONNEL", s.modeAt(MONDAY.plusDays(1).atTime(21, 0)));
    }

    @Test
    void unePlageQuiPasseMinuitDebordeSurLeLendemain() {
        ModeSchedule s = new ModeSchedule(List.of(w(Set.of(1), false, "22:00", "02:00")), Set.of());
        assertEquals("CONVENTIONNEL", s.modeAt(MONDAY.atTime(23, 0)));
        assertEquals("CONVENTIONNEL", s.modeAt(MONDAY.plusDays(1).atTime(1, 30))); // mardi 1 h 30
        assertEquals("AUTONOME", s.modeAt(MONDAY.plusDays(1).atTime(2, 0)));
        assertEquals("AUTONOME", s.modeAt(MONDAY.atTime(1, 30)));                  // lundi 1 h 30 : plage du dimanche absente
    }

    @Test
    void plageDeVingtQuatreHeures() {
        ModeSchedule s = new ModeSchedule(List.of(w(Set.of(1, 2, 3, 4, 5, 6, 7), false, "00:00", "00:00")), Set.of());
        assertEquals("CONVENTIONNEL", s.modeAt(MONDAY.atTime(3, 0)));
        assertNull(s.nextSwitch(MONDAY.atTime(3, 0)));
    }

    @Test
    void sansPlageAucunModeImpose() {
        ModeSchedule s = new ModeSchedule(List.of(), Set.of());
        assertNull(s.modeAt(MONDAY.atTime(12, 0)));
        assertNull(s.nextSwitch(MONDAY.atTime(12, 0)));
    }

    @Test
    void prochainChangement() {
        ModeSchedule s = defaults(Set.of());
        assertEquals(MONDAY.atTime(22, 0), s.nextSwitch(MONDAY.atTime(10, 0)));
        assertEquals(MONDAY.plusDays(1).atTime(6, 0), s.nextSwitch(MONDAY.atTime(22, 30)));
        // samedi soir → dimanche 7 h
        LocalDateTime saturdayNight = MONDAY.plusDays(5).atTime(23, 30);
        assertEquals(MONDAY.plusDays(6).atTime(7, 0), s.nextSwitch(saturdayNight));
    }
}
