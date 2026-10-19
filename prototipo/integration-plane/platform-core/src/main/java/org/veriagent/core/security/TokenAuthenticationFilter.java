package org.veriagent.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Autenticación por token de demostración ({@code Authorization: Bearer ...}). Un token presente pero
 * desconocido se rechaza con 401; sin token la petición sigue sin identidad y la cadena de seguridad decide.
 */
public class TokenAuthenticationFilter extends OncePerRequestFilter {
    private final DemoTokenStore tokens;

    public TokenAuthenticationFilter(DemoTokenStore tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            Optional<Actor> actor = tokens.resolve(header.substring(7).trim());
            if (actor.isEmpty()) {
                SecurityResponses.write(response, 401, "TOKEN_INVALID", "El token no es válido");
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(actor.get(), null,
                List.of(new SimpleGrantedAuthority(actor.get().role().authority())));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
