package tg.zener.station.cards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;

/** Règles de permissions appliquées à chaque paiement par carte. */
class CardServiceTest {

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    static final String PIN_HASH = ENCODER.encode("1234");
    // lundi 9 novembre 2026, 10 h
    static final LocalDateTime MONDAY_10 = LocalDateTime.of(2026, 11, 9, 10, 0);

    Db db;
    CardRefusals refusals;
    CardService service;
    Map<String, Object> card;
    Map<String, Object> usage;

    @BeforeEach
    void setUp() {
        db = mock(Db.class);
        refusals = mock(CardRefusals.class);
        service = new CardService(db, ENCODER, refusals);
        card = new HashMap<>();
        card.put("id", 7L);
        card.put("number", "601128470093");
        card.put("status", "ACTIVE");
        card.put("balance", 100_000L);
        card.put("pinHash", PIN_HASH);
        card.put("expiresOn", LocalDate.now().plusYears(1));
        card.put("holderName", "Translom SARL");
        card.put("products", List.of("SUPER", "GASOIL", "LUBRIFIANT"));
        card.put("days", List.of(1, 2, 3, 4, 5, 6));
        card.put("autonomousAllowed", true);
        usage = new HashMap<>(Map.of("spentToday", 0L, "spentMonth", 0L, "rechargedMonth", 0L));
        when(db.one(anyString(), anyMap())).thenAnswer(inv -> ((String) inv.getArgument(0)).contains("card_movement") ? usage : card);
    }

    static CardService.Charge charge(String product, String category, long amount, String pin, boolean requirePin, Long station,
                                     String mode, LocalDateTime at) {
        return new CardService.Charge("6011 2847 0093", null, pin, requirePin, station, mode,
                List.of(new CardService.Item(product, category, amount)), at);
    }

    static CardService.Charge fuel(long amount) {
        return charge("SUPER", "CARBURANT", amount, null, false, 1L, "CONVENTIONNEL", MONDAY_10);
    }

    String refusedCode(CardService.Charge c) {
        ApiException e = assertThrows(ApiException.class, () -> service.charge(c, "test", null));
        assertEquals(422, e.status().value());
        verify(refusals).record(eq(7L), eq("601128470093"), eqOrNull(c.stationId()), eqOrNull(c.items().isEmpty() ? null : c.total()),
                eq(e.code()), anyString());
        return e.code();
    }

    static <T> T eqOrNull(T value) {
        return value == null ? isNull() : eq(value);
    }

    @Test
    void paiementAccepteDebiteLeSolde() {
        when(db.value(argThat((String sql) -> sql.contains("UPDATE card SET balance")), anyMap(), eq(Long.class))).thenReturn(80_000L);
        Map<String, Object> res = service.charge(fuel(20_000), "Vente", 99L);
        assertEquals(80_000L, res.get("balance"));
        verify(db).update(argThat(sql -> sql.contains("INSERT INTO card_movement")), anyMap());
        verify(refusals, never()).record(anyLong(), anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                anyString(), anyString());
    }

    @Test
    void carteNonActive() {
        card.put("status", "BLOQUEE");
        assertEquals("CARTE_INACTIVE", refusedCode(fuel(1000)));
        card.put("status", "A_ACTIVER");
        setUp2();
        assertEquals("CARTE_INACTIVE", refusedCode(fuel(1000)));
    }

    /** Réinitialise le mock des refus pour une seconde vérification dans le même test. */
    private void setUp2() {
        org.mockito.Mockito.reset(refusals);
    }

    @Test
    void carteExpiree() {
        card.put("expiresOn", LocalDate.now().minusDays(1));
        assertEquals("CARTE_EXPIREE", refusedCode(fuel(1000)));
    }

    @Test
    void pinObligatoireEnModeAutonome() {
        assertEquals("PIN_INCORRECT", refusedCode(charge("SUPER", "CARBURANT", 1000, null, true, 1L, "AUTONOME", MONDAY_10)));
        setUp2();
        assertEquals("PIN_INCORRECT", refusedCode(charge("SUPER", "CARBURANT", 1000, "0000", true, 1L, "AUTONOME", MONDAY_10)));
    }

    @Test
    void pinNonDefini() {
        card.put("pinHash", null);
        assertEquals("PIN_NON_DEFINI", refusedCode(charge("SUPER", "CARBURANT", 1000, "1234", true, 1L, "AUTONOME", MONDAY_10)));
    }

