package nu.miguel.personabackend.storage;

import java.time.Duration;
import java.util.Optional;

public interface ExpiringStateStore {
    void put(String key, String value, Duration ttl);
    boolean putIfAbsent(String key, String value, Duration ttl);
    Optional<String> get(String key);
    long increment(String key, Duration ttl);
    boolean consumeIfEquals(String key, String expectedValue);
    void delete(String key);
}
