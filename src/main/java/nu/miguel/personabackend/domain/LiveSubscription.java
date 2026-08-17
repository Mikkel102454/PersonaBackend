package nu.miguel.personabackend.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LiveSubscription(UUID id, UUID sessionId, String type, Map<String, Object> filters,
                               Instant createdAt, Instant expiresAt) {
    public LiveSubscription { filters = Map.copyOf(filters); }
}
