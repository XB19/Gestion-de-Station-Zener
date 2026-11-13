package tg.zener.station.cards;

import java.time.Instant;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;

/**
 * Opérations de service sur les cartes (phases 2c et 2f) : transfert de solde entre deux cartes du même titulaire,
 * listes de cartes (noire : opposition ; grise : surveillance ; blanche : acceptée hors connexion par une station),
 * remplacement d'une carte perdue, volée ou abîmée (solde reporté, frais, opposition de l'ancienne).
 */
@Service
public class CardOperations {

    private final Db db;
    private final CardIssuer issuer;
    private final CardEvents events;
    private final Journal journal;
    private final Settings settings;

    public CardOperations(Db db, CardIssuer issuer, CardEvents events, Journal journal, Settings settings) {
        this.db = db;
        this.issuer = issuer;
        this.events = events;
        this.journal = journal;
        this.settings = settings;
    }

    private Map<String, Object> lock(long cardId) {
        return db.require("""
                \sSELECT id, number, status, balance, holder_id, group_id, type_code, techno, credit_limit, vehicle_id, driver_id,
                       vehicle_plate, vehicle_label, driver_name, pin_hash, expires_on
                FROM card WHERE id = :id FOR UPDATE""", Db.p("id", cardId), "Carte introuvable");
    }

    private String ref() {
        return "TRF-" + Year.now() + "-" + db.sum("SELECT nextval('ref_transfer_seq')", Map.of());
    }

    // ------------------------------------------------------------------ transfert carte → carte

    /** Transfert de solde vers une autre carte du même titulaire (cartes d'une entreprise, d'un particulier). */
    @Transactional
    public Map<String, Object> transfer(long fromId, long toId, long amount, String note, long employeeId) {
        if (fromId == toId) {
            throw new ApiException("Choisissez deux cartes différentes");
        }
        if (amount <= 0) {
            throw new ApiException("Montant invalide");
        }
        // verrouillage dans un ordre fixe pour éviter les interblocages
        Map<String, Object> a = lock(Math.min(fromId, toId));
        Map<String, Object> b = lock(Math.max(fromId, toId));
        Map<String, Object> from = fromId < toId ? a : b;
        Map<String, Object> to = fromId < toId ? b : a;
        if (from.get("holderId") == null || !from.get("holderId").equals(to.get("holderId"))) {
            throw new ApiException("Les deux cartes doivent appartenir au même titulaire");
        }
        if (!"ACTIVE".equals(from.get("status"))) {
            throw ApiException.conflict("La carte à débiter doit être active");
        }
        if (!"ACTIVE".equals(to.get("status")) && !"A_ACTIVER".equals(to.get("status"))) {
            throw ApiException.conflict("La carte à créditer doit être active ou à activer");
        }
        if (num(from.get("balance")) < amount) {
            throw ApiException.conflict("Solde insuffisant (" + fmt(num(from.get("balance"))) + " F disponibles)");
        }
        long id = move(from, to, amount, "TRANSFERT", note, employeeId);
        journal.logAs(employeeId, "TRANSFERT_CARTE", Journal.RECHARGES, "Transfert entre cartes",
                CardService.mask((String) from.get("number")) + " → " + CardService.mask((String) to.get("number")), amount, null, null);
        return db.one("SELECT * FROM card_transfer WHERE id = :id", Db.p("id", id));
    }

    /** Écritures d'un transfert (montant éventuellement négatif pour reporter une dette de carte de crédit). */
    private long move(Map<String, Object> from, Map<String, Object> to, long amount, String reason, String note, Long employeeId) {
        long fromBefore = num(from.get("balance"));
        long toBefore = num(to.get("balance"));
        long fromId = num(from.get("id"));
        long toId = num(to.get("id"));
        db.update("UPDATE card SET balance = balance - :a WHERE id = :id", Db.p("id", fromId, "a", amount));
        db.update("UPDATE card SET balance = balance + :a WHERE id = :id", Db.p("id", toId, "a", amount));
        long id = db.insert("""
                \sINSERT INTO card_transfer (ref, from_card_id, to_card_id, amount, from_balance_before, from_balance_after,
                                           to_balance_before, to_balance_after, reason, employee_id, note)
                VALUES (:ref, :f, :t, :a, :fb, :fa, :tb, :ta, :r, :e, :n)""",
                Db.p("ref", ref(), "f", fromId, "t", toId, "a", Math.abs(amount), "fb", fromBefore, "fa", fromBefore - amount,
                        "tb", toBefore, "ta", toBefore + amount, "r", reason, "e", employeeId, "n", note));
        db.update("""
                \sINSERT INTO card_movement (card_id, kind, amount, balance_after, detail, employee_id, transfer_id) VALUES
                    (:f, 'TRANSFERT', :neg, :fa, :df, :e, :id), (:t, 'TRANSFERT', :pos, :ta, :dt, :e, :id)""",
                Db.p("f", fromId, "t", toId, "neg", -amount, "pos", amount, "fa", fromBefore - amount, "ta", toBefore + amount,
                        "df", "Transfert vers " + CardService.mask((String) to.get("number")),
                        "dt", "Transfert de " + CardService.mask((String) from.get("number")), "e", employeeId, "id", id));
        String detail = CardService.mask((String) from.get("number")) + " → " + CardService.mask((String) to.get("number"))
                + " · " + fmt(amount) + " F";
        events.recordAs(employeeId, fromId, "TRANSFERT", detail, null, null);
        events.recordAs(employeeId, toId, "TRANSFERT", detail, null, null);
        return id;
    }

