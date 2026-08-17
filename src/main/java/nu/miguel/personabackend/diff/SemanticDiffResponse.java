package nu.miguel.personabackend.diff;

import java.util.List;

public record SemanticDiffResponse(List<SemanticDiffEntry> changes) {
    public SemanticDiffResponse { changes = changes == null ? List.of() : List.copyOf(changes); }
}
