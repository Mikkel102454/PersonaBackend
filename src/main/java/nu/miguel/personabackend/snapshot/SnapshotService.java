package nu.miguel.personabackend.snapshot;

import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.storage.HostedMetadataStore;
import nu.miguel.personabackend.storage.InMemoryHostedMetadataStore;
import nu.miguel.personabackend.domain.ContentRevision;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.domain.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public final class SnapshotService {
    private static final int MAX_FILES = 1_024;
    private static final long MAX_BYTES = 10L * 1_024 * 1_024;
    private final SessionService sessions;
    private final RateLimitService limits;
    private final QuotaProperties quotas;
    private final HostedMetadataStore metadata;
    private final AuditService audit;

    public SnapshotService(SessionService sessions) {
        this(sessions, new RateLimitService(), QuotaProperties.defaults(), new InMemoryHostedMetadataStore(), null);
    }

    @Autowired
    public SnapshotService(SessionService sessions, RateLimitService limits, QuotaProperties quotas,
                           HostedMetadataStore metadata, AuditService audit) {
        this.sessions = sessions; this.limits = limits; this.quotas = quotas; this.metadata = metadata;
        this.audit = audit == null ? new AuditService(metadata, new ObjectMapper()) : audit;
    }

    public ContentSnapshot store(UUID sessionId, String pluginLease, ContentSnapshot snapshot) {
        EditorSession session = sessions.authenticatePlugin(sessionId, pluginLease);
        limits.check("snapshot-upload", sessionId.toString(), quotas.snapshotsPerSession(), quotas.window());
        validate(session, snapshot);
        metadata.saveRevision(new ContentRevision(session.installationId(), snapshot.revision(),
                snapshot.contentFormatVersion(), snapshot.createdAt(), sessionId,
                snapshot.installationPublicKey(), snapshot.signature(), snapshot.files()));
        audit.record(session, AuditEvent.ActorType.INSTALLATION, session.installationId().toString(),
                AuditEvent.EventType.SNAPSHOT_ACCESS, AuditEvent.Outcome.SUCCESS,
                Map.of("operation", "upload", "revision", snapshot.revision(), "files", snapshot.files().size()),
                sessionId.toString());
        return metadata.latestRevisionForSession(sessionId).map(this::snapshot)
                .orElseThrow(() -> new IllegalStateException("Stored snapshot was not readable"));
    }

    public ContentSnapshot read(UUID sessionId, String browserLease) {
        EditorSession session = sessions.authenticateBrowser(sessionId, browserLease);
        limits.check("snapshot-download", sessionId.toString(), quotas.snapshotsPerSession(), quotas.window());
        ContentSnapshot result = metadata.latestRevisionForSession(sessionId).map(this::snapshot)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content snapshot is not available"));
        audit.record(session, AuditEvent.ActorType.BROWSER, session.browserDescription(),
                AuditEvent.EventType.SNAPSHOT_ACCESS, AuditEvent.Outcome.SUCCESS,
                Map.of("operation", "download", "revision", result.revision()), sessionId.toString());
        return result;
    }

    public Optional<String> currentRevision(UUID installationId) {
        return metadata.latestRevision(installationId).map(ContentRevision::revision);
    }

    private void validate(EditorSession session, ContentSnapshot snapshot) {
        if (snapshot == null || snapshot.protocolVersion() != Protocol.VERSION
                || !session.id().equals(snapshot.sessionId()) || snapshot.createdAt() == null
                || snapshot.revision() == null || snapshot.installationPublicKey() == null
                || snapshot.signature() == null || snapshot.files().size() > MAX_FILES)
            throw bad("Invalid snapshot envelope");
        if (snapshot.files().stream().anyMatch(file -> file == null || file.path() == null))
            throw bad("Snapshot contains an invalid file entry");
        if (!MessageDigest.isEqual(session.installationKey().getEncoded(), decode(snapshot.installationPublicKey())))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Snapshot installation identity does not match session");

        long bytes = 0;
        MessageDigest revision = digest();
        Set<String> paths = new HashSet<>();
        List<ContentFile> ordered = snapshot.files().stream().sorted(Comparator.comparing(ContentFile::path)).toList();
        for (ContentFile file : ordered) {
            if (file == null || !validPath(file.path()) || file.content() == null || file.sha256() == null
                    || !paths.add(file.path()) || !allowed(session.scope(), file.path()))
                throw bad("Snapshot contains an invalid, duplicate, or out-of-scope path");
            byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
            bytes += content.length;
            if (bytes > MAX_BYTES || !constantEquals(hex(digest().digest(content)), file.sha256()))
                throw bad(bytes > MAX_BYTES ? "Snapshot exceeds 10 MiB" : "Snapshot file digest does not match content");
            revision.update(file.path().getBytes(StandardCharsets.UTF_8));
            revision.update((byte) 0);
            revision.update(file.sha256().getBytes(StandardCharsets.US_ASCII));
            revision.update((byte) 0);
        }
        if (!constantEquals(hex(revision.digest()), snapshot.revision())) throw bad("Snapshot revision is invalid");
        if (!verify(session.installationKey(), snapshot.signingInput(), snapshot.signature()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid snapshot signature");
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

    public HostedMetadataStore metadataStore() { return metadata; }
    private ContentSnapshot snapshot(ContentRevision revision) {
        return new ContentSnapshot(Protocol.VERSION, revision.sourceSessionId(), revision.revision(),
                revision.contentFormatVersion(), revision.createdAt(), revision.installationPublicKey(),
                revision.files(), revision.signature());
    }
    private static byte[] decode(String value) {
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw bad("Invalid installation public key"); }
    }
    private static boolean verify(PublicKey key, String input, String encoded) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key); signature.update(input.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(encoded));
        } catch (GeneralSecurityException | IllegalArgumentException e) { return false; }
    }
    private static boolean constantEquals(String left, String right) {
        return right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
