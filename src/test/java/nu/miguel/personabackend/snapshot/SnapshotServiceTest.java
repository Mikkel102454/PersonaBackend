package nu.miguel.personabackend.snapshot;

import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.session.EditorProperties;
import nu.miguel.personabackend.session.SessionService;
import nu.miguel.personabackend.project.ProjectPathRules;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotServiceTest {
    private final SessionService sessions = new SessionService(new EditorProperties(
            "https://editor.example", "wss://editor.example", Duration.ofMinutes(5), Duration.ofMinutes(1), 3,
            Duration.ofSeconds(45), 16));
    private final SnapshotService snapshots = new SnapshotService(sessions);

    @Test void storesSignedScopedSnapshotAndReturnsItOnlyToVerifiedBrowser() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = sessions.create(createRequest(installation, EditorScope.BEHAVIORS));
        ContentSnapshot snapshot = snapshot(installation, created.sessionId(), "behaviors/tree.yml", "id: test:tree\n");

        assertThrows(ResponseStatusException.class, () -> snapshots.read(created.sessionId(), "not-a-browser-lease"));
        assertEquals(snapshot.revision(), snapshots.store(created.sessionId(), created.pluginLeaseToken(), snapshot).revision());

        KeyPair browser = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified = sessions.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()), "Test Browser"));
        assertEquals(snapshot, snapshots.read(created.sessionId(), verified.browserLeaseToken()));
    }

    @Test void rejectsTamperedAndOutOfScopeSnapshots() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = sessions.create(createRequest(installation, EditorScope.QUESTS));
        ContentSnapshot outOfScope = snapshot(installation, created.sessionId(), "behaviors/tree.yml", "id: test:tree\n");
        assertThrows(ResponseStatusException.class,
                () -> snapshots.store(created.sessionId(), created.pluginLeaseToken(), outOfScope));

        ContentSnapshot valid = snapshot(installation, created.sessionId(), "quests/story.yml", "id: test:story\n");
        ContentFile file = valid.files().getFirst();
        ContentSnapshot tampered = new ContentSnapshot(valid.protocolVersion(), valid.sessionId(), valid.revision(),
                valid.contentFormatVersion(), valid.createdAt(), valid.installationPublicKey(),
                List.of(new ContentFile(file.path(), file.sha256(), "changed")), valid.folders(),
                valid.manifestDigest(), valid.signature());
        assertThrows(ResponseStatusException.class,
                () -> snapshots.store(created.sessionId(), created.pluginLeaseToken(), tampered));
    }

    private SessionCreateRequest createRequest(KeyPair keys, EditorScope scope) throws Exception {
        SessionCreateRequest unsigned = new SessionCreateRequest(Protocol.VERSION, UUID.randomUUID(),
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), "console", "CONSOLE", scope,
                SessionRestrictions.UNRESTRICTED, Set.of(Capability.CONTENT_VIEW), System.currentTimeMillis(), UUID.randomUUID().toString(), "");
        return new SessionCreateRequest(unsigned.protocolVersion(), unsigned.installationId(), unsigned.installationPublicKey(),
                unsigned.initiatorId(), unsigned.initiatorName(), unsigned.scope(), unsigned.restrictions(), unsigned.requestedCapabilities(),
                unsigned.issuedAt(), unsigned.nonce(),
                sign(keys.getPrivate(), unsigned.signingInput()));
    }

    private ContentSnapshot snapshot(KeyPair keys, UUID sessionId, String path, String content) throws Exception {
        String fileHash = hex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        MessageDigest aggregate = MessageDigest.getInstance("SHA-256");
        aggregate.update(path.getBytes(StandardCharsets.UTF_8)); aggregate.update((byte) 0);
        aggregate.update(fileHash.getBytes(StandardCharsets.US_ASCII)); aggregate.update((byte) 0);
        ContentSnapshot unsigned = new ContentSnapshot(Protocol.VERSION, sessionId, hex(aggregate.digest()), 2,
                Instant.now(), Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                List.of(new ContentFile(path, fileHash, content)), Set.of(), ProjectPathRules.sha256(""), "");
        return new ContentSnapshot(unsigned.protocolVersion(), unsigned.sessionId(), unsigned.revision(),
                unsigned.contentFormatVersion(), unsigned.createdAt(), unsigned.installationPublicKey(), unsigned.files(),
                unsigned.folders(), unsigned.manifestDigest(), sign(keys.getPrivate(), unsigned.signingInput()));
    }

    private static String sign(PrivateKey key, String input) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key); signature.update(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
}
