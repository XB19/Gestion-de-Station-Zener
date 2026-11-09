package tg.zener.station.clients;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;

/**
 * Clients (groupe Zener, gérants libres) et licence annuelle (écran 14).
 * Une licence couvre toutes les stations du client ; son statut affiché est
 * Active, Expire bientôt (moins de 30 jours), Expirée ou Résiliée.
 */
@RestController
@RequestMapping("/api/clients")
@PreAuthorize("hasAuthority('CLIENTS_LICENCES')")
public class ClientController {

    /** Licence en cours (la plus récente non remplacée) de chaque client. */
    static final String CURRENT_LICENCE = """
            \sLEFT JOIN LATERAL (
                SELECT l.* FROM licence l WHERE l.client_id = c.id AND l.status <> 'REMPLACEE'
                ORDER BY l.end_date DESC LIMIT 1) l ON true""";

    static final String LICENCE_STATUS = """
            \sCASE WHEN l.id IS NULL THEN 'Aucune'
                 WHEN l.status = 'RESILIEE' THEN 'Résiliée'
                 WHEN l.end_date < current_date THEN 'Expirée'
                 WHEN l.end_date < current_date + 30 THEN 'Expire bientôt'
                 ELSE 'Active' END""";

    private final Db db;
    private final Journal journal;

    public ClientController(Db db, Journal journal) {
        this.db = db;
        this.journal = journal;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String q) {
        return db.list("""
                \sSELECT c.id, c.name, c.kind, c.country, c.contact_name, c.phone, c.email,
                       (SELECT count(*) FROM station s WHERE s.client_id = c.id AND s.active) AS stations,
                       l.id AS licence_id, l.start_date, l.end_date, l.amount_per_station,
                       """ + LICENCE_STATUS + """
                        \sAS licence_status,
                       CASE WHEN l.status = 'ACTIVE' THEN greatest(l.end_date - current_date, 0) ELSE 0 END AS days_left
                FROM client c
                """ + CURRENT_LICENCE + """
                \sWHERE CAST(:q AS text) IS NULL OR c.name ILIKE '%' || :q || '%'
                ORDER BY c.kind, c.name""", Db.p("q", q == null || q.isBlank() ? null : q.trim()));
    }

