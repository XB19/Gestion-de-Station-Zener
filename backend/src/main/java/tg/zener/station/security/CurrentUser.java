package tg.zener.station.security;

import java.util.Set;

/**
 * Utilisateur connecté. stationId est renseigné pour les rôles rattachés à une station
 * (gérant, chef de poste, pompiste) : ils ne voient que les données de leur station.
 */
public record CurrentUser(long id, String name, String role, Long stationId, boolean stationBound, Set<String> rights) {

    public boolean can(String right) {
        return rights.contains(right);
    }

    /** Station à laquelle limiter les requêtes, ou null pour tout le réseau. */
    public Long scope() {
        return stationBound ? stationId : null;
    }

    public String initials() {
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].isEmpty() ? "" : parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }
}
