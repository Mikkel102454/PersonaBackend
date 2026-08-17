package nu.miguel.personabackend.draft;

import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.session.EditorProperties;
import nu.miguel.personabackend.session.SessionService;
import nu.miguel.personabackend.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DraftServiceTest {
    private final SessionService sessions = new SessionService(new EditorProperties(
            "https://editor.example", "wss://editor.example", Duration.ofMinutes(5), Duration.ofMinutes(1), 3,
            Duration.ofSeconds(45), 16));
    private final SnapshotService snapshots = new SnapshotService(sessions);
    private final DraftService drafts = new DraftService(sessions, snapshots);

    @Test void preservesRawYamlAndIsolatesDraftByInstallationSessionAndAuthor() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID installationId = UUID.randomUUID();
        Session session = session(installation, installationId, "author-one",
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT));
        ContentSnapshot base = snapshot(installation, session.created().sessionId(),
                "behaviors/tree.yml", "# retained\nid: test:tree\nextension-owned:\n  future: true\n", Instant.now());
        snapshots.store(session.created().sessionId(), session.created().pluginLeaseToken(), base);
        String edited = "# retained\nid: test:tree\nextension-owned:\n  future: changed\n";
        UUID draftId = UUID.randomUUID();

        DraftResponse saved = drafts.save(session.created().sessionId(), draftId, session.verified().browserLeaseToken(),
                new DraftSaveRequest(Protocol.VERSION, base.revision(), List.of(file("behaviors/tree.yml", edited))));

        assertEquals(installationId, saved.installationId());
        assertEquals("author-one", saved.authorId());
        assertEquals(edited, saved.files().getFirst().content());
        assertFalse(saved.stale());
        assertThrows(ResponseStatusException.class, () -> drafts.save(session.created().sessionId(), draftId,
                session.verified().browserLeaseToken(), new DraftSaveRequest(Protocol.VERSION, "f".repeat(64),
                        List.of(file("behaviors/tree.yml", edited)))));

        Session other = session(installation, installationId, "author-two",
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT));
        assertThrows(ResponseStatusException.class, () -> drafts.read(
                other.created().sessionId(), draftId, other.verified().browserLeaseToken()));
        assertTrue(drafts.list(other.created().sessionId(), other.verified().browserLeaseToken()).isEmpty());
    }

    @Test void marksDraftStaleWhenAnotherSignedSessionAdvancesInstallationRevision() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID installationId = UUID.randomUUID();
        Session first = session(installation, installationId, "builder",
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT));
        Instant initialTime = Instant.now();
        ContentSnapshot initial = snapshot(installation, first.created().sessionId(),
                "scripts.yml", "scripts: {}\n", initialTime);
        snapshots.store(first.created().sessionId(), first.created().pluginLeaseToken(), initial);
        UUID draftId = UUID.randomUUID();
        drafts.save(first.created().sessionId(), draftId, first.verified().browserLeaseToken(),
                new DraftSaveRequest(Protocol.VERSION, initial.revision(), List.of(file("scripts.yml", "# work\nscripts: {}\n"))));

        Session second = session(installation, installationId, "console",
                Set.of(Capability.CONTENT_VIEW));
        ContentSnapshot changed = snapshot(installation, second.created().sessionId(),
                "scripts.yml", "scripts:\n  new: []\n", initialTime.plusSeconds(1));
        snapshots.store(second.created().sessionId(), second.created().pluginLeaseToken(), changed);

        DraftResponse stale = drafts.read(first.created().sessionId(), draftId, first.verified().browserLeaseToken());
        assertTrue(stale.stale());
        assertEquals(initial.revision(), stale.baseRevision());
        assertEquals(changed.revision(), stale.currentRevision());
        assertEquals("# work\nscripts: {}\n", stale.files().getFirst().content());
    }

    @Test void marksDraftStaleWhenItsOwnLiveSessionReloadsContent() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Session session = session(installation, UUID.randomUUID(), "builder",
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT));
        Instant initialTime = Instant.now();
        ContentSnapshot initial = snapshot(installation, session.created().sessionId(),
                "scripts.yml", "scripts: {}\n", initialTime);
        snapshots.store(session.created().sessionId(), session.created().pluginLeaseToken(), initial);
        UUID draftId = UUID.randomUUID();
        drafts.save(session.created().sessionId(), draftId, session.verified().browserLeaseToken(),
                new DraftSaveRequest(Protocol.VERSION, initial.revision(), List.of(file("scripts.yml", "# editing\nscripts: {}\n"))));

        ContentSnapshot reloaded = snapshot(installation, session.created().sessionId(),
                "scripts.yml", "scripts:\n  live: []\n", initialTime.plusSeconds(1));
        assertEquals(reloaded.revision(), snapshots.store(
                session.created().sessionId(), session.created().pluginLeaseToken(), reloaded).revision());

        DraftResponse stale = drafts.read(session.created().sessionId(), draftId, session.verified().browserLeaseToken());
        assertTrue(stale.stale());
        assertEquals(reloaded.revision(), stale.currentRevision());
    }

    @Test void requiresDraftCapabilityAndRejectsTamperedOrOutOfScopeFiles() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Session readOnly = session(installation, UUID.randomUUID(), "viewer", Set.of(Capability.CONTENT_VIEW));
        String revision = "a".repeat(64);
        assertThrows(ResponseStatusException.class, () -> drafts.save(readOnly.created().sessionId(), UUID.randomUUID(),
                readOnly.verified().browserLeaseToken(), new DraftSaveRequest(Protocol.VERSION, revision, List.of())));

        Session editable = session(installation, UUID.randomUUID(), "builder",
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT), EditorScope.BEHAVIORS);
        sessions.revokeCapabilities(editable.created().sessionId(), editable.created().pluginLeaseToken());
        assertThrows(ResponseStatusException.class, () -> drafts.save(editable.created().sessionId(), UUID.randomUUID(),
                editable.verified().browserLeaseToken(), new DraftSaveRequest(Protocol.VERSION, revision,
                        List.of(file("behaviors/tree.yml", "id: test:tree\n")))));
        sessions.grant(editable.created().sessionId(), editable.created().pluginLeaseToken(),
                new CapabilityGrantRequest(Protocol.VERSION, Set.of(Capability.DRAFT_EDIT)));
        ContentFile wrongDigest = new ContentFile("behaviors/tree.yml", "0".repeat(64), "id: test:tree\n");
        assertThrows(ResponseStatusException.class, () -> drafts.save(editable.created().sessionId(), UUID.randomUUID(),
                editable.verified().browserLeaseToken(), new DraftSaveRequest(Protocol.VERSION, revision, List.of(wrongDigest))));
        assertThrows(ResponseStatusException.class, () -> drafts.save(editable.created().sessionId(), UUID.randomUUID(),
                editable.verified().browserLeaseToken(), new DraftSaveRequest(Protocol.VERSION, revision,
                        List.of(file("quests/story.yml", "id: test:story\n")))));
    }

    @Test void reconstructsDigestGuardedPatchAgainstSignedBaseAndSupportsDeletion() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Session session = session(installation, UUID.randomUUID(), "patch-author",
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT));
        String original = "# base comment\nscripts: {}\n";
        ContentSnapshot base = snapshot(installation, session.created().sessionId(), "scripts.yml", original, Instant.now());
        snapshots.store(session.created().sessionId(), session.created().pluginLeaseToken(), base);
        String changed = "# base comment\nscripts:\n  hello: []\n";
        UUID draftId = UUID.randomUUID();

        DraftResponse patched = drafts.patch(session.created().sessionId(), draftId,
                session.verified().browserLeaseToken(), new DraftPatchRequest(Protocol.VERSION, base.revision(),
                        List.of(new DraftPatchFile("scripts.yml", base.files().getFirst().sha256(), changed,
                                file("scripts.yml", changed).sha256()))));

        assertEquals(changed, patched.files().getFirst().content());
        assertThrows(ResponseStatusException.class, () -> drafts.patch(session.created().sessionId(), UUID.randomUUID(),
                session.verified().browserLeaseToken(), new DraftPatchRequest(Protocol.VERSION, base.revision(),
                        List.of(new DraftPatchFile("scripts.yml", "0".repeat(64), changed,
                                file("scripts.yml", changed).sha256())))));
        DraftResponse deleted = drafts.patch(session.created().sessionId(), draftId,
                session.verified().browserLeaseToken(), new DraftPatchRequest(Protocol.VERSION, base.revision(),
                        List.of(new DraftPatchFile("scripts.yml", base.files().getFirst().sha256(), null, null))));
        assertTrue(deleted.files().isEmpty());
    }

    private Session session(KeyPair keys, UUID installationId, String author, Set<Capability> capabilities) throws Exception {
        return session(keys, installationId, author, capabilities, EditorScope.ALL);
    }

    private Session session(KeyPair keys, UUID installationId, String author, Set<Capability> capabilities,
                            EditorScope scope) throws Exception {
        SessionCreateRequest unsigned = new SessionCreateRequest(Protocol.VERSION, installationId,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), author, author, scope,
                SessionRestrictions.UNRESTRICTED, capabilities, System.currentTimeMillis(), UUID.randomUUID().toString(), "");
        SessionCreateRequest signed = new SessionCreateRequest(unsigned.protocolVersion(), unsigned.installationId(),
                unsigned.installationPublicKey(), unsigned.initiatorId(), unsigned.initiatorName(), unsigned.scope(),
                unsigned.restrictions(), unsigned.requestedCapabilities(), unsigned.issuedAt(), unsigned.nonce(), sign(keys.getPrivate(), unsigned.signingInput()));
        SessionCreateResponse created = sessions.create(signed);
        KeyPair browser = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified = sessions.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()), "Test Browser"));
        if (capabilities.contains(Capability.DRAFT_EDIT))
            sessions.grant(created.sessionId(), created.pluginLeaseToken(),
                    new CapabilityGrantRequest(Protocol.VERSION, Set.of(Capability.DRAFT_EDIT)));
        return new Session(created, verified);
    }

    private ContentSnapshot snapshot(KeyPair keys, UUID sessionId, String path, String content, Instant createdAt) throws Exception {
        ContentFile file = file(path, content);
        MessageDigest aggregate = MessageDigest.getInstance("SHA-256");
        aggregate.update(path.getBytes(StandardCharsets.UTF_8)); aggregate.update((byte) 0);
        aggregate.update(file.sha256().getBytes(StandardCharsets.US_ASCII)); aggregate.update((byte) 0);
        ContentSnapshot unsigned = new ContentSnapshot(Protocol.VERSION, sessionId, hex(aggregate.digest()), 1,
                createdAt, Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), List.of(file), "");
        return new ContentSnapshot(unsigned.protocolVersion(), unsigned.sessionId(), unsigned.revision(),
                unsigned.contentFormatVersion(), unsigned.createdAt(), unsigned.installationPublicKey(), unsigned.files(),
                sign(keys.getPrivate(), unsigned.signingInput()));
    }

    private static ContentFile file(String path, String content) throws Exception {
        return new ContentFile(path, hex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))), content);
    }
    private static String sign(PrivateKey key, String input) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key); signature.update(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private record Session(SessionCreateResponse created, SessionVerifyResponse verified) {}
}
