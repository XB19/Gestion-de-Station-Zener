package tg.zener.station.extractions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/**
 * Module Gestionnaire : extractions de données à l'écran (pages + totaux) et en fichiers CSV / XLSX.
 * Réservé au droit EXTRACTIONS ; un utilisateur rattaché à une station ne voit que sa station.
 * Chaque téléchargement est inscrit au journal (qui, quoi, quels filtres, combien de lignes).
 */
@RestController
@RequestMapping("/api/extractions")
@PreAuthorize("hasAuthority('EXTRACTIONS')")
public class ExtractionController {

    private final ExtractionCatalog catalog;
    private final ExtractionService service;
    private final Journal journal;
    private final Db db;
    private final CardService cards;

    public ExtractionController(ExtractionCatalog catalog, ExtractionService service, Journal journal, Db db, CardService cards) {
        this.catalog = catalog;
        this.service = service;
        this.journal = journal;
        this.db = db;
        this.cards = cards;
    }

    /** Catalogue : rubriques, extractions, colonnes et filtres acceptés. */
    @GetMapping
    public Map<String, Object> list() {
        boolean scoped = Auth.user().scope() != null;
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Map.Entry<String, String> s : ExtractionCatalog.SECTIONS.entrySet()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Extraction e : catalog.list()) {
                if (!e.section().equals(s.getKey()) || (scoped && !e.stationScoped())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", e.code());
                m.put("label", e.label());
                m.put("description", e.description());
                m.put("filters", new ArrayList<>(e.filters().keySet()));
                m.put("columns", ExtractionService.columns(e));
                items.add(m);
            }
            if (!items.isEmpty()) {
                sections.add(Map.of("code", s.getKey(), "label", s.getValue(), "items", items));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", sections);
        out.put("scopedStationId", Auth.user().scope());
        return out;
    }

    /** Résultat paginé, avec les totaux des colonnes chiffrées sur l'ensemble des lignes filtrées. */
    @GetMapping("/{code}")
    public Map<String, Object> page(@PathVariable String code, @RequestParam Map<String, String> q) {
        Extraction e = catalog.get(code);
        ExtractionFilters f = scoped(e, ExtractionFilters.parse(q));
        return service.page(e, f, intParam(q, "page"), intParam(q, "size"));
    }

    /** Fichier CSV (UTF-8 avec BOM, séparateur « ; ») ou XLSX, écrit en flux. */
    @GetMapping("/{code}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String code, @RequestParam Map<String, String> q) {
        Extraction e = catalog.get(code);
        ExtractionFilters f = scoped(e, ExtractionFilters.parse(q));
        String format = q.getOrDefault("format", "csv").toLowerCase();
        if (!format.equals("csv") && !format.equals("xlsx")) {
            throw new ApiException("Format accepté : csv ou xlsx");
        }
        List<String> ignored = service.unsupported(e, f);
        if (!ignored.isEmpty()) {
            throw new ApiException("Filtre non applicable à cette extraction : " + String.join(", ", ignored));
        }
        long rows = service.count(e, f);
        if (format.equals("xlsx") && rows > ExtractionService.XLSX_MAX_ROWS) {
            throw new ApiException(String.format("%,d lignes : trop pour un fichier Excel, utilisez le format CSV ou réduisez la période", rows));
        }
        CurrentUser u = Auth.user();
        List<String[]> description = service.describe(e, f, u.name());
        String filters = description.stream().skip(3).map(d -> d[0] + " : " + d[1]).collect(Collectors.joining(" · "));
        journal.log("EXTRACTION", Journal.EXTRACTIONS, "Extraction · " + e.label(),
                format.toUpperCase() + " · " + rows + " ligne(s)" + (filters.isEmpty() ? " · sans filtre" : " · " + filters),
                null, f.stationId(), f.pdvId());

        String file = "zener-" + e.code() + "-" + LocalDate.now() + "." + format;
        StreamingResponseBody body = format.equals("csv")
                ? os -> service.writeCsv(e, f, os)
                : os -> service.writeXlsx(e, f, description, os);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file, StandardCharsets.UTF_8).build().toString())
                .contentType(format.equals("csv") ? new MediaType("text", "csv", StandardCharsets.UTF_8)
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    // ------------------------------------------------------------------ listes des filtres

    /** Cartes par numéro complet, 5 derniers chiffres ou fragment : liste de choix si plusieurs correspondent. */
    @GetMapping("/cards")
    public List<Map<String, Object>> cards(@RequestParam String q) {
        return cards.search(q, 30);
    }

    @GetMapping("/holders")
    public List<Map<String, Object>> holders(@RequestParam(required = false) String q) {
        return db.list("""
                \sSELECT h.id, h.name, h.kind, (SELECT count(*) FROM card c WHERE c.holder_id = h.id) AS cards
                FROM holder h
                WHERE (CAST(:q AS text) IS NULL OR lower(h.name) LIKE '%' || lower(:q) || '%')
                ORDER BY (h.kind = 'EMPLOYE'), h.name LIMIT 30""", Db.p("q", blank(q)));
    }

    /** Stations, points de vente, produits et groupes de cartes. */
    @GetMapping("/options")
    public Map<String, Object> options() {
        Long scope = Auth.user().scope();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stations", db.list("""
                \sSELECT id, short_name AS name FROM station
                WHERE (CAST(:s AS bigint) IS NULL OR id = :s) ORDER BY short_name""", Db.p("s", scope)));
        out.put("pdvs", scope != null ? List.of() : db.list("SELECT id, name FROM pdv ORDER BY name"));
        out.put("products", db.list("""
                \sSELECT id, name, category, unit FROM product WHERE category <> 'CARTE'
                ORDER BY CASE category WHEN 'CARBURANT' THEN 1 WHEN 'GAZ' THEN 2 WHEN 'LUBRIFIANT' THEN 3 ELSE 4 END, name"""));
        out.put("vehicles", scope != null ? List.of() : db.list("""
                \sSELECT v.id, v.plate, v.holder_id, v.group_id, NULLIF(concat_ws(' ', v.brand, v.model), '') AS label
                FROM vehicle v ORDER BY v.plate"""));
        out.put("drivers", scope != null ? List.of() : db.list("""
                \sSELECT d.id, d.name, d.holder_id, d.group_id FROM driver d ORDER BY d.name"""));
        out.put("groups", db.list("""
                \sSELECT g.id, g.name, g.holder_id, h.name AS holder_name
                FROM card_group g JOIN holder h ON h.id = g.holder_id ORDER BY h.name, g.name"""));
        return out;
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(@RequestParam(required = false) String q) {
        return db.list("""
                \sSELECT e.id, e.first_name || ' ' || e.last_name AS name, r.label AS role_label, s.short_name AS station_name
                FROM employee e JOIN role r ON r.code = e.role_code LEFT JOIN station s ON s.id = e.station_id
                WHERE (CAST(:q AS text) IS NULL OR lower(e.first_name || ' ' || e.last_name) LIKE '%' || lower(:q) || '%')
                  AND (CAST(:s AS bigint) IS NULL OR e.station_id = :s)
                ORDER BY e.last_name, e.first_name LIMIT 30""", Db.p("q", blank(q), "s", Auth.user().scope()));
    }

    /** Postes d'un opérateur (filtre « poste »), les plus récents d'abord. */
    @GetMapping("/shifts")
    public List<Map<String, Object>> shifts(@RequestParam long employeeId, @RequestParam Map<String, String> q) {
        ExtractionFilters f = ExtractionFilters.parse(q);
        return db.list("""
                \sSELECT es.id, COALESCE(sc.name, 'Poste') || ' du ' || to_char(es.started_at, 'DD/MM/YYYY HH24:MI') AS label,
                       s.short_name AS station_name, es.status
                FROM employee_shift es JOIN station s ON s.id = es.station_id
                LEFT JOIN shift_config sc ON sc.id = es.config_id
                WHERE es.employee_id = :e
                  AND (CAST(:from AS timestamptz) IS NULL OR es.started_at >= :from)
                  AND (CAST(:to AS timestamptz) IS NULL OR es.started_at < :to)
                  AND (CAST(:s AS bigint) IS NULL OR es.station_id = :s)
                ORDER BY es.started_at DESC LIMIT 100""",
                Db.p("e", employeeId, "from", f.from(), "to", f.to() == null ? null : f.to().plusDays(1),
                        "s", Auth.user().scope()));
    }

    // ------------------------------------------------------------------ outils

    /** Utilisateur rattaché à une station : extraction limitée à sa station (refusée si elle ne s'y prête pas). */
    private static ExtractionFilters scoped(Extraction e, ExtractionFilters f) {
        Long scope = Auth.user().scope();
        if (scope == null) {
            return f;
        }
        if (!e.stationScoped()) {
            throw ApiException.forbidden("Cette extraction couvre tout le réseau : elle est réservée au siège");
        }
        return f.withStation(Auth.scopedStation(f.stationId()));
    }

    private static Integer intParam(Map<String, String> q, String key) {
        String v = q.get(key);
        try {
            return v == null || v.isBlank() ? null : Integer.valueOf(v.trim());
        } catch (NumberFormatException ex) {
            throw new ApiException("Valeur invalide pour " + key);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
