package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DraftResponse(
        UUID draftId,
        UUID installationId,
        UUID sessionId,
        String authorId,
        String authorName,
        String baseRevision,
        String currentRevision,
        boolean stale,
        Instant createdAt,
        Instant updatedAt,
        List<ContentFile> files
) {
    public DraftResponse {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
