package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.UUID;

public record PublishStatusResponse(UUID publishId, UUID draftId, String baseRevision, String proposedRevision,
                                    String status, Instant requestedAt, Instant completedAt,
                                    String activeRevision, String backupId, String error) {}
