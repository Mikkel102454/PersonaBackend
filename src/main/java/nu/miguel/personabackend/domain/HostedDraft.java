package nu.miguel.personabackend.domain;

import nu.miguel.persona.editor.protocol.ContentFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HostedDraft(UUID id, UUID installationId, UUID sessionId, String authorId, String authorName,
                          String baseRevision, Instant createdAt, Instant updatedAt, List<ContentFile> files) {
    public HostedDraft { files = List.copyOf(files); }
}
