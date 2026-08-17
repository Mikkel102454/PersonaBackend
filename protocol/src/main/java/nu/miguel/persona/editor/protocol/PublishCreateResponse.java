package nu.miguel.persona.editor.protocol;

import java.time.Instant;
import java.util.UUID;

public record PublishCreateResponse(UUID publishId, UUID draftId, String baseRevision, String proposedRevision,
                                    String status, String confirmationCode, Instant expiresAt) {}
