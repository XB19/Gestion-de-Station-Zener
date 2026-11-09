package tg.zener.station.cards;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Settings;

/**
 * Émission des cartes : numéros, stock de cartes vierges, attribution à un titulaire.
 * Une carte émise est « à activer » : une autre personne doit l'activer (garde-fou anti-fraude).
 */
@Service
public class CardIssuer {

    private final Db db;
    private final Settings settings;
    private final PasswordEncoder encoder;

    public CardIssuer(Db db, Settings settings, PasswordEncoder encoder) {
        this.db = db;
        this.settings = settings;
        this.encoder = encoder;
    }

    /** Génère des numéros consécutifs : préfixe (6011) + 8 chiffres. */
    @Transactional
    public List<String> nextNumbers(int quantity) {
        db.update("LOCK TABLE card IN SHARE ROW EXCLUSIVE MODE", Map.of());
        String prefix = settings.get("card.number_prefix", "6011");
        String max = db.value("SELECT max(number) FROM card WHERE number LIKE :p || '%'", Db.p("p", prefix), String.class);
        long next = max == null ? 10_000_001L : Long.parseLong(max.substring(prefix.length())) + 1;
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            String n = prefix + String.format("%08d", next + i);
            if (n.length() != 12) {
                throw new ApiException("Plus de numéros disponibles pour le préfixe " + prefix);
            }
            numbers.add(n);
        }
        return numbers;
    }

    /** Lot de cartes vierges mises en stock. */
    @Transactional
    public Map<String, Object> createBatch(String typeCode, String techno, int quantity, Long userId) {
        if (quantity < 1 || quantity > 5000) {
            throw new ApiException("Quantité entre 1 et 5 000");
        }
        List<String> numbers = nextNumbers(quantity);
        long batch = db.insert("""
                \sINSERT INTO card_batch (type_code, techno, first_number, quantity, created_by)
                VALUES (:t, :te, :f, :q, :u)""",
                Db.p("t", typeCode, "te", techno, "f", numbers.get(0), "q", quantity, "u", userId));
        db.update("""
                \sINSERT INTO card (number, type_code, techno, status, batch_id, created_by)
                SELECT n, :t, :te, 'STOCK', :b, :u FROM unnest(CAST(:nums AS text[])) AS n""",
                Db.p("t", typeCode, "te", techno, "b", batch, "u", userId, "nums", db.array("text", numbers.toArray())));
        return Map.of("batchId", batch, "firstNumber", numbers.get(0), "lastNumber", numbers.get(numbers.size() - 1),
                "quantity", quantity);
    }

    /**
     * Attribue une carte à un titulaire : carte du stock (numéro donné) ou nouvelle carte.
     * La carte passe « à activer ».
     */
    @Transactional
    public long issue(String number, String typeCode, String techno, long holderId, Long groupId, Long issuedBy, String pin) {
        Long cardId;
        if (number != null && !number.isBlank()) {
            String n = CardService.normalize(number);
            Map<String, Object> c = db.require("SELECT id, status, type_code FROM card WHERE number = :n FOR UPDATE",
                    Db.p("n", n), "Carte introuvable dans le stock");
            if (!"STOCK".equals(c.get("status")) && !"PDV".equals(c.get("status"))) {
                throw ApiException.conflict("Cette carte est déjà attribuée");
            }
            if (typeCode != null && !typeCode.equals(c.get("typeCode"))) {
                throw new ApiException("Cette carte du stock est de type " + c.get("typeCode"));
            }
            cardId = ((Number) c.get("id")).longValue();
        } else {
            String n = nextNumbers(1).get(0);
            cardId = db.insert("""
                    \sINSERT INTO card (number, type_code, techno, status, created_by)
                    VALUES (:n, :t, COALESCE(:te, 'PUCE'), 'STOCK', :u)""",
                    Db.p("n", n, "t", typeCode, "te", techno, "u", issuedBy));
        }
        db.update("""
                \sUPDATE card SET status = 'A_ACTIVER', holder_id = :h, group_id = :g, issued_by = :u,
                       pin_hash = COALESCE(:pin, pin_hash), expires_on = COALESCE(expires_on, current_date + interval '3 years')
                WHERE id = :id""",
                Db.p("id", cardId, "h", holderId, "g", groupId, "u", issuedBy,
                        "pin", pin == null ? null : hashPin(pin)));
        db.update("INSERT INTO card_permission (card_id, updated_by) VALUES (:c, :u) ON CONFLICT DO NOTHING",
                Db.p("c", cardId, "u", issuedBy));
        return cardId;
    }

    public String hashPin(String pin) {
        if (pin == null || !pin.matches("[0-9]{4,6}")) {
            throw new ApiException("Le code PIN doit contenir 4 à 6 chiffres");
        }
        return encoder.encode(pin);
    }
}
