package nu.miguel.personabackend.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(UUID id, UUID installationId, UUID sessionId, ActorType actorType,
                         String actorId, EventType eventType, Outcome outcome, Instant occurredAt,
                         Map<String, Object> details, String correlationId) {
    public AuditEvent { details = Map.copyOf(details); }
    public enum ActorType { INSTALLATION, BROWSER, OPERATOR, SYSTEM }
    public enum Outcome { SUCCESS, DENIED, FAILED }
    public enum EventType {
        CONNECTION, TRUST, SNAPSHOT_ACCESS, DRAFT_UPLOAD, VALIDATION, PUBLISH, ROLLBACK,
        SIGNAL, MEMORY_MUTATION, SESSION_REVOCATION, SUBSCRIPTION
    }
}
