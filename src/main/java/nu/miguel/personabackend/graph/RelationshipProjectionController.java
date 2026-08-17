package nu.miguel.personabackend.graph;

import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/projects")
public final class RelationshipProjectionController {
    private final GraphProjectionService projections;
    private final RateLimitService limits; private final QuotaProperties quotas;
    @Autowired public RelationshipProjectionController(GraphProjectionService projections, RateLimitService limits, QuotaProperties quotas) {
        this.projections = projections; this.limits = limits; this.quotas = quotas;
    }
    RelationshipProjectionController(GraphProjectionService projections) { this(projections, new RateLimitService(), QuotaProperties.defaults()); }
    @PostMapping("/relationship-projection")
    public EditorGraphProjection relationship(@PathVariable UUID sessionId,
                                              @RequestBody RelationshipProjectionRequest request) {
        limits.check("relationship-projection", sessionId.toString(), quotas.messagesPerSession(), quotas.window());
        return projections.relationship(request);
    }
}
