package tg.zener.station.cards;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tg.zener.station.common.Db;
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

    public CardRefusals(Db db, Journal journal, PlatformTransactionManager tm) {
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
        });
    }
}
