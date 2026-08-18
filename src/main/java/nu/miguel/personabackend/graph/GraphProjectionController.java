package nu.miguel.personabackend.graph;

import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.snapshot.EditorMetadataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/documents")
public final class GraphProjectionController {
    private final GraphProjectionService projections;
    private final EditorMetadataService metadata;
    private final RateLimitService limits; private final QuotaProperties quotas;
    @Autowired public GraphProjectionController(GraphProjectionService projections, EditorMetadataService metadata,
                                                RateLimitService limits, QuotaProperties quotas) {
        this.projections = projections; this.metadata = metadata; this.limits = limits; this.quotas = quotas;
    }
    GraphProjectionController(GraphProjectionService projections) { this(projections, null, new RateLimitService(), QuotaProperties.defaults()); }
    GraphProjectionController(GraphProjectionService projections, RateLimitService limits, QuotaProperties quotas) {
        this(projections, null, limits, quotas);
    }
    @PostMapping("/projection")
    public EditorGraphProjection projection(@PathVariable UUID sessionId,
                                            @RequestBody GraphProjectionRequest request) {
        limits.check("graph-projection", sessionId.toString(), quotas.messagesPerSession(), quotas.window());
        var signed = metadata == null ? null : metadata.current(sessionId).orElse(null);
        return projections.project(request, signed == null ? java.util.List.of() : signed.schemas(),
                signed == null ? "none" : signed.revision());
    }
}
