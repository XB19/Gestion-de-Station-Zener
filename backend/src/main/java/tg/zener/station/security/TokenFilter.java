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

    public TokenFilter(JwtService jwt, UserLoader users) {
        this.jwt = jwt;
        this.users = users;
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
            Long id = jwt.verify(token);
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
