package nu.miguel.personabackend.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.personabackend.domain.AuditEvent;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.storage.InMemoryHostedMetadataStore;
import nu.miguel.personabackend.relay.RelayJsonConfiguration;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditServiceTest {
    @Test void persistsStructuredAuditAndDropsPrivateValues() throws Exception {
        InMemoryHostedMetadataStore store = new InMemoryHostedMetadataStore();
        ObjectMapper mapper = new RelayJsonConfiguration().protocolObjectMapper();
        AuditService audit = new AuditService(store, mapper);
        EditorSession session = mock(EditorSession.class);
        when(session.id()).thenReturn(UUID.randomUUID());
        when(session.installationId()).thenReturn(UUID.randomUUID());

        AuditEvent event = audit.record(session, AuditEvent.ActorType.BROWSER, "Browser\nName",
                AuditEvent.EventType.MEMORY_MUTATION, AuditEvent.Outcome.DENIED,
                Map.of("operation", "set", "memory-key", "private.secret", "old-value", "sensitive",
                        "new-value", "also-sensitive", "scope", "player"), "correlation");

        assertEquals(List.of(event), store.auditEvents());
        assertEquals("Browser Name", event.actorId());
        assertEquals("set", event.details().get("operation"));
        assertEquals("player", event.details().get("scope"));
        assertFalse(event.details().keySet().stream().anyMatch(key -> key.contains("memory") || key.contains("value")));
        String serialized = mapper.writeValueAsString(event);
        assertTrue(serialized.contains("occurredAt"));
        assertTrue(serialized.contains(event.occurredAt().toString()));
    }
}
