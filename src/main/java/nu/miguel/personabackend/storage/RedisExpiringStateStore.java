package nu.miguel.personabackend.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "persona.editor.infrastructure", havingValue = "postgres-redis", matchIfMissing = true)
public final class RedisExpiringStateStore implements ExpiringStateStore {
    private static final String PREFIX = "persona:v1:";
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local value = redis.call('INCR', KEYS[1])
            if value == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return value
            """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value and value == ARGV[1] then redis.call('DEL', KEYS[1]); return 1 end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public RedisExpiringStateStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override public void put(String key, String value, Duration ttl) { redis.opsForValue().set(key(key), value, ttl); }
    @Override public boolean putIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(key), value, ttl));
    }
    @Override public Optional<String> get(String key) { return Optional.ofNullable(redis.opsForValue().get(key(key))); }
    @Override public long increment(String key, Duration ttl) {
        Long value = redis.execute(INCREMENT, List.of(key(key)), Long.toString(ttl.toMillis()));
        if (value == null) throw new IllegalStateException("Redis did not return an increment result");
        return value;
    }
    @Override public boolean consumeIfEquals(String key, String expectedValue) {
        return Long.valueOf(1).equals(redis.execute(CONSUME, List.of(key(key)), expectedValue));
    }
    @Override public void delete(String key) { redis.delete(key(key)); }
    private static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException("Invalid Redis state key");
        return PREFIX + value;
    }
}
