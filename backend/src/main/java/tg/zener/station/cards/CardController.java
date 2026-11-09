package tg.zener.station.cards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/** Cartes (écrans 05 et 06) : consultation, création, stock, permissions, activation, blocage. */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    static final String STATUS_LABEL = """
            \sCASE c.status WHEN 'ACTIVE' THEN 'Active' WHEN 'BLOQUEE' THEN 'Bloquée' WHEN 'INACTIVE' THEN 'Inactive'
                          WHEN 'A_ACTIVER' THEN 'À activer' WHEN 'PDV' THEN 'Au point de vente' ELSE 'En stock' END""";

    static final String LIST_SELECT = """
            \sSELECT c.id, c.number, c.type_code, ct.label AS type_label, c.techno, c.status,
                   """ + STATUS_LABEL + """
                    \sAS status_label,
                   c.balance, c.holder_id, h.name AS holder_name, h.kind AS holder_kind, c.group_id, g.name AS group_name,
                   c.vehicle_label, c.expires_on, c.created_at,
                   last_op.label AS last_operation, last_op.at AS last_operation_at
            FROM card c
            JOIN card_type ct ON ct.code = c.type_code
            LEFT JOIN holder h ON h.id = c.holder_id
            LEFT JOIN card_group g ON g.id = c.group_id
            LEFT JOIN LATERAL (
                SELECT * FROM (
                    SELECT m.at, CASE m.kind WHEN 'RECHARGE' THEN CASE WHEN r.channel = 'MASSE' THEN 'Recharge masse' ELSE 'Recharge' END
                                             WHEN 'VENTE' THEN 'Vente ' || COALESCE(m.detail, '') ELSE 'Ajustement' END AS label
                    FROM card_movement m LEFT JOIN recharge r ON r.id = m.recharge_id
                    WHERE m.card_id = c.id
                    UNION ALL
                    SELECT f.at, 'Refus · ' || f.detail FROM card_refusal f WHERE f.card_id = c.id
                ) ops ORDER BY at DESC LIMIT 1) last_op ON true""";

    private final Db db;
    private final CardService cards;
    private final CardIssuer issuer;
    private final Settings settings;
    private final Journal journal;

    public CardController(Db db, CardService cards, CardIssuer issuer, Settings settings, Journal journal) {
        this.db = db;
        this.cards = cards;
        this.issuer = issuer;
        this.settings = settings;
        this.journal = journal;
    }

    private static Map<String, Object> decorate(Map<String, Object> c) {
        c.put("numberFormatted", CardService.format((String) c.get("number")));
        return c;
    }

    /**
     * Liste filtrée. tab : ALL (cartes émises), FLOTTE, STOCK (cartes vierges et au point de vente),
     * A_ACTIVER (file d'activation).
     */
    @GetMapping
    public Page list(@RequestParam(required = false) String q, @RequestParam(required = false) String tab,
                     @RequestParam(required = false) String type, @RequestParam(required = false) Long groupId,
                     @RequestParam(required = false) Long holderId, @RequestParam(required = false) String status,
                     @RequestParam(required = false) String techno,
                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        String t = tab == null ? "ALL" : tab;
        String where = """
                 \sWHERE (CAST(:q AS text) IS NULL OR c.number LIKE '%' || regexp_replace(:q, '[^0-9]', '', 'g') || '%'
                        AND regexp_replace(:q, '[^0-9]', '', 'g') <> '' OR h.name ILIKE '%' || :q || '%'
                        OR c.vehicle_plate ILIKE '%' || :q || '%')
                   AND (CAST(:type AS text) IS NULL OR c.type_code = :type)
                   AND (CAST(:g AS bigint) IS NULL OR c.group_id = :g)
                   AND (CAST(:h AS bigint) IS NULL OR c.holder_id = :h)
                   AND (CAST(:st AS text) IS NULL OR c.status = :st)
                   AND (CAST(:te AS text) IS NULL OR c.techno = :te)
                   AND CASE :tab WHEN 'FLOTTE' THEN c.type_code = 'FLOTTE' AND c.status NOT IN ('STOCK', 'PDV')
                                 WHEN 'STOCK' THEN c.status IN ('STOCK', 'PDV')
                                 WHEN 'A_ACTIVER' THEN c.status = 'A_ACTIVER'
                                 ELSE c.status NOT IN ('STOCK', 'PDV') END""";
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> params = Db.p("q", q == null || q.isBlank() ? null : q.trim(), "type", type, "g", groupId,
                "h", holderId, "st", status, "te", techno, "tab", t, "lim", sz, "off", pg * sz);
        List<Map<String, Object>> items = db.list("SELECT * FROM (" + LIST_SELECT + where
                + ") x ORDER BY x.last_operation_at DESC NULLS LAST, x.id DESC LIMIT :lim OFFSET :off", params);
        items.forEach(CardController::decorate);
        long total = db.count("""
                \sSELECT count(*) FROM card c LEFT JOIN holder h ON h.id = c.holder_id""" + where, params);
        return new Page(items, total, pg, sz);
    }

    /** Indicateurs et cycle de vie du stock de cartes numérotées. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> m = db.one("""
                \sSELECT count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                       count(*) FILTER (WHERE status = 'ACTIVE' AND activated_at >= date_trunc('month', now())) AS activated_this_month,
                       count(*) FILTER (WHERE type_code = 'FLOTTE' AND status NOT IN ('STOCK', 'PDV')) AS fleet,
                       count(DISTINCT holder_id) FILTER (WHERE type_code = 'FLOTTE' AND status NOT IN ('STOCK', 'PDV')) AS fleet_companies,
                       (SELECT count(*) FROM card_group) AS groups,
                       COALESCE(sum(balance) FILTER (WHERE status = 'ACTIVE'), 0) AS total_balance,
                       count(*) FILTER (WHERE status = 'STOCK') AS in_stock,
                       count(*) FILTER (WHERE status = 'PDV') AS at_pdv,
                       count(*) FILTER (WHERE sold_at IS NOT NULL) AS sold,
                       count(*) FILTER (WHERE status = 'A_ACTIVER') AS to_activate,
                       count(*) FILTER (WHERE status = 'BLOQUEE') AS blocked
                FROM card""", Map.of());
        m.put("stockByType", db.list("""
                \sSELECT ct.code, ct.label, ct.price, count(c.id) FILTER (WHERE c.status = 'STOCK') AS in_stock,
                       count(c.id) FILTER (WHERE c.status = 'PDV') AS at_pdv
                FROM card_type ct LEFT JOIN card c ON c.type_code = ct.code
                GROUP BY ct.code, ct.label, ct.price, ct.sort ORDER BY ct.sort"""));
        m.put("types", db.list("SELECT code, label, price, sold_to_public FROM card_type ORDER BY sort"));
        return m;
    }

    /** Consultation rapide par numéro (saisi ou lu par le lecteur) : dernières informations de la carte. */
    @GetMapping("/lookup/{number}")
    public Map<String, Object> lookup(@PathVariable String number) {
        Map<String, Object> c = db.one(LIST_SELECT + " WHERE c.number = :n", Db.p("n", CardService.normalize(number)));
        if (c == null) {
            throw ApiException.notFound("Carte introuvable");
        }
        return decorate(c);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> c = db.one(LIST_SELECT + " WHERE c.id = :id", Db.p("id", id));
        if (c == null) {
            throw ApiException.notFound("Carte introuvable");
        }
        decorate(c);
        c.putAll(db.one("""
                \sSELECT c.vehicle_plate, c.driver_name, c.blocked_reason, c.activated_at, c.sold_at,
                       (c.pin_hash IS NOT NULL) AS has_pin,
                       h.id_doc_type, h.id_doc_number, h.phone AS holder_phone, h.email AS holder_email, h.profile AS holder_profile,
                       ie.first_name || ' ' || ie.last_name AS issued_by_name,
                       ae.first_name || ' ' || ae.last_name AS activated_by_name,
                       COALESCE(sp.name, 'Siège') AS issued_at_place
                FROM card c
                LEFT JOIN holder h ON h.id = c.holder_id
                LEFT JOIN employee ie ON ie.id = c.issued_by
                LEFT JOIN employee ae ON ae.id = c.activated_by
                LEFT JOIN pdv sp ON sp.id = c.sold_pdv_id
                WHERE c.id = :id""", Db.p("id", id)));
        Map<String, Object> perm = db.one("""
                \sSELECT products, daily_limit, monthly_limit, per_tx_limit, days, time_from, time_to, station_ids,
                       autonomous_allowed, updated_at FROM card_permission WHERE card_id = :id""", Db.p("id", id));
        c.put("permissions", perm);
        Map<String, Object> usage = cards.usage(id);
        c.put("usage", usage);
        long threshold = settings.getLong("validation.threshold", 500_000);
        boolean company = "ENTREPRISE".equals(c.get("holderKind"));
        c.put("validation", Map.of("threshold", threshold,
                "approvers", company && settings.getBool("validation.company_needs_daf", true) ? "Comptable + DAF" : "Comptable"));
        c.put("operations", operations(id, 0, 10).items());
        return c;
    }

    /** Historique : ventes, recharges et refus de la carte. */
    @GetMapping("/{id}/operations")
    public Page operations(@PathVariable long id, @RequestParam(required = false) Integer page,
                           @RequestParam(required = false) Integer size) {
        int sz = Page.size(size);
        int pg = Page.page(page);
        String union = """
                \sSELECT m.at, m.kind, m.amount, m.balance_after, m.detail, s.short_name AS station_name, m.station_id,
                       x.pump_id, pu.code AS pump_code, x.mode, e.first_name || ' ' || e.last_name AS employee_name,
                       r.channel, r.ref AS recharge_ref, vr.status AS validation_status
                FROM card_movement m
                LEFT JOIN station s ON s.id = m.station_id
                LEFT JOIN sale x ON x.id = m.sale_id
                LEFT JOIN pump pu ON pu.id = x.pump_id
                LEFT JOIN employee e ON e.id = x.employee_id
                LEFT JOIN recharge r ON r.id = m.recharge_id
                LEFT JOIN validation_request vr ON vr.id = r.validation_id
                WHERE m.card_id = :id
                UNION ALL
                SELECT f.at, 'REFUS', 0, NULL, f.detail, s.short_name, f.station_id, NULL, NULL, NULL, NULL, NULL, NULL, NULL
                FROM card_refusal f LEFT JOIN station s ON s.id = f.station_id WHERE f.card_id = :id""";
        Map<String, Object> p = Db.p("id", id, "lim", sz, "off", pg * sz);
        return new Page(db.list(union + " ORDER BY at DESC LIMIT :lim OFFSET :off", p),
                db.count("SELECT count(*) FROM (" + union + ") u", p), pg, sz);
    }

    // ------------------------------------------------------------------ création

    public record HolderForm(@NotBlank @Pattern(regexp = "PARTICULIER|ENTREPRISE") String kind, @NotBlank String name,
                             @Pattern(regexp = "GRAND_PUBLIC|PARTENAIRE") String profile,
                             String idDocType, String idDocNumber, String phone, String email, String address) {}

    public record CardForm(Long holderId, @Valid HolderForm holder,
                           @NotBlank @Pattern(regexp = "CLASSIQUE|VIP|FLOTTE") String typeCode,
                           @Pattern(regexp = "PUCE|M1") String techno, String number, Long groupId,
                           String vehiclePlate, String vehicleLabel, String driverName, LocalDate expiresOn, String pin) {}

    /** Création d'une carte pour un titulaire (carte du stock ou nouveau numéro) ; elle reste « à activer ». */
    @PostMapping
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CardForm f) {
        CurrentUser u = Auth.user();
        long holderId = f.holderId() != null ? f.holderId() : createHolder(f.holder());
        Map<String, Object> h = db.require("SELECT kind, name, id_doc_number FROM holder WHERE id = :id",
                Db.p("id", holderId), "Titulaire introuvable");
        if ("FLOTTE".equals(f.typeCode()) && !"ENTREPRISE".equals(h.get("kind"))) {
            throw new ApiException("Une carte flotte est attribuée à une entreprise");
        }
        if ("PARTICULIER".equals(h.get("kind")) && h.get("idDocNumber") == null) {
            throw new ApiException("Pièce d'identité du titulaire requise");
        }
        if (f.groupId() != null && db.count("SELECT count(*) FROM card_group WHERE id = :g AND holder_id = :h",
                Db.p("g", f.groupId(), "h", holderId)) == 0) {
            throw new ApiException("Ce groupe n'appartient pas au titulaire");
        }
        long id = issuer.issue(f.number(), f.typeCode(), f.techno(), holderId, f.groupId(), u.id(), f.pin());
        db.update("""
                \sUPDATE card SET vehicle_plate = :vp, vehicle_label = :vl, driver_name = :d,
                       expires_on = COALESCE(:e, expires_on) WHERE id = :id""",
                Db.p("id", id, "vp", f.vehiclePlate(), "vl", f.vehicleLabel(), "d", f.driverName(), "e", f.expiresOn()));
        Map<String, Object> c = get(id);
        journal.log("CARTE_CREEE", Journal.VENTES_CARTES, "Carte créée · à activer",
                CardService.mask((String) c.get("number")) + " · " + h.get("name"), null, u.stationId(), null);
        return c;
    }

    long createHolder(HolderForm f) {
        if (f == null) {
            throw new ApiException("Titulaire requis (holderId ou holder)");
        }
        return db.insert("""
                \sINSERT INTO holder (kind, name, profile, id_doc_type, id_doc_number, phone, email, address)
                VALUES (:k, :n, COALESCE(:p, 'GRAND_PUBLIC'), :dt, :dn, :ph, :e, :a)""",
                Db.p("k", f.kind(), "n", f.name().trim(), "p", f.profile(), "dt", f.idDocType(), "dn", f.idDocNumber(),
                        "ph", f.phone(), "e", f.email(), "a", f.address()));
    }

    public record BatchForm(@NotBlank @Pattern(regexp = "CLASSIQUE|VIP|FLOTTE|EMPLOYE") String typeCode,
                            @NotBlank @Pattern(regexp = "PUCE|M1") String techno, @Min(1) @Max(5000) int quantity) {}

    /** Commande d'un lot de cartes vierges numérotées (mises en stock). */
    @PostMapping("/batches")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public Map<String, Object> createBatch(@Valid @RequestBody BatchForm f) {
        Map<String, Object> r = issuer.createBatch(f.typeCode(), f.techno(), f.quantity(), Auth.user().id());
        journal.log("STOCK_CARTES", Journal.MODIFICATIONS, "Lot de cartes vierges créé",
                f.quantity() + " cartes " + f.typeCode().toLowerCase() + " · " + CardService.format((String) r.get("firstNumber"))
                        + " → " + CardService.format((String) r.get("lastNumber")), null, null, null);
        return r;
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        return db.list("""
                \sSELECT b.id, b.type_code, b.techno, b.first_number, b.quantity, b.created_at,
                       e.first_name || ' ' || e.last_name AS created_by_name
                FROM card_batch b LEFT JOIN employee e ON e.id = b.created_by ORDER BY b.id DESC LIMIT 100""");
    }

    public record CardUpdate(Long groupId, String vehiclePlate, String vehicleLabel, String driverName, LocalDate expiresOn) {}

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public Map<String, Object> update(@PathVariable long id, @RequestBody CardUpdate f) {
        Map<String, Object> c = db.require("SELECT holder_id, number FROM card WHERE id = :id", Db.p("id", id), "Carte introuvable");
        if (f.groupId() != null && db.count("SELECT count(*) FROM card_group WHERE id = :g AND holder_id = :h",
                Db.p("g", f.groupId(), "h", c.get("holderId"))) == 0) {
            throw new ApiException("Ce groupe n'appartient pas au titulaire");
        }
        db.update("""
                \sUPDATE card SET group_id = :g, vehicle_plate = :vp, vehicle_label = :vl, driver_name = :d,
                       expires_on = COALESCE(:e, expires_on) WHERE id = :id""",
                Db.p("id", id, "g", f.groupId(), "vp", f.vehiclePlate(), "vl", f.vehicleLabel(), "d", f.driverName(),
                        "e", f.expiresOn()));
        journal.log("CARTE_MODIFIEE", Journal.MODIFICATIONS, "Carte modifiée", CardService.mask((String) c.get("number")),
                null, null, null);
        return get(id);
    }

    public record PermissionForm(List<@Pattern(regexp = "SUPER|GASOIL|PETROLE|LUBRIFIANT|GAZ|ACCESSOIRE") String> products,
                                 @Min(0) Long dailyLimit, @Min(0) Long monthlyLimit, @Min(0) Long perTxLimit,
                                 List<@Min(1) @Max(7) Integer> days, LocalTime timeFrom, LocalTime timeTo,
                                 List<Long> stationIds, Boolean autonomousAllowed) {}

    /** Permissions appliquées par toutes les stations, y compris en mode autonome. */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    @Transactional
    public Map<String, Object> permissions(@PathVariable long id, @Valid @RequestBody PermissionForm f) {
        Map<String, Object> c = db.require("SELECT number FROM card WHERE id = :id", Db.p("id", id), "Carte introuvable");
        if ((f.timeFrom() == null) != (f.timeTo() == null)) {
            throw new ApiException("Indiquez l'heure de début et l'heure de fin, ou aucune");
        }
        if (f.timeFrom() != null && !f.timeFrom().isBefore(f.timeTo())) {
            throw new ApiException("L'heure de début doit précéder l'heure de fin");
        }
        Map<String, Object> before = db.one("SELECT * FROM card_permission WHERE card_id = :id", Db.p("id", id));
        List<Integer> days = f.days() == null ? List.of(1, 2, 3, 4, 5, 6, 7) : f.days();
        List<String> products = f.products() == null ? List.of("SUPER", "GASOIL", "PETROLE", "LUBRIFIANT", "GAZ", "ACCESSOIRE")
                : f.products();
        db.update("""
                \sINSERT INTO card_permission (card_id, products, daily_limit, monthly_limit, per_tx_limit, days, time_from, time_to,
                                             station_ids, autonomous_allowed, updated_at, updated_by)
                VALUES (:id, :pr, :dl, :ml, :tl, :d, :tf, :tt, :st, COALESCE(:au, true), now(), :u)
                ON CONFLICT (card_id) DO UPDATE SET products = EXCLUDED.products, daily_limit = EXCLUDED.daily_limit,
                    monthly_limit = EXCLUDED.monthly_limit, per_tx_limit = EXCLUDED.per_tx_limit, days = EXCLUDED.days,
                    time_from = EXCLUDED.time_from, time_to = EXCLUDED.time_to, station_ids = EXCLUDED.station_ids,
                    autonomous_allowed = EXCLUDED.autonomous_allowed, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                Db.p("id", id, "pr", db.array("text", products.toArray()), "dl", f.dailyLimit(), "ml", f.monthlyLimit(),
                        "tl", f.perTxLimit(), "d", db.array("smallint", days.toArray()), "tf", f.timeFrom(), "tt", f.timeTo(),
                        "st", f.stationIds() == null ? null : db.array("bigint", f.stationIds().toArray()),
                        "au", f.autonomousAllowed(), "u", Auth.user().id()));
        journal.log("PERMISSIONS", Journal.MODIFICATIONS, "Permissions modifiées",
                "Carte " + CardService.mask((String) c.get("number")) + changes(before, f), null, Auth.user().stationId(), null);
        return get(id);
    }

    /** Résumé lisible des plafonds modifiés (« plafond journalier 150 000 → 200 000 F »). */
    private static String changes(Map<String, Object> before, PermissionForm f) {
        if (before == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        diff(parts, "plafond journalier", before.get("dailyLimit"), f.dailyLimit());
        diff(parts, "plafond mensuel", before.get("monthlyLimit"), f.monthlyLimit());
        diff(parts, "plafond par transaction", before.get("perTxLimit"), f.perTxLimit());
        return parts.isEmpty() ? "" : " · " + String.join(", ", parts);
    }

    private static void diff(List<String> parts, String label, Object before, Long after) {
        Long b = before == null ? null : ((Number) before).longValue();
        if (!Objects.equals(b, after)) {
            parts.add(label + " " + money(b) + " → " + money(after));
        }
    }

    private static String money(Long v) {
        return v == null ? "aucun" : String.format("%,d", v).replace(',', ' ') + " F";
    }

    // ------------------------------------------------------------------ activation, blocage, PIN

    /** Activation par une autre personne que celle qui a émis la carte (anti-fraude). */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('CARTES_ACTIVER')")
    @Transactional
    public Map<String, Object> activate(@PathVariable long id) {
        CurrentUser u = Auth.user();
        Map<String, Object> c = db.require("SELECT number, status, issued_by, holder_id FROM card WHERE id = :id FOR UPDATE",
                Db.p("id", id), "Carte introuvable");
        if (!"A_ACTIVER".equals(c.get("status"))) {
            throw ApiException.conflict("Cette carte n'est pas en attente d'activation");
        }
        if (c.get("issuedBy") != null && ((Number) c.get("issuedBy")).longValue() == u.id()) {
            throw ApiException.forbidden("Vous avez émis cette carte : une autre personne doit l'activer");
        }
        if (c.get("holderId") == null) {
            throw new ApiException("La carte n'a pas de titulaire");
        }
        db.update("UPDATE card SET status = 'ACTIVE', activated_by = :u, activated_at = now() WHERE id = :id",
                Db.p("id", id, "u", u.id()));
        journal.log("CARTE_ACTIVEE", Journal.VENTES_CARTES, "Carte activée", CardService.mask((String) c.get("number")),
                null, u.stationId(), null);
        return get(id);
    }

    public record BlockForm(String reason) {}

    @PostMapping("/{id}/block")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public Map<String, Object> block(@PathVariable long id, @RequestBody(required = false) BlockForm f) {
        Map<String, Object> c = db.require("SELECT number, status FROM card WHERE id = :id", Db.p("id", id), "Carte introuvable");
        if (!"ACTIVE".equals(c.get("status")) && !"A_ACTIVER".equals(c.get("status"))) {
            throw ApiException.conflict("Seule une carte active ou à activer peut être bloquée");
        }
        String reason = f == null || f.reason() == null || f.reason().isBlank() ? "Bloquée par " + Auth.user().name() : f.reason().trim();
        db.update("UPDATE card SET status = 'BLOQUEE', blocked_reason = :r WHERE id = :id", Db.p("id", id, "r", reason));
        journal.log("CARTE_BLOQUEE", Journal.MODIFICATIONS, "Carte bloquée",
                CardService.mask((String) c.get("number")) + " · " + reason, null, Auth.user().stationId(), null);
        return get(id);
    }

    /** Déblocage : la carte doit être réactivée par une autre personne. */
    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public Map<String, Object> unblock(@PathVariable long id) {
        Map<String, Object> c = db.require("SELECT number, status FROM card WHERE id = :id", Db.p("id", id), "Carte introuvable");
        if (!"BLOQUEE".equals(c.get("status"))) {
            throw ApiException.conflict("Cette carte n'est pas bloquée");
        }
        db.update("""
                \sUPDATE card SET status = 'A_ACTIVER', blocked_reason = NULL, issued_by = :u, activated_by = NULL, activated_at = NULL
                WHERE id = :id""", Db.p("id", id, "u", Auth.user().id()));
        journal.log("CARTE_DEBLOQUEE", Journal.MODIFICATIONS, "Carte débloquée · à réactiver",
                CardService.mask((String) c.get("number")), null, Auth.user().stationId(), null);
        return get(id);
    }

    public record PinForm(@NotBlank String pin) {}

    @PostMapping("/{id}/pin")
    @PreAuthorize("hasAuthority('CARTES_GERER') or hasAuthority('RECHARGER')")
    public Map<String, Object> setPin(@PathVariable long id, @Valid @RequestBody PinForm f) {
        Map<String, Object> c = db.require("SELECT number FROM card WHERE id = :id", Db.p("id", id), "Carte introuvable");
        db.update("UPDATE card SET pin_hash = :h WHERE id = :id", Db.p("id", id, "h", issuer.hashPin(f.pin())));
        journal.log("PIN_CARTE", Journal.MODIFICATIONS, "Code PIN modifié", CardService.mask((String) c.get("number")),
                null, Auth.user().stationId(), null);
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ types et prix

    @GetMapping("/types")
    public List<Map<String, Object>> types() {
        return db.list("SELECT code, label, price, sold_to_public, sort FROM card_type ORDER BY sort");
    }

    public record TypePrice(@Min(0) long price) {}

    /** Prix de vente d'un type de carte (ex. carte classique 1 000 F). */
    @PutMapping("/types/{code}")
    @PreAuthorize("hasAuthority('PRIX')")
    @Transactional
    public List<Map<String, Object>> setTypePrice(@PathVariable String code, @Valid @RequestBody TypePrice f) {
        Map<String, Object> t = db.require("SELECT label, price FROM card_type WHERE code = :c", Db.p("c", code), "Type introuvable");
        db.update("UPDATE card_type SET price = :p WHERE code = :c", Db.p("c", code, "p", f.price()));
        // le produit « carte » vendu au grand public suit le prix du type
        db.update("""
                \sUPDATE product_price SET price = :p, updated_at = now(), updated_by = :u
                WHERE profile = 'GRAND_PUBLIC' AND product_id IN (SELECT id FROM product WHERE card_type_code = :c)""",
                Db.p("c", code, "p", f.price(), "u", Auth.user().id()));
        journal.log("PRIX_CARTE", Journal.MODIFICATIONS, "Prix modifié", "Carte " + t.get("label") + " · "
                + money(((Number) t.get("price")).longValue()) + " → " + money(f.price()), null, null, null);
        return types();
    }
}
