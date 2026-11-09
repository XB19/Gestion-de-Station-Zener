package tg.zener.station.pdv;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.cards.CardIssuer;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;
import tg.zener.station.sales.SaleService;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.validations.ValidationService;

/**
 * Points de vente de cartes : un point permanent au siège et des points temporaires
 * (événements, campagnes) ouverts après validation par l'Admin ou le DAF. Seule la vente
 * de nouvelles cartes s'y fait, sur pièce d'identité, en espèces ou mobile money,
 * y compris hors connexion (synchronisation au retour du réseau).
 */
@Service
public class PdvService {

    public record Holder(String name, String idDocType, String idDocNumber, String phone, String email) {}

    public record CardSale(UUID uuid, String cardNumber, Holder holder, String paymentMethod, String paymentRef, Instant at) {}

    private final Db db;
    private final CardIssuer issuer;
    private final SaleService sales;
    private final Journal journal;

    public PdvService(Db db, CardIssuer issuer, SaleService sales, Journal journal) {
        this.db = db;
        this.issuer = issuer;
        this.sales = sales;
        this.journal = journal;
    }

    /** Vente d'une nouvelle carte : la carte doit avoir été attribuée à ce point de vente. */
    @Transactional
    public Map<String, Object> sellCard(long pdvId, CardSale s, CurrentUser agent, boolean synced) {
        Map<String, Object> done = sales.existing(s.uuid());
        if (done != null) {
            done.put("duplicate", true);
            return done;
        }
        Map<String, Object> pdv = db.require("SELECT id, name, status, start_date, end_date FROM pdv WHERE id = :id",
                Db.p("id", pdvId), "Point de vente introuvable");
        LocalDate day = s.at() == null ? LocalDate.now() : LocalDate.ofInstant(s.at(), java.time.ZoneId.systemDefault());
        boolean open = "OUVERT".equals(pdv.get("status"))
                || synced && "A_CLOTURER".equals(pdv.get("status")); // ventes faites avant la fin, reçues en différé
        if (!open) {
            throw ApiException.conflict("Ce point de vente n'est pas ouvert");
        }
        if (pdv.get("startDate") != null && day.isBefore((LocalDate) pdv.get("startDate"))) {
            throw ApiException.conflict("Ce point de vente n'ouvre que le " + pdv.get("startDate"));
        }
        if (!"ADMIN".equals(agent.role()) && db.count("SELECT count(*) FROM pdv_agent WHERE pdv_id = :p AND employee_id = :e",
                Db.p("p", pdvId, "e", agent.id())) == 0) {
            throw ApiException.forbidden("Vous n'êtes pas affecté à ce point de vente");
        }
        if (!List.of("ESPECES", "MOBILE_MONEY").contains(s.paymentMethod())) {
            throw new ApiException("Paiement en espèces ou par mobile money uniquement");
        }
        if ("MOBILE_MONEY".equals(s.paymentMethod()) && (s.paymentRef() == null || s.paymentRef().isBlank())) {
            throw new ApiException("Référence de la transaction mobile money requise");
        }
        Holder h = s.holder();
        if (h == null || h.name() == null || h.name().isBlank() || h.idDocNumber() == null || h.idDocNumber().isBlank()) {
            throw new ApiException("Nom et pièce d'identité de l'acheteur requis");
        }
        Map<String, Object> card = db.require("""
                \sSELECT c.id, c.number, c.type_code, c.status, c.pdv_id, ct.label FROM card c JOIN card_type ct ON ct.code = c.type_code
                WHERE c.number = :n FOR UPDATE OF c""", Db.p("n", CardService.normalize(s.cardNumber())), "Carte introuvable");
        if (!"PDV".equals(card.get("status")) || card.get("pdvId") == null || ((Number) card.get("pdvId")).longValue() != pdvId) {
            throw ApiException.conflict("Cette carte n'est pas disponible dans ce point de vente");
        }
        long holderId = db.insert("""
                \sINSERT INTO holder (kind, name, id_doc_type, id_doc_number, phone, email)
                VALUES ('PARTICULIER', :n, :dt, :dn, :ph, :e)""",
                Db.p("n", h.name().trim(), "dt", h.idDocType(), "dn", h.idDocNumber().trim(), "ph", h.phone(), "e", h.email()));
        long cardId = ((Number) card.get("id")).longValue();
        issuer.issue((String) card.get("number"), (String) card.get("typeCode"), null, holderId, null, agent.id(), null);
        String product = "CARTE_" + card.get("typeCode");
        String note = CardService.mask((String) card.get("number")) + " · pièce d'identité vérifiée · "
                + ("MOBILE_MONEY".equals(s.paymentMethod()) ? "mobile money" : "espèces");
        Map<String, Object> sale = sales.record(new SaleService.Sale(s.uuid(), null, pdvId, "CARTE", "PDV", null,
                List.of(new SaleService.Line(product, BigDecimal.ONE, false)), s.paymentMethod(), s.paymentRef(), null, null,
                false, agent.id(), s.at(), synced, note));
        long saleId = ((Number) sale.get("id")).longValue();
        db.update("UPDATE sale_line SET card_id = :c WHERE sale_id = :s", Db.p("c", cardId, "s", saleId));
        db.update("""
                \sUPDATE card SET pdv_id = NULL, sold_at = COALESCE(CAST(:at AS timestamptz), now()), sold_by = :u, sold_pdv_id = :p
                WHERE id = :id""",
                Db.p("id", cardId, "u", agent.id(), "p", pdvId, "at", s.at() == null ? null : java.sql.Timestamp.from(s.at())));
        db.update("UPDATE pdv_card SET sold_at = now() WHERE pdv_id = :p AND card_id = :c", Db.p("p", pdvId, "c", cardId));
        db.update("UPDATE pdv SET last_sync_at = now() WHERE id = :p", Db.p("p", pdvId));
        return sale;
    }

    @EventListener
    public void onDecision(ValidationService.Decided d) {
        if (!ValidationService.OUVERTURE_PDV.equals(d.kind()) || d.targetId() == null) {
            return;
        }
        Map<String, Object> p = db.one("SELECT name FROM pdv WHERE id = :id", Db.p("id", d.targetId()));
        db.update("UPDATE pdv SET status = :s WHERE id = :id AND status = 'DEMANDE'",
                Db.p("id", d.targetId(), "s", d.approved() ? "OUVERT" : "REFUSE"));
        journal.logAs(d.deciderId(), "PDV_DECISION", Journal.VALIDATIONS,
                d.approved() ? "Ouverture de point de vente validée" : "Ouverture de point de vente refusée",
                p == null ? null : (String) p.get("name"), null, null, d.targetId());
    }

    /** Les points de vente temporaires arrivés à leur date de fin passent « à clôturer ». */
    @Scheduled(cron = "0 5 0 * * *", zone = "${app.timezone}")
    public void expire() {
        for (Map<String, Object> p : db.list("""
                \sUPDATE pdv SET status = 'A_CLOTURER'
                WHERE kind = 'TEMPORAIRE' AND status = 'OUVERT' AND end_date < current_date RETURNING id, name""")) {
            journal.logAs(null, "PDV_FIN", Journal.SYSTEME, "Point de vente à clôturer", (String) p.get("name"), null, null,
                    ((Number) p.get("id")).longValue());
        }
    }
}
