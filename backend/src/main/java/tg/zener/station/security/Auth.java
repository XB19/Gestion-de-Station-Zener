package tg.zener.station.security;

import tg.zener.station.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Accès à l'utilisateur connecté et contrôle du périmètre (station). */
public final class Auth {

    private Auth() {}

    public static CurrentUser user() {
        CurrentUser u = userOrNull();
        if (u == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NON_CONNECTE", "Connexion requise");
        }
        return u;
    }

    /** Utilisateur connecté, ou null pour une tâche planifiée / le PC station. */
    public static CurrentUser userOrNull() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof CurrentUser u ? u : null;
    }

    public static Long userIdOrNull() {
        CurrentUser u = userOrNull();
        return u == null ? null : u.id();
    }

    /** Station demandée, restreinte à la station de l'utilisateur s'il y est rattaché. */
    public static Long scopedStation(Long requested) {
        CurrentUser u = user();
        if (u.scope() == null) {
            return requested;
        }
        if (requested != null && !requested.equals(u.scope())) {
            throw ApiException.forbidden("Vous n'avez accès qu'à votre station");
        }
        return u.scope();
    }

    /** Vérifie que l'utilisateur peut agir sur cette station. */
    public static void checkStation(Long stationId) {
        CurrentUser u = user();
        if (u.scope() != null && (stationId == null || !u.scope().equals(stationId))) {
            throw ApiException.forbidden("Vous n'avez accès qu'à votre station");
        }
    }

    public static void require(String right) {
        if (!user().can(right)) {
            throw ApiException.forbidden("Vous n'avez pas le droit d'effectuer cette action");
        }
    }
}
