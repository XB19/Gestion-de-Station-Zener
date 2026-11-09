package tg.zener.station.employees;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import tg.zener.station.cards.CardIssuer;
import tg.zener.station.cards.CardService;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.common.Page;
import tg.zener.station.journal.Journal;
import tg.zener.station.security.Auth;
import tg.zener.station.security.CurrentUser;

/**
 * Employés (écran « Employés & rôles ») : fiches avec photo, création, désactivation,
 * carte employé et employé du mois. Un gérant ne gère que les employés de sa station.
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    /** Rôles qu'un gérant ne peut ni créer ni modifier. */
    static final Set<String> RESERVED_ROLES = Set.of("ADMIN", "DAF", "COMPTABLE");

    private final Db db;
    private final PasswordEncoder encoder;
    private final PhotoStore photos;
    private final CardIssuer issuer;
    private final Journal journal;

    public EmployeeController(Db db, PasswordEncoder encoder, PhotoStore photos, CardIssuer issuer, Journal journal) {
        this.db = db;
        this.encoder = encoder;
        this.photos = photos;
        this.issuer = issuer;
        this.journal = journal;
    }

    static final String SELECT = """
            \sSELECT e.id, e.matricule, e.first_name, e.last_name, e.first_name || ' ' || e.last_name AS name,
                   upper(left(e.first_name, 1) || left(e.last_name, 1)) AS initials,
                   e.email, e.phone, e.role_code AS role, r.label AS role_label, r.station_bound,
                   e.station_id, s.name AS station_name, s.short_name AS station_short_name,
                   CASE WHEN e.photo_path IS NULL THEN NULL ELSE '/files/' || e.photo_path END AS photo_url,
                   e.id_doc_type, e.id_doc_number, e.active, e.created_at, (e.password_hash IS NOT NULL) AS has_password,
                   (SELECT c.id FROM card c JOIN holder h ON h.id = c.holder_id
                    WHERE h.employee_id = e.id AND c.status IN ('A_ACTIVER', 'ACTIVE', 'BLOQUEE')
                    ORDER BY c.created_at DESC LIMIT 1) AS card_id,
                   (SELECT c.status FROM card c JOIN holder h ON h.id = c.holder_id
                    WHERE h.employee_id = e.id AND c.status IN ('A_ACTIVER', 'ACTIVE', 'BLOQUEE')
                    ORDER BY c.created_at DESC LIMIT 1) AS card_status
            FROM employee e JOIN role r ON r.code = e.role_code LEFT JOIN station s ON s.id = e.station_id""";

    // ------------------------------------------------------------------ consultation

    @GetMapping
    @PreAuthorize("hasAuthority('EMPLOYES') or hasAuthority('CARTES_EMPLOYES') or hasAuthority('PDV_GERER') or hasAuthority('LIVRAISONS')")
    public Page list(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
                     @RequestParam(required = false) Long stationId, @RequestParam(required = false) Boolean active,
                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Long station = Auth.scopedStation(stationId);
        String where = """
                 \sWHERE (CAST(:q AS text) IS NULL OR (e.first_name || ' ' || e.last_name || ' ' || e.matricule || ' '
                        || COALESCE(e.email, '')) ILIKE '%' || :q || '%')
                   AND (CAST(:r AS text) IS NULL OR e.role_code = :r)
                   AND (CAST(:s AS bigint) IS NULL OR e.station_id = :s)
                   AND (CAST(:a AS boolean) IS NULL OR e.active = :a)""";
        int sz = Page.size(size);
        int pg = Page.page(page);
        Map<String, Object> p = Db.p("q", blank(q), "r", blank(role), "s", station, "a", active == null ? Boolean.TRUE : active,
                "lim", sz, "off", pg * sz);
        List<Map<String, Object>> items = db.list(SELECT + where + " ORDER BY r.sort, e.last_name, e.first_name LIMIT :lim OFFSET :off", p);
        return new Page(items, db.count("SELECT count(*) FROM employee e" + where, p), pg, sz);
    }

    /** Indicateurs : nombre d'employés actifs, par rôle, nombre de stations couvertes. */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('EMPLOYES')")
    public Map<String, Object> summary() {
        Long station = Auth.scopedStation(null);
        Map<String, Object> p = Db.p("s", station);
        Map<String, Object> m = db.one("""
                \sSELECT count(*) AS total, count(DISTINCT station_id) AS stations,
                       (SELECT count(*) FROM station WHERE active AND (CAST(:s AS bigint) IS NULL OR id = :s)) AS stations_total
                FROM employee WHERE active AND (CAST(:s AS bigint) IS NULL OR station_id = :s)""", p);
        m.put("byRole", db.list("""
                \sSELECT r.code AS role, r.label, count(e.id) AS count
                FROM role r LEFT JOIN employee e ON e.role_code = r.code AND e.active
                     AND (CAST(:s AS bigint) IS NULL OR e.station_id = :s)
                GROUP BY r.code, r.label, r.sort ORDER BY r.sort""", p));
        return m;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYES') or hasAuthority('CARTES_EMPLOYES')")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> e = load(id);
        e.put("card", e.get("cardId") == null ? null : db.one("""
                \sSELECT c.id, c.number, c.status, c.balance, c.expires_on, c.techno, h.profile, c.activated_at
                FROM card c JOIN holder h ON h.id = c.holder_id WHERE c.id = :c""", Db.p("c", e.get("cardId"))));
        if (e.get("card") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) e.get("card");
            c.put("numberFormatted", CardService.format((String) c.get("number")));
        }
        e.put("currentShift", db.one("""
                \sSELECT es.id, es.status, es.started_at, es.pumps, sc.name AS shift_name
                FROM employee_shift es LEFT JOIN shift_config sc ON sc.id = es.config_id
                WHERE es.employee_id = :e AND es.status <> 'CLOS'""", Db.p("e", id)));
        e.put("month", stats(id, YearMonth.now()));
        e.put("recentSales", db.list("""
                \sSELECT x.id, x.at, x.kind, x.total, x.payment_method, s.short_name AS station_name
                FROM sale x LEFT JOIN station s ON s.id = x.station_id
                WHERE x.employee_id = :e ORDER BY x.at DESC LIMIT 10""", Db.p("e", id)));
        e.put("shifts", db.list("""
                \sSELECT id, status, started_at, ended_at, expected_cash, remitted_cash,
                       COALESCE(expected_cash, 0) - COALESCE(remitted_cash, 0) AS gap
                FROM employee_shift WHERE employee_id = :e ORDER BY started_at DESC LIMIT 10""", Db.p("e", id)));
        return e;
    }

    private Map<String, Object> stats(long employeeId, YearMonth month) {
        return db.one("""
                \sSELECT count(sl.id) AS products_sold,
                       COALESCE(sum(sl.quantity) FILTER (WHERE p.category = 'CARBURANT'), 0) AS liters,
                       COALESCE(sum(sl.quantity) FILTER (WHERE p.category = 'LUBRIFIANT'), 0) AS lubricants,
                       COALESCE(sum(sl.amount), 0) AS revenue, count(DISTINCT x.id) AS transactions
                FROM sale x JOIN sale_line sl ON sl.sale_id = x.id JOIN product p ON p.id = sl.product_id
                WHERE x.employee_id = :e AND x.at >= :from AND x.at < :to""",
                Db.p("e", employeeId, "from", month.atDay(1), "to", month.plusMonths(1).atDay(1)));
    }

    /** Employé du mois : classement par produits vendus (lignes de vente), puis litres servis. */
    @GetMapping("/of-the-month")
    public Map<String, Object> ofTheMonth(@RequestParam(required = false) String month,
                                          @RequestParam(required = false) Long stationId,
                                          @RequestParam(required = false) Integer limit) {
        Long station = Auth.scopedStation(stationId);
        YearMonth ym;
        try {
            // par défaut : le dernier mois complet (l'employé du mois d'octobre est désigné en novembre)
            ym = month == null || month.isBlank() ? YearMonth.now().minusMonths(1) : YearMonth.parse(month.trim());
        } catch (Exception ex) {
            throw new ApiException("Mois invalide (format AAAA-MM)");
        }
        List<Map<String, Object>> ranking = db.list("""
                \sSELECT e.id, e.first_name || ' ' || e.last_name AS name,
                       upper(left(e.first_name, 1) || left(e.last_name, 1)) AS initials,
                       r.label AS role_label, s.name AS station_name,
                       CASE WHEN e.photo_path IS NULL THEN NULL ELSE '/files/' || e.photo_path END AS photo_url,
                       count(sl.id) AS products_sold,
                       COALESCE(sum(sl.quantity) FILTER (WHERE p.category = 'CARBURANT'), 0) AS liters,
                       COALESCE(sum(sl.quantity) FILTER (WHERE p.category = 'LUBRIFIANT'), 0) AS lubricants,
                       COALESCE(sum(sl.amount), 0) AS revenue
                FROM sale x JOIN sale_line sl ON sl.sale_id = x.id JOIN product p ON p.id = sl.product_id
                JOIN employee e ON e.id = x.employee_id JOIN role r ON r.code = e.role_code
                LEFT JOIN station s ON s.id = e.station_id
                WHERE x.at >= :from AND x.at < :to AND e.active
                  AND (CAST(:s AS bigint) IS NULL OR x.station_id = :s)
                GROUP BY e.id, r.label, s.name
                ORDER BY products_sold DESC, liters DESC, revenue DESC
                LIMIT :l""",
                Db.p("from", ym.atDay(1), "to", ym.plusMonths(1).atDay(1), "s", station,
                        "l", limit == null ? 10 : Math.max(1, Math.min(limit, 100))));
        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).put("rank", i + 1);
        }
        return Map.of("month", ym.toString(), "label", monthLabel(ym),
                "winner", ranking.isEmpty() ? Map.of() : ranking.get(0), "ranking", ranking);
    }

    static String monthLabel(YearMonth ym) {
        return ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRENCH) + " " + ym.getYear();
    }

    // ------------------------------------------------------------------ création et modification

    public record EmployeeForm(@NotBlank String firstName, @NotBlank String lastName, @Email String email, String phone,
                               @NotBlank String role, Long stationId, String idDocType, String idDocNumber,
                               @Size(min = 8, message = "8 caractères minimum") String password) {}

    @PostMapping
    @PreAuthorize("hasAuthority('EMPLOYES')")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody EmployeeForm f) {
        CurrentUser u = Auth.user();
        Long station = checkForm(f, u);
        String matricule = "ZN-" + String.format("%05d", db.sum("SELECT COALESCE(max(id), 0) + 1 FROM employee", Map.of()));
        long id = db.insert("""
                \sINSERT INTO employee (matricule, first_name, last_name, email, phone, password_hash, role_code, station_id,
                                      id_doc_type, id_doc_number, created_by)
                VALUES (:m, :fn, :ln, :e, :ph, :pw, :r, :s, :dt, :dn, :u)""",
                Db.p("m", matricule, "fn", f.firstName().trim(), "ln", f.lastName().trim(), "e", blankLower(f.email()),
                        "ph", blank(f.phone()), "pw", f.password() == null ? null : encoder.encode(f.password()),
                        "r", f.role(), "s", station, "dt", blank(f.idDocType()), "dn", blank(f.idDocNumber()), "u", u.id()));
        if ("GERANT".equals(f.role()) && station != null) {
            db.update("UPDATE station SET manager_id = :e WHERE id = :s AND manager_id IS NULL", Db.p("e", id, "s", station));
        }
        journal.log("EMPLOYE_CREE", Journal.MODIFICATIONS, "Employé créé",
                f.firstName().trim() + " " + f.lastName().trim() + " · " + roleLabel(f.role()), null, station, null);
        return get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYES')")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody EmployeeForm f) {
        CurrentUser u = Auth.user();
        Map<String, Object> before = loadForChange(id, u);
        Long station = checkForm(f, u);
        if (id == u.id() && !f.role().equals(before.get("role"))) {
            throw ApiException.forbidden("Vous ne pouvez pas changer votre propre rôle");
        }
        db.update("""
                \sUPDATE employee SET first_name = :fn, last_name = :ln, email = :e, phone = :ph, role_code = :r, station_id = :s,
                       id_doc_type = :dt, id_doc_number = :dn,
                       password_hash = COALESCE(:pw, password_hash)
                WHERE id = :id""",
                Db.p("id", id, "fn", f.firstName().trim(), "ln", f.lastName().trim(), "e", blankLower(f.email()),
                        "ph", blank(f.phone()), "r", f.role(), "s", station, "dt", blank(f.idDocType()),
                        "dn", blank(f.idDocNumber()), "pw", f.password() == null ? null : encoder.encode(f.password())));
        String change = before.get("role").equals(f.role()) ? "" : " · " + before.get("roleLabel") + " → " + roleLabel(f.role());
        journal.log("EMPLOYE_MODIFIE", Journal.MODIFICATIONS, "Employé modifié",
                f.firstName().trim() + " " + f.lastName().trim() + change, null, station, null);
        return get(id);
    }

    /** Désactivation : l'employé ne peut plus se connecter ; son historique reste consultable. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYES')")
    public Map<String, Object> deactivate(@PathVariable long id) {
        CurrentUser u = Auth.user();
        if (id == u.id()) {
            throw ApiException.forbidden("Vous ne pouvez pas désactiver votre propre compte");
        }
        Map<String, Object> e = loadForChange(id, u);
        if (db.count("SELECT count(*) FROM employee_shift WHERE employee_id = :e AND status <> 'CLOS'", Db.p("e", id)) > 0) {
            throw ApiException.conflict("Cet employé a un poste en cours : clôturez-le d'abord");
        }
        db.update("UPDATE employee SET active = false WHERE id = :id", Db.p("id", id));
        db.update("UPDATE station SET manager_id = NULL WHERE manager_id = :id", Db.p("id", id));
        // la carte employé ne doit plus servir
        db.update("""
                \sUPDATE card SET status = 'BLOQUEE', blocked_reason = 'Employé désactivé'
                WHERE status IN ('ACTIVE', 'A_ACTIVER') AND holder_id = (SELECT id FROM holder WHERE employee_id = :id)""",
                Db.p("id", id));
        journal.log("EMPLOYE_DESACTIVE", Journal.MODIFICATIONS, "Employé désactivé", (String) e.get("name"), null,
                num(e.get("stationId")), null);
        return get(id);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('EMPLOYES')")
    public Map<String, Object> reactivate(@PathVariable long id) {
        Map<String, Object> e = loadForChange(id, Auth.user());
        db.update("UPDATE employee SET active = true WHERE id = :id", Db.p("id", id));
        journal.log("EMPLOYE_REACTIVE", Journal.MODIFICATIONS, "Employé réactivé", (String) e.get("name"), null,
                num(e.get("stationId")), null);
        return get(id);
    }

    public record PasswordForm(@NotBlank @Size(min = 8, message = "8 caractères minimum") String password) {}

    /** Mot de passe défini par l'administrateur ou le gérant (première connexion, oubli). */
    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('EMPLOYES')")
    public Map<String, Object> setPassword(@PathVariable long id, @Valid @RequestBody PasswordForm f) {
        Map<String, Object> e = loadForChange(id, Auth.user());
        db.update("UPDATE employee SET password_hash = :h WHERE id = :id", Db.p("id", id, "h", encoder.encode(f.password())));
        journal.log("MOT_DE_PASSE", Journal.MODIFICATIONS, "Mot de passe défini", (String) e.get("name"), null,
                num(e.get("stationId")), null);
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ photo

    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('EMPLOYES')")
    public Map<String, Object> uploadPhoto(@PathVariable long id, @RequestPart("file") MultipartFile file) {
        Map<String, Object> e = loadForChange(id, Auth.user());
        String old = db.value("SELECT photo_path FROM employee WHERE id = :id", Db.p("id", id), String.class);
        String path = photos.save(file);
        db.update("UPDATE employee SET photo_path = :p WHERE id = :id", Db.p("id", id, "p", path));
        photos.delete(old);
        journal.log("PHOTO_EMPLOYE", Journal.MODIFICATIONS, "Photo de l'employé modifiée", (String) e.get("name"), null,
                num(e.get("stationId")), null);
        return get(id);
    }

    @DeleteMapping("/{id}/photo")
    @PreAuthorize("hasAuthority('EMPLOYES')")
    public Map<String, Object> deletePhoto(@PathVariable long id) {
        loadForChange(id, Auth.user());
        String old = db.value("SELECT photo_path FROM employee WHERE id = :id", Db.p("id", id), String.class);
        db.update("UPDATE employee SET photo_path = NULL WHERE id = :id", Db.p("id", id));
        photos.delete(old);
        return get(id);
    }

    // ------------------------------------------------------------------ carte employé

    public record EmployeeCardForm(@Pattern(regexp = "EMPLOYE_STATION|EMPLOYE_GROUPE") String profile,
                                   @Pattern(regexp = "PUCE|M1") String techno, String number, String pin) {}

    /**
     * Carte personnelle de l'employé, créée par le gérant. Elle reste « à activer » : une autre
     * personne doit l'activer. Son historique sert à l'employé du mois et à la clôture de poste.
     */
    @PostMapping("/{id}/card")
    @PreAuthorize("hasAuthority('CARTES_EMPLOYES')")
    @Transactional
    public Map<String, Object> createCard(@PathVariable long id, @Valid @RequestBody(required = false) EmployeeCardForm f) {
        CurrentUser u = Auth.user();
        Map<String, Object> e = load(id);
        if (!Boolean.TRUE.equals(e.get("active"))) {
            throw ApiException.conflict("Cet employé est désactivé");
        }
        if (e.get("cardId") != null && !"BLOQUEE".equals(e.get("cardStatus"))) {
            throw ApiException.conflict("Cet employé a déjà une carte : bloquez-la avant d'en créer une nouvelle");
        }
        EmployeeCardForm form = f == null ? new EmployeeCardForm(null, null, null, null) : f;
        String profile = form.profile() != null ? form.profile()
                : e.get("stationId") != null ? "EMPLOYE_STATION" : "EMPLOYE_GROUPE";
        Long holderId = db.value("SELECT id FROM holder WHERE employee_id = :e", Db.p("e", id), Long.class);
        if (holderId == null) {
            holderId = db.insert("""
                    \sINSERT INTO holder (kind, name, profile, id_doc_type, id_doc_number, phone, email, employee_id)
                    VALUES ('EMPLOYE', :n, :p, :dt, :dn, :ph, :em, :e)""",
                    Db.p("n", e.get("name"), "p", profile, "dt", e.get("idDocType"), "dn", e.get("idDocNumber"),
                            "ph", e.get("phone"), "em", e.get("email"), "e", id));
        } else {
            db.update("UPDATE holder SET name = :n, profile = :p WHERE id = :h", Db.p("h", holderId, "n", e.get("name"), "p", profile));
        }
        // une ancienne carte bloquée est retirée définitivement
        db.update("UPDATE card SET status = 'INACTIVE' WHERE holder_id = :h AND status = 'BLOQUEE'", Db.p("h", holderId));
        long cardId = issuer.issue(form.number(), "EMPLOYE", form.techno(), holderId, null, u.id(), blank(form.pin()));
        String number = db.value("SELECT number FROM card WHERE id = :c", Db.p("c", cardId), String.class);
        journal.log("CARTE_EMPLOYE", Journal.VENTES_CARTES, "Carte employé créée · à activer",
                CardService.mask(number) + " · " + e.get("name"), null, num(e.get("stationId")), null);
        return get(id);
    }

    // ------------------------------------------------------------------ contrôles

    private Map<String, Object> load(long id) {
        Map<String, Object> e = db.require(SELECT + " WHERE e.id = :id", Db.p("id", id), "Employé introuvable");
        Long station = num(e.get("stationId"));
        CurrentUser u = Auth.user();
        if (u.scope() != null && !u.scope().equals(station)) {
            throw ApiException.forbidden("Vous n'avez accès qu'aux employés de votre station");
        }
        return e;
    }

    /** Employé que l'utilisateur peut modifier (un gérant ne touche ni Admin, ni DAF, ni Comptable). */
    private Map<String, Object> loadForChange(long id, CurrentUser u) {
        Map<String, Object> e = load(id);
        if (u.scope() != null && RESERVED_ROLES.contains((String) e.get("role"))) {
            throw ApiException.forbidden("Vous ne pouvez pas modifier ce compte");
        }
        return e;
    }

    /** Vérifie le rôle et la station ; renvoie la station retenue. */
    private Long checkForm(EmployeeForm f, CurrentUser u) {
        Map<String, Object> role = db.require("SELECT code, station_bound FROM role WHERE code = :r", Db.p("r", f.role()),
                "Rôle inconnu : " + f.role());
        Long station = f.stationId();
        if (u.scope() != null) {
            if (RESERVED_ROLES.contains(f.role())) {
                throw ApiException.forbidden("Un gérant ne peut pas créer de compte " + roleLabel(f.role()));
            }
            if (station != null && !station.equals(u.scope())) {
                throw ApiException.forbidden("Vous ne gérez que les employés de votre station");
            }
            station = u.scope();
        }
        if (Boolean.TRUE.equals(role.get("stationBound")) && station == null) {
            throw new ApiException("Une station est requise pour le rôle " + roleLabel(f.role()));
        }
        if (station != null && db.count("SELECT count(*) FROM station WHERE id = :s AND active", Db.p("s", station)) == 0) {
            throw ApiException.notFound("Station introuvable");
        }
        if (f.password() != null && blank(f.email()) == null) {
            throw new ApiException("Un email est requis pour pouvoir se connecter");
        }
        return station;
    }

    private String roleLabel(String code) {
        String l = db.value("SELECT label FROM role WHERE code = :c", Db.p("c", code), String.class);
        return l == null ? code : l;
    }

    static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String blankLower(String s) {
        String b = blank(s);
        return b == null ? null : b.toLowerCase();
    }
}
