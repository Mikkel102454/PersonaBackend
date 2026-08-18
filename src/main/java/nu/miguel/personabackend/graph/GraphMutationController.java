package nu.miguel.personabackend.graph;

import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.snapshot.EditorMetadataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/documents")
public final class GraphMutationController {
    private final GraphMutationService mutations;
    private final EditorMetadataService metadata;
    private final RateLimitService limits; private final QuotaProperties quotas;
    @Autowired public GraphMutationController(GraphMutationService mutations, EditorMetadataService metadata,
                                              RateLimitService limits, QuotaProperties quotas) {
        this.mutations = mutations; this.metadata = metadata; this.limits = limits; this.quotas = quotas;
    }
    GraphMutationController(GraphMutationService mutations) { this(mutations, null, new RateLimitService(), QuotaProperties.defaults()); }

    @PostMapping("/mutate")
    public GraphMutationResponse mutate(@PathVariable UUID sessionId,
                                        @RequestBody GraphMutationRequest request) {
        limits.check("graph-mutation", sessionId.toString(), quotas.messagesPerSession(), quotas.window());
        var signed = metadata == null ? null : metadata.current(sessionId).orElse(null);
        return mutations.mutate(request, signed == null ? java.util.List.of() : signed.schemas(),
                signed == null ? "none" : signed.revision());
    }
}
