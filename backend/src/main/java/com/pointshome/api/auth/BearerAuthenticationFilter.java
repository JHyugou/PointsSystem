package com.pointshome.api.auth;

import com.pointshome.api.auth.Account.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final SessionStore sessions;
    private final Crypto crypto;

    public BearerAuthenticationFilter(SessionStore sessions, Crypto crypto) {
        this.sessions = sessions;
        this.crypto = crypto;
    }

    public static String tokenFromHeader(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7);
        return token.matches("[A-Za-z0-9_-]{43}") ? token : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = tokenFromHeader(request.getHeader("Authorization"));
        if (token != null) {
            sessions.lookup(crypto.sha256(token), System.currentTimeMillis()).ifPresent(user -> {
                String authority = user.role() == Role.GUARDIAN ? "GUARDIAN" : "CHILD";
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority(authority)));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
