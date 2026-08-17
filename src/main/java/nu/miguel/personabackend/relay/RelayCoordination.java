package nu.miguel.personabackend.relay;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

public interface RelayCoordination {
    record Forwarded(String sourceRole, UUID sessionId, String payload, String originNode) {}
    void listen(Consumer<Forwarded> listener);
    void forward(RelaySocketHandler.Role sourceRole, UUID sessionId, String payload);
    void connected(RelaySocketHandler.Role role, UUID sessionId, Duration ttl);
    void disconnected(RelaySocketHandler.Role role, UUID sessionId);
}
