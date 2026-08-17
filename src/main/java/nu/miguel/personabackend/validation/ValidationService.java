package nu.miguel.personabackend.validation;

import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.domain.AuditEvent;
import nu.miguel.personabackend.domain.HostedDraft;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import nu.miguel.personabackend.storage.ExpiringStateStore;
import nu.miguel.personabackend.storage.HostedMetadataStore;
import nu.miguel.personabackend.snapshot.EditorMetadataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Correlates identifier-only relay messages with persisted drafts; Persona remains the validator. */
@Service
public final class ValidationService {
    private static final Duration REQUEST_LIFETIME = Duration.ofMinutes(2);
    private static final int MAX_DIAGNOSTICS = 512;
    private final SessionService sessions;
    private final HostedMetadataStore metadata;
    private final ExpiringStateStore state;
    private final AuditService audit;
    private final EditorMetadataService editorMetadata;

    public ValidationService(SessionService sessions, HostedMetadataStore metadata,
                             ExpiringStateStore state, AuditService audit) {
        this(sessions,metadata,state,audit,null);
    }
    @Autowired
    public ValidationService(SessionService sessions, HostedMetadataStore metadata,
                             ExpiringStateStore state, AuditService audit,EditorMetadataService editorMetadata) {
        this.sessions = sessions; this.metadata = metadata; this.state = state; this.audit = audit;this.editorMetadata=editorMetadata;
    }

    public void request(EditorSession session, ValidationRequest request) {
        if (request == null || request.protocolVersion() != Protocol.VERSION
                || request.requestId() == null || request.draftId() == null)
            throw bad("Invalid validation request");
        requireDraft(session, request.draftId());
        HostedDraft draft = requireDraft(session, request.draftId());
        String revision = ContentProjectRevision.compute(draft.files());
        String metadataRevision=metadataRevision(session.id());
        if (!state.putIfAbsent(key(request.requestId()), value(session.id(), request.draftId(), revision,metadataRevision), REQUEST_LIFETIME))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Validation request ID was already used");
        audit.record(session, AuditEvent.ActorType.BROWSER, session.browserDescription(),
                AuditEvent.EventType.VALIDATION, AuditEvent.Outcome.SUCCESS,
                Map.of("operation", "requested", "draft-id", request.draftId(),"metadata-revision",metadataRevision), request.requestId().toString());
    }

    public ValidationProject project(UUID sessionId, UUID requestId, String pluginLease) {
        EditorSession session = sessions.authenticatePlugin(sessionId, pluginLease);
        String stored = state.get(key(requestId)).orElseThrow(() -> gone("Validation request expired"));
        String prefix = sessionId + ":";
        if (!stored.startsWith(prefix)) throw hidden();
        String[] parts = stored.substring(prefix.length()).split(":", 3);
        UUID draftId;
        try { draftId = UUID.fromString(parts[0]); }
        catch (IllegalArgumentException corrupt) { throw hidden(); }
        HostedDraft draft = requireDraft(session, draftId);
        String revision = ContentProjectRevision.compute(draft.files());
        if (parts.length != 3 || !revision.equals(parts[1])||!metadataRevision(session.id()).equals(parts[2])) throw gone("Draft or extension metadata changed after validation was requested");
        return new ValidationProject(Protocol.VERSION, requestId, session.id(), draft.id(), session.scope(),
                draft.baseRevision(), revision, draft.files());
    }

    public void complete(EditorSession session, ValidationResult result) {
        validateResult(result);
        HostedDraft draft = requireDraft(session, result.draftId());
        String revision = ContentProjectRevision.compute(draft.files());
        if (!revision.equals(result.proposedRevision())) throw bad("Validation result revision does not match the draft");
        String metadataRevision=metadataRevision(session.id());String expected = value(session.id(), result.draftId(), revision,metadataRevision);
        if (!state.consumeIfEquals(key(result.requestId()), expected))
            throw gone("Validation request expired or did not match the draft");
        state.put(validatedKey(session.id(), result.draftId(), revision,metadataRevision),
                result.valid() ? "valid" : "invalid", REQUEST_LIFETIME);
        audit.record(session, AuditEvent.ActorType.INSTALLATION, session.installationId().toString(),
                AuditEvent.EventType.VALIDATION, result.valid() ? AuditEvent.Outcome.SUCCESS : AuditEvent.Outcome.FAILED,
                Map.of("operation", "completed", "draft-id", result.draftId(),
                        "valid", result.valid(), "diagnostics", result.diagnostics().size(),"metadata-revision",metadataRevision), result.requestId().toString());
    }

    private HostedDraft requireDraft(EditorSession session, UUID draftId) {
        HostedDraft draft = metadata.draft(draftId).orElse(null);
        if (draft == null || !draft.sessionId().equals(session.id())
                || !draft.installationId().equals(session.installationId())) throw hidden();
        return draft;
    }

    private static void validateResult(ValidationResult result) {
        if (result == null || result.protocolVersion() != Protocol.VERSION || result.requestId() == null
                || result.draftId() == null || result.proposedRevision() == null
                || !result.proposedRevision().matches("[0-9a-f]{64}") || result.contentFormatVersion() < 1
                || result.diagnostics().size() > MAX_DIAGNOSTICS || result.valid() != result.diagnostics().isEmpty())
            throw bad("Invalid validation result");
        for (ValidationDiagnostic item : result.diagnostics()) {
            if (item == null || item.path() == null || item.path().length() > 512 || item.line() < 1
                    || item.column() < 1 || item.message() == null || item.message().isBlank()
                    || item.message().length() > 2_048 || item.nodeId() != null && item.nodeId().length() > 256
                    || item.referenceType() != null && item.referenceType().length() > 64
                    || item.referenceId() != null && item.referenceId().length() > 256
                    || item.suggestion() != null && item.suggestion().length() > 1_024
                    || !"ERROR".equals(item.severity())) throw bad("Invalid validation diagnostic");
        }
    }

    private static String key(UUID requestId) { return "validation:" + requestId; }
    public boolean validated(UUID sessionId, UUID draftId, String revision) {
        return state.get(validatedKey(sessionId, draftId, revision,metadataRevision(sessionId))).filter("valid"::equals).isPresent();
    }
    private static String value(UUID sessionId, UUID draftId, String revision,String metadataRevision) { return sessionId + ":" + draftId + ":" + revision+":"+metadataRevision; }
    private static String validatedKey(UUID sessionId, UUID draftId, String revision,String metadataRevision) {
        return "validated:" + sessionId + ":" + draftId + ":" + revision+":"+metadataRevision;
    }
    public String metadataRevision(UUID sessionId){return editorMetadata==null?"none":editorMetadata.currentRevision(sessionId).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Signed extension metadata is unavailable"));}
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException hidden() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Validation request does not exist"); }
    private static ResponseStatusException gone(String message) { return new ResponseStatusException(HttpStatus.GONE, message); }
}
