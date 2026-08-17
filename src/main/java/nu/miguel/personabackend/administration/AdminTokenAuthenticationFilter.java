package nu.miguel.personabackend.administration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public final class AdminTokenAuthenticationFilter extends OncePerRequestFilter {
    private final AdministrationProperties properties;
    public AdminTokenAuthenticationFilter(AdministrationProperties properties) { this.properties = properties; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/actuator/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String supplied = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (!properties.enabled() || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                properties.actuatorToken().getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Actuator authentication required"); return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "persona-actuator", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
