package nu.miguel.personabackend.diff;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/editor/projects")
public final class SemanticDiffController {
    private final SemanticDiffService diffs;
    public SemanticDiffController(SemanticDiffService diffs) { this.diffs = diffs; }
    @PostMapping("/semantic-diff")
    public SemanticDiffResponse compare(@RequestBody SemanticDiffRequest request) { return diffs.compare(request); }
}
