package nu.miguel.personabackend.diff;

import nu.miguel.persona.editor.protocol.ContentFile;
import java.util.List;

public record SemanticDiffRequest(List<ContentFile> before, List<ContentFile> after) {
    public SemanticDiffRequest {
        before = before == null ? List.of() : List.copyOf(before);
        after = after == null ? List.of() : List.copyOf(after);
    }
}
