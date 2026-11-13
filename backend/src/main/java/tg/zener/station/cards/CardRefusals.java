package tg.zener.station.cards;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;

/**
 * Enregistre un refus de paiement dans sa propre transaction : il reste tracé même si
 * l'opération qui l'a provoqué est annulée.
 */
@Component
public class CardRefusals {

    private final Db db;
    private final Journal journal;
    private final TransactionTemplate tx;
    private final Settings settings;

    public CardRefusals(Db db, Journal journal, PlatformTransactionManager tm, Settings settings) {
        this.settings = settings;
        this.db = db;
        this.journal = journal;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(long cardId, String number, Long stationId, Long amount, String reason, String detail) {
        tx.executeWithoutResult(s -> {
            db.update("""
                    \sINSERT INTO card_refusal (card_id, station_id, amount, reason, detail)
                    VALUES (:c, :s, :a, :r, :d)""",
                    Db.p("c", cardId, "s", stationId, "a", amount, "r", reason, "d", detail));
            journal.log("REFUS_CARTE", Journal.VENTES_CARBURANT, "Refus · " + detail,
                    "Carte " + CardService.mask(number), amount, stationId, null);
            // codes PIN erronés répétés : la carte passe sous surveillance (liste grise, PIN exigé partout)
            if ("PIN_INCORRECT".equals(reason)) {
                long max = settings.getLong("card.gray_pin_failures", 3);
                long recent = db.count("""
                        \sSELECT count(*) FROM card_refusal WHERE card_id = :c AND reason = 'PIN_INCORRECT'
                          AND at > now() - interval '15 minutes'""", Db.p("c", cardId));
                if (recent >= max && db.update("""
                        \sINSERT INTO card_list (kind, card_id, reason) VALUES ('GRISE', :c, :r) ON CONFLICT DO NOTHING""",
                        Db.p("c", cardId, "r", recent + " codes PIN erronés en 15 minutes")) > 0) {
                    db.update("INSERT INTO card_event (card_id, kind, station_id, detail) VALUES (:c, 'LISTE', :s, :d)",
                            Db.p("c", cardId, "s", stationId, "d", "Liste grise · " + recent + " codes PIN erronés"));
                    journal.log("LISTE_GRISE", Journal.SYSTEME, "Carte mise sous surveillance",
                            "Carte " + CardService.mask(number) + " · " + recent + " codes PIN erronés en 15 min", null, stationId, null);
                }
            }
        });
    }
}
