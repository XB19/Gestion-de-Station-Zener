package tg.zener.station.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lit le jeton « Authorization: Bearer … ». Pour le flux temps réel (/api/live), qu'EventSource
 * ne peut pas accompagner d'en-tête, le jeton est accepté dans le paramètre access_token.
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final UserLoader users;
    private final tg.zener.station.common.Db db;

    public TokenFilter(JwtService jwt, UserLoader users, tg.zener.station.common.Db db) {
        this.jwt = jwt;
        this.users = users;
        this.db = db;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = null;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        } else if (req.getRequestURI().startsWith("/api/live")) {
            token = req.getParameter("access_token");
        }
        if (token != null) {
            JwtService.Token t = jwt.read(token);
            Long id = t == null ? null : t.employeeId();
            if (t != null && t.sessionId() != null) {
                // session révoquée (déconnexion, révocation par l'Admin) : jeton refusé ; sinon dernière activité (au plus toutes les 5 min)
                int alive = db.update("""
                        \sUPDATE auth_session SET last_seen_at = CASE WHEN last_seen_at < now() - interval '5 minutes' THEN now() ELSE last_seen_at END
                        WHERE id = :s AND revoked_at IS NULL AND expires_at > now()""", tg.zener.station.common.Db.p("s", t.sessionId()));
                if (alive == 0) {
                    id = null;
                } else {
                    req.setAttribute(tg.zener.station.common.RequestInfo.SESSION_ATTR, t.sessionId());
                }
            }
            CurrentUser user = id == null ? null : users.load(id);
            if (user != null) {
                var auth = new UsernamePasswordAuthenticationToken(user, null,
                        user.rights().stream().map(SimpleGrantedAuthority::new).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(req, res);
    }
}
