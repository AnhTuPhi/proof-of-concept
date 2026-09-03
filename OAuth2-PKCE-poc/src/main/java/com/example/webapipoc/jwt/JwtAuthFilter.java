package com.example.webapipoc.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads `Authorization: Bearer <jwt>`, validates the ACCESS token, and populates SecurityContext.
 * Refresh tokens (typ=refresh) are rejected here — they are only valid at /auth/refresh.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(req, res);
            return;
        }
        String token = header.substring(PREFIX.length());
        try {
            Claims claims = jwt.parse(token);
            if (!JwtService.TYPE_ACCESS.equals(jwt.tokenType(claims))) {
                // Refresh tokens MUST NOT authorize API calls
                chain.doFilter(req, res);
                return;
            }
            var authn = new UsernamePasswordAuthenticationToken(
                claims.getSubject(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            authn.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
            SecurityContextHolder.getContext().setAuthentication(authn);
        } catch (JwtException ignored) {
            // Leave context empty → request hits authorizeHttpRequests rules
        }
        chain.doFilter(req, res);
    }
}
