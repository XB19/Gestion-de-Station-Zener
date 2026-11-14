package tg.zener.station.fleets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
import tg.zener.station.cards.CardEvents;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;

/**
 * Flottes (version simple) : une flotte est un groupe de cartes d'une entreprise (card_group). On crée la flotte,
 * on y attribue des cartes de l'entreprise ou on les en retire ; la flotte sert ensuite de filtre (cartes,
 * extractions). Pas de compte flotte, de véhicules ni de chauffeurs pour l'instant.
 */
@RestController
public class FleetController {

    private static final String READ = "hasAuthority('FLOTTES')";

    private final Db db;
    private final CardEvents events;
    private final Journal journal;

    public FleetController(Db db, CardEvents events, Journal journal) {
        this.db = db;
        this.events = events;
        this.journal = journal;
    }

    /** Flottes avec leur entreprise, le nombre de cartes, le solde des cartes et la consommation sur 30 jours. */
    @GetMapping("/api/fleets")
    @PreAuthorize(READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) Long holderId) {
        return db.list("""
                \sSELECT g.id, g.name, g.holder_id, h.name AS holder_name, g.contact_name, g.phone, g.active,
                       count(c.id) AS cards, count(c.id) FILTER (WHERE c.status = 'ACTIVE') AS active_cards,
                       COALESCE(sum(c.balance), 0) AS balance,
                       (SELECT COALESCE(sum(x.total), 0) FROM sale x JOIN card c2 ON c2.id = x.card_id
                        WHERE c2.group_id = g.id AND x.at >= now() - interval '30 days') AS spent_30d
                FROM card_group g JOIN holder h ON h.id = g.holder_id LEFT JOIN card c ON c.group_id = g.id
                WHERE CAST(:h AS bigint) IS NULL OR g.holder_id = :h
                GROUP BY g.id, h.name ORDER BY g.active DESC, h.name, g.name""", Db.p("h", holderId));
    }

    @GetMapping("/api/fleets/{id}")
    @PreAuthorize(READ)
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> g = db.require("""
                \sSELECT g.id, g.name, g.holder_id, h.name AS holder_name, h.phone AS holder_phone, g.contact_name, g.phone, g.note,
                       g.active, g.created_at
                FROM card_group g JOIN holder h ON h.id = g.holder_id WHERE g.id = :id""", Db.p("id", id), "Flotte introuvable");
        g.put("cards", decorate(db.list(CARDS + " WHERE c.group_id = :id ORDER BY (c.status = 'INACTIVE'), c.number", Db.p("id", id))));
        return g;
    }

    /** Cartes de l'entreprise qui ne sont pas dans cette flotte (à attribuer). */
    @GetMapping("/api/fleets/{id}/available-cards")
    @PreAuthorize(READ)
    public List<Map<String, Object>> available(@PathVariable long id) {
        Map<String, Object> g = fleet(id);
        return decorate(db.list(CARDS + """
                 \sWHERE c.holder_id = :h AND c.group_id IS DISTINCT FROM :id AND c.status IN ('ACTIVE', 'A_ACTIVER', 'BLOQUEE')
                ORDER BY c.number""", Db.p("h", g.get("holderId"), "id", id)));
    }

    private static final String CARDS = """
            \sSELECT c.id, c.number, c.status, c.balance, c.type_code, c.vehicle_plate, c.vehicle_label, c.driver_name,
                   o.name AS current_group, (SELECT max(x.at) FROM sale x WHERE x.card_id = c.id) AS last_sale_at,
                   (SELECT COALESCE(sum(x.total), 0) FROM sale x WHERE x.card_id = c.id AND x.at >= now() - interval '30 days') AS spent_30d,
                   CASE c.status WHEN 'ACTIVE' THEN 'Active' WHEN 'BLOQUEE' THEN 'Bloquée' WHEN 'INACTIVE' THEN 'Inactive'
                                 WHEN 'A_ACTIVER' THEN 'À activer' ELSE 'En stock' END AS status_label
            FROM card c LEFT JOIN card_group o ON o.id = c.group_id""";

    private static List<Map<String, Object>> decorate(List<Map<String, Object>> cards) {
        cards.forEach(c -> c.put("numberFormatted", CardService.format((String) c.get("number"))));
        return cards;
    }

    private Map<String, Object> fleet(long id) {
        return db.require("""
                \sSELECT g.id, g.name, g.holder_id, h.name AS holder_name FROM card_group g JOIN holder h ON h.id = g.holder_id
                WHERE g.id = :id""", Db.p("id", id), "Flotte introuvable");
    }

    // ------------------------------------------------------------------ création, modification

    public record FleetForm(@NotNull Long holderId, @NotBlank String name, String contactName, String phone, String note,
                            Boolean active) {}

    @PostMapping("/api/fleets")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> create(@Valid @RequestBody FleetForm f) {
        Map<String, Object> h = db.require("SELECT name, kind FROM holder WHERE id = :id", Db.p("id", f.holderId()),
                "Entreprise introuvable");
        if (!"ENTREPRISE".equals(h.get("kind"))) {
            throw new ApiException("Une flotte appartient à une entreprise");
        }
        if (db.count("SELECT count(*) FROM card_group WHERE holder_id = :h AND lower(name) = lower(:n)",
                Db.p("h", f.holderId(), "n", f.name().trim())) > 0) {
            throw ApiException.conflict("Cette entreprise a déjà une flotte de ce nom");
        }
        long id = db.insert("""
                \sINSERT INTO card_group (holder_id, name, contact_name, phone, note) VALUES (:h, :n, :c, :p, :note)""",
                Db.p("h", f.holderId(), "n", f.name().trim(), "c", blank(f.contactName()), "p", blank(f.phone()), "note", blank(f.note())));
        journal.log("FLOTTE", Journal.MODIFICATIONS, "Flotte créée", h.get("name") + " · " + f.name().trim(), null, null, null);
        return get(id);
    }

    @PutMapping("/api/fleets/{id}")
    @PreAuthorize("hasAuthority('FLOTTES')")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody FleetForm f) {
        Map<String, Object> g = fleet(id);
        if (db.count("SELECT count(*) FROM card_group WHERE holder_id = :h AND lower(name) = lower(:n) AND id <> :id",
                Db.p("h", g.get("holderId"), "n", f.name().trim(), "id", id)) > 0) {
            throw ApiException.conflict("Cette entreprise a déjà une flotte de ce nom");
        }
        db.update("""
                \sUPDATE card_group SET name = :n, contact_name = :c, phone = :p, note = :note, active = COALESCE(:a, active)
                WHERE id = :id""",
                Db.p("id", id, "n", f.name().trim(), "c", blank(f.contactName()), "p", blank(f.phone()), "note", blank(f.note()),
                        "a", f.active()));
        journal.log("FLOTTE", Journal.MODIFICATIONS, "Flotte modifiée", g.get("holderName") + " · " + f.name().trim(), null, null, null);
        return get(id);
    }

    // ------------------------------------------------------------------ attribution des cartes

    public record CardsForm(@NotEmpty List<Long> cardIds) {}

    /** Attribue des cartes de l'entreprise à la flotte (une carte n'appartient qu'à une flotte à la fois). */
    @PostMapping("/api/fleets/{id}/cards")
    @PreAuthorize("hasAuthority('FLOTTES')")
    @Transactional
    public Map<String, Object> attach(@PathVariable long id, @Valid @RequestBody CardsForm f) {
        Map<String, Object> g = fleet(id);
        List<Map<String, Object>> cards = db.list("""
                \sSELECT c.id, c.number, c.holder_id, o.name AS previous FROM card c LEFT JOIN card_group o ON o.id = c.group_id
                WHERE c.id = ANY(:ids) FOR UPDATE OF c""", Db.p("ids", db.array("bigint", f.cardIds().toArray())));
        if (cards.size() != f.cardIds().size()) {
            throw ApiException.notFound("Carte introuvable");
        }
        for (Map<String, Object> c : cards) {
            if (!g.get("holderId").equals(c.get("holderId"))) {
                throw new ApiException("La carte " + CardService.mask((String) c.get("number")) + " n'appartient pas à " + g.get("holderName"));
            }
        }
        db.update("UPDATE card SET group_id = :g WHERE id = ANY(:ids)", Db.p("g", id, "ids", db.array("bigint", f.cardIds().toArray())));
        for (Map<String, Object> c : cards) {
            events.record(((Number) c.get("id")).longValue(), CardEvents.AFFECTATION,
                    "Flotte " + g.get("name") + (c.get("previous") == null ? "" : " (avant : " + c.get("previous") + ")"),
                    Auth.user().stationId(), null);
        }
        journal.log("FLOTTE_CARTES", Journal.MODIFICATIONS, cards.size() + " carte(s) attribuée(s) à une flotte",
                g.get("holderName") + " · " + g.get("name"), null, null, null);
        return get(id);
    }

    /** Retire une carte de la flotte (elle reste à l'entreprise, sans flotte). */
    @PostMapping("/api/fleets/{id}/cards/{cardId}/remove")
    @PreAuthorize("hasAuthority('FLOTTES')")
    @Transactional
    public Map<String, Object> detach(@PathVariable long id, @PathVariable long cardId) {
        Map<String, Object> g = fleet(id);
        if (db.update("UPDATE card SET group_id = NULL WHERE id = :c AND group_id = :g", Db.p("c", cardId, "g", id)) == 0) {
            throw ApiException.notFound("Cette carte n'est pas dans la flotte");
        }
        events.record(cardId, CardEvents.AFFECTATION, "Retirée de la flotte " + g.get("name"), Auth.user().stationId(), null);
        return get(id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
