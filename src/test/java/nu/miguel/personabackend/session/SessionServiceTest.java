package nu.miguel.personabackend.session;

import nu.miguel.persona.editor.protocol.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import nu.miguel.personabackend.storage.InMemoryHostedMetadataStore;
import nu.miguel.personabackend.storage.InMemoryExpiringStateStore;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.security.QuotaProperties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {
    private final EditorProperties properties = new EditorProperties(
            "https://editor.example", "wss://editor.example", Duration.ofMinutes(5), Duration.ofMinutes(1), 3,
            Duration.ofSeconds(45), 16);

    @Test void createsSignedSessionAndConsumesVerificationCodeOnce() throws Exception {
        SessionService service = new SessionService(properties);
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateRequest request = request(installation, UUID.randomUUID(), "0123456789abcdef");

        SessionCreateResponse created = service.create(request);

        assertEquals(12, created.verificationCode().length());
        assertFalse(created.editorUrl().contains(created.verificationCode()));
        KeyPair browser = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified = service.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()), "Test Browser"));
        assertEquals(created.sessionId(), verified.sessionId());
        assertEquals(java.util.Set.of(Capability.CONTENT_VIEW), verified.capabilities());
        assertThrows(ResponseStatusException.class, () -> service.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()), "Test Browser")));
    }

    @Test void requiresAOneTimeServerChallengeLeaseOnTheHttpCreationPath() throws Exception {
        SessionService service=new SessionService(properties);KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();UUID installationId=UUID.randomUUID();String publicKey=Base64.getEncoder().encodeToString(installation.getPublic().getEncoded());
        InstallationChallengeResponse challenge=service.challenge(new InstallationChallengeRequest(Protocol.VERSION,installationId,publicKey));InstallationChallengeProof unsigned=new InstallationChallengeProof(Protocol.VERSION,challenge.challengeId(),installationId,publicKey,challenge.challenge(),"");Signature signature=Signature.getInstance("Ed25519");signature.initSign(installation.getPrivate());signature.update(unsigned.signingInput().getBytes(StandardCharsets.UTF_8));InstallationChallengeProof proof=new InstallationChallengeProof(unsigned.protocolVersion(),unsigned.challengeId(),unsigned.installationId(),unsigned.installationPublicKey(),unsigned.challenge(),Base64.getEncoder().encodeToString(signature.sign()));
        InstallationChallengeProofResponse authorized=service.prove(proof);SessionCreateRequest request=request(installation,installationId,"challenge-session-1");assertNotNull(service.create(request,authorized.installationLease()));assertThrows(ResponseStatusException.class,()->service.create(request(installation,installationId,"challenge-session-2"),authorized.installationLease()));assertThrows(ResponseStatusException.class,()->service.prove(proof));
    }

    @Test void anotherBackendInstanceRehydratesSessionFromDurableAndExpiringStores() throws Exception {
        InMemoryHostedMetadataStore metadata = new InMemoryHostedMetadataStore();
        InMemoryExpiringStateStore state = new InMemoryExpiringStateStore();
        SessionService first = new SessionService(properties, new RateLimitService(state),
                QuotaProperties.defaults(), metadata, state, null);
        SessionService second = new SessionService(properties, new RateLimitService(state),
                QuotaProperties.defaults(), metadata, state, null);
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = first.create(request(installation, UUID.randomUUID(), "cross-instance-0001"));

        assertEquals(created.sessionId(), second.authenticatePlugin(created.sessionId(), created.pluginLeaseToken()).id());
        KeyPair browser = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified = second.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()), "Other Node"));
        SessionService third = new SessionService(properties, new RateLimitService(state),
                QuotaProperties.defaults(), metadata, state, null);

        assertEquals("Other Node", third.authenticateBrowser(created.sessionId(), verified.browserLeaseToken()).browserDescription());
        assertThrows(ResponseStatusException.class, () -> first.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()), "Replay")));
    }

    @Test void rejectsReplayAndChangedInstallationIdentity() throws Exception {
        SessionService service = new SessionService(properties);
        UUID id = UUID.randomUUID();
        KeyPair first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateRequest request = request(first, id, "fedcba9876543210");
        service.create(request);
        assertThrows(ResponseStatusException.class, () -> service.create(request));

        KeyPair replacement = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(ResponseStatusException.class, () -> service.create(request(replacement, id, "aaaabbbbccccdddd")));
    }

    @Test void verifiedBrowserRemainsReadOnlyUntilPluginExplicitlyTrustsRequestedCapabilities() throws Exception {
        SessionService service = new SessionService(properties);
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateRequest request = request(installation, UUID.randomUUID(), "1212121212121212",
                java.util.Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT, Capability.CONTENT_PUBLISH));
        SessionCreateResponse created = service.create(request);
        KeyPair browser = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified = service.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()),
                "Firefox on Linux\nforged line"));

        assertEquals(java.util.Set.of(Capability.CONTENT_VIEW), verified.capabilities());
        EditorSessionStatus pending = service.statusForPlugin(created.sessionId(), created.pluginLeaseToken());
        assertEquals("Firefox on Linux forged line", pending.browserDescription());
        assertEquals(request.requestedCapabilities(), pending.requestedCapabilities());

        EditorSessionStatus trusted = service.grant(created.sessionId(), created.pluginLeaseToken(),
                new CapabilityGrantRequest(Protocol.VERSION,
                        java.util.Set.of(Capability.DRAFT_EDIT, Capability.CONTENT_PUBLISH)));
        assertEquals(request.requestedCapabilities(), trusted.grantedCapabilities());

        EditorSessionStatus revoked = service.revokeCapabilities(created.sessionId(), created.pluginLeaseToken());
        assertEquals(java.util.Set.of(Capability.CONTENT_VIEW), revoked.grantedCapabilities());
    }

    @Test void invalidSignatureCannotPinAnInstallationId() throws Exception {
        SessionService service = new SessionService(properties);
        UUID id = UUID.randomUUID();
        KeyPair legitimate = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateRequest valid = request(legitimate, id, "1111222233334444");
        SessionCreateRequest forged = new SessionCreateRequest(valid.protocolVersion(), valid.installationId(),
                valid.installationPublicKey(), valid.initiatorId(), valid.initiatorName(), valid.scope(),
                valid.restrictions(), valid.requestedCapabilities(), valid.issuedAt(), valid.nonce(), Base64.getEncoder().encodeToString(new byte[64]));
        assertThrows(ResponseStatusException.class, () -> service.create(forged));
        assertNotNull(service.create(valid));
    }

    @Test void eitherLeaseCanExplicitlyRevokeTheAbsoluteSession() throws Exception {
        SessionService service = new SessionService(properties);
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = service.create(request(installation, UUID.randomUUID(), "9999000011112222"));
        KeyPair browser = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified = service.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()), "Test Browser"));

        service.revoke(created.sessionId(), verified.browserLeaseToken());

        assertThrows(ResponseStatusException.class,
                () -> service.authenticatePlugin(created.sessionId(), created.pluginLeaseToken()));
    }

    @Test void signedLeastPrivilegeRestrictionsAreCanonicalAndVisibleInStatus() throws Exception {
        SessionService service = new SessionService(properties);
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionRestrictions restrictions = new SessionRestrictions(Set.of("World_Nether", "world"),
                Set.of("B0000000-0000-0000-0000-000000000001"), Set.of("story:keeper"), Set.of("Story"));
        SessionCreateRequest unsigned = new SessionCreateRequest(Protocol.VERSION, UUID.randomUUID(),
                Base64.getEncoder().encodeToString(installation.getPublic().getEncoded()), "console", "CONSOLE",
                EditorScope.ALL, restrictions, Set.of(Capability.CONTENT_VIEW), System.currentTimeMillis(),
                "restriction-test-nonce", "");
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(installation.getPrivate());
        signature.update(unsigned.signingInput().getBytes(StandardCharsets.UTF_8));
        SessionCreateRequest signed = new SessionCreateRequest(unsigned.protocolVersion(), unsigned.installationId(),
                unsigned.installationPublicKey(), unsigned.initiatorId(), unsigned.initiatorName(), unsigned.scope(),
                unsigned.restrictions(), unsigned.requestedCapabilities(), unsigned.issuedAt(), unsigned.nonce(),
                Base64.getEncoder().encodeToString(signature.sign()));

        SessionCreateResponse created = service.create(signed);
        EditorSessionStatus status = service.statusForPlugin(created.sessionId(), created.pluginLeaseToken());

        assertEquals(Set.of("world", "world_nether"), status.restrictions().worlds());
        assertEquals(Set.of("story"), status.restrictions().contentNamespaces());
        assertTrue(unsigned.signingInput().contains("worlds=[world, world_nether]"));
    }

    @Test void rejectsInsecureNonLocalPublicEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> new EditorProperties(
                "http://editor.example", "wss://editor.example", Duration.ofMinutes(5), Duration.ofMinutes(1), 3,
                Duration.ofSeconds(45), 16));
        assertThrows(IllegalArgumentException.class, () -> new EditorProperties(
                "https://editor.example", "ws://editor.example", Duration.ofMinutes(5), Duration.ofMinutes(1), 3,
                Duration.ofSeconds(45), 16));
        assertDoesNotThrow(() -> new EditorProperties(
                "http://localhost:8080", "ws://localhost:8080", Duration.ofMinutes(5), Duration.ofMinutes(1), 3,
                Duration.ofSeconds(45), 16));
    }

    @Test void absoluteExpiryRemovesSessionEvenWithoutAuthenticationActivity() throws Exception {
        SessionService service = new SessionService(new EditorProperties(
                "https://editor.example", "wss://editor.example", Duration.ZERO, Duration.ofMinutes(1), 3,
                Duration.ofSeconds(45), 16));
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = service.create(request(installation, UUID.randomUUID(), "abcdabcdabcdabcd"));

        assertTrue(service.removeExpired().contains(created.sessionId()));
        assertThrows(ResponseStatusException.class, () -> service.require(created.sessionId()));
    }

    private SessionCreateRequest request(KeyPair keys, UUID installationId, String nonce) throws Exception {
        return request(keys, installationId, nonce, java.util.Set.of(Capability.CONTENT_VIEW));
    }

    private SessionCreateRequest request(KeyPair keys, UUID installationId, String nonce,
                                         java.util.Set<Capability> capabilities) throws Exception {
        SessionCreateRequest unsigned = new SessionCreateRequest(Protocol.VERSION, installationId,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), "console", "CONSOLE",
                EditorScope.ALL, SessionRestrictions.UNRESTRICTED, capabilities, System.currentTimeMillis(), nonce, "");
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(unsigned.signingInput().getBytes(StandardCharsets.UTF_8));
        return new SessionCreateRequest(unsigned.protocolVersion(), unsigned.installationId(), unsigned.installationPublicKey(),
                unsigned.initiatorId(), unsigned.initiatorName(), unsigned.scope(), unsigned.restrictions(), unsigned.requestedCapabilities(),
                unsigned.issuedAt(), unsigned.nonce(),
                Base64.getEncoder().encodeToString(signature.sign()));
    }
}
