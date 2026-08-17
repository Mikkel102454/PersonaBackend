package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.personabackend.storage.ExpiringStateStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "persona.editor.infrastructure", havingValue = "postgres-redis", matchIfMissing = true)
public final class RedisRelayCoordination implements RelayCoordination, SmartLifecycle {
    private static final ChannelTopic TOPIC = new ChannelTopic("persona:v1:relay");
    private final String node = UUID.randomUUID().toString();
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listeners;
    private final ObjectMapper json;
    private final ExpiringStateStore state;
    private volatile Consumer<Forwarded> listener = ignored -> {};
    private volatile boolean running;

    public RedisRelayCoordination(StringRedisTemplate redis, RedisConnectionFactory factory,
                                  ObjectMapper json, ExpiringStateStore state) {
        this.redis = redis; this.json = json; this.state = state;
        this.listeners = new RedisMessageListenerContainer();
        this.listeners.setConnectionFactory(factory);
        this.listeners.addMessageListener((message, pattern) -> receive(
                new String(message.getBody(), StandardCharsets.UTF_8)), TOPIC);
    }
    @Override public void listen(Consumer<Forwarded> listener) { this.listener = listener; }
    @Override public void forward(RelaySocketHandler.Role role, UUID id, String payload) {
        try { redis.convertAndSend(TOPIC.getTopic(), json.writeValueAsString(new Forwarded(role.name(), id, payload, node))); }
        catch (Exception error) { throw new IllegalStateException("Could not publish relay envelope", error); }
    }
    @Override public void connected(RelaySocketHandler.Role role, UUID id, Duration ttl) {
        state.put(presenceKey(role, id), node, ttl);
    }
    @Override public void disconnected(RelaySocketHandler.Role role, UUID id) { state.delete(presenceKey(role, id)); }
    private void receive(String encoded) {
        try {
            Forwarded value = json.readValue(encoded, Forwarded.class);
            if (!node.equals(value.originNode())) listener.accept(value);
        } catch (Exception ignored) { /* Redis channel is private; malformed coordination data is discarded. */ }
    }
    private String presenceKey(RelaySocketHandler.Role role, UUID id) {
        return "presence:" + node + ':' + role.name().toLowerCase() + ':' + id;
    }
    @Override public void start() { if (!running) { listeners.afterPropertiesSet(); listeners.start(); running = true; } }
    @Override public void stop() { if (running) { listeners.stop(); running = false; } }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MIN_VALUE + 100; }
}
