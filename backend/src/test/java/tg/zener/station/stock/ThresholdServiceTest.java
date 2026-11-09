package tg.zener.station.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ThresholdServiceTest {

    @Test
    void seuilEgalConsommationMoyenneFoisCouverture() {
        // 70 L sur 7 jours = 10 L/jour ; délai 3 j + marge 2 j = 5 j
        assertEquals(50, ThresholdService.threshold(70, 70, 3, 2));
    }

    @Test
    void seuilMajoreQuandLaConsommationAugmente() {
        // 20 L/jour, consommation doublée (+100 %) : 20 × 2 × 5
        assertEquals(200, ThresholdService.threshold(140, 70, 3, 2));
    }

    @Test
    void pasDeMinorationQuandLaConsommationBaisse() {
        assertEquals(25, ThresholdService.threshold(35, 70, 3, 2));
    }

    @Test
    void sansHistoriqueLaTendanceEstNeutre() {
        assertEquals(15, ThresholdService.threshold(21, 0, 3, 2));
    }

    @Test
    void seuilArrondiALUniteSuperieure() {
        assertEquals(8, ThresholdService.threshold(10, 10, 3, 2)); // 10/7 × 5 = 7,14
    }

    @Test
    void tendanceEnPourcentage() {
        assertEquals(100.0, ThresholdService.trend(140, 70));
        assertEquals(-10.0, ThresholdService.trend(63, 70));
        assertEquals(14.3, ThresholdService.trend(8, 7));
        assertNull(ThresholdService.trend(50, 0));
    }
}
