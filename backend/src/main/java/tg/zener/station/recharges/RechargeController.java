package tg.zener.station.recharges;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.validations.ValidationService;

/** Rechargements (écran 07) : unitaire, à distance, en masse. */
@RestController
@RequestMapping("/api/recharges")
@PreAuthorize("hasAuthority('RECHARGER')")
public class RechargeController {

    private final Db db;
    private final RechargeService recharges;
    private final ValidationService validations;
    private final Journal journal;

    public RechargeController(Db db, RechargeService recharges, ValidationService validations, Journal journal) {
        this.db = db;
        this.recharges = recharges;
        this.validations = validations;
        this.journal = journal;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('RECHARGER') or hasAuthority('TRANSACTIONS')")
    public Page history(@RequestParam(required = false) String status, @RequestParam(required = false) String channel,
                        @RequestParam(required = false) Long cardId, @RequestParam(required = false) Integer page,
                        @RequestParam(required = false) Integer size) {
        Long scope = Auth.scopedStation(null);
        String where = """
                 \sWHERE (CAST(:st AS text) IS NULL OR r.status = :st) AND (CAST(:ch AS text) IS NULL OR r.channel = :ch)
                   AND (CAST(:c AS bigint) IS NULL OR r.card_id = :c) AND (CAST(:scope AS bigint) IS NULL OR r.station_id = :scope)""";
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> p = Db.p("st", status, "ch", channel, "c", cardId, "scope", scope, "lim", sz, "off", pg * sz);
        List<Map<String, Object>> items = db.list("""
                \sSELECT r.id, r.ref, r.amount, r.channel, r.payment_method, r.payment_ref, r.status, r.created_at, r.credited_at,
                       c.number, h.name AS holder_name, v.ref AS validation_ref,
                       e.first_name || ' ' || e.last_name AS requested_by_name,
                       COALESCE(pv.name, s.short_name, 'Siège') AS place
                FROM recharge r JOIN card c ON c.id = r.card_id LEFT JOIN holder h ON h.id = c.holder_id
                LEFT JOIN validation_request v ON v.id = r.validation_id
                JOIN employee e ON e.id = r.requested_by
                LEFT JOIN station s ON s.id = r.station_id LEFT JOIN pdv pv ON pv.id = r.pdv_id""" + where
                + " ORDER BY r.created_at DESC LIMIT :lim OFFSET :off", p);
        items.forEach(i -> i.put("masked", CardService.mask((String) i.get("number"))));
        return new Page(items, db.count("SELECT count(*) FROM recharge r" + where, p), pg, sz);
    }

    public record RechargeForm(@NotBlank String cardNumber, @Positive long amount,
                               @NotBlank @Pattern(regexp = "ESPECES|MOBILE_MONEY|VIREMENT|CARTE_BANCAIRE") String paymentMethod,
                               String paymentRef, Long pdvId) {}

    /** Recharge unitaire (au guichet). Au-dessus du palier, elle part en validation. */
    @PostMapping
    public Map<String, Object> recharge(@Valid @RequestBody RechargeForm f) {
        CurrentUser u = Auth.user();
        return recharges.recharge(new RechargeService.Request(f.cardNumber(), f.amount(), "UNITAIRE", f.paymentMethod(),
                f.paymentRef(), u.stationId(), f.pdvId()), u);
    }

    /** Contrôle « nom + numéro de carte » avant une recharge à distance (ne révèle pas le nom du titulaire). */
    @GetMapping("/remote-check")
    public Map<String, Object> remoteCheck(@RequestParam String cardNumber, @RequestParam String name) {
        return Map.of("match", recharges.nameMatches(cardNumber, name));
    }

    public record RemoteForm(@NotBlank String cardNumber, @NotBlank String holderName, @Positive long amount,
                             @Pattern(regexp = "MOBILE_MONEY|VIREMENT|CARTE_BANCAIRE") String paymentMethod,
                             String paymentRef) {}

    /** Recharge à distance : le nom de l'utilisateur et le numéro de carte suffisent, s'ils concordent. */
    @PostMapping("/remote")
    public Map<String, Object> remote(@Valid @RequestBody RemoteForm f) {
        if (!recharges.nameMatches(f.cardNumber(), f.holderName())) {
            throw new ApiException("Le nom ne correspond pas au titulaire de la carte");
        }
        CurrentUser u = Auth.user();
        return recharges.recharge(new RechargeService.Request(f.cardNumber(), f.amount(), "DISTANCE",
                f.paymentMethod() == null ? "MOBILE_MONEY" : f.paymentMethod(), f.paymentRef(), u.stationId(), null), u);
    }

    // ------------------------------------------------------------------ recharge en masse

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        String csv = "﻿numero_carte;montant;libelle\n6011 2847 0093;250000;Camion 12\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele_recharges.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Import d'un fichier (.csv, .xlsx) : chaque ligne est contrôlée avant envoi. */
    @PostMapping(value = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Object> importFile(@RequestPart("file") MultipartFile file,
                                          @RequestParam(defaultValue = "VIREMENT") String paymentMethod,
                                          @RequestParam(required = false) String paymentRef) throws IOException {
        List<BatchImport.Line> lines = BatchImport.read(file.getOriginalFilename(), file.getInputStream());
        return createBatch(file.getOriginalFilename(), lines, paymentMethod, paymentRef);
    }

    public record ListLine(String cardNumber, String amount, String label) {}

    public record ListForm(@NotEmpty List<ListLine> lines, String paymentMethod, String paymentRef) {}

    /** Même contrôle à partir d'une liste saisie à l'écran. */
    @PostMapping(value = "/batches", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String, Object> importList(@Valid @RequestBody ListForm f) {
        List<BatchImport.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < f.lines().size(); i++) {
            ListLine l = f.lines().get(i);
            lines.add(new BatchImport.Line(i + 1, l.cardNumber() == null ? "" : l.cardNumber(),
                    l.amount() == null ? "" : l.amount(), l.label()));
        }
        return createBatch(null, lines, f.paymentMethod() == null ? "VIREMENT" : f.paymentMethod(), f.paymentRef());
    }

    private Map<String, Object> createBatch(String fileName, List<BatchImport.Line> lines, String paymentMethod, String paymentRef) {
        if (!List.of("ESPECES", "MOBILE_MONEY", "VIREMENT", "CARTE_BANCAIRE").contains(paymentMethod)) {
            throw new ApiException("Mode de paiement invalide");
        }
        if (lines.isEmpty()) {
            throw new ApiException("Le fichier ne contient aucune ligne");
        }
        long threshold = validations.threshold();
        long batch = db.insert("""
                \sINSERT INTO recharge_batch (file_name, payment_method, payment_ref, created_by)
                VALUES (:f, :pm, :pr, :u)""",
                Db.p("f", fileName, "pm", paymentMethod, "pr", paymentRef, "u", Auth.user().id()));
        Set<String> seen = new HashSet<>();
        for (BatchImport.Line l : lines) {
            String digits = l.cardNumber().replaceAll("[^0-9]", "");
            Long amount = parseAmount(l.amount());
            Map<String, Object> card = digits.length() == 12 ? db.one("""
                    \sSELECT c.id, c.status, h.name, c.vehicle_label FROM card c LEFT JOIN holder h ON h.id = c.holder_id
                    WHERE c.number = :n""", Db.p("n", digits)) : null;
            String status;
            if (card == null) {
                status = "CARTE_INTROUVABLE";
            } else if (!seen.add(digits)) {
                status = "DOUBLON";
            } else if (!"ACTIVE".equals(card.get("status"))) {
                status = "CARTE_INACTIVE";
            } else if (amount == null || amount <= 0) {
                status = "MONTANT_INVALIDE";
            } else if (amount > threshold) {
                status = "VALIDATION_REQUISE";
            } else {
                status = "PRETE";
            }
            String holder = card == null ? null : card.get("name")
                    + (l.label() != null ? " · " + l.label() : card.get("vehicleLabel") != null ? " · " + card.get("vehicleLabel") : "");
            db.update("""
                    \sINSERT INTO recharge_batch_line (batch_id, line_no, card_number, card_id, holder_label, amount, check_status, included)
                    VALUES (:b, :n, :cn, :c, :h, :a, :s, :inc)""",
                    Db.p("b", batch, "n", l.lineNo(), "cn", digits.isEmpty() ? l.cardNumber() : digits,
                            "c", card == null ? null : card.get("id"), "h", holder, "a", amount, "s", status,
                            "inc", "PRETE".equals(status) || "VALIDATION_REQUISE".equals(status)));
        }
        return batch(batch, 0, 50);
    }

    private static Long parseAmount(String s) {
        String digits = s == null ? "" : s.replaceAll("[\\s  ]", "").replace(",00", "").replace(".00", "");
        try {
            return digits.isEmpty() ? null : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Aperçu du lot : lignes contrôlées et récapitulatif (montant total, montant soumis à validation…). */
    @GetMapping("/batches/{id}")
    public Map<String, Object> batch(@PathVariable long id, @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        Map<String, Object> b = db.require("""
                \sSELECT b.id, b.file_name, b.status, b.payment_method, b.payment_ref, b.created_at,
                       e.first_name || ' ' || e.last_name AS created_by_name
                FROM recharge_batch b JOIN employee e ON e.id = b.created_by WHERE b.id = :id""", Db.p("id", id), "Lot introuvable");
        b.putAll(db.one("""
                \sSELECT count(*) AS lines,
                       count(*) FILTER (WHERE check_status = 'PRETE') AS ready,
                       count(*) FILTER (WHERE check_status = 'VALIDATION_REQUISE') AS to_validate,
                       count(*) FILTER (WHERE check_status NOT IN ('PRETE', 'VALIDATION_REQUISE')) AS errors,
                       count(*) FILTER (WHERE included) AS valid_cards,
                       COALESCE(sum(amount) FILTER (WHERE included), 0) AS total_amount,
                       COALESCE(sum(amount) FILTER (WHERE included AND check_status = 'VALIDATION_REQUISE'), 0) AS amount_to_validate,
                       count(*) FILTER (WHERE included AND check_status = 'PRETE' AND recharge_id IS NULL) AS ready_to_credit
                FROM recharge_batch_line WHERE batch_id = :id""", Db.p("id", id)));
        b.put("threshold", validations.threshold());
        b.put("validation", db.one("""
                \sSELECT DISTINCT v.id, v.ref, v.status FROM recharge r JOIN validation_request v ON v.id = r.validation_id
                WHERE r.batch_id = :id""", Db.p("id", id)));
        int sz = Page.size(size == null ? 50 : size);
        int pg = Page.page(page);
        List<Map<String, Object>> lines = db.list("""
                \sSELECT l.id, l.line_no, l.card_number, l.holder_label, l.amount, l.check_status, l.included,
                       r.status AS recharge_status
                FROM recharge_batch_line l LEFT JOIN recharge r ON r.id = l.recharge_id
                WHERE l.batch_id = :id ORDER BY l.line_no LIMIT :lim OFFSET :off""", Db.p("id", id, "lim", sz, "off", pg * sz));
        lines.forEach(l -> {
            String n = (String) l.get("cardNumber");
            l.put("cardNumberFormatted", n != null && n.length() == 12 ? CardService.format(n) : n);
            l.put("checkLabel", switch ((String) l.get("checkStatus")) {
                case "PRETE" -> "Prête";
                case "VALIDATION_REQUISE" -> "Validation requise";
                case "CARTE_INTROUVABLE" -> "Carte introuvable";
                case "CARTE_INACTIVE" -> "Carte inactive";
                case "DOUBLON" -> "Doublon";
                default -> "Montant invalide";
            });
        });
        b.put("items", lines);
        b.put("page", pg);
        b.put("size", sz);
        return b;
    }

    public record LineToggle(boolean included) {}

    /** Coche / décoche une ligne (seules les lignes prêtes ou à valider peuvent être incluses). */
    @PutMapping("/batches/{id}/lines/{lineId}")
    public Map<String, Object> toggle(@PathVariable long id, @PathVariable long lineId, @RequestBody LineToggle t) {
        draft(id);
        int n = db.update("""
                \sUPDATE recharge_batch_line SET included = :i
                WHERE id = :l AND batch_id = :b AND recharge_id IS NULL
                  AND (NOT :i OR check_status IN ('PRETE', 'VALIDATION_REQUISE'))""",
                Db.p("b", id, "l", lineId, "i", t.included()));
        if (n == 0) {
            throw new ApiException("Cette ligne ne peut pas être modifiée");
        }
        return batch(id, 0, 50);
    }

    /** Crédite tout de suite les cartes prêtes (sous le palier). */
    @PostMapping("/batches/{id}/credit-ready")
    @Transactional
    public Map<String, Object> creditReady(@PathVariable long id) {
        Map<String, Object> b = draft(id);
        CurrentUser u = Auth.user();
        long count = 0;
        long total = 0;
        for (Map<String, Object> l : db.list("""
                \sSELECT l.id, l.card_id, l.amount FROM recharge_batch_line l
                WHERE l.batch_id = :b AND l.included AND l.check_status = 'PRETE' AND l.recharge_id IS NULL ORDER BY l.line_no""",
                Db.p("b", id))) {
            long rid = insertRecharge(l, id, b, u, "CREDITEE");
            recharges.credit(rid, u.id());
            count++;
            total += ((Number) l.get("amount")).longValue();
        }
        if (count > 0) {
            journal.log("RECHARGE_MASSE", Journal.RECHARGES, "Recharge en masse", count + " cartes créditées · lot " + id,
                    total, u.stationId(), null);
        }
        if (db.count("SELECT count(*) FROM recharge_batch_line WHERE batch_id = :b AND included AND recharge_id IS NULL",
                Db.p("b", id)) == 0) {
            db.update("UPDATE recharge_batch SET status = 'TERMINE' WHERE id = :b", Db.p("b", id));
        }
        return batch(id, 0, 50);
    }

    /**
     * Soumission : crédite les cartes prêtes et regroupe les lignes au-dessus du palier
     * dans une seule demande de validation.
     */
    @PostMapping("/batches/{id}/submit")
    @Transactional
    public Map<String, Object> submit(@PathVariable long id) {
        Map<String, Object> b = draft(id);
        creditReady(id);
        CurrentUser u = Auth.user();
        List<Map<String, Object>> toValidate = db.list("""
                \sSELECT l.id, l.card_id, l.amount, h.kind, h.name FROM recharge_batch_line l
                JOIN card c ON c.id = l.card_id LEFT JOIN holder h ON h.id = c.holder_id
                WHERE l.batch_id = :b AND l.included AND l.check_status = 'VALIDATION_REQUISE' AND l.recharge_id IS NULL""",
                Db.p("b", id));
        if (toValidate.isEmpty()) {
            return batch(id, 0, 50);
        }
        long total = 0;
        boolean company = false;
        Set<String> holders = new java.util.LinkedHashSet<>();
        for (Map<String, Object> l : toValidate) {
            total += ((Number) l.get("amount")).longValue();
            company |= "ENTREPRISE".equals(l.get("kind"));
            holders.add(String.valueOf(l.get("name")));
        }
        String beneficiary = holders.size() == 1 ? holders.iterator().next() : holders.size() + " titulaires";
        long v = validations.create(new ValidationService.NewRequest(ValidationService.RECHARGE_MASSE, beneficiary,
                "Recharge en masse · " + toValidate.size() + " carte" + (toValidate.size() > 1 ? "s" : ""), total, company,
                "Import en masse", RechargeService.paymentLabel((String) b.get("paymentMethod"), (String) b.get("paymentRef")), id), u);
        for (Map<String, Object> l : toValidate) {
            long rid = insertRecharge(l, id, b, u, "EN_VALIDATION");
            db.update("UPDATE recharge SET validation_id = :v WHERE id = :r", Db.p("v", v, "r", rid));
        }
        db.update("UPDATE recharge_batch SET status = 'SOUMIS' WHERE id = :b", Db.p("b", id));
        return batch(id, 0, 50);
    }

    private long insertRecharge(Map<String, Object> line, long batchId, Map<String, Object> batch, CurrentUser u, String status) {
        long rid = db.insert("""
                \sINSERT INTO recharge (ref, card_id, amount, channel, payment_method, payment_ref, status, batch_id, station_id, requested_by)
                VALUES ((SELECT 'REC-' || extract(year FROM now()) || '-' || nextval('ref_recharge_seq')), :c, :a, 'MASSE', :pm, :pr,
                        :st, :b, :s, :u)""",
                Db.p("c", line.get("cardId"), "a", line.get("amount"), "pm", batch.get("paymentMethod"),
                        "pr", batch.get("paymentRef"), "st", status, "b", batchId, "s", u.stationId(), "u", u.id()));
        db.update("UPDATE recharge_batch_line SET recharge_id = :r WHERE id = :l", Db.p("r", rid, "l", line.get("id")));
        return rid;
    }

    private Map<String, Object> draft(long id) {
        Map<String, Object> b = db.require("SELECT id, status, payment_method, payment_ref, created_by FROM recharge_batch WHERE id = :id",
                Db.p("id", id), "Lot introuvable");
        if (!"BROUILLON".equals(b.get("status"))) {
            throw ApiException.conflict("Ce lot a déjà été soumis");
        }
        return b;
    }
}
