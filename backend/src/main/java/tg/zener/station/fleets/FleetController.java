package tg.zener.station.fleets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;

/**
 * Flottes d'entreprise, véhicules et chauffeurs (écran Flottes). Lecture : FLOTTES ou VALIDER_MONTANTS ;
 * modification : FLOTTES. La création d'une flotte passe par POST /api/card-groups (groupe de cartes).
 */
@RestController
public class FleetController {

    /** Lecture : gestion des flottes, ou validation des montants (Comptable, DAF) ; pas les gérants de station. */
    private static final String READ = "hasAuthority('FLOTTES') or hasAuthority('VALIDER_MONTANTS')";

    private final Db db;
    private final FleetService fleets;
    private final Journal journal;

    public FleetController(Db db, FleetService fleets, Journal journal) {
        this.db = db;
        this.fleets = fleets;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ flottes

    /** Flottes des entreprises : compte flotte, cartes (et leur solde), véhicules, chauffeurs, consommation du mois. */
    @GetMapping("/api/fleets")
    @PreAuthorize(READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) Long holderId, @RequestParam(required = false) String q) {
        return db.list("""
                \sSELECT g.id, g.name, g.code, g.holder_id, h.name AS holder_name, g.contact_name, g.phone, g.active, g.balance,
                       (SELECT count(*) FROM card c WHERE c.group_id = g.id AND c.status <> 'INACTIVE') AS cards,
                       (SELECT COALESCE(sum(c.balance), 0) FROM card c WHERE c.group_id = g.id) AS cards_balance,
                       (SELECT count(*) FROM vehicle v WHERE v.group_id = g.id AND v.active) AS vehicles,
                       (SELECT count(*) FROM driver d WHERE d.group_id = g.id AND d.active) AS drivers,
                       (SELECT COALESCE(sum(x.total), 0) FROM sale x JOIN card c ON c.id = x.card_id
                        WHERE c.group_id = g.id AND x.at >= date_trunc('month', now())) AS spent_month
                FROM card_group g JOIN holder h ON h.id = g.holder_id
                WHERE h.kind = 'ENTREPRISE' AND (CAST(:h AS bigint) IS NULL OR g.holder_id = :h)
                  AND (CAST(:q AS text) IS NULL OR lower(h.name || ' ' || g.name) LIKE '%' || lower(:q) || '%')
                ORDER BY h.name, g.name""", Db.p("h", holderId, "q", blank(q)));
    }

