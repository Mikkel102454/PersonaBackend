package nu.miguel.personabackend.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "persona.editor.infrastructure", havingValue = "memory")
public final class InMemoryRelayCoordination implements RelayCoordination {
    public static final class Bus { private final CopyOnWriteArrayList<Consumer<Forwarded>> listeners = new CopyOnWriteArrayList<>(); }
    private final Bus bus;
    private final String node = UUID.randomUUID().toString();
    public InMemoryRelayCoordination() { this(new Bus()); }
    public InMemoryRelayCoordination(Bus bus) { this.bus = bus; }
    @Override public void listen(Consumer<Forwarded> listener) { bus.listeners.add(listener); }
    @Override public void forward(RelaySocketHandler.Role role, UUID id, String payload) {
        Forwarded message = new Forwarded(role.name(), id, payload, node);
        bus.listeners.forEach(listener -> listener.accept(message));
    }
    @Override public void connected(RelaySocketHandler.Role role, UUID id, Duration ttl) {}
    @Override public void disconnected(RelaySocketHandler.Role role, UUID id) {}
}
