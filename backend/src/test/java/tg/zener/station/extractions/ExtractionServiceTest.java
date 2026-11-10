package tg.zener.station.extractions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;

class ExtractionServiceTest {

    private final ExtractionCatalog catalog = new ExtractionCatalog();
    private final ExtractionService service = new ExtractionService(mock(Db.class), mock(DataSource.class));

    @Test
    void sansFiltreAucuneCondition() {
        ExtractionService.Query q = service.build(catalog.get("audit"), ExtractionFilters.parse(Map.of()));
        assertFalse(q.sql().contains(" WHERE "));
        assertTrue(q.sql().contains(" FROM "));
    }

    @Test
    void seulsLesFiltresRenseignesSontAppliques() {
        ExtractionFilters f = ExtractionFilters.parse(Map.of("from", "2026-11-01", "to", "2026-11-10", "cardId", "7"));
        ExtractionService.Query q = service.build(catalog.get("historique-carte"), f);
        assertTrue(q.sql().contains("m.at >= :from"));
        assertTrue(q.sql().contains("m.at < :to"));
        assertTrue(q.sql().contains("m.card_id = :cardId"));
        assertFalse(q.sql().contains(":holderId"));
        // la date de fin est incluse : :to vaut le lendemain
        assertEquals(LocalDate.parse("2026-11-11"), q.params().get("to"));
        assertEquals(7L, q.params().get("cardId"));
    }

    @Test
    void conditionDeBaseEtRegroupement() {
        ExtractionService.Query q = service.build(catalog.get("carte-produits"), ExtractionFilters.parse(Map.of("holderId", "3")));
        assertTrue(q.sql().contains("x.payment_method = 'CARTE' AND c.holder_id = :holderId"));
        assertTrue(q.sql().contains(" GROUP BY "));
    }

    @Test
    void filtresNonApplicablesSignales() {
        ExtractionFilters f = ExtractionFilters.parse(Map.of("stationId", "1", "holderId", "2"));
        assertEquals(List.of("station"), service.unsupported(catalog.get("client-cartes"), f));
    }

    @Test
    void parametresInvalidesRefuses() {
        assertThrows(ApiException.class, () -> ExtractionFilters.parse(Map.of("from", "2026-11-10", "to", "2026-11-01")));
        assertThrows(ApiException.class, () -> ExtractionFilters.parse(Map.of("from", "10/11/2026")));
        assertThrows(ApiException.class, () -> ExtractionFilters.parse(Map.of("cardId", "abc")));
        assertNull(ExtractionFilters.parse(Map.of("cardId", " ")).cardId());
    }

    @Test
    void catalogueCoherent() {
        Set<String> codes = new HashSet<>();
        for (Extraction e : catalog.list()) {
            assertTrue(codes.add(e.code()), "code en double : " + e.code());
            assertTrue(ExtractionCatalog.SECTIONS.containsKey(e.section()), e.code());
            assertFalse(e.cols().isEmpty(), e.code());
            assertTrue(e.orderBy() != null && !e.orderBy().isBlank(), e.code());
            for (Extraction.Col c : e.cols()) {
                assertTrue(e.select().contains(c.alias()), e.code() + " : colonne " + c.alias() + " absente du SELECT");
            }
        }
        assertEquals(12, catalog.list().size());
    }

    @Test
    void formatCsv() {
        assertEquals("", ExtractionService.csv(null));
        assertEquals("Agoè", ExtractionService.csv("Agoè"));
        assertEquals("\"a;b\"", ExtractionService.csv("a;b"));
        assertEquals("\"dit \"\"oui\"\"\"", ExtractionService.csv("dit \"oui\""));
        assertEquals("25,50", ExtractionService.format(new BigDecimal("25.5"), Extraction.Type.QTY));
        assertEquals("10/11/2026 08:05", ExtractionService.format(LocalDateTime.of(2026, 11, 10, 8, 5), Extraction.Type.DATETIME));
        assertEquals("-60953", ExtractionService.format(-60953L, Extraction.Type.MONEY));
    }

    @Test
    void nomDeFeuilleExcel() {
        assertEquals("Ordres de travail (Zen Mobility", ExtractionService.sheetName("Ordres de travail (Zen Mobility)"));
        assertEquals("Ventes par station et point de ", ExtractionService.sheetName("Ventes par station et point de vente"));
        assertEquals("A B", ExtractionService.sheetName("A/B"));
    }
}