    /** Détail d'une flotte : cartes avec véhicule et chauffeur, véhicules, chauffeurs, consommation 30 jours. */
    @GetMapping("/api/fleets/{id}")
    @PreAuthorize(READ)
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> g = db.require("""
                \sSELECT g.id, g.name, g.code, g.holder_id, h.name AS holder_name, g.contact_name, g.phone, g.address, g.note,
                       g.active, g.balance, g.load_up_limit, g.recharge_fee_pct, h.phone AS holder_phone, h.email AS holder_email,
                       (SELECT json_agg(json_build_object('id', o.id, 'name', o.name) ORDER BY o.name) FROM card_group o
                        WHERE o.holder_id = g.holder_id AND o.id <> g.id) AS sister_fleets
                FROM card_group g JOIN holder h ON h.id = g.holder_id WHERE g.id = :id AND h.kind = 'ENTREPRISE'""",
                Db.p("id", id), "Flotte introuvable");
        List<Map<String, Object>> cards = db.list("""
                \sSELECT c.id, c.number, c.status, c.balance, c.vehicle_id, v.plate, v.type_code, vt.label AS vehicle_type,
                       NULLIF(concat_ws(' ', v.brand, v.model), '') AS vehicle_label, c.driver_id, d.name AS driver_name,
                       (SELECT max(x.at) FROM sale x WHERE x.card_id = c.id) AS last_sale_at,
                       (SELECT COALESCE(sum(x.total), 0) FROM sale x WHERE x.card_id = c.id AND x.at >= now() - interval '30 days') AS spent_30d,
                       CASE c.status WHEN 'ACTIVE' THEN 'Active' WHEN 'BLOQUEE' THEN 'Bloquée' WHEN 'INACTIVE' THEN 'Inactive'
                                     WHEN 'A_ACTIVER' THEN 'À activer' ELSE 'En stock' END AS status_label
                FROM card c LEFT JOIN vehicle v ON v.id = c.vehicle_id LEFT JOIN vehicle_type vt ON vt.code = v.type_code
                LEFT JOIN driver d ON d.id = c.driver_id
                WHERE c.group_id = :id ORDER BY (c.status = 'INACTIVE'), c.number""", Db.p("id", id));
        cards.forEach(c -> c.put("numberFormatted", CardService.format((String) c.get("number"))));
        g.put("cards", cards);
        g.put("vehicles", vehicles(null, id, null, true));
        g.put("drivers", drivers(null, id, null, true));
        g.put("consumption", db.one("""
                \sSELECT count(DISTINCT x.id) AS fills, COALESCE(sum(l.quantity), 0) AS liters, COALESCE(sum(l.amount), 0) AS amount
                FROM sale x JOIN sale_line l ON l.sale_id = x.id JOIN card c ON c.id = x.card_id JOIN product p ON p.id = l.product_id
                WHERE c.group_id = :id AND p.category = 'CARBURANT' AND x.at >= now() - interval '30 days'""", Db.p("id", id)));
        g.put("pendingRecharges", db.list("""
                \sSELECT r.id, r.ref, r.amount, r.created_at, v.ref AS validation_ref
                FROM fleet_recharge r LEFT JOIN validation_request v ON v.id = r.validation_id
                WHERE r.group_id = :id AND r.status = 'EN_VALIDATION' ORDER BY r.created_at""", Db.p("id", id)));
        return g;
    }

    public record FleetForm(String code, String contactName, String phone, String address, String note, Boolean active,
                            @Positive Long loadUpLimit, @jakarta.validation.constraints.DecimalMin("0") BigDecimal rechargeFeePct) {}

    @PutMapping("/api/fleets/{id}")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody FleetForm f) {
        fleets.fleet(id);
        db.update("""
                \sUPDATE card_group SET code = :c, contact_name = :cn, phone = :ph, address = :a, note = :n, active = COALESCE(:ac, active),
                       load_up_limit = :lim, recharge_fee_pct = COALESCE(:fee, recharge_fee_pct)
                WHERE id = :id""",
                Db.p("id", id, "c", blank(f.code()), "cn", blank(f.contactName()), "ph", blank(f.phone()), "a", blank(f.address()),
                        "n", blank(f.note()), "ac", f.active(), "lim", f.loadUpLimit(), "fee", f.rechargeFeePct()));
        journal.log("FLOTTE_MODIFIEE", Journal.MODIFICATIONS, "Flotte modifiée", (String) get(id).get("name"), null, null, null);
        return get(id);
    }

    /** Mouvements du compte flotte (recharges, dotations, reprises). */
    @GetMapping("/api/fleets/{id}/movements")
    @PreAuthorize(READ)
    public Page movements(@PathVariable long id, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> p = Db.p("id", id, "l", sz, "o", pg * sz);
        List<Map<String, Object>> items = db.list("""
                \sSELECT m.id, m.at, m.kind, m.amount, m.balance_after, m.card_id, c.number, m.card_balance_before, m.card_balance_after,
                       r.ref AS recharge_ref, m.note, e.first_name || ' ' || e.last_name AS employee_name
                FROM fleet_movement m LEFT JOIN card c ON c.id = m.card_id LEFT JOIN fleet_recharge r ON r.id = m.fleet_recharge_id
                LEFT JOIN employee e ON e.id = m.employee_id
                WHERE m.group_id = :id ORDER BY m.at DESC, m.id DESC LIMIT :l OFFSET :o""", p);
        items.forEach(i -> i.put("numberFormatted", i.get("number") == null ? null : CardService.format((String) i.get("number"))));
        return new Page(items, db.count("SELECT count(*) FROM fleet_movement WHERE group_id = :id", p), pg, sz);
    }

    public record RechargeForm(@Positive long amount,
                               @NotBlank @Pattern(regexp = "ESPECES|MOBILE_MONEY|VIREMENT|CARTE_BANCAIRE") String paymentMethod,
                               String paymentRef, String note) {}

    /** Recharge du compte flotte (validation au-dessus du palier). */
    @PostMapping("/api/fleets/{id}/recharges")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> recharge(@PathVariable long id, @Valid @RequestBody RechargeForm f) {
        return fleets.recharge(id, f.amount(), f.paymentMethod(), blank(f.paymentRef()), blank(f.note()), Auth.user());
    }

    public record FleetTransferForm(@NotNull Long toGroupId, @Positive long amount, String note) {}

    /** Transfert vers une autre flotte de la même entreprise. */
    @PostMapping("/api/fleets/{id}/transfer")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> transfer(@PathVariable long id, @Valid @RequestBody FleetTransferForm f) {
        fleets.transfer(id, f.toGroupId(), f.amount(), blank(f.note()), Auth.user().id());
        return get(id);
    }

    public record AllocationLine(@NotNull Long cardId, @Positive long amount) {}

    public record AllocationForm(@NotEmpty List<@Valid AllocationLine> lines, String note) {}

    /** Dotation : le compte flotte crédite une ou plusieurs cartes de la flotte. */
    @PostMapping("/api/fleets/{id}/allocate")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> allocate(@PathVariable long id, @Valid @RequestBody AllocationForm f) {
        fleets.allocate(id, f.lines().stream().map(l -> new FleetService.Line(l.cardId(), l.amount())).toList(), blank(f.note()),
                Auth.user().id());
        return get(id);
    }

    public record ReclaimForm(@NotNull Long cardId, @Positive Long amount, String note) {}

    /** Reprise : le solde (ou une partie) d'une carte revient au compte flotte. */
    @PostMapping("/api/fleets/{id}/reclaim")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> reclaim(@PathVariable long id, @Valid @RequestBody ReclaimForm f) {
        fleets.reclaim(id, f.cardId(), f.amount(), blank(f.note()), Auth.user().id());
        return get(id);
    }

    public record CardAssignment(Long vehicleId, Long driverId) {}

    /** Véhicule et chauffeur d'une carte. */
    @PutMapping("/api/fleets/cards/{cardId}/assignment")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> assignCard(@PathVariable long cardId, @RequestBody CardAssignment f) {
        fleets.assignCard(cardId, f.vehicleId(), f.driverId(), Auth.user().id());
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ véhicules

    @GetMapping("/api/vehicle-types")
    public List<Map<String, Object>> vehicleTypes() {
        return db.list("SELECT code, label FROM vehicle_type ORDER BY sort");
    }

    @GetMapping("/api/vehicles")
    @PreAuthorize(READ)
    public List<Map<String, Object>> vehicles(@RequestParam(required = false) Long holderId, @RequestParam(required = false) Long groupId,
                                              @RequestParam(required = false) String q, @RequestParam(required = false) Boolean all) {
        return db.list("""
                \sSELECT v.id, v.holder_id, h.name AS holder_name, v.group_id, g.name AS group_name, v.plate, v.type_code,
                       vt.label AS type_label, v.brand, v.model, v.color, v.product_id, p.name AS product_name, v.tank_capacity,
                       v.odometer_km, v.active, v.note, a.driver_id, d.name AS driver_name, a.started_at AS assigned_at,
                       (SELECT string_agg(substr(c.number, 9), ', ') FROM card c WHERE c.vehicle_id = v.id) AS cards
                FROM vehicle v JOIN holder h ON h.id = v.holder_id LEFT JOIN card_group g ON g.id = v.group_id
                JOIN vehicle_type vt ON vt.code = v.type_code LEFT JOIN product p ON p.id = v.product_id
                LEFT JOIN vehicle_assignment a ON a.vehicle_id = v.id AND a.ended_at IS NULL
                LEFT JOIN driver d ON d.id = a.driver_id
                WHERE (CAST(:h AS bigint) IS NULL OR v.holder_id = :h) AND (CAST(:g AS bigint) IS NULL OR v.group_id = :g)
                  AND (CAST(:q AS text) IS NULL OR v.plate ILIKE '%' || :q || '%' OR concat_ws(' ', v.brand, v.model) ILIKE '%' || :q || '%')
                  AND (CAST(:all AS boolean) OR v.active)
                ORDER BY v.active DESC, v.plate LIMIT 500""",
                Db.p("h", holderId, "g", groupId, "q", blank(q), "all", Boolean.TRUE.equals(all)));
    }

    public record VehicleForm(@NotNull Long holderId, Long groupId, @NotBlank String plate, String typeCode, String brand,
                              String model, String color, String productCode, @Positive BigDecimal tankCapacity,
                              @Min(0) Integer odometerKm, Boolean active, String note) {}

    @PostMapping("/api/vehicles")
    @PreAuthorize("hasAuthority('FLOTTES')")
    @Transactional
    public Map<String, Object> createVehicle(@Valid @RequestBody VehicleForm f) {
        checkOwner(f.holderId(), f.groupId());
        String plate = plate(f.plate());
        if (db.count("SELECT count(*) FROM vehicle WHERE plate = :p", Db.p("p", plate)) > 0) {
            throw ApiException.conflict("Un véhicule immatriculé " + plate + " existe déjà");
        }
        long id = db.insert("""
                \sINSERT INTO vehicle (holder_id, group_id, plate, type_code, brand, model, color, product_id, tank_capacity, odometer_km,
                                     note, created_by)
                VALUES (:h, :g, :p, COALESCE(:t, 'AUTRE'), :b, :m, :c, (SELECT id FROM product WHERE code = :pc), :tc, :km, :n, :u)""",
                vehicleParams(f).with("u", Auth.user().id()).map());
        journal.log("VEHICULE_CREE", Journal.MODIFICATIONS, "Véhicule ajouté", plate, null, null, null);
        return vehicle(id);
    }

    @PutMapping("/api/vehicles/{id}")
    @PreAuthorize("hasAuthority('FLOTTES')")
    @Transactional
    public Map<String, Object> updateVehicle(@PathVariable long id, @Valid @RequestBody VehicleForm f) {
        checkOwner(f.holderId(), f.groupId());
        String plate = plate(f.plate());
        if (db.count("SELECT count(*) FROM vehicle WHERE plate = :p AND id <> :id", Db.p("p", plate, "id", id)) > 0) {
            throw ApiException.conflict("Un véhicule immatriculé " + plate + " existe déjà");
        }
        db.update("""
                \sUPDATE vehicle SET group_id = :g, plate = :p, type_code = COALESCE(:t, type_code), brand = :b, model = :m, color = :c,
                       product_id = (SELECT id FROM product WHERE code = :pc), tank_capacity = :tc, odometer_km = COALESCE(:km, odometer_km),
                       note = :n, active = COALESCE(:ac, active)
                WHERE id = :id AND holder_id = :h""", vehicleParams(f).with("id", id).with("ac", f.active()).map());
        db.update("UPDATE card SET vehicle_plate = :p WHERE vehicle_id = :id", Db.p("id", id, "p", plate));
        if (Boolean.FALSE.equals(f.active())) {
            db.update("UPDATE vehicle_assignment SET ended_at = now() WHERE vehicle_id = :id AND ended_at IS NULL", Db.p("id", id));
        }
        journal.log("VEHICULE_MODIFIE", Journal.MODIFICATIONS, "Véhicule modifié", plate, null, null, null);
        return vehicle(id);
    }

    public record DriverAssignment(Long driverId, String note) {}

    /** Affecte un chauffeur au véhicule (driverId nul : véhicule sans chauffeur). */
    @PostMapping("/api/vehicles/{id}/assign")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> assignDriver(@PathVariable long id, @RequestBody DriverAssignment f) {
        fleets.assignDriver(id, f.driverId(), blank(f.note()), Auth.user().id());
        return vehicle(id);
    }

    /** Historique des affectations du véhicule. */
    @GetMapping("/api/vehicles/{id}/assignments")
    @PreAuthorize(READ)
    public List<Map<String, Object>> assignments(@PathVariable long id) {
        return db.list("""
                \sSELECT a.id, a.started_at, a.ended_at, d.name AS driver_name, a.note, e.first_name || ' ' || e.last_name AS assigned_by_name
                FROM vehicle_assignment a JOIN driver d ON d.id = a.driver_id LEFT JOIN employee e ON e.id = a.assigned_by
                WHERE a.vehicle_id = :id ORDER BY a.started_at DESC""", Db.p("id", id));
    }

    private Map<String, Object> vehicle(long id) {
        return vehicles(null, null, null, true).stream().filter(v -> ((Number) v.get("id")).longValue() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Véhicule introuvable"));
    }

    private static Params vehicleParams(VehicleForm f) {
        return new Params(Db.p("h", f.holderId(), "g", f.groupId(), "p", plate(f.plate()), "t", blank(f.typeCode()), "b", blank(f.brand()),
                "m", blank(f.model()), "c", blank(f.color()), "pc", blank(f.productCode()), "tc", f.tankCapacity(), "km", f.odometerKm(),
                "n", blank(f.note())));
    }

    static String plate(String p) {
        String s = p == null ? "" : p.trim().toUpperCase().replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            throw new ApiException("Immatriculation requise");
        }
        return s;
    }

    // ------------------------------------------------------------------ chauffeurs

    @GetMapping("/api/drivers")
    @PreAuthorize(READ)
    public List<Map<String, Object>> drivers(@RequestParam(required = false) Long holderId, @RequestParam(required = false) Long groupId,
                                             @RequestParam(required = false) String q, @RequestParam(required = false) Boolean all) {
        return db.list("""
                \sSELECT d.id, d.holder_id, h.name AS holder_name, d.group_id, g.name AS group_name, d.name, d.phone, d.licence_number,
                       d.licence_expires, d.id_doc_number, d.active, d.note, a.vehicle_id, v.plate,
                       (d.licence_expires IS NOT NULL AND d.licence_expires < current_date + 30) AS licence_expiring,
                       (SELECT string_agg(substr(c.number, 9), ', ') FROM card c WHERE c.driver_id = d.id) AS cards
                FROM driver d JOIN holder h ON h.id = d.holder_id LEFT JOIN card_group g ON g.id = d.group_id
                LEFT JOIN vehicle_assignment a ON a.driver_id = d.id AND a.ended_at IS NULL LEFT JOIN vehicle v ON v.id = a.vehicle_id
                WHERE (CAST(:h AS bigint) IS NULL OR d.holder_id = :h) AND (CAST(:g AS bigint) IS NULL OR d.group_id = :g)
                  AND (CAST(:q AS text) IS NULL OR lower(d.name) LIKE '%' || lower(:q) || '%' OR d.phone LIKE '%' || :q || '%')
                  AND (CAST(:all AS boolean) OR d.active)
                ORDER BY d.active DESC, d.name LIMIT 500""",
                Db.p("h", holderId, "g", groupId, "q", blank(q), "all", Boolean.TRUE.equals(all)));
    }

    public record DriverForm(@NotNull Long holderId, Long groupId, @NotBlank String name, String phone, String licenceNumber,
                             LocalDate licenceExpires, String idDocNumber, Boolean active, String note) {}

    @PostMapping("/api/drivers")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> createDriver(@Valid @RequestBody DriverForm f) {
        checkOwner(f.holderId(), f.groupId());
        long id = db.insert("""
                \sINSERT INTO driver (holder_id, group_id, name, phone, licence_number, licence_expires, id_doc_number, note, created_by)
                VALUES (:h, :g, :n, :ph, :ln, :le, :id, :note, :u)""", driverParams(f).with("u", Auth.user().id()).map());
        journal.log("CHAUFFEUR_CREE", Journal.MODIFICATIONS, "Chauffeur ajouté", f.name().trim(), null, null, null);
        return driver(id);
    }

    @PutMapping("/api/drivers/{id}")
    @PreAuthorize("hasAuthority('FLOTTES')")
    @Transactional
    public Map<String, Object> updateDriver(@PathVariable long id, @Valid @RequestBody DriverForm f) {
        checkOwner(f.holderId(), f.groupId());
        db.update("""
                \sUPDATE driver SET group_id = :g, name = :n, phone = :ph, licence_number = :ln, licence_expires = :le, id_doc_number = :id,
                       note = :note, active = COALESCE(:ac, active)
                WHERE id = :did AND holder_id = :h""", driverParams(f).with("did", id).with("ac", f.active()).map());
        db.update("UPDATE card SET driver_name = :n WHERE driver_id = :id", Db.p("id", id, "n", f.name().trim()));
        if (Boolean.FALSE.equals(f.active())) {
            db.update("UPDATE vehicle_assignment SET ended_at = now() WHERE driver_id = :id AND ended_at IS NULL", Db.p("id", id));
        }
        journal.log("CHAUFFEUR_MODIFIE", Journal.MODIFICATIONS, "Chauffeur modifié", f.name().trim(), null, null, null);
        return driver(id);
    }

    private Map<String, Object> driver(long id) {
        return drivers(null, null, null, true).stream().filter(d -> ((Number) d.get("id")).longValue() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Chauffeur introuvable"));
    }

    private static Params driverParams(DriverForm f) {
        return new Params(Db.p("h", f.holderId(), "g", f.groupId(), "n", f.name().trim(), "ph", blank(f.phone()),
                "ln", blank(f.licenceNumber()), "le", f.licenceExpires(), "id", blank(f.idDocNumber()), "note", blank(f.note())));
    }

    // ------------------------------------------------------------------ outils

    /** Le titulaire doit être une entreprise, et la flotte (facultative) la sienne. */
    private void checkOwner(Long holderId, Long groupId) {
        String kind = db.value("SELECT kind FROM holder WHERE id = :h", Db.p("h", holderId), String.class);
        if (!"ENTREPRISE".equals(kind)) {
            throw new ApiException("Véhicules et chauffeurs sont rattachés à une entreprise cliente");
        }
        if (groupId != null && db.count("SELECT count(*) FROM card_group WHERE id = :g AND holder_id = :h", Db.p("g", groupId, "h", holderId)) == 0) {
            throw new ApiException("Cette flotte n'appartient pas à l'entreprise");
        }
    }

    /** Paramètres nommés complétés à la volée. */
    private record Params(Map<String, Object> base) {
        Params with(String k, Object v) {
            java.util.Map<String, Object> m = new java.util.HashMap<>(base);
            m.put(k, v);
            return new Params(m);
        }

        Map<String, Object> map() {
            return base;
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
