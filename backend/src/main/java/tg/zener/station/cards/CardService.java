package tg.zener.station.cards;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;

/**
 * Cartes : numéros, contrôle des permissions et débit lors d'un paiement.
 * Les mêmes règles s'appliquent en caisse, à la pompe, au distributeur de gaz et en mode autonome.
 */
@Service
public class CardService {

    /** Article payé par carte : code produit (SUPER, GASOIL…), catégorie, montant et quantité (litres pour le carburant). */
    public record Item(String productCode, String category, long amount, BigDecimal quantity) {

        public Item(String productCode, String category, long amount) {
            this(productCode, category, amount, null);
        }
    }

    /** Paiement : carte (numéro ou identifiant), PIN, lieu, mode et moment. */
    public record Charge(String number, Long cardId, String pin, boolean requirePin, Long stationId, String mode,
                         List<Item> items, LocalDateTime at) {

        long total() {
            return items.stream().mapToLong(Item::amount).sum();
        }
    }

    private final Db db;
    private final PasswordEncoder encoder;
    private final CardRefusals refusals;

    public CardService(Db db, PasswordEncoder encoder, CardRefusals refusals) {
        this.db = db;
        this.encoder = encoder;
        this.refusals = refusals;
    }

    /** Numéro sans espaces ni préfixe (« ZN 6011 2847 0093 » → « 601128470093 »). */
    /**
     * Recherche partagée par tous les écrans qui demandent une carte : numéro complet (12 chiffres),
     * 5 derniers chiffres (index card_last5) ou fragment d'au moins 4 chiffres. Espaces ignorés.
     * L'écran choisit directement la carte s'il n'y a qu'un résultat, sinon il affiche la liste de choix.
     */
    public List<Map<String, Object>> search(String q, int limit) {
        String digits = q == null ? "" : q.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            throw new ApiException("Saisissez le numéro complet ou les 5 derniers chiffres de la carte");
        }
        if (digits.length() > 12) {
            throw new ApiException("Numéro de carte invalide (12 chiffres au plus)");
        }
        String cond = digits.length() == 12 ? "c.number = :q"
                : digits.length() == 5 ? "right(c.number, 5) = :q"
                : "c.number LIKE '%' || :q || '%'";
        List<Map<String, Object>> rows = db.list("""
                \sSELECT c.id, c.number, c.type_code, ct.label AS type_label, c.status,
                       CASE c.status WHEN 'ACTIVE' THEN 'Active' WHEN 'BLOQUEE' THEN 'Bloquée' WHEN 'INACTIVE' THEN 'Inactive'
                                     WHEN 'A_ACTIVER' THEN 'À activer' WHEN 'PDV' THEN 'Au point de vente' ELSE 'En stock' END AS status_label,
                       c.holder_id, h.name AS holder_name, h.kind AS holder_kind, c.group_id, g.name AS group_name,
                       c.vehicle_plate, c.vehicle_label, c.driver_name, c.balance
                FROM card c JOIN card_type ct ON ct.code = c.type_code
                LEFT JOIN holder h ON h.id = c.holder_id LEFT JOIN card_group g ON g.id = c.group_id
                WHERE\s""" + cond + " ORDER BY (c.status = 'ACTIVE') DESC, c.number LIMIT :l", Db.p("q", digits, "l", limit));
        rows.forEach(r -> r.put("numberFormatted", format((String) r.get("number"))));
        return rows;
    }

    public static String normalize(String number) {
        if (number == null) {
            return null;
        }
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() != 12) {
            throw new ApiException("Numéro de carte invalide (12 chiffres attendus)");
        }
        return digits;
    }

    /** Affichage groupé : 6011 2847 0093. */
    public static String format(String number) {
        return number == null || number.length() != 12 ? number
                : number.substring(0, 4) + " " + number.substring(4, 8) + " " + number.substring(8);
    }

    /** Affichage masqué : ···0093. */
    public static String mask(String number) {
        return number == null ? "" : "···" + number.substring(Math.max(0, number.length() - 4));
    }

    public Map<String, Object> find(String number, Long cardId) {
        Map<String, Object> c = cardId != null
                ? db.one(CARD_SELECT + " WHERE c.id = :id", Db.p("id", cardId))
                : db.one(CARD_SELECT + " WHERE c.number = :n", Db.p("n", normalize(number)));
        if (c == null) {
            throw ApiException.notFound("Carte introuvable");
        }
        return c;
    }

    static final String CARD_SELECT = """
            \sSELECT c.id, c.number, c.type_code, c.techno, c.status, c.balance, c.pin_hash, c.expires_on, c.holder_id,
                   c.group_id, c.credit_limit, h.kind AS holder_kind, h.name AS holder_name, h.profile AS holder_profile,
                   cp.products, cp.daily_limit, cp.monthly_limit, cp.per_tx_limit, cp.days, cp.time_from, cp.time_to,
                   cp.station_ids, cp.autonomous_allowed, cp.daily_volume_limit, cp.monthly_volume_limit, cp.daily_fills_limit,
                   cp.min_balance,
                   EXISTS (SELECT 1 FROM card_list l WHERE l.card_id = c.id AND l.kind = 'NOIRE' AND l.removed_at IS NULL
                           AND (l.valid_until IS NULL OR l.valid_until > now())) AS blacklisted,
                   EXISTS (SELECT 1 FROM card_list l WHERE l.card_id = c.id AND l.kind = 'GRISE' AND l.removed_at IS NULL
                           AND (l.valid_until IS NULL OR l.valid_until > now())) AS graylisted
            FROM card c LEFT JOIN holder h ON h.id = c.holder_id LEFT JOIN card_permission cp ON cp.card_id = c.id""";

    /** Montants déjà dépensés par la carte aujourd'hui et ce mois-ci. */
    public Map<String, Object> usage(long cardId) {
        return db.one("""
                \sSELECT COALESCE(-sum(amount) FILTER (WHERE kind = 'VENTE' AND at >= current_date), 0) AS spent_today,
                       COALESCE(-sum(amount) FILTER (WHERE kind = 'VENTE' AND at >= date_trunc('month', now())), 0) AS spent_month,
                       COALESCE(sum(amount) FILTER (WHERE kind = 'RECHARGE' AND at >= date_trunc('month', now())), 0) AS recharged_month
                FROM card_movement WHERE card_id = :c AND (kind = 'VENTE' OR kind = 'RECHARGE')""", Db.p("c", cardId));
    }

    /**
     * Vérifie tout ce qui ne dépend pas du montant ; renvoie le montant maximal autorisé
     * (solde et plafonds restants). Sert à la pré-autorisation des pompes en mode autonome.
     */
    public Map<String, Object> authorize(Charge ch) {
        Map<String, Object> card = find(ch.number(), ch.cardId());
        check(card, ch, false);
        long max = maxAmount(card);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("cardId", card.get("id"));
        res.put("number", card.get("number"));
        res.put("holderName", card.get("holderName"));
        res.put("balance", card.get("balance"));
        res.put("maxAmount", max);
        return res;
    }

    /** Contrôle complet puis débit atomique du solde. Renvoie la carte avec son nouveau solde. */
    public Map<String, Object> charge(Charge ch, String movementDetail, Long saleId) {
        Map<String, Object> card = find(ch.number(), ch.cardId());
        check(card, ch, true);
        long id = ((Number) card.get("id")).longValue();
        long total = ch.total();
        Long balance = db.value("""
                \sUPDATE card SET balance = balance - :t WHERE id = :id AND balance - :t >= :floor RETURNING balance""",
                Db.p("id", id, "t", total, "floor", floor(card)), Long.class);
        if (balance == null) {
            refuse(card, ch, "SOLDE_INSUFFISANT", credit(card) ? "Plafond de crédit atteint" : "Solde insuffisant");
        }
        db.update("""
                \sINSERT INTO card_movement (card_id, kind, amount, balance_after, station_id, sale_id, detail, employee_id)
                VALUES (:c, 'VENTE', :a, :b, :s, :sale, :d, (SELECT employee_id FROM sale WHERE id = :sale))""",
                Db.p("c", id, "a", -total, "b", balance, "s", ch.stationId(), "sale", saleId, "d", movementDetail));
        card.put("balance", balance);
        return card;
    }

    /** Carte de crédit : le solde peut devenir négatif jusqu'au plafond de crédit. */
    static boolean credit(Map<String, Object> card) {
        Long limit = num(card.get("creditLimit"));
        return limit != null && limit > 0;
    }

    /** Solde le plus bas autorisé après un paiement. */
    static long floor(Map<String, Object> card) {
        if (credit(card)) {
            return -num(card.get("creditLimit"));
        }
        Long min = num(card.get("minBalance"));
        return min == null ? 0 : min;
    }

    /** Montant disponible : solde jusqu'au plancher. */
    static long available(Map<String, Object> card) {
        return num(card.get("balance")) - floor(card);
    }

    private void check(Map<String, Object> card, Charge ch, boolean withAmount) {
        if (Boolean.TRUE.equals(card.get("blacklisted"))) {
            refuse(card, ch, "CARTE_OPPOSITION", "Carte en opposition (liste noire)");
        }
        String status = (String) card.get("status");
        if (!"ACTIVE".equals(status)) {
            refuse(card, ch, "CARTE_INACTIVE", switch (status) {
                case "BLOQUEE" -> "Carte bloquée";
                case "A_ACTIVER" -> "Carte pas encore activée";
                default -> "Carte inactive";
            });
        }
        if (card.get("expiresOn") != null && ((LocalDate) card.get("expiresOn")).isBefore(LocalDate.now())) {
            refuse(card, ch, "CARTE_EXPIREE", "Carte expirée");
        }
        // carte sous surveillance (liste grise) : code PIN exigé à chaque paiement
        if (ch.requirePin() || ch.pin() != null || Boolean.TRUE.equals(card.get("graylisted"))) {
            String hash = (String) card.get("pinHash");
            if (hash == null) {
                refuse(card, ch, "PIN_NON_DEFINI", "Code PIN non défini");
            }
            if (ch.pin() == null || !encoder.matches(ch.pin(), hash)) {
                refuse(card, ch, "PIN_INCORRECT", "Code PIN incorrect");
            }
        }
        LocalDateTime at = ch.at() == null ? LocalDateTime.now() : ch.at();
        if (card.get("products") != null) {
            @SuppressWarnings("unchecked")
            List<Object> allowed = (List<Object>) card.get("products");
            for (Item it : ch.items()) {
                String key = "CARBURANT".equals(it.category()) ? it.productCode() : it.category();
                if (!allowed.contains(key)) {
                    refuse(card, ch, "PRODUIT_NON_AUTORISE", "Produit non autorisé (" + key.toLowerCase() + ")");
                }
            }
            @SuppressWarnings("unchecked")
            List<Object> days = (List<Object>) card.get("days");
            int dow = at.getDayOfWeek().getValue();
            if (days != null && days.stream().noneMatch(d -> ((Number) d).intValue() == dow)) {
                refuse(card, ch, "JOUR_NON_AUTORISE", "Jour non autorisé ("
                        + DayOfWeek.of(dow).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRENCH) + ")");
            }
            LocalTime from = (LocalTime) card.get("timeFrom");
            LocalTime to = (LocalTime) card.get("timeTo");
            LocalTime t = at.toLocalTime();
            if (from != null && to != null && (t.isBefore(from) || !t.isBefore(to))) {
                DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
                refuse(card, ch, "HORS_HORAIRES", "Hors horaires · " + t.format(hm)
                        + (t.isBefore(from) ? " < " + from.format(hm) : " > " + to.format(hm)));
            }
            @SuppressWarnings("unchecked")
            List<Object> stations = (List<Object>) card.get("stationIds");
            if (stations != null && ch.stationId() != null
                    && stations.stream().noneMatch(s -> ((Number) s).longValue() == ch.stationId())) {
                refuse(card, ch, "STATION_NON_AUTORISEE", "Station non autorisée");
            }
            if ("AUTONOME".equals(ch.mode()) && !Boolean.TRUE.equals(card.get("autonomousAllowed"))) {
                refuse(card, ch, "AUTONOME_INTERDIT", "Carte non autorisée en mode autonome");
            }
        }
        if (!withAmount) {
            return;
        }
        long total = ch.total();
        Long perTx = num(card.get("perTxLimit"));
        if (perTx != null && total > perTx) {
            refuse(card, ch, "PLAFOND_TRANSACTION", "Plafond par transaction dépassé");
        }
        Map<String, Object> u = usage(((Number) card.get("id")).longValue());
        Long daily = num(card.get("dailyLimit"));
        if (daily != null && num(u.get("spentToday")) + total > daily) {
            refuse(card, ch, "PLAFOND_JOURNALIER", "Plafond journalier atteint");
        }
        Long monthly = num(card.get("monthlyLimit"));
        if (monthly != null && num(u.get("spentMonth")) + total > monthly) {
            refuse(card, ch, "PLAFOND_MENSUEL", "Plafond mensuel atteint");
        }
        checkVolume(card, ch);
        if (available(card) < total) {
            refuse(card, ch, "SOLDE_INSUFFISANT", credit(card) ? "Plafond de crédit atteint" : "Solde insuffisant");
        }
    }

    /** Plafonds en litres (jour, mois) et nombre de pleins par jour, pour le carburant. */
    private void checkVolume(Map<String, Object> card, Charge ch) {
        Object dayVol = card.get("dailyVolumeLimit");
        Object monthVol = card.get("monthlyVolumeLimit");
        Object fills = card.get("dailyFillsLimit");
        if (dayVol == null && monthVol == null && fills == null) {
            return;
        }
        BigDecimal liters = ch.items().stream().filter(i -> "CARBURANT".equals(i.category()) && i.quantity() != null)
                .map(Item::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (liters.signum() == 0) {
            return;
        }
        Map<String, Object> u = db.one("""
                \sSELECT COALESCE(sum(l.quantity) FILTER (WHERE x.at >= current_date), 0) AS liters_today,
                       COALESCE(sum(l.quantity), 0) AS liters_month,
                       count(DISTINCT x.id) FILTER (WHERE x.at >= current_date) AS fills_today
                FROM sale x JOIN sale_line l ON l.sale_id = x.id JOIN product p ON p.id = l.product_id
                WHERE x.card_id = :c AND p.category = 'CARBURANT' AND x.at >= date_trunc('month', now())""",
                Db.p("c", card.get("id")));
        if (fills != null && ((Number) u.get("fillsToday")).intValue() >= ((Number) fills).intValue()) {
            refuse(card, ch, "PLAFOND_PLEINS", "Nombre de pleins du jour atteint (" + fills + ")");
        }
        if (dayVol != null && ((BigDecimal) u.get("litersToday")).add(liters).compareTo((BigDecimal) dayVol) > 0) {
            refuse(card, ch, "PLAFOND_VOLUME_JOUR", "Plafond journalier de " + ((BigDecimal) dayVol).stripTrailingZeros().toPlainString()
                    + " L atteint");
        }
        if (monthVol != null && ((BigDecimal) u.get("litersMonth")).add(liters).compareTo((BigDecimal) monthVol) > 0) {
            refuse(card, ch, "PLAFOND_VOLUME_MOIS", "Plafond mensuel de " + ((BigDecimal) monthVol).stripTrailingZeros().toPlainString()
                    + " L atteint");
        }
    }

    private long maxAmount(Map<String, Object> card) {
        List<Long> limits = new ArrayList<>();
        limits.add(available(card));
        Map<String, Object> u = usage(((Number) card.get("id")).longValue());
        if (card.get("perTxLimit") != null) {
            limits.add(num(card.get("perTxLimit")));
        }
        if (card.get("dailyLimit") != null) {
            limits.add(num(card.get("dailyLimit")) - num(u.get("spentToday")));
        }
        if (card.get("monthlyLimit") != null) {
            limits.add(num(card.get("monthlyLimit")) - num(u.get("spentMonth")));
        }
        return Math.max(0, limits.stream().mapToLong(Long::longValue).min().orElse(0));
    }

    private void refuse(Map<String, Object> card, Charge ch, String code, String message) {
        refusals.record(((Number) card.get("id")).longValue(), (String) card.get("number"), ch.stationId(),
                ch.items().isEmpty() ? null : ch.total(), code, message);
        throw ApiException.refused(code, message);
    }

    static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
