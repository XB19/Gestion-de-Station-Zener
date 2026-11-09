package tg.zener.station.cards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;

/** Titulaires de cartes (particuliers, entreprises) et groupes de cartes des organisations. */
@RestController
public class HolderController {

    private final Db db;
    private final CardController cards;
    private final Journal journal;

    public HolderController(Db db, CardController cards, Journal journal) {
        this.db = db;
        this.cards = cards;
        this.journal = journal;
    }

    @GetMapping("/api/holders")
    public List<Map<String, Object>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String kind) {
        return db.list("""
                \sSELECT h.id, h.kind, h.name, h.profile, h.id_doc_type, h.id_doc_number, h.phone, h.email, h.address,
                       count(c.id) AS cards, COALESCE(sum(c.balance), 0) AS balance
                FROM holder h LEFT JOIN card c ON c.holder_id = h.id
                WHERE (CAST(:q AS text) IS NULL OR h.name ILIKE '%' || :q || '%' OR h.phone LIKE '%' || :q || '%')
                  AND (CAST(:k AS text) IS NULL OR h.kind = :k)
                GROUP BY h.id ORDER BY h.name LIMIT 200""", Db.p("q", q == null || q.isBlank() ? null : q.trim(), "k", kind));
    }

    @GetMapping("/api/holders/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> h = db.require("SELECT * FROM holder WHERE id = :id", Db.p("id", id), "Titulaire introuvable");
        h.put("groups", db.list("SELECT id, name FROM card_group WHERE holder_id = :id ORDER BY name", Db.p("id", id)));
        h.put("cards", cards.list(null, "ALL", null, null, id, null, null, 0, 200).items());
        return h;
    }

    @PostMapping("/api/holders")
    @PreAuthorize("hasAuthority('CARTES_GERER') or hasAuthority('PDV_VENDRE') or hasAuthority('LIVRAISONS')")
    public Map<String, Object> create(@Valid @RequestBody CardController.HolderForm f) {
        long id = cards.createHolder(f);
        journal.log("TITULAIRE", Journal.MODIFICATIONS, "Titulaire créé", f.name(), null, null, null);
        return get(id);
    }

    @PutMapping("/api/holders/{id}")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody CardController.HolderForm f) {
        int n = db.update("""
                \sUPDATE holder SET name = :n, profile = COALESCE(:p, profile), id_doc_type = :dt, id_doc_number = :dn,
                       phone = :ph, email = :e, address = :a
                WHERE id = :id AND kind = :k""",
                Db.p("id", id, "k", f.kind(), "n", f.name().trim(), "p", f.profile(), "dt", f.idDocType(),
                        "dn", f.idDocNumber(), "ph", f.phone(), "e", f.email(), "a", f.address()));
        if (n == 0) {
            throw ApiException.notFound("Titulaire introuvable");
        }
        return get(id);
    }

    // ------------------------------------------------------------------ groupes de cartes

    @GetMapping("/api/card-groups")
    public List<Map<String, Object>> groups(@RequestParam(required = false) Long holderId) {
        return db.list("""
                \sSELECT g.id, g.name, g.holder_id, h.name AS holder_name, count(c.id) AS cards,
                       COALESCE(sum(c.balance), 0) AS balance
                FROM card_group g JOIN holder h ON h.id = g.holder_id LEFT JOIN card c ON c.group_id = g.id
                WHERE CAST(:h AS bigint) IS NULL OR g.holder_id = :h
                GROUP BY g.id, h.name ORDER BY h.name, g.name""", Db.p("h", holderId));
    }

    public record GroupForm(@NotNull Long holderId, @NotBlank String name) {}

    @PostMapping("/api/card-groups")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public List<Map<String, Object>> createGroup(@Valid @RequestBody GroupForm f) {
        Map<String, Object> h = db.require("SELECT name, kind FROM holder WHERE id = :id", Db.p("id", f.holderId()),
                "Titulaire introuvable");
        if (!"ENTREPRISE".equals(h.get("kind"))) {
            throw new ApiException("Les groupes de cartes sont réservés aux organisations");
        }
        db.update("INSERT INTO card_group (holder_id, name) VALUES (:h, :n)", Db.p("h", f.holderId(), "n", f.name().trim()));
        journal.log("GROUPE_CARTES", Journal.MODIFICATIONS, "Groupe de cartes créé", h.get("name") + " · " + f.name(),
                null, null, null);
        return groups(f.holderId());
    }

    @PutMapping("/api/card-groups/{id}")
    @PreAuthorize("hasAuthority('CARTES_GERER')")
    public List<Map<String, Object>> renameGroup(@PathVariable long id, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new ApiException("Nom requis");
        }
        Map<String, Object> g = db.require("SELECT holder_id FROM card_group WHERE id = :id", Db.p("id", id), "Groupe introuvable");
        db.update("UPDATE card_group SET name = :n WHERE id = :id", Db.p("id", id, "n", name.trim()));
        return groups(((Number) g.get("holderId")).longValue());
    }
}
