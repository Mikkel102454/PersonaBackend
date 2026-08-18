package nu.miguel.personabackend.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.diff.SemanticDiffService;
import nu.miguel.personabackend.document.YamlDocumentService;
import nu.miguel.personabackend.draft.DraftService;
import nu.miguel.personabackend.security.*;
import nu.miguel.personabackend.session.*;
import nu.miguel.personabackend.snapshot.SnapshotService;
import nu.miguel.personabackend.storage.*;
import nu.miguel.personabackend.validation.ValidationService;
import nu.miguel.personabackend.project.ProjectPathRules;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PublishServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final InMemoryHostedMetadataStore metadata = new InMemoryHostedMetadataStore();
    private final InMemoryExpiringStateStore state = new InMemoryExpiringStateStore();
    private final AuditService audit = new AuditService(metadata, json);
    private final RateLimitService limits = new RateLimitService(state);
    private final QuotaProperties quotas = QuotaProperties.defaults();
    private final EditorProperties editorProperties = new EditorProperties("https://editor.example", "wss://editor.example",
            Duration.ofMinutes(10), Duration.ofMinutes(1), 3, Duration.ofSeconds(45), 16);
    private final SessionService sessions = new SessionService(editorProperties, limits, quotas, metadata, state, audit);
    private final SnapshotService snapshots = new SnapshotService(sessions, limits, quotas, metadata, audit);
    private final DraftService drafts = new DraftService(sessions, snapshots, limits, quotas, metadata, audit);
    private final ValidationService validation = new ValidationService(sessions, metadata, state, audit);
    private final PublishService publishes = new PublishService(sessions, metadata, state, validation,
            new SemanticDiffService(new YamlDocumentService()), json, audit, limits,
            new PublishProperties(Duration.ofMinutes(5), 20));

    @Test void requiresExactValidatedRevisionAndLetsTrustedPluginClaimOnce() throws Exception {
        Session fixture = session();
        String baseYaml = "# base\ncontent-version: 2\nid: publish-test\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\nconnections: {}\n";
        ContentSnapshot base = snapshot(fixture.installation(), fixture.created().sessionId(), baseYaml);
        snapshots.store(fixture.created().sessionId(), fixture.created().pluginLeaseToken(), base);
        String candidate = "# base\ncontent-version: 2\nid: publish-test\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: { hello: { type: stop } }\nconnections: {}\n";
        ContentFile candidateFile = file("scripts/publish-test.yml", candidate);
        UUID draftId = UUID.randomUUID();
        DraftResponse draft = drafts.save(fixture.created().sessionId(), draftId, fixture.verified().browserLeaseToken(),
                new DraftSaveRequest(Protocol.VERSION, base.revision(), List.of(candidateFile)));
        String proposed = ContentProjectRevision.compute(draft.files());
        UUID validationId = UUID.randomUUID();
        var editor = sessions.authenticateBrowser(fixture.created().sessionId(), fixture.verified().browserLeaseToken());
        validation.request(editor, new ValidationRequest(Protocol.VERSION, validationId, draftId));
        validation.complete(editor, new ValidationResult(Protocol.VERSION, validationId, draftId, true,
                proposed, 1, List.of()));

        PublishCreateResponse requested = publishes.request(fixture.created().sessionId(),
                fixture.verified().browserLeaseToken(), new PublishCreateRequest(Protocol.VERSION, draftId, proposed));

        assertEquals("REQUESTED", requested.status()); assertEquals(12, requested.confirmationCode().length());
        assertThrows(ResponseStatusException.class, () -> publishes.confirm(fixture.created().sessionId(),
                fixture.created().pluginLeaseToken(), new PublishConfirmRequest(Protocol.VERSION, "AAAAAAAAAAAA")));
        PublishProject project = publishes.claim(fixture.created().sessionId(), fixture.created().pluginLeaseToken()).orElseThrow();
        assertEquals(candidate, project.files().getFirst().content()); assertEquals(proposed, project.proposedRevision());
        assertTrue(publishes.claim(fixture.created().sessionId(), fixture.created().pluginLeaseToken()).isEmpty());

        PublishStatusResponse completed = publishes.complete(fixture.created().sessionId(), requested.publishId(),
                fixture.created().pluginLeaseToken(), new PublishApplyResult(Protocol.VERSION, requested.publishId(), true,
                        proposed, "backup-1", null));
        assertEquals("PUBLISHED", completed.status()); assertEquals("backup-1", completed.backupId());
        var persisted = metadata.publishRequest(requested.publishId()).orElseThrow();
        assertEquals(base.revision(), persisted.rollbackRevision()); assertNotNull(persisted.semanticDiff());
        assertTrue(persisted.validationResult().contains("validation")); assertNotNull(persisted.completedAt());
        assertEquals(fixture.created().sessionId(), persisted.sessionId()); assertEquals(draftId, persisted.draftId());
        assertTrue(metadata.auditEvents().stream().anyMatch(event -> event.eventType().name().equals("PUBLISH")));

        RollbackProject rollback = publishes.beginRollback(fixture.created().sessionId(), requested.publishId(),
                fixture.created().pluginLeaseToken());
        assertEquals("backup-1", rollback.backupId()); assertEquals(proposed, rollback.currentRevision());
        PublishStatusResponse rolledBack = publishes.completeRollback(fixture.created().sessionId(), requested.publishId(),
                fixture.created().pluginLeaseToken(), new RollbackApplyResult(Protocol.VERSION, rollback.rollbackId(),
                        requested.publishId(), true, base.revision(), "rollback-safety", null));
        assertEquals("ROLLED_BACK", rolledBack.status()); assertEquals(base.revision(), rolledBack.activeRevision());
        assertEquals("rollback-safety", rolledBack.backupId());
        assertTrue(metadata.auditEvents().stream().anyMatch(event -> event.eventType().name().equals("ROLLBACK")));
    }

    @Test void rejectsUnvalidatedOrMismatchedCandidate() throws Exception {
        Session fixture = session();
        ContentSnapshot base = snapshot(fixture.installation(), fixture.created().sessionId(), "content-version: 2\nid: publish-test\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\nconnections: {}\n");
        snapshots.store(fixture.created().sessionId(), fixture.created().pluginLeaseToken(), base);
        UUID draftId = UUID.randomUUID();
        DraftResponse draft = drafts.save(fixture.created().sessionId(), draftId, fixture.verified().browserLeaseToken(),
                new DraftSaveRequest(Protocol.VERSION, base.revision(), List.of(file("scripts/publish-test.yml", "content-version: 2\nid: publish-test\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: { x: { type: stop } }\nconnections: {}\n"))));
        assertThrows(ResponseStatusException.class, () -> publishes.request(fixture.created().sessionId(),
                fixture.verified().browserLeaseToken(), new PublishCreateRequest(Protocol.VERSION, draftId,
                        ContentProjectRevision.compute(draft.files()))));
        assertThrows(ResponseStatusException.class, () -> publishes.request(fixture.created().sessionId(),
                fixture.verified().browserLeaseToken(), new PublishCreateRequest(Protocol.VERSION, draftId, "0".repeat(64))));
    }

    private Session session() throws Exception {
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateRequest unsigned = new SessionCreateRequest(Protocol.VERSION, UUID.randomUUID(),
                Base64.getEncoder().encodeToString(installation.getPublic().getEncoded()), "console", "CONSOLE",
                EditorScope.SCRIPTS, SessionRestrictions.UNRESTRICTED,
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT, Capability.CONTENT_PUBLISH),
                System.currentTimeMillis(), UUID.randomUUID().toString(), "");
        SessionCreateRequest signed = new SessionCreateRequest(unsigned.protocolVersion(), unsigned.installationId(),
                unsigned.installationPublicKey(), unsigned.initiatorId(), unsigned.initiatorName(), unsigned.scope(),
                unsigned.restrictions(), unsigned.requestedCapabilities(), unsigned.issuedAt(), unsigned.nonce(),
                sign(installation.getPrivate(), unsigned.signingInput()));
        SessionCreateResponse created = sessions.create(signed);
        KeyPair browser = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse verified = sessions.verify(created.sessionId(), new SessionVerifyRequest(created.verificationCode(),
                Base64.getEncoder().encodeToString(browser.getPublic().getEncoded()), "Browser"));
        sessions.grant(created.sessionId(), created.pluginLeaseToken(), new CapabilityGrantRequest(Protocol.VERSION,
                Set.of(Capability.DRAFT_EDIT, Capability.CONTENT_PUBLISH)));
        return new Session(installation, created, verified);
    }
    private ContentSnapshot snapshot(KeyPair installation, UUID sessionId, String content) throws Exception {
        ContentFile file = file("scripts/publish-test.yml", content); String revision = ContentProjectRevision.compute(List.of(file));
        ContentSnapshot unsigned = new ContentSnapshot(Protocol.VERSION, sessionId, revision, 2, Instant.now(),
                Base64.getEncoder().encodeToString(installation.getPublic().getEncoded()), List.of(file),
                Set.of(), ProjectPathRules.sha256(""), "");
        return new ContentSnapshot(unsigned.protocolVersion(), unsigned.sessionId(), unsigned.revision(),
                unsigned.contentFormatVersion(), unsigned.createdAt(), unsigned.installationPublicKey(), unsigned.files(),
                unsigned.folders(), unsigned.manifestDigest(), sign(installation.getPrivate(), unsigned.signingInput()));
    }
    private static ContentFile file(String path, String content) throws Exception {
        return new ContentFile(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))), content);
    }
    private static String sign(PrivateKey key, String input) throws Exception {
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key);
        signature.update(input.getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(signature.sign());
    }
    private record Session(KeyPair installation, SessionCreateResponse created, SessionVerifyResponse verified) {}
}
