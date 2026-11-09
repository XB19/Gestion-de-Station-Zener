package tg.zener.station.pdv;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Refs;
import tg.zener.station.common.Settings;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;
import tg.zener.station.validations.ValidationService;

/** Points de vente (écran 10) : liste, demande d'ouverture, cartes attribuées, ventes, synchronisation, clôture. */
@RestController
@RequestMapping("/api/pdv")
public class PdvController {

    private final Db db;
    private final PdvService pdvs;
    private final ValidationService validations;
    private final Refs refs;
    private final Settings settings;
    private final Journal journal;
    private final TransactionTemplate tx;

    public PdvController(Db db, PdvService pdvs, ValidationService validations, Refs refs, Settings settings, Journal journal,
                         PlatformTransactionManager tm) {
        this.db = db;
        this.pdvs = pdvs;
        this.validations = validations;
        this.refs = refs;
        this.settings = settings;
        this.journal = journal;
        this.tx = new TransactionTemplate(tm);
    }

    private String select() {
        long offline = settings.getLong("pdv.offline_seconds", 300);
        return """
                \sSELECT p.id, p.code, p.name, p.kind, p.address, p.latitude, p.longitude, p.start_date, p.end_date, p.status,
                       p.cards_planned, p.reason, p.last_sync_at, p.created_at,
                       CASE WHEN p.status = 'OUVERT' AND p.kind = 'TEMPORAIRE'
                                 AND (p.last_sync_at IS NULL OR p.last_sync_at < now() - interval '{OFFLINE} seconds') THEN 'Hors ligne'
                            WHEN p.status = 'OUVERT' THEN 'Ouvert' WHEN p.status = 'DEMANDE' THEN 'À valider'
                            WHEN p.status = 'A_CLOTURER' THEN 'À clôturer' WHEN p.status = 'CLOS' THEN 'Clos' ELSE 'Refusé' END AS status_label,
                       CASE WHEN p.end_date IS NOT NULL AND p.status IN ('OUVERT', 'A_CLOTURER') THEN p.end_date - current_date END AS days_left,
                       (SELECT count(*) FROM pdv_agent a WHERE a.pdv_id = p.id) AS agents,
                       (SELECT count(*) FROM pdv_card pc WHERE pc.pdv_id = p.id) AS cards_allocated,
                       (SELECT count(*) FROM pdv_card pc WHERE pc.pdv_id = p.id AND pc.sold_at IS NOT NULL) AS cards_sold,
                       COALESCE((SELECT sum(total) FROM sale x WHERE x.pdv_id = p.id AND x.at >= current_date), 0) AS amount_today,
                       COALESCE((SELECT sum(total) FROM sale x WHERE x.pdv_id = p.id), 0) AS amount_total,
                       v.ref AS validation_ref, v.status AS validation_status,
                       (SELECT d.first_name || ' ' || d.last_name FROM validation_step st JOIN employee d ON d.id = st.decided_by
                        WHERE st.request_id = v.id ORDER BY st.step_no DESC LIMIT 1) AS validated_by,
                       (SELECT st.decided_at FROM validation_step st WHERE st.request_id = v.id ORDER BY st.step_no DESC LIMIT 1) AS validated_at
                FROM pdv p LEFT JOIN validation_request v ON v.id = p.validation_id""".replace("{OFFLINE}", String.valueOf(offline));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PDV_GERER') or hasAuthority('PDV_VENDRE') or hasAuthority('PDV_VALIDER')")
    public List<Map<String, Object>> list() {
        return db.list(select() + """
                 \sORDER BY CASE p.status WHEN 'OUVERT' THEN 0 WHEN 'A_CLOTURER' THEN 1 WHEN 'DEMANDE' THEN 2 ELSE 3 END,
                          p.kind, p.start_date DESC NULLS FIRST""");
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PDV_GERER') or hasAuthority('PDV_VENDRE') or hasAuthority('PDV_VALIDER')")
    public Map<String, Object> summary() {
        Map<String, Object> m = db.one("""
                \sSELECT count(*) FILTER (WHERE status = 'OUVERT') AS open,
                       count(*) FILTER (WHERE status = 'OUVERT' AND kind = 'PERMANENT') AS open_permanent,
                       count(*) FILTER (WHERE status = 'OUVERT' AND kind = 'TEMPORAIRE') AS open_temporary,
                       count(*) FILTER (WHERE status = 'DEMANDE') AS pending_requests,
                       count(*) FILTER (WHERE status = 'A_CLOTURER') AS to_close
                FROM pdv""", Map.of());
        m.putAll(db.one("""
                \sSELECT count(*) FILTER (WHERE x.at >= current_date) AS cards_sold_today,
                       count(*) FILTER (WHERE x.at >= current_date - 7 AND x.at < now() - interval '7 days') AS cards_sold_same_day_last_week,
                       COALESCE(sum(x.total) FILTER (WHERE x.at >= current_date), 0) AS cashed_today,
                       COALESCE(sum(x.total) FILTER (WHERE x.at >= current_date AND x.payment_method = 'ESPECES'), 0) AS cash_today,
                       COALESCE(sum(x.total) FILTER (WHERE x.at >= current_date AND x.payment_method = 'MOBILE_MONEY'), 0) AS mobile_money_today
                FROM sale x WHERE x.kind = 'CARTE' AND x.at >= current_date - 7""", Map.of()));
        return m;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PDV_GERER') or hasAuthority('PDV_VENDRE') or hasAuthority('PDV_VALIDER')")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> p = db.require(select() + " WHERE p.id = :id", Db.p("id", id), "Point de vente introuvable");
        p.put("agentsList", db.list("""
                \sSELECT e.id, e.first_name || ' ' || e.last_name AS name, upper(left(e.first_name, 1) || left(e.last_name, 1)) AS initials,
                       a.role, e.photo_path,
                       (SELECT count(*) FROM sale x WHERE x.pdv_id = a.pdv_id AND x.employee_id = e.id AND x.kind = 'CARTE') AS cards_sold
                FROM pdv_agent a JOIN employee e ON e.id = a.employee_id
                WHERE a.pdv_id = :id ORDER BY a.role DESC, cards_sold DESC""", Db.p("id", id)));
        p.put("reconciliation", reconciliation(id));
        p.put("closure", db.one("SELECT * FROM pdv_closure WHERE pdv_id = :id", Db.p("id", id)));
        p.put("cardsByType", db.list("""
                \sSELECT c.type_code, count(*) AS allocated, count(pc.sold_at) AS sold
                FROM pdv_card pc JOIN card c ON c.id = pc.card_id WHERE pc.pdv_id = :id GROUP BY c.type_code""", Db.p("id", id)));
        return p;
    }

    /** Cartes attribuées / vendues / restantes et encaissements attendus par mode de paiement. */
    Map<String, Object> reconciliation(long id) {
        Map<String, Object> r = db.one("""
                \sSELECT count(*) AS cards_allocated, count(sold_at) AS cards_sold,
                       count(*) FILTER (WHERE returned_at IS NOT NULL) AS cards_returned,
                       count(*) FILTER (WHERE sold_at IS NULL AND returned_at IS NULL) AS cards_remaining
                FROM pdv_card WHERE pdv_id = :id""", Db.p("id", id));
        r.putAll(db.one("""
                \sSELECT COALESCE(sum(total) FILTER (WHERE payment_method = 'ESPECES'), 0) AS expected_cash,
                       COALESCE(sum(total) FILTER (WHERE payment_method = 'MOBILE_MONEY'), 0) AS expected_mobile_money,
                       COALESCE(sum(total), 0) AS expected_total
                FROM sale WHERE pdv_id = :id""", Db.p("id", id)));
        long allocated = ((Number) r.get("cardsAllocated")).longValue();
        long accounted = ((Number) r.get("cardsSold")).longValue() + ((Number) r.get("cardsReturned")).longValue()
                + ((Number) r.get("cardsRemaining")).longValue();
        r.put("cardsGap", accounted - allocated);
        return r;
    }

    // ------------------------------------------------------------------ création et ouverture

    public record PdvForm(@NotBlank String name, String address, BigDecimal latitude, BigDecimal longitude,
                          LocalDate startDate, LocalDate endDate, @Min(0) Integer cardsPlanned, String reason,
                          List<Long> agentIds) {}

    /** Point de vente permanent (siège) : ouvert immédiatement. */
    @PostMapping
    @PreAuthorize("hasAuthority('PDV_GERER')")
    @Transactional
    public Map<String, Object> createPermanent(@Valid @RequestBody PdvForm f) {
        long id = db.insert("""
                \sINSERT INTO pdv (code, name, kind, address, latitude, longitude, status, cards_planned, requested_by, last_sync_at)
                VALUES (:c, :n, 'PERMANENT', :a, :lat, :lon, 'OUVERT', :cp, :u, now())""",
                Db.p("c", refs.pdv(), "n", f.name().trim(), "a", f.address(), "lat", f.latitude(), "lon", f.longitude(),
                        "cp", f.cardsPlanned(), "u", Auth.user().id()));
        addAgents(id, f.agentIds());
        journal.log("PDV_CREE", Journal.MODIFICATIONS, "Point de vente permanent créé", f.name(), null, null, id);
        return get(id);
    }

    /** Demande d'ouverture d'un point de vente temporaire (durée réglable) : validée par l'Admin ou le DAF. */
    @PostMapping("/requests")
    @PreAuthorize("hasAuthority('PDV_GERER') or hasAuthority('PDV_VENDRE')")
    @Transactional
    public Map<String, Object> request(@Valid @RequestBody PdvForm f) {
        if (f.startDate() == null || f.endDate() == null || f.endDate().isBefore(f.startDate())) {
            throw new ApiException("Dates de début et de fin requises (fin après le début)");
        }
        if (f.endDate().isBefore(LocalDate.now())) {
            throw new ApiException("La date de fin est déjà passée");
        }
        CurrentUser u = Auth.user();
        long id = db.insert("""
                \sINSERT INTO pdv (code, name, kind, address, latitude, longitude, start_date, end_date, status, cards_planned, reason,
                                 requested_by)
                VALUES (:c, :n, 'TEMPORAIRE', :a, :lat, :lon, :sd, :ed, 'DEMANDE', :cp, :r, :u)""",
                Db.p("c", refs.pdv(), "n", f.name().trim(), "a", f.address(), "lat", f.latitude(), "lon", f.longitude(),
                        "sd", f.startDate(), "ed", f.endDate(), "cp", f.cardsPlanned(), "r", f.reason(), "u", u.id()));
        addAgents(id, f.agentIds());
        long v = validations.create(new ValidationService.NewRequest(ValidationService.OUVERTURE_PDV, f.name().trim(),
                "Ouverture d'un point de vente temporaire · " + f.startDate() + " → " + f.endDate(), null, false,
                f.reason(), null, id), u);
        db.update("UPDATE pdv SET validation_id = :v WHERE id = :id", Db.p("id", id, "v", v));
        return get(id);
    }

    /** Modification ; la date de fin d'un point temporaire reste réglable (prolongation, fin anticipée). */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PDV_GERER')")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody PdvForm f) {
        Map<String, Object> p = db.require("SELECT kind, status, start_date FROM pdv WHERE id = :id", Db.p("id", id),
                "Point de vente introuvable");
        if ("CLOS".equals(p.get("status")) || "REFUSE".equals(p.get("status"))) {
            throw ApiException.conflict("Ce point de vente est clos");
        }
        LocalDate start = f.startDate() != null ? f.startDate() : (LocalDate) p.get("startDate");
        if ("TEMPORAIRE".equals(p.get("kind")) && f.endDate() != null && start != null && f.endDate().isBefore(start)) {
            throw new ApiException("La date de fin doit suivre la date de début");
        }
        db.update("""
                \sUPDATE pdv SET name = :n, address = :a, latitude = :lat, longitude = :lon,
                       start_date = CASE WHEN kind = 'TEMPORAIRE' THEN COALESCE(:sd, start_date) END,
                       end_date = CASE WHEN kind = 'TEMPORAIRE' THEN COALESCE(:ed, end_date) END,
                       cards_planned = COALESCE(:cp, cards_planned),
                       status = CASE WHEN status = 'A_CLOTURER' AND COALESCE(:ed, end_date) >= current_date THEN 'OUVERT' ELSE status END
                WHERE id = :id""",
                Db.p("id", id, "n", f.name().trim(), "a", f.address(), "lat", f.latitude(), "lon", f.longitude(),
                        "sd", f.startDate(), "ed", f.endDate(), "cp", f.cardsPlanned()));
        journal.log("PDV_MODIFIE", Journal.MODIFICATIONS, "Point de vente modifié",
                f.name() + (f.endDate() == null ? "" : " · fin le " + f.endDate()), null, null, id);
        return get(id);
    }

    public record AgentForm(@NotNull Long employeeId, @Pattern(regexp = "AGENT|SUPERVISEUR") String role) {}

    @PostMapping("/{id}/agents")
    @PreAuthorize("hasAuthority('PDV_GERER')")
    public Map<String, Object> addAgent(@PathVariable long id, @Valid @RequestBody AgentForm f) {
        db.update("""
                \sINSERT INTO pdv_agent (pdv_id, employee_id, role) VALUES (:p, :e, COALESCE(:r, 'AGENT'))
                ON CONFLICT (pdv_id, employee_id) DO UPDATE SET role = EXCLUDED.role""",
                Db.p("p", id, "e", f.employeeId(), "r", f.role()));
        return get(id);
    }

    @DeleteMapping("/{id}/agents/{employeeId}")
    @PreAuthorize("hasAuthority('PDV_GERER')")
    public Map<String, Object> removeAgent(@PathVariable long id, @PathVariable long employeeId) {
        db.update("DELETE FROM pdv_agent WHERE pdv_id = :p AND employee_id = :e", Db.p("p", id, "e", employeeId));
        return get(id);
    }

    private void addAgents(long pdvId, List<Long> ids) {
        if (ids == null) {
            return;
        }
        for (Long e : ids) {
            db.update("INSERT INTO pdv_agent (pdv_id, employee_id) VALUES (:p, :e) ON CONFLICT DO NOTHING", Db.p("p", pdvId, "e", e));
        }
    }

    // ------------------------------------------------------------------ cartes du point de vente

    public record Allocation(@Pattern(regexp = "CLASSIQUE|VIP") String typeCode, @Min(1) @Max(2000) Integer quantity,
                             List<String> numbers) {}

    /** Attribution de cartes vierges du stock au point de vente (par type et quantité, ou par numéros). */
    @PostMapping("/{id}/cards/allocate")
    @PreAuthorize("hasAuthority('PDV_GERER')")
    @Transactional
    public Map<String, Object> allocate(@PathVariable long id, @Valid @RequestBody Allocation f) {
        Map<String, Object> p = db.require("SELECT name, status FROM pdv WHERE id = :id", Db.p("id", id), "Point de vente introuvable");
        if (!List.of("OUVERT", "DEMANDE").contains(p.get("status"))) {
            throw ApiException.conflict("Ce point de vente n'accepte plus de cartes");
        }
        List<Map<String, Object>> cards;
        if (f.numbers() != null && !f.numbers().isEmpty()) {
            List<String> nums = f.numbers().stream().map(CardService::normalize).toList();
            cards = db.list("SELECT id FROM card WHERE number = ANY(:n) AND status = 'STOCK' FOR UPDATE",
                    Db.p("n", db.array("text", nums.toArray())));
            if (cards.size() != nums.size()) {
                throw ApiException.conflict("Certaines cartes ne sont pas disponibles dans le stock");
            }
        } else {
            if (f.typeCode() == null || f.quantity() == null) {
                throw new ApiException("Type et quantité requis");
            }
            cards = db.list("""
                    \sSELECT id FROM card WHERE type_code = :t AND status = 'STOCK' ORDER BY number LIMIT :q FOR UPDATE SKIP LOCKED""",
                    Db.p("t", f.typeCode(), "q", f.quantity()));
            if (cards.size() < f.quantity()) {
                throw ApiException.conflict("Stock insuffisant : " + cards.size() + " cartes " + f.typeCode().toLowerCase() + " disponibles");
            }
        }
        Object[] ids = cards.stream().map(c -> c.get("id")).toArray();
        db.update("UPDATE card SET status = 'PDV', pdv_id = :p WHERE id = ANY(:ids)", Db.p("p", id, "ids", db.array("bigint", ids)));
        db.update("""
                \sINSERT INTO pdv_card (pdv_id, card_id) SELECT :p, unnest(CAST(:ids AS bigint[]))
                ON CONFLICT (pdv_id, card_id) DO UPDATE SET returned_at = NULL, allocated_at = now()""",
                Db.p("p", id, "ids", db.array("bigint", ids)));
        journal.log("PDV_CARTES", Journal.MODIFICATIONS, "Cartes attribuées au point de vente",
                p.get("name") + " · " + ids.length + " cartes", null, null, id);
        return get(id);
    }

    /** Cartes encore disponibles au point de vente (à proposer à l'agent). */
    @GetMapping("/{id}/cards")
    @PreAuthorize("hasAuthority('PDV_GERER') or hasAuthority('PDV_VENDRE')")
    public List<Map<String, Object>> cards(@PathVariable long id) {
        List<Map<String, Object>> rows = db.list("""
                \sSELECT c.id, c.number, c.type_code, pc.allocated_at, pc.sold_at
                FROM pdv_card pc JOIN card c ON c.id = pc.card_id
                WHERE pc.pdv_id = :id AND pc.returned_at IS NULL ORDER BY pc.sold_at NULLS FIRST, c.number""", Db.p("id", id));
        rows.forEach(r -> r.put("numberFormatted", CardService.format((String) r.get("number"))));
        return rows;
    }

    // ------------------------------------------------------------------ ventes et synchronisation

    public record HolderForm(@NotBlank String name, String idDocType, @NotBlank String idDocNumber, String phone, String email) {}

    public record SaleForm(UUID uuid, @NotBlank String cardNumber, @NotNull @Valid HolderForm holder,
                           @NotBlank @Pattern(regexp = "ESPECES|MOBILE_MONEY") String paymentMethod, String paymentRef,
                           java.time.Instant at) {}

    @PostMapping("/{id}/sales")
    @PreAuthorize("hasAuthority('PDV_VENDRE')")
    public Map<String, Object> sell(@PathVariable long id, @Valid @RequestBody SaleForm f) {
        return pdvs.sellCard(id, toSale(f, f.at() == null ? null : f.at()), Auth.user(), false);
    }

    private static PdvService.CardSale toSale(SaleForm f, java.time.Instant at) {
        HolderForm h = f.holder();
        return new PdvService.CardSale(f.uuid() == null ? UUID.randomUUID() : f.uuid(), f.cardNumber(),
                new PdvService.Holder(h.name(), h.idDocType(), h.idDocNumber(), h.phone(), h.email()),
                f.paymentMethod(), f.paymentRef(), at);
    }

    public record SyncForm(@NotEmpty List<@Valid SaleForm> operations) {}

    /**
     * Synchronisation des ventes faites hors connexion. Chaque opération est traitée seule :
     * une erreur n'empêche pas les autres ; un renvoi est reconnu comme doublon.
     */
    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAuthority('PDV_VENDRE')")
    public Map<String, Object> sync(@PathVariable long id, @Valid @RequestBody SyncForm f) {
        CurrentUser u = Auth.user();
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        for (SaleForm op : f.operations()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("uuid", op.uuid());
            if (op.uuid() == null) {
                r.put("status", "ERREUR");
                r.put("message", "Identifiant d'opération (uuid) requis");
                results.add(r);
                continue;
            }
            try {
                Map<String, Object> sale = tx.execute(s -> pdvs.sellCard(id, toSale(op, op.at()), u, true));
                r.put("status", Boolean.TRUE.equals(sale.get("duplicate")) ? "DOUBLON" : "OK");
                r.put("saleId", sale.get("id"));
                ok++;
            } catch (ApiException e) {
                r.put("status", "ERREUR");
                r.put("message", e.getMessage());
            }
            results.add(r);
        }
        db.update("UPDATE pdv SET last_sync_at = now() WHERE id = :id", Db.p("id", id));
        journal.log("SYNCHRO", Journal.SYSTEME, "Synchronisation hors ligne",
                ok + " opération" + (ok > 1 ? "s" : "") + " envoyée" + (ok > 1 ? "s" : "") + " après retour réseau", null, null, id);
        return Map.of("results", results, "ok", ok, "errors", f.operations().size() - ok);
    }

    /** Signal de vie de l'appareil du point de vente (statut « Synchronisé · il y a 30 s »). */
    @PostMapping("/{id}/heartbeat")
    @PreAuthorize("hasAuthority('PDV_VENDRE')")
    public Map<String, Object> heartbeat(@PathVariable long id) {
        db.update("UPDATE pdv SET last_sync_at = now() WHERE id = :id", Db.p("id", id));
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ clôture

    public record ClosureForm(@NotNull @Min(0) Long countedCash, @NotNull @Min(0) Long countedMobileMoney, String note) {}

    /**
     * Clôture (à la date de fin ou manuelle) : comptage des espèces et du mobile money,
     * retour des cartes invendues au stock, rapport de rapprochement.
     */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('PDV_GERER') or hasAuthority('PDV_VENDRE')")
    @Transactional
    public Map<String, Object> close(@PathVariable long id, @Valid @RequestBody ClosureForm f) {
        CurrentUser u = Auth.user();
        Map<String, Object> p = db.require("SELECT name, kind, status FROM pdv WHERE id = :id FOR UPDATE", Db.p("id", id),
                "Point de vente introuvable");
        if ("PERMANENT".equals(p.get("kind"))) {
            throw ApiException.conflict("Le point de vente permanent ne se clôture pas");
        }
        if (!List.of("OUVERT", "A_CLOTURER").contains(p.get("status"))) {
            throw ApiException.conflict("Ce point de vente n'est pas ouvert");
        }
        if (!u.can("PDV_GERER") && db.count("SELECT count(*) FROM pdv_agent WHERE pdv_id = :p AND employee_id = :e AND role = 'SUPERVISEUR'",
                Db.p("p", id, "e", u.id())) == 0) {
            throw ApiException.forbidden("Seul le superviseur du point de vente peut le clôturer");
        }
        // cartes invendues : retour au stock
        db.update("""
                \sUPDATE card SET status = 'STOCK', pdv_id = NULL
                WHERE id IN (SELECT card_id FROM pdv_card WHERE pdv_id = :p AND sold_at IS NULL AND returned_at IS NULL)""",
                Db.p("p", id));
        db.update("UPDATE pdv_card SET returned_at = now() WHERE pdv_id = :p AND sold_at IS NULL AND returned_at IS NULL", Db.p("p", id));
        Map<String, Object> r = reconciliation(id);
        long expectedCash = ((Number) r.get("expectedCash")).longValue();
        long expectedMm = ((Number) r.get("expectedMobileMoney")).longValue();
        long gap = f.countedCash() + f.countedMobileMoney() - expectedCash - expectedMm;
        db.update("""
                \sINSERT INTO pdv_closure (pdv_id, cards_allocated, cards_sold, cards_returned, expected_cash, counted_cash,
                                         expected_mm, counted_mm, gap, note, closed_by)
                VALUES (:p, :ca, :cs, :cr, :ec, :cc, :em, :cm, :g, :n, :u)""",
                Db.p("p", id, "ca", r.get("cardsAllocated"), "cs", r.get("cardsSold"), "cr", r.get("cardsReturned"),
                        "ec", expectedCash, "cc", f.countedCash(), "em", expectedMm, "cm", f.countedMobileMoney(), "g", gap,
                        "n", f.note(), "u", u.id()));
        db.update("UPDATE pdv SET status = 'CLOS' WHERE id = :p", Db.p("p", id));
        journal.log("PDV_CLOS", Journal.VENTES_CARTES, "Point de vente clôturé",
                p.get("name") + " · " + r.get("cardsSold") + " cartes vendues · écart " + gap + " F",
                f.countedCash() + f.countedMobileMoney(), null, id);
        return get(id);
    }
}
