package nu.miguel.personabackend.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.relay.RelayCoordination;
import nu.miguel.personabackend.relay.RedisRelayCoordination;
import nu.miguel.personabackend.relay.RelaySocketHandler;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ProductionInfrastructureContextTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) {
        values.add("spring.autoconfigure.exclude", () -> "");
        values.add("spring.flyway.enabled", () -> "true");
        values.add("persona.editor.infrastructure", () -> "postgres-redis");
        values.add("spring.datasource.url", postgres::getJdbcUrl);
        values.add("spring.datasource.username", postgres::getUsername);
        values.add("spring.datasource.password", postgres::getPassword);
        values.add("spring.data.redis.host", redis::getHost);
        values.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        values.add("persona.editor.administration.actuator-token", () -> "integration-actuator-token-at-least-32-chars");
        values.add("management.endpoints.web.exposure.include", () -> "health,info,metrics,prometheus");
        values.add("management.prometheus.metrics.export.enabled", () -> "true");
        values.add("management.otlp.metrics.export.enabled", () -> "false");
    }

    @Autowired HostedMetadataStore metadata;
    @Autowired ExpiringStateStore state;
    @Autowired RelayCoordination coordination;
    @Autowired SessionService sessions;
    @Autowired RateLimitService limits;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RedisConnectionFactory redisConnections;
    @LocalServerPort int port;

    @Test void productionProfileUsesPostgresRedisAndCrossInstanceCoordinationBeans() {
        assertInstanceOf(JdbcHostedMetadataStore.class, metadata);
        assertInstanceOf(RedisExpiringStateStore.class, state);
        assertInstanceOf(RedisRelayCoordination.class, coordination);
        state.put("context-probe", "ready", java.time.Duration.ofSeconds(10));
        assertEquals("ready", state.get("context-probe").orElseThrow());
    }

    @Test void readinessAndPrometheusAreLiveAndRequireAdministrationToken() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI readiness = URI.create("http://localhost:" + port + "/actuator/health/readiness");
        assertEquals(401, client.send(HttpRequest.newBuilder(readiness).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(readiness)
                .header("Authorization", "Bearer integration-actuator-token-at-least-32-chars").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("UP"));
        HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .header("Authorization", "Bearer integration-actuator-token-at-least-32-chars").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("jvm_"));
    }

    @Test void realWebSocketsAuthenticateRouteAndReconnectAcrossTheHttpServer() throws Exception {
        KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created=sessions.create(createRequest(installation));
        KeyPair browserKey=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified=sessions.verify(created.sessionId(),new SessionVerifyRequest(
                created.verificationCode(),Base64.getEncoder().encodeToString(browserKey.getPublic().getEncoded()),"Integration browser"));
        SocketMessages browserMessages=new SocketMessages();
        java.net.http.WebSocket plugin=HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer "+created.pluginLeaseToken()).buildAsync(
                URI.create("ws://localhost:"+port+"/ws/v1/plugin?session="+created.sessionId()),new SocketMessages()).get(5,TimeUnit.SECONDS);
        long presenceDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!coordination.isConnected(RelaySocketHandler.Role.PLUGIN,created.sessionId())&&System.nanoTime()<presenceDeadline)Thread.sleep(10);
        assertTrue(coordination.isConnected(RelaySocketHandler.Role.PLUGIN,created.sessionId()),"Plugin presence was not registered");
        java.net.http.WebSocket browser=HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
                URI.create("ws://localhost:"+port+"/ws/v1/browser?session="+created.sessionId()+"&lease="+verified.browserLeaseToken()),browserMessages).get(5,TimeUnit.SECONDS);
        plugin.sendText(json.writeValueAsString(signed(installation.getPrivate(),created.sessionId(),1)),true).get(5,TimeUnit.SECONDS);
        assertEquals(Protocol.HEARTBEAT,awaitType(browserMessages,Protocol.HEARTBEAT));
        browser.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE,"reconnect").get(5,TimeUnit.SECONDS);
        SocketMessages reconnectedMessages=new SocketMessages();
        java.net.http.WebSocket reconnected=HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
                URI.create("ws://localhost:"+port+"/ws/v1/browser?session="+created.sessionId()+"&lease="+verified.browserLeaseToken()+"&lastSequence=0"),reconnectedMessages).get(5,TimeUnit.SECONDS);
        assertEquals(Protocol.HEARTBEAT,awaitType(reconnectedMessages,Protocol.HEARTBEAT));
        plugin.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE,"done").get(5,TimeUnit.SECONDS);
        reconnected.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE,"done").get(5,TimeUnit.SECONDS);
    }

    @Test void redisProvidesActualExpiryRateLimitsAndCrossInstanceRouting() throws Exception {
        String key="integration-expiry-"+UUID.randomUUID();state.put(key,"present",Duration.ofMillis(150));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(state.get(key).isPresent()&&System.nanoTime()<deadline)Thread.sleep(25);
        assertTrue(state.get(key).isEmpty(),"Redis entry did not expire");
        String subject=UUID.randomUUID().toString();limits.check("integration",subject,1,Duration.ofMinutes(1));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->limits.check("integration",subject,1,Duration.ofMinutes(1))).getStatusCode().value());

        RedisRelayCoordination first=new RedisRelayCoordination(redisTemplate,redisConnections,json,state),second=new RedisRelayCoordination(redisTemplate,redisConnections,json,state);
        CompletableFuture<RelayCoordination.Forwarded> received=new CompletableFuture<>();second.listen(received::complete);first.start();second.start();
        try{UUID session=UUID.randomUUID();first.forward(RelaySocketHandler.Role.PLUGIN,session,"signed-envelope");RelayCoordination.Forwarded forwarded=received.get(5,TimeUnit.SECONDS);assertEquals(session,forwarded.sessionId());assertEquals("signed-envelope",forwarded.payload());}
        finally{first.stop();second.stop();}
    }

    private SessionCreateRequest createRequest(KeyPair keys)throws Exception{SessionCreateRequest unsigned=new SessionCreateRequest(Protocol.VERSION,UUID.randomUUID(),Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),"console","CONSOLE",EditorScope.ALL,SessionRestrictions.UNRESTRICTED,Set.of(Capability.CONTENT_VIEW),System.currentTimeMillis(),UUID.randomUUID().toString(),"");return new SessionCreateRequest(unsigned.protocolVersion(),unsigned.installationId(),unsigned.installationPublicKey(),unsigned.initiatorId(),unsigned.initiatorName(),unsigned.scope(),unsigned.restrictions(),unsigned.requestedCapabilities(),unsigned.issuedAt(),unsigned.nonce(),sign(keys.getPrivate(),unsigned.signingInput()));}
    private SocketMessage signed(PrivateKey key,UUID session,long sequence)throws Exception{Map<String,Object> payload=Map.of("at",System.currentTimeMillis());String digest=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(payload))),input=Protocol.VERSION+"\n"+session+"\n"+sequence+"\n"+Protocol.HEARTBEAT+"\n"+digest;return new SocketMessage(Protocol.VERSION,session,sequence,Protocol.HEARTBEAT,payload,sign(key,input));}
    private static String sign(PrivateKey key,String input)throws Exception{Signature signature=Signature.getInstance("Ed25519");signature.initSign(key);signature.update(input.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(signature.sign());}
    private String awaitType(SocketMessages messages,String expected)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){String value=messages.values.poll(250,TimeUnit.MILLISECONDS);if(value!=null&&expected.equals(json.readTree(value).path("type").asText()))return expected;}fail("Did not receive "+expected);return null;}
    private static final class SocketMessages implements java.net.http.WebSocket.Listener{final BlockingQueue<String> values=new LinkedBlockingQueue<>();final StringBuilder partial=new StringBuilder();@Override public void onOpen(java.net.http.WebSocket socket){socket.request(1);}@Override public CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence data,boolean last){partial.append(data);if(last){values.offer(partial.toString());partial.setLength(0);}socket.request(1);return null;}@Override public CompletionStage<?> onBinary(java.net.http.WebSocket socket,ByteBuffer data,boolean last){socket.request(1);return null;}}
}
