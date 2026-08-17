package nu.miguel.personabackend.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitServiceTest {
    @Test void isolatesCategoriesAndSubjectsAndReturns429AfterBound() {
        RateLimitService limits = new RateLimitService(Clock.fixed(Instant.parse("2026-08-17T12:00:00Z"), ZoneOffset.UTC));

        limits.check("snapshot", "session-a", 2, Duration.ofMinutes(1));
        limits.check("snapshot", "session-a", 2, Duration.ofMinutes(1));
        limits.check("snapshot", "session-b", 2, Duration.ofMinutes(1));
        limits.check("draft", "session-a", 2, Duration.ofMinutes(1));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> limits.check("snapshot", "session-a", 2, Duration.ofMinutes(1)));
        assertEquals(429, error.getStatusCode().value());
        assertEquals(3, limits.trackedKeys());
    }

    @Test void expiresOldWindowsWithoutUnboundedRetention() {
        MutableClock clock = new MutableClock();
        RateLimitService limits = new RateLimitService(clock);
        limits.check("verify", "one", 1, Duration.ofSeconds(30));
        assertThrows(ResponseStatusException.class, () -> limits.check("verify", "one", 1, Duration.ofSeconds(30)));

        clock.now = clock.now.plusSeconds(31);
        assertDoesNotThrow(() -> limits.check("verify", "one", 1, Duration.ofSeconds(30)));
        limits.cleanup(clock.instant());
        assertEquals(1, limits.trackedKeys());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-17T12:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