    @Test
    void produitNonAutorise() {
        assertEquals("PRODUIT_NON_AUTORISE", refusedCode(charge("PETROLE", "CARBURANT", 1000, null, false, 1L, "CONVENTIONNEL", MONDAY_10)));
        setUp2();
        // hors carburant, la permission porte sur la catégorie
        assertEquals("PRODUIT_NON_AUTORISE", refusedCode(charge("GAZ_6", "GAZ", 4500, null, false, 1L, "CONVENTIONNEL", MONDAY_10)));
    }

    @Test
    void jourNonAutorise() {
        assertEquals("JOUR_NON_AUTORISE", refusedCode(charge("SUPER", "CARBURANT", 1000, null, false, 1L, "CONVENTIONNEL",
                MONDAY_10.plusDays(6))));
    }

    @Test
    void horsHoraires() {
        card.put("timeFrom", LocalTime.of(6, 0));
        card.put("timeTo", LocalTime.of(20, 0));
        assertEquals("HORS_HORAIRES", refusedCode(charge("SUPER", "CARBURANT", 1000, null, false, 1L, "CONVENTIONNEL",
                MONDAY_10.withHour(21))));
        setUp2();
        assertEquals("HORS_HORAIRES", refusedCode(charge("SUPER", "CARBURANT", 1000, null, false, 1L, "CONVENTIONNEL",
                MONDAY_10.withHour(5).withMinute(59))));
    }

    @Test
    void stationNonAutorisee() {
        card.put("stationIds", List.of(2L, 3L));
        assertEquals("STATION_NON_AUTORISEE", refusedCode(fuel(1000)));
    }

    @Test
    void autonomeInterdit() {
        card.put("autonomousAllowed", false);
        assertEquals("AUTONOME_INTERDIT", refusedCode(charge("SUPER", "CARBURANT", 1000, "1234", true, 1L, "AUTONOME", MONDAY_10)));
    }

    @Test
    void plafonds() {
        card.put("perTxLimit", 30_000L);
        assertEquals("PLAFOND_TRANSACTION", refusedCode(fuel(30_001)));
        setUp2();
        card.put("perTxLimit", null);
        card.put("dailyLimit", 50_000L);
        usage.put("spentToday", 40_000L);
        assertEquals("PLAFOND_JOURNALIER", refusedCode(fuel(10_001)));
        setUp2();
        card.put("dailyLimit", null);
        card.put("monthlyLimit", 500_000L);
        usage.put("spentMonth", 495_000L);
        assertEquals("PLAFOND_MENSUEL", refusedCode(fuel(6_000)));
    }

    @Test
    void soldeInsuffisant() {
        assertEquals("SOLDE_INSUFFISANT", refusedCode(fuel(100_001)));
    }

    @Test
    void soldeConsommeEntreLeControleEtLeDebit() {
        // un autre paiement a vidé la carte entre-temps : le débit conditionnel ne trouve plus le solde
        when(db.value(argThat((String sql) -> sql.contains("UPDATE card SET balance")), anyMap(), eq(Long.class))).thenReturn(null);
        assertEquals("SOLDE_INSUFFISANT", refusedCode(fuel(20_000)));
    }

    @Test
    void preAutorisationRenvoieLeMontantMaximal() {
        card.put("perTxLimit", 80_000L);
        card.put("dailyLimit", 120_000L);
        usage.put("spentToday", 70_000L);
        Map<String, Object> res = service.authorize(charge("SUPER", "CARBURANT", 0, "1234", true, 1L, "AUTONOME", MONDAY_10));
        assertEquals(50_000L, res.get("maxAmount")); // min(solde 100 000, 80 000, 120 000 − 70 000)
    }

    @Test
    void sansPermissionsTouteLaCarteEstLibre() {
        card.put("products", null);
        card.put("days", null);
        when(db.value(argThat((String sql) -> sql.contains("UPDATE card SET balance")), anyMap(), eq(Long.class))).thenReturn(95_500L);
        Map<String, Object> res = service.charge(charge("GAZ_6", "GAZ", 4_500, null, false, 1L, "CONVENTIONNEL",
                MONDAY_10.plusDays(6).withHour(23)), "Vente", null);
        assertEquals(95_500L, res.get("balance"));
    }

    @Test
    void numeros() {
        assertEquals("601128470093", CardService.normalize("ZN 6011 2847 0093"));
        assertEquals("6011 2847 0093", CardService.format("601128470093"));
        assertEquals("···0093", CardService.mask("601128470093"));
        assertThrows(ApiException.class, () -> CardService.normalize("6011 2847"));
    }
}
