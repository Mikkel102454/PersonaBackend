package nu.miguel.personabackend.security;

import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.personabackend.session.EditorSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/** Applies the same Spring Security authority contract to non-STOMP WebSocket envelopes. */
@Component
public final class EditorAuthorization {
    public boolean hasCapability(EditorSession session, Capability capability) {
        List<SimpleGrantedAuthority> authorities = session.capabilities().stream()
                .map(value -> new SimpleGrantedAuthority("CAP_" + value.name())).toList();
        var authentication = new UsernamePasswordAuthenticationToken(session.id(), null, authorities);
        AuthorizationManager<Object> manager = AuthorityAuthorizationManager.hasAuthority("CAP_" + capability.name());
        var decision = manager.authorize(() -> authentication, session);
        return decision != null && decision.isGranted();
    }
}
