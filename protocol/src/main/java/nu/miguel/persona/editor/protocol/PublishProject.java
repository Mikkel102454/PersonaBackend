package nu.miguel.persona.editor.protocol;

import java.util.List;
import java.util.UUID;

public record PublishProject(int protocolVersion, UUID publishId, UUID sessionId, UUID draftId,
                             EditorScope scope, String baseRevision, String proposedRevision,
                             List<ContentFile> files) {
    public PublishProject { files = files == null ? List.of() : List.copyOf(files); }
}
