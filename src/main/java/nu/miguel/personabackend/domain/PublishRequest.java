package nu.miguel.personabackend.domain;

import java.time.Instant;
import java.util.UUID;

public record PublishRequest(UUID id, UUID installationId, UUID sessionId, UUID draftId,
                             String baseRevision, String proposedRevision, Status status,
                             Instant requestedAt, Instant completedAt, String validationResult,
                             String semanticDiff, String rollbackRevision) {
    public enum Status { REQUESTED, VALIDATING, AWAITING_CONFIRMATION, APPLYING, PUBLISHED, REJECTED, FAILED,
        ROLLING_BACK, ROLLED_BACK, ROLLBACK_FAILED }
}