    // ------------------------------------------------------------------ listes

    /** Ajout en liste (NOIRE, GRISE, BLANCHE) ; une liste blanche peut viser une station. */
    @Transactional
    public Map<String, Object> list(long cardId, String kind, Long stationId, String reason, Instant validUntil, Long employeeId) {
        Map<String, Object> c = db.require("SELECT number FROM card WHERE id = :id", Db.p("id", cardId), "Carte introuvable");
        if (!"BLANCHE".equals(kind) && stationId != null) {
            throw new ApiException("Seule la liste blanche se rapporte à une station");
        }
        Long id = db.value("""
                \sINSERT INTO card_list (kind, card_id, station_id, reason, added_by, valid_until)
                VALUES (:k, :c, :s, :r, :e, CAST(:v AS timestamptz)) ON CONFLICT DO NOTHING RETURNING id""",
                Db.p("k", kind, "c", cardId, "s", stationId, "r", reason, "e", employeeId,
                        "v", validUntil == null ? null : java.sql.Timestamp.from(validUntil)), Long.class);
        if (id == null) {
            throw ApiException.conflict("Cette carte est déjà dans cette liste");
        }
        events.recordAs(employeeId, cardId, "LISTE", label(kind) + " · " + reason, stationId, null);
        journal.logAs(employeeId, "LISTE_CARTE", "NOIRE".equals(kind) ? Journal.MODIFICATIONS : Journal.SYSTEME,
                "Carte ajoutée à la " + label(kind).toLowerCase(), CardService.mask((String) c.get("number")) + " · " + reason, null,
                stationId, null);
        return db.one("SELECT * FROM card_list WHERE id = :id", Db.p("id", id));
    }

    /** Retrait d'une liste (levée d'opposition, fin de surveillance…). */
    @Transactional
    public void unlist(long listId, String note, long employeeId) {
        Map<String, Object> l = db.require("""
                \sUPDATE card_list SET removed_at = now(), removed_by = :e, removal_note = :n WHERE id = :id AND removed_at IS NULL
                RETURNING card_id, kind""", Db.p("id", listId, "e", employeeId, "n", note), "Entrée de liste introuvable ou déjà levée");
        events.recordAs(employeeId, num(l.get("cardId")), "LISTE", "Retrait de la " + label((String) l.get("kind")).toLowerCase()
                + (note == null ? "" : " · " + note), null, null);
        journal.logAs(employeeId, "LISTE_CARTE", Journal.MODIFICATIONS, "Carte retirée de la " + label((String) l.get("kind")).toLowerCase(),
                note, null, null, null);
    }

    public static String label(String kind) {
        return switch (kind) {
            case "NOIRE" -> "Liste noire";
            case "GRISE" -> "Liste grise";
            case "BLANCHE" -> "Liste blanche";
            default -> kind;
        };
    }

    // ------------------------------------------------------------------ remplacement

