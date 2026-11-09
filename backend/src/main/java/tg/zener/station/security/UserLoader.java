package tg.zener.station.security;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tg.zener.station.common.Db;

/** Charge un employé actif avec ses droits (rôle → droits). */
@Service
public class UserLoader {

    private final Db db;

    public UserLoader(Db db) {
        this.db = db;
    }

    public CurrentUser load(long employeeId) {
        Map<String, Object> e = db.one("""
                \sSELECT e.id, e.first_name || ' ' || e.last_name AS name, e.role_code, e.station_id, r.station_bound
                FROM employee e JOIN role r ON r.code = e.role_code
                WHERE e.id = :id AND e.active""", Db.p("id", employeeId));
        if (e == null) {
            return null;
        }
        List<Map<String, Object>> rights = db.list("SELECT right_code FROM role_right WHERE role_code = :r",
                Db.p("r", e.get("roleCode")));
        Set<String> set = new HashSet<>();
        rights.forEach(r -> set.add((String) r.get("rightCode")));
        return new CurrentUser(employeeId, (String) e.get("name"), (String) e.get("roleCode"),
                e.get("stationId") == null ? null : ((Number) e.get("stationId")).longValue(),
                Boolean.TRUE.equals(e.get("stationBound")), Set.copyOf(set));
    }
}
