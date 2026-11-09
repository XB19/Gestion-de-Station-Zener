package tg.zener.station.common;

import org.springframework.http.HttpStatus;

/** Erreur métier renvoyée au client avec un message en français. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(String message) {
        this(HttpStatus.BAD_REQUEST, "INVALIDE", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "INTROUVABLE", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "INTERDIT", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLIT", message);
    }

    /** Paiement par carte refusé (permissions, solde, statut). */
    public static ApiException refused(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
