package tg.zener.station.employees;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final tg.zener.station.validations.SensitiveActions sensitive;

    public RoleController(Db db, Journal journal, tg.zener.station.validations.SensitiveActions sensitive) {
        this.db = db;
        this.journal = journal;
        this.sensitive = sensitive;
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
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("roles", roles);
        out.put("rights", rights);
        out.put("pendingRequests", db.list("""
                \sSELECT id, ref, payload->>'role' AS role, summary, requested_at FROM validation_request
                WHERE kind = 'DROITS_ROLE' AND status = 'EN_ATTENTE' ORDER BY id"""));
        return out;
    }

    public record RightsForm(@NotNull List<String> rights, String reason) {}

    /**
     * Demande de modification des droits d'un rôle : appliquée après l'approbation d'une seconde personne (Admin ou DAF).
     * Le rôle Admin garde toujours le droit PARAMETRES.
     */
    @PutMapping("/{code}/rights")
    @PreAuthorize("hasAuthority('PARAMETRES')")
    public Map<String, Object> setRights(@PathVariable String code, @Valid @RequestBody RightsForm f) {
        db.require("SELECT code FROM role WHERE code = :c", Db.p("c", code), "Rôle introuvable");
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
        if (before.equals(wanted)) {
            return matrix();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(sensitive.requestRoleRights(code, new ArrayList<>(wanted), f.reason(),
                tg.zener.station.security.Auth.user()));
        out.putAll(matrix());
        return out;
    }
}
