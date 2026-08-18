package nu.miguel.personabackend.storage;

import nu.miguel.personabackend.domain.ContentRevision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryHostedMetadataStoreTest {
    @Test void preservesEachSignedEnvelopeWhenContentIsDeduplicatedAcrossSessions() {
        InMemoryHostedMetadataStore store = new InMemoryHostedMetadataStore();
        UUID installation = UUID.randomUUID(), firstSession = UUID.randomUUID(), secondSession = UUID.randomUUID();
        String revision = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        Instant firstAt = Instant.parse("2026-08-18T17:00:00.123456789Z");
        ContentRevision first = new ContentRevision(installation, revision, 1, firstAt, firstSession,
                "key", "first-signature", List.of());
        ContentRevision second = new ContentRevision(installation, revision, 2, firstAt.plusNanos(7), secondSession,
                "key", "second-signature", List.of());

        store.saveRevision(first);
        store.saveRevision(second);

        assertEquals(1, store.latestRevisionForSession(firstSession).orElseThrow().contentFormatVersion());
        ContentRevision restored = store.latestRevisionForSession(secondSession).orElseThrow();
        assertEquals(2, restored.contentFormatVersion());
        assertEquals(second.createdAt(), restored.createdAt());
        assertEquals("second-signature", restored.signature());
    }
}
