package nu.miguel.persona.editor.protocol;

import java.util.List;
import java.util.UUID;

/** Complete, bounded candidate scope supplied to Persona for authoritative read-only validation. */
public record ValidationProject(int protocolVersion, UUID requestId, UUID sessionId, UUID draftId,
                                EditorScope scope, String baseRevision, String proposedRevision, List<ContentFile> files) {
    public ValidationProject { files = files == null ? List.of() : List.copyOf(files); }
}
