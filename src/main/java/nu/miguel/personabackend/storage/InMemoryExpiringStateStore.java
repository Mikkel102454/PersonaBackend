package nu.miguel.personabackend.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "persona.editor.infrastructure", havingValue = "memory")
public final class InMemoryExpiringStateStore implements ExpiringStateStore {
    private static final int MAX_KEYS = 100_000;
    private final Map<String, Value> values = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryExpiringStateStore() { this(Clock.systemUTC()); }
    public InMemoryExpiringStateStore(Clock clock) { this.clock = clock; }

    @Override public void put(String key, String value, Duration ttl) {
        validate(key, ttl); ensureCapacity(); values.put(key, new Value(value, clock.instant().plus(ttl)));
    }
    @Override public boolean putIfAbsent(String key, String value, Duration ttl) {
        validate(key, ttl); ensureCapacity(); Instant now = clock.instant();
        AtomicBoolean inserted = new AtomicBoolean();
        values.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.expiresAt())) {
                inserted.set(true); return new Value(value, now.plus(ttl));
            }
            return current;
        });
        return inserted.get();
    }
    @Override public Optional<String> get(String key) {
        Value value = values.get(key);
        if (value == null) return Optional.empty();
        if (!clock.instant().isBefore(value.expiresAt())) { values.remove(key, value); return Optional.empty(); }
        return Optional.of(value.value());
    }
    @Override public long increment(String key, Duration ttl) {
        validate(key, ttl); ensureCapacity(); Instant now = clock.instant();
        return Long.parseLong(values.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.expiresAt())) return new Value("1", now.plus(ttl));
            return new Value(Long.toString(Long.parseLong(current.value()) + 1), current.expiresAt());
        }).value());
    }
    @Override public boolean consumeIfEquals(String key, String expectedValue) {
        AtomicBoolean consumed = new AtomicBoolean(); Instant now = clock.instant();
        values.computeIfPresent(key, (ignored, current) -> {
            if (now.isBefore(current.expiresAt()) && java.security.MessageDigest.isEqual(
                    current.value().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    expectedValue.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                consumed.set(true); return null;
            }
            return now.isBefore(current.expiresAt()) ? current : null;
        });
        return consumed.get();
    }
    @Override public void delete(String key) { values.remove(key); }
    public void cleanup() { Instant now = clock.instant(); values.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt())); }
    public int size() { cleanup(); return values.size(); }
    private void ensureCapacity() {
        if (values.size() < MAX_KEYS) return;
        cleanup();
        if (values.size() >= MAX_KEYS) throw new IllegalStateException("Expiring state key limit reached");
    }
    private static void validate(String key, Duration ttl) {
        if (key == null || key.isBlank() || key.length() > 512 || ttl == null || ttl.isZero() || ttl.isNegative())
            throw new IllegalArgumentException("Invalid expiring state key or TTL");
    }
    private record Value(String value, Instant expiresAt) {}
}
