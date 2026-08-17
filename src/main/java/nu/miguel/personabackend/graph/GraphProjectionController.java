package nu.miguel.personabackend.graph;

import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/documents")
public final class GraphProjectionController {
    private final GraphProjectionService projections;
    private final RateLimitService limits; private final QuotaProperties quotas;
    @Autowired public GraphProjectionController(GraphProjectionService projections, RateLimitService limits, QuotaProperties quotas) {
        this.projections = projections; this.limits = limits; this.quotas = quotas;
    }
    GraphProjectionController(GraphProjectionService projections) { this(projections, new RateLimitService(), QuotaProperties.defaults()); }
    @PostMapping("/projection")
    public EditorGraphProjection projection(@PathVariable UUID sessionId,
                                            @RequestBody GraphProjectionRequest request) {
        limits.check("graph-projection", sessionId.toString(), quotas.messagesPerSession(), quotas.window());
        return projections.project(request);
    }
}
