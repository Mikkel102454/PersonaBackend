package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.personabackend.session.SessionService;
import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.security.EditorAuthorization;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.validation.ValidationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final SessionService sessions;
    private final ObjectMapper json;
    private final RelayHub hub;
    private final RateLimitService limits;
    private final QuotaProperties quotas;
    private final EditorAuthorization authorization;
    private final AuditService audit;
    private final ValidationService validation;
    private final LiveSubscriptionService live;

    public WebSocketConfiguration(SessionService sessions, ObjectMapper json, RelayHub hub,
                                  RateLimitService limits, QuotaProperties quotas,
                                  EditorAuthorization authorization, AuditService audit,
                                  ValidationService validation,LiveSubscriptionService live) {
        this.sessions = sessions; this.json = json; this.hub = hub;
        this.limits = limits; this.quotas = quotas;
        this.authorization = authorization;
        this.audit = audit;
        this.validation = validation;
        this.live=live;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RelaySocketHandler(RelaySocketHandler.Role.PLUGIN, sessions, json, hub, limits, quotas, authorization, audit, validation,live), "/ws/v1/plugin");
        registry.addHandler(new RelaySocketHandler(RelaySocketHandler.Role.BROWSER, sessions, json, hub, limits, quotas, authorization, audit, validation,live), "/ws/v1/browser");
    }
}
