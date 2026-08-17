package nu.miguel.personabackend.diff;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor/sessions/{sessionId}/projects")
public final class SemanticDiffController {
    private final SemanticDiffService diffs;
    public SemanticDiffController(SemanticDiffService diffs) { this.diffs = diffs; }
    @PostMapping("/semantic-diff")
    public SemanticDiffResponse compare(@PathVariable UUID sessionId, @RequestBody SemanticDiffRequest request) { return diffs.compare(request); }
}
