package tg.zener.station.employees;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tg.zener.station.common.ApiException;
import tg.zener.station.common.Db;
import tg.zener.station.journal.Journal;

/** Rôles et droits : matrice de l'écran « Employés & rôles », modifiable avec le droit PARAMETRES. */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final Db db;
    private final Journal journal;

    public RoleController(Db db, Journal journal) {
        this.db = db;
        this.journal = journal;
    }

    /** { roles: [...], rights: [{ code, label, roles: [codes] }] }. */
    @GetMapping
    public Map<String, Object> matrix() {
        List<Map<String, Object>> roles = db.list("""
                \sSELECT r.code, r.label, r.sort, r.station_bound,
                       (SELECT count(*) FROM employee e WHERE e.role_code = r.code AND e.active) AS employees
                FROM role r ORDER BY r.sort""");
        List<Map<String, Object>> rights = db.list("""
                \sSELECT a.code, a.label, a.sort,
                       COALESCE((SELECT array_agg(rr.role_code ORDER BY ro.sort) FROM role_right rr JOIN role ro ON ro.code = rr.role_code
                                 WHERE rr.right_code = a.code), '{}') AS roles
                FROM app_right a ORDER BY a.sort""");
        return Map.of("roles", roles, "rights", rights);
    }

    public record RightsForm(@NotNull List<String> rights) {}

    /** Remplace les droits d'un rôle. Le rôle Admin garde toujours le droit PARAMETRES. */
    @PutMapping("/{code}/rights")
    @PreAuthorize("hasAuthority('PARAMETRES')")
    @Transactional
    public Map<String, Object> setRights(@PathVariable String code, @Valid @RequestBody RightsForm f) {
        Map<String, Object> role = db.require("SELECT code, label FROM role WHERE code = :c", Db.p("c", code), "Rôle introuvable");
        Set<String> wanted = new LinkedHashSet<>(f.rights());
        if ("ADMIN".equals(code) && !wanted.contains("PARAMETRES")) {
            throw new ApiException("Le rôle Admin doit garder le droit « Paramètres »");
        }
        Set<String> known = new LinkedHashSet<>();
        db.list("SELECT code FROM app_right").forEach(r -> known.add((String) r.get("code")));
        List<String> unknown = wanted.stream().filter(r -> !known.contains(r)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException("Droits inconnus : " + String.join(", ", unknown));
        }
        Set<String> before = new LinkedHashSet<>();
        db.list("SELECT right_code FROM role_right WHERE role_code = :c", Db.p("c", code))
                .forEach(r -> before.add((String) r.get("rightCode")));
        db.update("DELETE FROM role_right WHERE role_code = :c", Db.p("c", code));
        if (!wanted.isEmpty()) {
            db.update("INSERT INTO role_right (role_code, right_code) SELECT :c, unnest(CAST(:r AS text[]))",
                    Db.p("c", code, "r", db.array("text", wanted.toArray())));
        }
        List<String> added = new ArrayList<>(wanted);
        added.removeAll(before);
        List<String> removed = new ArrayList<>(before);
        removed.removeAll(wanted);
        if (!added.isEmpty() || !removed.isEmpty()) {
            journal.log("DROITS_MODIFIES", Journal.MODIFICATIONS, "Droits du rôle " + role.get("label") + " modifiés",
                    (added.isEmpty() ? "" : "+ " + String.join(", ", added)) + (added.isEmpty() || removed.isEmpty() ? "" : " · ")
                            + (removed.isEmpty() ? "" : "− " + String.join(", ", removed)), null, null, null);
        }
        return matrix();
    }
}