    /** Indicateurs de l'en-tête : clients, licences actives, à renouveler sous 30 j, résiliées. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return db.one("""
                \sSELECT count(*) AS clients,
                       count(*) FILTER (WHERE c.kind = 'GROUPE') AS groups,
                       count(*) FILTER (WHERE c.kind = 'GERANT') AS managers,
                       (SELECT count(*) FROM station s JOIN client c2 ON c2.id = s.client_id
                        JOIN licence l2 ON l2.client_id = c2.id AND l2.status = 'ACTIVE' AND l2.end_date >= current_date
                        WHERE s.active) AS stations_licensed,
                       (SELECT count(*) FROM station WHERE active) AS stations_total,
                       count(*) FILTER (WHERE l.status = 'ACTIVE' AND l.end_date >= current_date
                                        AND l.end_date < current_date + 30) AS to_renew,
                       count(*) FILTER (WHERE l.status = 'RESILIEE') AS terminated
                FROM client c
                """ + CURRENT_LICENCE, Map.of());
    }

    /** Fiche licence : stations couvertes, montant annuel, rappels. */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> c = db.require("""
                \sSELECT c.*, l.id AS licence_id, l.start_date, l.end_date, l.amount_per_station, l.status AS licence_state,
                       """ + LICENCE_STATUS + """
                        \sAS licence_status,
                       CASE WHEN l.status = 'ACTIVE' THEN greatest(l.end_date - current_date, 0) ELSE 0 END AS days_left
                FROM client c
                """ + CURRENT_LICENCE + " WHERE c.id = :id", Db.p("id", id), "Client introuvable");
        List<Map<String, Object>> stations = db.list(
                "SELECT id, name, short_name FROM station WHERE client_id = :id AND active ORDER BY name", Db.p("id", id));
        c.put("stations", stations);
        Object perStation = c.get("amountPerStation");
        c.put("annualAmount", perStation == null ? 0 : ((Number) perStation).longValue() * stations.size());
        c.put("reminderDays", db.value("SELECT value FROM setting WHERE key = 'licence.reminder_days'", Map.of(), String.class));
        c.put("licences", db.list("""
                \sSELECT id, start_date, end_date, amount_per_station, status, terminated_at, created_at
                FROM licence WHERE client_id = :id ORDER BY start_date DESC""", Db.p("id", id)));
        return c;
    }

    public record ClientForm(@NotBlank String name, @NotBlank @Pattern(regexp = "GROUPE|GERANT") String kind,
                             String country, String contactName, String phone, String email,
                             Long amountPerStation, LocalDate licenceStart) {}

    @PostMapping
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody ClientForm f) {
        long id = db.insert("""
                \sINSERT INTO client (name, kind, country, contact_name, phone, email)
                VALUES (:n, :k, COALESCE(:c, 'Togo'), :cn, :p, :e)""",
                Db.p("n", f.name().trim(), "k", f.kind(), "c", f.country(), "cn", f.contactName(), "p", f.phone(), "e", f.email()));
        LocalDate start = f.licenceStart() == null ? LocalDate.now() : f.licenceStart();
        db.update("""
                \sINSERT INTO licence (client_id, start_date, end_date, amount_per_station)
                VALUES (:c, :s, :e, :a)""",
                Db.p("c", id, "s", start, "e", start.plusYears(1), "a", f.amountPerStation() == null ? 0 : f.amountPerStation()));
        journal.log("CLIENT_CREE", Journal.MODIFICATIONS, "Client créé", f.name(), null, null, null);
        return get(id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody ClientForm f) {
        int n = db.update("""
                \sUPDATE client SET name = :n, kind = :k, country = COALESCE(:c, country), contact_name = :cn,
                                  phone = :p, email = :e WHERE id = :id""",
                Db.p("id", id, "n", f.name().trim(), "k", f.kind(), "c", f.country(), "cn", f.contactName(),
                        "p", f.phone(), "e", f.email()));
        if (n == 0) {
            throw ApiException.notFound("Client introuvable");
        }
        if (f.amountPerStation() != null) {
            db.update("""
                    \sUPDATE licence SET amount_per_station = :a
                    WHERE client_id = :id AND status = 'ACTIVE'""", Db.p("id", id, "a", f.amountPerStation()));
        }
        return get(id);
    }

    public record Renewal(Integer years, Long amountPerStation) {}

    /**
     * Renouvellement : nouvelle période qui commence à la fin de la licence en cours
     * (ou aujourd'hui si elle est expirée ou résiliée).
     */
    @PostMapping("/{id}/licence/renew")
    @Transactional
    public Map<String, Object> renew(@PathVariable long id, @RequestBody(required = false) Renewal r) {
        Map<String, Object> c = get(id);
        int years = r == null || r.years() == null ? 1 : Math.max(1, Math.min(r.years(), 5));
        LocalDate end = (LocalDate) c.get("endDate");
        boolean running = "ACTIVE".equals(c.get("licenceState")) && end != null && !end.isBefore(LocalDate.now());
        LocalDate start = running ? end : LocalDate.now();
        Object amount = r != null && r.amountPerStation() != null ? r.amountPerStation() : c.get("amountPerStation");
        db.update("UPDATE licence SET status = 'REMPLACEE' WHERE client_id = :id AND status = 'ACTIVE'", Db.p("id", id));
        db.update("""
                \sINSERT INTO licence (client_id, start_date, end_date, amount_per_station)
                VALUES (:c, :s, :e, COALESCE(:a, 0))""",
                Db.p("c", id, "s", start, "e", start.plusYears(years), "a", amount));
        journal.log("LICENCE_RENOUVELEE", Journal.MODIFICATIONS, "Licence renouvelée",
                c.get("name") + " · jusqu'au " + start.plusYears(years), null, null, null);
        return get(id);
    }

    @PostMapping("/{id}/licence/terminate")
    @Transactional
    public Map<String, Object> terminate(@PathVariable long id) {
        Map<String, Object> c = get(id);
        int n = db.update("""
                \sUPDATE licence SET status = 'RESILIEE', terminated_at = now()
                WHERE client_id = :id AND status = 'ACTIVE'""", Db.p("id", id));
        if (n == 0) {
            throw new ApiException("Ce client n'a pas de licence active");
        }
        journal.log("LICENCE_RESILIEE", Journal.MODIFICATIONS, "Licence résiliée", (String) c.get("name"), null, null, null);
        return get(id);
    }

    /** Réactivation après résiliation : nouvelle licence d'un an à partir d'aujourd'hui. */
    @PostMapping("/{id}/licence/reactivate")
    @Transactional
    public Map<String, Object> reactivate(@PathVariable long id) {
        Map<String, Object> c = get(id);
        if (!"Résiliée".equals(c.get("licenceStatus")) && !"Expirée".equals(c.get("licenceStatus"))) {
            throw new ApiException("La licence de ce client est encore active");
        }
        return renew(id, new Renewal(1, null));
    }
}
