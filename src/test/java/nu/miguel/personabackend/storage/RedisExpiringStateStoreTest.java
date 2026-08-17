package nu.miguel.personabackend.storage;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.personabackend.relay.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers(disabledWithoutDocker = true)
class RedisExpiringStateStoreTest {
    @Container static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);
    private LettuceConnectionFactory connection;
    private RedisExpiringStateStore state;

    @BeforeEach void setUp() {
        connection = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connection.afterPropertiesSet(); connection.start();
        StringRedisTemplate template = new StringRedisTemplate(connection);
        template.afterPropertiesSet();
        connection.getConnection().serverCommands().flushAll();
        state = new RedisExpiringStateStore(template);
    }
    @AfterEach void close() { connection.destroy(); }

    @Test void verificationValueCanBeConsumedExactlyOnceAndExpires() throws Exception {
        state.put("verification:one", "hash", Duration.ofSeconds(10));
        assertTrue(state.consumeIfEquals("verification:one", "hash"));
        assertFalse(state.consumeIfEquals("verification:one", "hash"));

        state.put("short", "secret", Duration.ofMillis(100));
        Thread.sleep(180);
        assertTrue(state.get("short").isEmpty());
    }

    @Test void incrementIsAtomicAndRetainsExpiryAcrossConcurrentInstances() throws Exception {
        int operations = 40;
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Long>>();
            for (int index = 0; index < operations; index++) futures.add(workers.submit(() -> {
                start.await(); return state.increment("quota:shared", Duration.ofSeconds(30));
            }));
            start.countDown();
            for (Future<Long> future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(Long.toString(operations), state.get("quota:shared").orElseThrow());
        } finally { workers.shutdownNow(); }
    }

    @Test void pubSubRoutesEnvelopeAcrossBackendNodes() throws Exception {
        StringRedisTemplate template = new StringRedisTemplate(connection); template.afterPropertiesSet();
        RedisRelayCoordination first = new RedisRelayCoordination(template, connection, new ObjectMapper(), state);
        RedisRelayCoordination second = new RedisRelayCoordination(template, connection, new ObjectMapper(), state);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<RelayCoordination.Forwarded> observed = new AtomicReference<>();
        second.listen(value -> { observed.set(value); received.countDown(); });
        first.start(); second.start();
        try {
            UUID sessionId = UUID.randomUUID();
            first.forward(RelaySocketHandler.Role.PLUGIN, sessionId, "signed-json");
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(sessionId, observed.get().sessionId());
            assertEquals("PLUGIN", observed.get().sourceRole());
            assertEquals("signed-json", observed.get().payload());
        } finally { first.stop(); second.stop(); }
    }
}