    /**
     * Remplacement d'une carte perdue, volée ou abîmée : nouvelle carte « à activer » pour le même titulaire
     * (groupe, véhicule, chauffeur, plafonds, crédit, PIN repris), l'ancienne est bloquée (et mise en opposition
     * en cas de perte ou de vol), son solde — diminué des frais — est reporté sur la nouvelle.
     */
    @Transactional
    public Map<String, Object> replace(long cardId, String reason, String newNumber, String note, long employeeId) {
        Map<String, Object> old = lock(cardId);
        String status = (String) old.get("status");
        if (old.get("holderId") == null || "STOCK".equals(status) || "PDV".equals(status) || "INACTIVE".equals(status)) {
            throw ApiException.conflict("Seule une carte attribuée et en service peut être remplacée");
        }
        long newId = issuer.issue(newNumber, (String) old.get("typeCode"), (String) old.get("techno"), num(old.get("holderId")),
                old.get("groupId") == null ? null : num(old.get("groupId")), employeeId, null);
        db.update("""
                \sUPDATE card n SET vehicle_id = o.vehicle_id, driver_id = o.driver_id, vehicle_plate = o.vehicle_plate,
                       vehicle_label = o.vehicle_label, driver_name = o.driver_name, credit_limit = o.credit_limit,
                       pin_hash = COALESCE(n.pin_hash, o.pin_hash), expires_on = GREATEST(n.expires_on, o.expires_on)
                FROM card o WHERE n.id = :n AND o.id = :o""", Db.p("n", newId, "o", cardId));
        db.update("""
                \sUPDATE card_permission n SET products = o.products, daily_limit = o.daily_limit, monthly_limit = o.monthly_limit,
                       per_tx_limit = o.per_tx_limit, days = o.days, time_from = o.time_from, time_to = o.time_to,
                       station_ids = o.station_ids, autonomous_allowed = o.autonomous_allowed,
                       daily_volume_limit = o.daily_volume_limit, monthly_volume_limit = o.monthly_volume_limit,
                       daily_fills_limit = o.daily_fills_limit, min_balance = o.min_balance, updated_at = now(), updated_by = :e
                FROM card_permission o WHERE n.card_id = :n AND o.card_id = :o""", Db.p("n", newId, "o", cardId, "e", employeeId));
        String label = switch (reason) {
            case "PERTE" -> "perte";
            case "VOL" -> "vol";
            case "DETERIORATION" -> "carte abîmée";
            default -> "remplacement";
        };
        db.update("UPDATE card SET status = 'BLOQUEE', blocked_reason = :r WHERE id = :id",
                Db.p("id", cardId, "r", "Remplacée (" + label + ")"));
        if ("PERTE".equals(reason) || "VOL".equals(reason)) {
            db.update("""
                    \sINSERT INTO card_list (kind, card_id, reason, added_by) VALUES ('NOIRE', :c, :r, :e) ON CONFLICT DO NOTHING""",
                    Db.p("c", cardId, "r", "Carte déclarée " + ("VOL".equals(reason) ? "volée" : "perdue"), "e", employeeId));
        }
        // frais de remplacement prélevés sur le solde, puis report du solde (une dette de crédit suit la nouvelle carte)
        long balance = num(old.get("balance"));
        long fee = Math.min(settings.getLong("card.replacement_fee", 1000), Math.max(balance, 0));
        if (fee > 0) {
            balance -= fee;
            db.update("UPDATE card SET balance = :b WHERE id = :id", Db.p("id", cardId, "b", balance));
            db.update("""
                    \sINSERT INTO card_movement (card_id, kind, amount, balance_after, detail, employee_id)
                    VALUES (:c, 'FRAIS', :a, :b, 'Frais de remplacement', :e)""", Db.p("c", cardId, "a", -fee, "b", balance, "e", employeeId));
        }
        Long transferId = null;
        if (balance != 0) {
            old.put("balance", balance);
            Map<String, Object> fresh = lock(newId);
            transferId = move(old, fresh, balance, "REMPLACEMENT", "Remplacement (" + label + ")", employeeId);
        }
        long id = db.insert("""
                \sINSERT INTO card_replacement (old_card_id, new_card_id, reason, balance_transferred, fee, transfer_id, employee_id, note)
                VALUES (:o, :n, :r, :b, :f, :t, :e, :note)""",
                Db.p("o", cardId, "n", newId, "r", reason, "b", balance, "f", fee, "t", transferId, "e", employeeId, "note", note));
        String newNo = db.value("SELECT number FROM card WHERE id = :id", Db.p("id", newId), String.class);
        events.recordAs(employeeId, cardId, "REMPLACEMENT", "Remplacée par " + CardService.mask(newNo) + " · " + label, null, null);
        events.recordAs(employeeId, newId, "REMPLACEMENT", "Remplace " + CardService.mask((String) old.get("number")) + " · " + label, null, null);
        journal.logAs(employeeId, "REMPLACEMENT_CARTE", Journal.VENTES_CARTES, "Carte remplacée · " + label,
                CardService.mask((String) old.get("number")) + " → " + CardService.mask(newNo) + " · à activer", balance, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("oldCardId", cardId);
        out.put("newCardId", newId);
        out.put("newNumber", newNo);
        out.put("balanceTransferred", balance);
        out.put("fee", fee);
        return out;
    }

    // ------------------------------------------------------------------ crédit

    /** Plafond de crédit d'une carte (0 : carte prépayée). Le solde actuel doit rester au-dessus du nouveau plancher. */
    @Transactional
    public void creditLimit(long cardId, long limit, long employeeId) {
        Map<String, Object> c = lock(cardId);
        if (limit < 0) {
            throw new ApiException("Plafond invalide");
        }
        if (num(c.get("balance")) < -limit) {
            throw ApiException.conflict("La carte doit " + fmt(-num(c.get("balance"))) + " F : plafond inférieur impossible");
        }
        db.update("UPDATE card SET credit_limit = :l WHERE id = :id", Db.p("id", cardId, "l", limit));
        events.recordAs(employeeId, cardId, "MODIFICATION", limit == 0 ? "Carte prépayée (crédit retiré)"
                : "Plafond de crédit · " + fmt(limit) + " F", null, null);
        journal.logAs(employeeId, "CREDIT_CARTE", Journal.MODIFICATIONS, "Plafond de crédit modifié",
                CardService.mask((String) c.get("number")) + " · " + fmt(num(c.get("creditLimit"))) + " → " + fmt(limit) + " F", null, null, null);
    }

    static String fmt(long v) {
        return String.format("%,d", v).replace(',', ' ');
    }

    static long num(Object o) {
        return ((Number) o).longValue();
    }
}
