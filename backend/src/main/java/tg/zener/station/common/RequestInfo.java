package tg.zener.station.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** D'où vient la requête en cours : adresse IP, appareil (navigateur), session de connexion. Nuls hors requête. */
public final class RequestInfo {

    public static final String SESSION_ATTR = "zener.session";

    private RequestInfo() {
    }

    public static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }

    public static String ip() {
        HttpServletRequest r = request();
        if (r == null) {
            return null;
        }
        String fwd = r.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return r.getRemoteAddr();
    }

    /** Appareil : navigateur et système (« Chrome · Windows »), ou l'en-tête brut abrégé. */
    public static String userAgent() {
        HttpServletRequest r = request();
        String ua = r == null ? null : r.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return null;
        }
        String device = ua.contains("Electron") ? "Application Zener" : ua.contains("Edg/") ? "Edge" : ua.contains("Chrome") ? "Chrome"
                : ua.contains("Firefox") ? "Firefox" : ua.contains("Safari") ? "Safari" : ua.startsWith("python") ? "Script" : null;
        String os = ua.contains("Windows") ? "Windows" : ua.contains("Android") ? "Android" : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Mac OS") ? "macOS" : ua.contains("Linux") ? "Linux" : null;
        if (device == null && os == null) {
            return ua.length() > 120 ? ua.substring(0, 120) : ua;
        }
        return (device == null ? "Navigateur" : device) + (os == null ? "" : " · " + os);
    }

    public static UUID sessionId() {
        HttpServletRequest r = request();
        return r == null || !(r.getAttribute(SESSION_ATTR) instanceof UUID u) ? null : u;
    }
}
