package nu.miguel.personabackend.draft;

import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import nu.miguel.personabackend.snapshot.SnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.storage.HostedMetadataStore;
import nu.miguel.personabackend.domain.HostedDraft;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.domain.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public final class DraftService {
    private static final int MAX_FILES = 1_024;
    private static final long MAX_BYTES = 10L * 1_024 * 1_024;
    private final SessionService sessions;
    private final SnapshotService snapshots;
    private final RateLimitService limits;
    private final QuotaProperties quotas;
    private final HostedMetadataStore metadata;
    private final AuditService audit;

    public DraftService(SessionService sessions, SnapshotService snapshots) {
        this(sessions, snapshots, new RateLimitService(), QuotaProperties.defaults(), snapshots.metadataStore(), null);
    }

    @Autowired
    public DraftService(SessionService sessions, SnapshotService snapshots,
                        RateLimitService limits, QuotaProperties quotas, HostedMetadataStore metadata,
                        AuditService audit) {
        this.sessions = sessions; this.snapshots = snapshots; this.limits = limits; this.quotas = quotas;
        this.metadata = metadata;
        this.audit = audit == null ? new AuditService(metadata, new ObjectMapper()) : audit;
    }

    public DraftResponse save(UUID sessionId, UUID draftId, String browserLease, DraftSaveRequest request) {
        EditorSession session = editable(sessionId, browserLease);
        rateLimit(sessionId);
        validate(session, request);
        return persist(session, draftId, request.baseRevision(), request.files());
    }

    public DraftResponse patch(UUID sessionId, UUID draftId, String browserLease, DraftPatchRequest request) {
        EditorSession session = editable(sessionId, browserLease);
        rateLimit(sessionId);
        if (request == null || request.protocolVersion() != Protocol.VERSION || request.baseRevision() == null
                || !request.baseRevision().matches("[0-9a-f]{64}") || request.changes().size() > MAX_FILES)
            throw bad("Invalid draft patch envelope");
        var base = metadata.revision(session.installationId(), request.baseRevision())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "The signed base revision is unavailable for this installation"));
        Map<String, ContentFile> candidate = new TreeMap<>();
        base.files().forEach(file -> candidate.put(file.path(), file));
        Set<String> paths = new HashSet<>();
        for (DraftPatchFile change : request.changes()) {
            if (change == null || !validPath(change.path()) || !allowed(session.scope(), change.path())
                    || !paths.add(change.path())) throw bad("Patch contains an invalid, duplicate, or out-of-scope path");
            ContentFile previous = candidate.get(change.path());
            String previousDigest = previous == null ? null : previous.sha256();
            if (!Objects.equals(previousDigest, change.baseSha256()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Patch base digest does not match " + change.path());
            if (change.content() == null || change.sha256() == null) {
                if (change.content() != null || change.sha256() != null || previous == null)
                    throw bad("Patch deletion is malformed");
                candidate.remove(change.path());
            } else {
                byte[] content = change.content().getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(hex(digest().digest(content)).getBytes(StandardCharsets.US_ASCII),
                        change.sha256().getBytes(StandardCharsets.US_ASCII))) throw bad("Patch file digest does not match content");
                candidate.put(change.path(), new ContentFile(change.path(), change.sha256(), change.content()));
            }
        }
        DraftSaveRequest complete = new DraftSaveRequest(Protocol.VERSION, request.baseRevision(),
                List.copyOf(candidate.values()));
        validate(session, complete);
        return persist(session, draftId, complete.baseRevision(), complete.files());
    }

    private DraftResponse persist(EditorSession session, UUID draftId, String baseRevision, List<ContentFile> files) {
        Instant now = Instant.now();
        HostedDraft existing = metadata.draft(draftId).orElse(null);
        if (existing != null && !existing.sessionId().equals(session.id())) throw hidden();
        if (existing != null && !existing.baseRevision().equals(baseRevision))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A draft cannot be moved to a different base revision; create a new draft after rebasing");
        HostedDraft updated = new HostedDraft(draftId, session.installationId(), session.id(), session.initiatorId(),
                session.initiatorName(), baseRevision, existing == null ? now : existing.createdAt(), now, files);
        metadata.saveDraft(updated);
        audit.record(session, AuditEvent.ActorType.BROWSER, session.browserDescription(),
                AuditEvent.EventType.DRAFT_UPLOAD, AuditEvent.Outcome.SUCCESS,
                Map.of("draft-id", draftId, "base-revision", baseRevision, "files", files.size()),
                draftId.toString());
        return response(updated);
    }

    public DraftResponse read(UUID sessionId, UUID draftId, String browserLease) {
        editable(sessionId, browserLease);
        rateLimit(sessionId);
        HostedDraft draft = metadata.draft(draftId).orElse(null);
        if (draft == null || !draft.sessionId().equals(sessionId)) throw hidden();
        return response(draft);
    }

    public List<DraftResponse> list(UUID sessionId, String browserLease) {
        editable(sessionId, browserLease);
        rateLimit(sessionId);
        return metadata.drafts(sessionId).stream().map(this::response).toList();
    }

    public void delete(UUID sessionId, UUID draftId, String browserLease) {
        editable(sessionId, browserLease);
        rateLimit(sessionId);
        if (!metadata.deleteDraft(draftId, sessionId)) throw hidden();
    }

    private EditorSession editable(UUID sessionId, String lease) {
        EditorSession session = sessions.authenticateBrowser(sessionId, lease);
        if (!session.capabilities().contains(Capability.DRAFT_EDIT))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session cannot edit hosted drafts");
        return session;
    }

    private void rateLimit(UUID sessionId) {
        limits.check("draft", sessionId.toString(), quotas.draftsPerSession(), quotas.window());
    }

    private void validate(EditorSession session, DraftSaveRequest request) {
        if (request == null || request.protocolVersion() != Protocol.VERSION || request.baseRevision() == null
                || !request.baseRevision().matches("[0-9a-f]{64}") || request.files().size() > MAX_FILES)
            throw bad("Invalid draft envelope");
        long bytes = 0;
        Set<String> paths = new HashSet<>();
        for (ContentFile file : request.files()) {
            if (file == null || !validPath(file.path()) || file.content() == null || file.sha256() == null
                    || !paths.add(file.path()) || !allowed(session.scope(), file.path()))
                throw bad("Draft contains an invalid, duplicate, or out-of-scope path");
            byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
            bytes += content.length;
            if (bytes > MAX_BYTES || !MessageDigest.isEqual(hex(digest().digest(content)).getBytes(StandardCharsets.US_ASCII),
                    file.sha256().getBytes(StandardCharsets.US_ASCII)))
                throw bad(bytes > MAX_BYTES ? "Draft exceeds 10 MiB" : "Draft file digest does not match content");
        }
    }

    private DraftResponse response(HostedDraft draft) {
        String current = snapshots.currentRevision(draft.installationId()).orElse(null);
        return new DraftResponse(draft.id(), draft.installationId(), draft.sessionId(), draft.authorId(),
                draft.authorName(), draft.baseRevision(), current, current != null && !current.equals(draft.baseRevision()),
                draft.createdAt(), draft.updatedAt(), draft.files());
    }

    private static boolean validPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("\u0000")) return false;
        String[] parts = path.split("/");
        if (Arrays.stream(parts).anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private static boolean allowed(EditorScope scope, String path) {
        return switch (scope) {
            case ALL, CONTENT -> path.equals("scripts.yml") || path.startsWith("behaviors/")
                    || path.startsWith("npcs/") || path.startsWith("dialogues/") || path.startsWith("quests/");
            case SCRIPTS -> path.equals("scripts.yml");
            case BEHAVIORS -> path.startsWith("behaviors/");
            case NPCS -> path.startsWith("npcs/");
            case DIALOGUES -> path.startsWith("dialogues/");
            case QUESTS -> path.startsWith("quests/");
        };
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException hidden() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft does not exist"); }

}
