package nu.miguel.personabackend.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.relay.RelayHub;
import nu.miguel.personabackend.relay.RelaySocketHandler;
import nu.miguel.personabackend.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SnapshotHttpContractTest {
    @Autowired SessionService sessions;
    @Autowired SnapshotService snapshots;
    @Autowired RelayHub relay;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test void browserHttpResponseRetainsTheExactSignedSnapshotEnvelope() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = sessions.create(createRequest(installation));
        String emptyDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        Instant signedAt = Instant.parse("2026-08-18T17:21:51.123456789Z");
        ContentSnapshot unsigned = new ContentSnapshot(Protocol.VERSION, created.sessionId(), emptyDigest, 2,
                signedAt, Base64.getEncoder().encodeToString(installation.getPublic().getEncoded()),
                List.of(), Set.of(), emptyDigest, "");
        ContentSnapshot uploaded = new ContentSnapshot(unsigned.protocolVersion(), unsigned.sessionId(),
                unsigned.revision(), unsigned.contentFormatVersion(), unsigned.createdAt(),
                unsigned.installationPublicKey(), unsigned.files(), unsigned.folders(), unsigned.manifestDigest(),
                sign(installation.getPrivate(), unsigned.signingInput()));
        snapshots.store(created.sessionId(), created.pluginLeaseToken(), uploaded);

        KeyPair browser = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified = sessions.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()),
                "HTTP contract browser"));
        HttpClient client = HttpClient.newHttpClient();
        WebSocket plugin = client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + created.pluginLeaseToken())
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/v1/plugin?session="
                        + created.sessionId()), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket socket) { socket.request(1); }
                }).get(5, TimeUnit.SECONDS);
        long presenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!relay.connected(RelaySocketHandler.Role.PLUGIN, created.sessionId())
                && System.nanoTime() < presenceDeadline) Thread.sleep(10);
        assertTrue(relay.connected(RelaySocketHandler.Role.PLUGIN, created.sessionId()),
                "Plugin presence was not registered");
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/api/v1/editor/sessions/" + created.sessionId() + "/snapshot"))
                    .header("Authorization", "Bearer " + verified.browserLeaseToken()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } finally {
            plugin.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
        }

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(signedAt.toString(), json.readTree(response.body()).path("createdAt").asText());
        ContentSnapshot downloaded = json.readValue(response.body(), ContentSnapshot.class);
        assertEquals(uploaded.signingInput(), downloaded.signingInput());
        assertTrue(verify(installation.getPublic(), downloaded.signingInput(), downloaded.signature()));
    }

    private static SessionCreateRequest createRequest(KeyPair keys) throws Exception {
        SessionCreateRequest unsigned = new SessionCreateRequest(Protocol.VERSION, UUID.randomUUID(),
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), "console", "CONSOLE",
                EditorScope.ALL, SessionRestrictions.UNRESTRICTED, Set.of(Capability.CONTENT_VIEW),
                System.currentTimeMillis(), UUID.randomUUID().toString(), "");
        return new SessionCreateRequest(unsigned.protocolVersion(), unsigned.installationId(),
                unsigned.installationPublicKey(), unsigned.initiatorId(), unsigned.initiatorName(), unsigned.scope(),
                unsigned.restrictions(), unsigned.requestedCapabilities(), unsigned.issuedAt(), unsigned.nonce(),
                sign(keys.getPrivate(), unsigned.signingInput()));
    }

    private static String sign(PrivateKey key, String input) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static boolean verify(PublicKey key, String input, String encoded) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(key);
        signature.update(input.getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getDecoder().decode(encoded));
    }
}
