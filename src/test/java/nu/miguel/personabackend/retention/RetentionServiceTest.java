package nu.miguel.personabackend.retention;

import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.domain.*;
import nu.miguel.personabackend.storage.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RetentionServiceTest {
    @Test void removesExpiredUnreferencedMetadataWhileKeepingLatestAndActiveWorkflowData() {
        Instant now = Instant.parse("2026-08-17T12:00:00Z"), old = now.minus(Duration.ofDays(400));
        UUID installation = UUID.randomUUID(), session = UUID.randomUUID();
        InMemoryHostedMetadataStore store = new InMemoryHostedMetadataStore();
        store.registerInstallation(new ServerInstallation(installation, new byte[44], old, now));
        store.createSession(new HostedEditorSession(session, installation, "console", "CONSOLE", EditorScope.ALL,
                SessionRestrictions.UNRESTRICTED, Set.of(Capability.CONTENT_VIEW), old, now.plusSeconds(60), null));
        ContentRevision expired = revision(installation, session, "1".repeat(64), old);
        ContentRevision latest = revision(installation, session, "2".repeat(64), now);
        store.saveRevision(expired); store.saveRevision(latest);
        UUID draft = UUID.randomUUID();
        store.saveDraft(new HostedDraft(draft, installation, session, "console", "CONSOLE", expired.revision(),
                old, old, List.of()));
        store.savePublishRequest(new PublishRequest(UUID.randomUUID(), installation, session, draft,
                expired.revision(), "3".repeat(64), PublishRequest.Status.PUBLISHED, old, old,
                "{}", "{}", expired.revision()));
        store.saveSubscription(new LiveSubscription(UUID.randomUUID(), session, "runtime", Map.of(), old, old.plusSeconds(1)));
        store.appendAudit(new AuditEvent(UUID.randomUUID(), installation, session, AuditEvent.ActorType.SYSTEM,
                "test", AuditEvent.EventType.CONNECTION, AuditEvent.Outcome.SUCCESS, old, Map.of(), "old"));

        RetentionResult result = new RetentionService(store, new RetentionProperties(Duration.ofDays(30),
                Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(365), Duration.ofHours(1), 100)).sweep(now);

        assertEquals(1, result.publishes()); assertEquals(1, result.drafts()); assertEquals(1, result.revisions());
        assertEquals(1, result.subscriptions()); assertEquals(1, result.auditEvents()); assertEquals(0, result.liveTraces());
        assertTrue(store.revision(installation, latest.revision()).isPresent());
        assertTrue(store.revision(installation, expired.revision()).isEmpty());
        assertTrue(store.auditEvents().isEmpty());
    }

    private static ContentRevision revision(UUID installation, UUID session, String id, Instant at) {
        return new ContentRevision(installation, id, 1, at, session, "key", "signature", List.of());
    }
}
