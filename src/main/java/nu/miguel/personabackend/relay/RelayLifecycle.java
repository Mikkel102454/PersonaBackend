package nu.miguel.personabackend.relay;

import nu.miguel.personabackend.session.EditorProperties;
import nu.miguel.personabackend.session.SessionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.time.Instant;

@Component
public final class RelayLifecycle {
    private final SessionService sessions;
    private final RelayHub hub;
    private final EditorProperties properties;

    public RelayLifecycle(SessionService sessions, RelayHub hub, EditorProperties properties) {
        this.sessions = sessions; this.hub = hub; this.properties = properties;
    }

    @Scheduled(fixedDelay = 5_000)
    public void sweep() {
        sessions.removeExpired().forEach(id -> hub.closeSession(id,
                CloseStatus.POLICY_VIOLATION.withReason("Session expired")));
        Instant cutoff = Instant.now().minus(properties.socketIdleTimeout());
        sessions.activeSessions().forEach(session -> {
            if (session.pluginIdle(cutoff)) hub.closeRole(RelaySocketHandler.Role.PLUGIN, session.id(),
                    CloseStatus.SESSION_NOT_RELIABLE.withReason("Idle timeout"));
            if (session.verified() && session.browserIdle(cutoff)) hub.closeRole(RelaySocketHandler.Role.BROWSER,
                    session.id(), CloseStatus.SESSION_NOT_RELIABLE.withReason("Idle timeout"));
        });
    }
}
