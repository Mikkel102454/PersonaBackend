package nu.miguel.personabackend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

@Component
public final class EditorLeaseAuthenticationFilter extends OncePerRequestFilter {
    private final SessionService sessions;

    public EditorLeaseAuthenticationFilter(SessionService sessions) { this.sessions = sessions; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/api/v1/editor/sessions") && request.getMethod().equals("POST")) return true;
        if ((path.equals("/api/v1/editor/sessions/installation-challenges")
                || path.equals("/api/v1/editor/sessions/installation-challenges/prove"))
                && request.getMethod().equals("POST")) return true;
        if (path.matches("/api/v1/editor/sessions/[0-9a-fA-F-]+/verify") && request.getMethod().equals("POST")) return true;
        return !path.startsWith("/api/v1/editor/sessions/") && !path.startsWith("/ws/v1/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        try {
            UUID sessionId = sessionId(request);
            RequiredRole required = requiredRole(request);
            String lease = lease(request, required);
            EditorSession session;
            EditorPrincipal.Role actual;
            if (required == RequiredRole.PLUGIN) {
                session = sessions.authenticatePlugin(sessionId, lease); actual = EditorPrincipal.Role.PLUGIN;
            } else if (required == RequiredRole.BROWSER) {
                session = sessions.authenticateBrowser(sessionId, lease); actual = EditorPrincipal.Role.BROWSER;
            } else {
                try { session = sessions.authenticatePlugin(sessionId, lease); actual = EditorPrincipal.Role.PLUGIN; }
                catch (ResponseStatusException denied) {
                    session = sessions.authenticateBrowser(sessionId, lease); actual = EditorPrincipal.Role.BROWSER;
                }
            }
            EditorPrincipal principal = new EditorPrincipal(session.id(), session.installationId(), actual,
                    session.capabilities());
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + actual.name()));
            session.capabilities().forEach(capability -> authorities.add(new SimpleGrantedAuthority("CAP_" + capability.name())));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities));
            chain.doFilter(request, response);
        } catch (ResponseStatusException | IllegalArgumentException denied) {
            response.sendError(denied instanceof ResponseStatusException status ? status.getStatusCode().value() : 401,
                    "Editor session authentication failed");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static UUID sessionId(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/ws/v1/")) return UUID.fromString(requiredParameter(request, "session"));
        String[] parts = request.getRequestURI().split("/");
        if (parts.length < 6) throw new IllegalArgumentException("Missing session ID");
        return UUID.fromString(parts[5]);
    }
    private static RequiredRole requiredRole(HttpServletRequest request) {
        String path = request.getRequestURI(), method = request.getMethod();
        if (path.equals("/ws/v1/plugin") || path.endsWith("/capabilities")
                || (path.endsWith("/snapshot") || path.endsWith("/metadata")) && method.equals("PUT") || path.contains("/validation/")
                || path.endsWith("/publishes/confirm") || path.matches(".*/publishes/[^/]+/result$"))
            return RequiredRole.PLUGIN;
        if (path.matches(".*/publishes/[^/]+/rollback-(project|result)$"))
            return RequiredRole.PLUGIN;
        if (path.equals("/ws/v1/browser") || path.contains("/drafts") || path.contains("/publishes")
                || (path.endsWith("/snapshot") || path.endsWith("/metadata")) && method.equals("GET"))
            return RequiredRole.BROWSER;
        return RequiredRole.EITHER;
    }
    private static String lease(HttpServletRequest request, RequiredRole role) {
        if (role == RequiredRole.BROWSER && request.getRequestURI().startsWith("/ws/v1/"))
            return requiredParameter(request, "lease");
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) throw new IllegalArgumentException("Missing lease");
        return header.substring(7);
    }
    private static String requiredParameter(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    private enum RequiredRole { PLUGIN, BROWSER, EITHER }
}
