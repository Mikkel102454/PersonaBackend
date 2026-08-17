package nu.miguel.personabackend.domain;

import nu.miguel.persona.editor.protocol.ContentFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContentRevision(UUID installationId, String revision, int contentFormatVersion,
                              Instant createdAt, UUID sourceSessionId, String installationPublicKey,
                              String signature, List<ContentFile> files) {
    public ContentRevision { files = List.copyOf(files); }
}
