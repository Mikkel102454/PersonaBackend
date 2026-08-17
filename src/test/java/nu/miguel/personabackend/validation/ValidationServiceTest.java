package nu.miguel.personabackend.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.domain.HostedDraft;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import nu.miguel.personabackend.storage.InMemoryExpiringStateStore;
import nu.miguel.personabackend.storage.InMemoryHostedMetadataStore;
import nu.miguel.personabackend.snapshot.EditorMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ValidationServiceTest {
    @Test void correlatesDraftWithPluginLeaseAndConsumesOnlyMatchingResult() {
        UUID sessionId = UUID.randomUUID(), installationId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID(), requestId = UUID.randomUUID();
        EditorSession session = mock(EditorSession.class);
        when(session.id()).thenReturn(sessionId); when(session.installationId()).thenReturn(installationId);
        when(session.scope()).thenReturn(EditorScope.BEHAVIORS); when(session.browserDescription()).thenReturn("test browser");
        SessionService sessions = mock(SessionService.class);
        when(sessions.authenticatePlugin(sessionId, "plugin-lease")).thenReturn(session);
        InMemoryHostedMetadataStore metadata = new InMemoryHostedMetadataStore();
        ContentFile file = new ContentFile("behaviors/a.yml", "0".repeat(64), "# raw\nid: test:a\n");
        metadata.saveDraft(new HostedDraft(draftId, installationId, sessionId, "author", "Author",
                "a".repeat(64), Instant.now(), Instant.now(), List.of(file)));
        ValidationService service = new ValidationService(sessions, metadata, new InMemoryExpiringStateStore(),
                new AuditService(metadata, new ObjectMapper()));

        service.request(session, new ValidationRequest(Protocol.VERSION, requestId, draftId));
        ValidationProject project = service.project(sessionId, requestId, "plugin-lease");
        assertEquals(EditorScope.BEHAVIORS, project.scope()); assertEquals(file.content(), project.files().getFirst().content());

        String revision = ContentProjectRevision.compute(List.of(file));
        ValidationResult result = new ValidationResult(Protocol.VERSION, requestId, draftId, true, revision, 1, List.of());
        service.complete(session, result);
        assertTrue(service.validated(sessionId, draftId, revision));
        assertThrows(ResponseStatusException.class, () -> service.complete(session, result));
    }

    @Test void rejectsMismatchedDraftAndInconsistentResult() {
        UUID sessionId = UUID.randomUUID(), installationId = UUID.randomUUID(), draftId = UUID.randomUUID();
        EditorSession session = mock(EditorSession.class);
        when(session.id()).thenReturn(sessionId); when(session.installationId()).thenReturn(installationId);
        when(session.browserDescription()).thenReturn("browser");
        InMemoryHostedMetadataStore metadata = new InMemoryHostedMetadataStore();
        metadata.saveDraft(new HostedDraft(draftId, installationId, UUID.randomUUID(), "a", "a",
                "a".repeat(64), Instant.now(), Instant.now(), List.of()));
        ValidationService service = new ValidationService(mock(SessionService.class), metadata,
                new InMemoryExpiringStateStore(), new AuditService(metadata, new ObjectMapper()));
        assertThrows(ResponseStatusException.class, () -> service.request(session,
                new ValidationRequest(Protocol.VERSION, UUID.randomUUID(), draftId)));
        ValidationResult inconsistent = new ValidationResult(Protocol.VERSION, UUID.randomUUID(), draftId,
                true, "a".repeat(64), 1, List.of(new ValidationDiagnostic("a.yml", 1, 1, null, null, null, "bad", null, "ERROR")));
        assertThrows(ResponseStatusException.class, () -> service.complete(session, inconsistent));
    }

    @Test void catalogMetadataRevisionIsPartOfTheValidationProof() {
        UUID sessionId=UUID.randomUUID(),installationId=UUID.randomUUID(),draftId=UUID.randomUUID(),requestId=UUID.randomUUID();
        EditorSession session=mock(EditorSession.class);when(session.id()).thenReturn(sessionId);when(session.installationId()).thenReturn(installationId);when(session.scope()).thenReturn(EditorScope.ALL);when(session.browserDescription()).thenReturn("browser");
        SessionService sessions=mock(SessionService.class);when(sessions.authenticatePlugin(sessionId,"plugin")).thenReturn(session);
        InMemoryHostedMetadataStore metadata=new InMemoryHostedMetadataStore();metadata.saveDraft(new HostedDraft(draftId,installationId,sessionId,"author","Author","a".repeat(64),Instant.now(),Instant.now(),List.of()));
        EditorMetadataService editorMetadata=mock(EditorMetadataService.class);when(editorMetadata.currentRevision(sessionId)).thenReturn(java.util.Optional.of("b".repeat(64)),java.util.Optional.of("c".repeat(64)));
        ValidationService service=new ValidationService(sessions,metadata,new InMemoryExpiringStateStore(),new AuditService(metadata,new ObjectMapper()),editorMetadata);
        service.request(session,new ValidationRequest(Protocol.VERSION,requestId,draftId));
        assertThrows(ResponseStatusException.class,()->service.project(sessionId,requestId,"plugin"));
    }
}
