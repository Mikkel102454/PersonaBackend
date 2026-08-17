package nu.miguel.personabackend.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "persona.editor.infrastructure", havingValue = "memory")
public final class InMemoryRelayCoordination implements RelayCoordination {
    public static final class Bus {
        private final CopyOnWriteArrayList<Consumer<Forwarded>> listeners = new CopyOnWriteArrayList<>();
        private final Map<String, Instant> presence = new ConcurrentHashMap<>();
    }
    private final Bus bus;
    private final String node = UUID.randomUUID().toString();
    public InMemoryRelayCoordination() { this(new Bus()); }
    public InMemoryRelayCoordination(Bus bus) { this.bus = bus; }
    @Override public void listen(Consumer<Forwarded> listener) { bus.listeners.add(listener); }
    @Override public void forward(RelaySocketHandler.Role role, UUID id, String payload) {
        Forwarded message = new Forwarded(role.name(), id, payload, node);
        bus.listeners.forEach(listener -> listener.accept(message));
    }
    @Override public void connected(RelaySocketHandler.Role role, UUID id, Duration ttl) {
        bus.presence.put(presenceKey(role, id), Instant.now().plus(ttl));
    }
    @Override public void disconnected(RelaySocketHandler.Role role, UUID id) {
        bus.presence.remove(presenceKey(role, id));
    }
    @Override public boolean isConnected(RelaySocketHandler.Role role, UUID id) {
        String key = presenceKey(role, id);
        Instant expiresAt = bus.presence.get(key);
        if (expiresAt == null) return false;
        if (Instant.now().isBefore(expiresAt)) return true;
        bus.presence.remove(key, expiresAt);
        return false;
    }
    private static String presenceKey(RelaySocketHandler.Role role, UUID id) {
        return role.name().toLowerCase() + ':' + id;
    }
}
