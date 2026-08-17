package nu.miguel.personabackend.security;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import nu.miguel.personabackend.storage.ExpiringStateStore;
import nu.miguel.personabackend.storage.InMemoryExpiringStateStore;

/**
 * Bounded fixed-window quotas. The key format is deliberately backend-independent so
 * the same atomic operation can be moved to Redis for horizontally scaled deployments.
 */
@Service
public final class RateLimitService {
    private final ExpiringStateStore state;

    public RateLimitService() { this(new InMemoryExpiringStateStore()); }
    RateLimitService(Clock clock) { this(new InMemoryExpiringStateStore(clock)); }
    @Autowired public RateLimitService(ExpiringStateStore state) { this.state = state; }

    public void check(String category, String subject, int maximum, Duration duration) {
        if (category == null || subject == null || maximum < 1 || duration == null
                || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Invalid rate-limit policy");
        long count = state.increment("quota:" + category + ':' + subject, duration);
        if (count > maximum)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded for " + category);
    }

    void cleanup(Instant now) { if (state instanceof InMemoryExpiringStateStore memory) memory.cleanup(); }
    int trackedKeys() { return state instanceof InMemoryExpiringStateStore memory ? memory.size() : -1; }
}
