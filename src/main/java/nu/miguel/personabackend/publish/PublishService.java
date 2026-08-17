package nu.miguel.personabackend.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.diff.*;
import nu.miguel.personabackend.domain.*;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.session.*;
import nu.miguel.personabackend.storage.*;
import nu.miguel.personabackend.validation.ValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Service
public final class PublishService {
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final SessionService sessions;
    private final HostedMetadataStore metadata;
    private final ExpiringStateStore state;
    private final ValidationService validation;
    private final SemanticDiffService diffs;
    private final ObjectMapper json;
    private final AuditService audit;
    private final RateLimitService limits;
    private final PublishProperties properties;

    public PublishService(SessionService sessions, HostedMetadataStore metadata, ExpiringStateStore state,
                          ValidationService validation, SemanticDiffService diffs, ObjectMapper json,
                          AuditService audit, RateLimitService limits, PublishProperties properties) {
        this.sessions = sessions; this.metadata = metadata; this.state = state; this.validation = validation;
        this.diffs = diffs; this.json = json; this.audit = audit; this.limits = limits; this.properties = properties;
    }

    public PublishCreateResponse request(UUID sessionId, String browserLease, PublishCreateRequest body) {
        EditorSession session = sessions.authenticateBrowser(sessionId, browserLease);
        requireCapability(session);
        limits.check("publish-request", sessionId.toString(), properties.requestsPerSession(), properties.confirmationLifetime());
        if (body == null || body.protocolVersion() != Protocol.VERSION || body.draftId() == null
                || body.proposedRevision() == null || !body.proposedRevision().matches("[0-9a-f]{64}")) throw bad("Invalid publish request");
        HostedDraft draft = draft(session, body.draftId());
        String proposed = ContentProjectRevision.compute(draft.files());
        if (!proposed.equals(body.proposedRevision())) throw conflict("Draft changed after validation");
        ContentRevision base = metadata.revision(session.installationId(), draft.baseRevision())
                .orElseThrow(() -> conflict("Signed base revision is unavailable"));
        ContentRevision current = metadata.latestRevisionForSession(session.id()).orElse(null);
        if (current == null || !current.revision().equals(draft.baseRevision())) throw conflict("Live content changed; rebase and validate again");
        if (!validation.validated(session.id(), draft.id(), proposed)) throw conflict("This exact draft revision has not passed Persona validation");
        String metadataRevision=validation.metadataRevision(session.id());
        UUID publishId = UUID.randomUUID(); String code = randomCode(); Instant now = Instant.now();
        SemanticDiffResponse diff = diffs.compare(new SemanticDiffRequest(base.files(), draft.files()));
        PublishRequest record = new PublishRequest(publishId, session.installationId(), session.id(), draft.id(),
                draft.baseRevision(), proposed, PublishRequest.Status.AWAITING_CONFIRMATION, now, null,
                write(Map.of("valid", true, "proposedRevision", proposed,"metadataRevision",metadataRevision)), write(diff), draft.baseRevision());
        metadata.savePublishRequest(record);
        state.put(codeKey(sessionId, code), publishId.toString(), properties.confirmationLifetime());
        state.put(proofKey(publishId), proposed, properties.confirmationLifetime());
        audit.record(session, AuditEvent.ActorType.BROWSER, session.browserDescription(), AuditEvent.EventType.PUBLISH,
                AuditEvent.Outcome.SUCCESS, Map.of("operation", "requested", "publish-id", publishId,
                        "draft-id", draft.id(), "base-revision", draft.baseRevision(), "proposed-revision", proposed,"metadata-revision",metadataRevision),
                publishId.toString());
        return new PublishCreateResponse(publishId, draft.id(), draft.baseRevision(), proposed,
                record.status().name(), code, now.plus(properties.confirmationLifetime()));
    }

    public PublishProject confirm(UUID sessionId, String pluginLease, PublishConfirmRequest body) {
        EditorSession session = sessions.authenticatePlugin(sessionId, pluginLease); requireCapability(session);
        limits.check("publish-confirm", sessionId.toString(), 10, properties.confirmationLifetime());
        if (body == null || body.protocolVersion() != Protocol.VERSION || body.confirmationCode() == null)
            throw bad("Invalid publish confirmation");
        String code = normalizeCode(body.confirmationCode());
        String key = codeKey(sessionId, code);
        String publishText = state.get(key).orElseThrow(() -> denied("Confirmation code is invalid or expired"));
        UUID publishId;
        try { publishId = UUID.fromString(publishText); } catch (IllegalArgumentException error) { throw denied("Confirmation code is invalid"); }
        PublishRequest publish = requirePublish(session, publishId);
        if (publish.status() != PublishRequest.Status.AWAITING_CONFIRMATION
                || !state.consumeIfEquals(key, publishId.toString())) throw conflict("Publish is not awaiting confirmation");
        HostedDraft draft = draft(session, publish.draftId());
        String proposed = ContentProjectRevision.compute(draft.files());
        String proof = state.get(proofKey(publishId)).orElse(null);
        ContentRevision current = metadata.latestRevisionForSession(session.id()).orElse(null);
        if (!publish.proposedRevision().equals(proposed) || !proposed.equals(proof)
                || current == null || !publish.baseRevision().equals(current.revision())
                || !validation.validated(session.id(),draft.id(),proposed)) {
            reject(publish, session, "candidate-or-base-changed"); throw conflict("Candidate or live base changed before confirmation");
        }
        PublishRequest applying = copy(publish, PublishRequest.Status.APPLYING, null, publish.validationResult());
        metadata.savePublishRequest(applying);
        audit.record(session, AuditEvent.ActorType.OPERATOR, session.initiatorId(), AuditEvent.EventType.PUBLISH,
                AuditEvent.Outcome.SUCCESS, Map.of("operation", "confirmed", "publish-id", publishId), publishId.toString());
        return new PublishProject(Protocol.VERSION, publish.id(), session.id(), draft.id(), session.scope(),
                publish.baseRevision(), publish.proposedRevision(), draft.files());
    }

    public PublishStatusResponse complete(UUID sessionId, UUID publishId, String pluginLease, PublishApplyResult result) {
        EditorSession session = sessions.authenticatePlugin(sessionId, pluginLease); requireCapability(session);
        PublishRequest publish = requirePublish(session, publishId);
        if (result == null || result.protocolVersion() != Protocol.VERSION || !publishId.equals(result.publishId())
                || result.activeRevision() == null || !result.activeRevision().matches("[0-9a-f]{64}")
                || result.backupId() != null && result.backupId().length() > 256
                || result.error() != null && result.error().length() > 2_048) throw bad("Invalid publish result");
        if (publish.status() != PublishRequest.Status.APPLYING) return status(publish);
        boolean success = result.success() && publish.proposedRevision().equals(result.activeRevision());
        PublishRequest.Status finalStatus = success ? PublishRequest.Status.PUBLISHED : PublishRequest.Status.FAILED;
        String applyDetails = write(Map.of("success", success, "activeRevision", result.activeRevision(),
                "backupId", Objects.toString(result.backupId(), ""), "error", Objects.toString(result.error(), "")));
        String details;
        try {
            var combined = json.createObjectNode();
            combined.set("validation", json.readTree(publish.validationResult()));
            combined.set("apply", json.readTree(applyDetails));
            details = json.writeValueAsString(combined);
        } catch (Exception serializationFailure) { throw new IllegalStateException(serializationFailure); }
        PublishRequest completed = copy(publish, finalStatus, Instant.now(), details);
        metadata.savePublishRequest(completed); state.delete(proofKey(publishId));
        audit.record(session, AuditEvent.ActorType.INSTALLATION, session.installationId().toString(),
                AuditEvent.EventType.PUBLISH, success ? AuditEvent.Outcome.SUCCESS : AuditEvent.Outcome.FAILED,
                Map.of("operation", "completed", "publish-id", publishId, "status", finalStatus,
                        "active-revision", result.activeRevision(), "backup-id", Objects.toString(result.backupId(), "")),
                publishId.toString());
        return status(completed);
    }

    public PublishStatusResponse status(UUID sessionId, UUID publishId, String browserLease) {
        EditorSession session = sessions.authenticateBrowser(sessionId, browserLease); requireCapability(session);
        return status(requirePublish(session, publishId));
    }

    public RollbackProject beginRollback(UUID sessionId, UUID publishId, String pluginLease) {
        EditorSession session = sessions.authenticatePlugin(sessionId, pluginLease); requireCapability(session);
        limits.check("publish-rollback", sessionId.toString(), 10, properties.confirmationLifetime());
        PublishRequest publish = requirePublish(session, publishId);
        if (publish.status() != PublishRequest.Status.PUBLISHED) throw conflict("Only a published revision can be rolled back");
        String backupId = applyNode(publish).path("backupId").asText();
        if (backupId.isBlank() || backupId.length() > 256) throw conflict("Publication has no recoverable backup");
        UUID rollbackId = UUID.randomUUID();
        state.put(rollbackKey(rollbackId), publishId.toString(), properties.confirmationLifetime());
        metadata.savePublishRequest(copy(publish, PublishRequest.Status.ROLLING_BACK, publish.completedAt(),
                publish.validationResult()));
        audit.record(session, AuditEvent.ActorType.OPERATOR, session.initiatorId(), AuditEvent.EventType.ROLLBACK,
                AuditEvent.Outcome.SUCCESS, Map.of("operation", "requested", "publish-id", publishId,
                        "rollback-id", rollbackId, "target-revision", publish.rollbackRevision()), rollbackId.toString());
        return new RollbackProject(Protocol.VERSION, rollbackId, publishId, session.id(), session.scope(),
                publish.proposedRevision(), publish.rollbackRevision(), backupId);
    }

    public PublishStatusResponse completeRollback(UUID sessionId, UUID publishId, String pluginLease,
                                                  RollbackApplyResult result) {
        EditorSession session = sessions.authenticatePlugin(sessionId, pluginLease); requireCapability(session);
        PublishRequest publish = requirePublish(session, publishId);
        if (result == null || result.protocolVersion() != Protocol.VERSION || !publishId.equals(result.publishId())
                || result.rollbackId() == null || result.activeRevision() == null
                || !result.activeRevision().matches("[0-9a-f]{64}")
                || result.safetyBackupId() != null && result.safetyBackupId().length() > 256
                || result.error() != null && result.error().length() > 2_048) throw bad("Invalid rollback result");
        if (publish.status() != PublishRequest.Status.ROLLING_BACK) return status(publish);
        if (!state.consumeIfEquals(rollbackKey(result.rollbackId()), publishId.toString()))
            throw conflict("Rollback authorization expired or was already used");
        boolean success = result.success() && publish.rollbackRevision().equals(result.activeRevision());
        PublishRequest.Status finalStatus = success ? PublishRequest.Status.ROLLED_BACK : PublishRequest.Status.ROLLBACK_FAILED;
        String details;
        try {
            var root = json.readTree(publish.validationResult()).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).set("rollback", json.valueToTree(Map.of(
                    "success", success, "rollbackId", result.rollbackId(), "activeRevision", result.activeRevision(),
                    "safetyBackupId", Objects.toString(result.safetyBackupId(), ""),
                    "error", Objects.toString(result.error(), ""))));
            details = json.writeValueAsString(root);
        } catch (Exception error) { throw new IllegalStateException(error); }
        PublishRequest completed = copy(publish, finalStatus, Instant.now(), details); metadata.savePublishRequest(completed);
        audit.record(session, AuditEvent.ActorType.INSTALLATION, session.installationId().toString(),
                AuditEvent.EventType.ROLLBACK, success ? AuditEvent.Outcome.SUCCESS : AuditEvent.Outcome.FAILED,
                Map.of("operation", "completed", "publish-id", publishId, "rollback-id", result.rollbackId(),
                        "active-revision", result.activeRevision(), "safety-backup-id",
                        Objects.toString(result.safetyBackupId(), "")), result.rollbackId().toString());
        return status(completed);
    }

    private void reject(PublishRequest publish, EditorSession session, String reason) {
        metadata.savePublishRequest(copy(publish, PublishRequest.Status.REJECTED, Instant.now(),
                write(Map.of("valid", false, "reason", reason)))); state.delete(proofKey(publish.id()));
        audit.record(session, AuditEvent.ActorType.SYSTEM, "publish-guard", AuditEvent.EventType.PUBLISH,
                AuditEvent.Outcome.DENIED, Map.of("operation", "confirmation", "reason", reason), publish.id().toString());
    }
    private PublishStatusResponse status(PublishRequest publish) {
        PublishApplyResult result = null;
        if (publish.completedAt() != null && publish.validationResult() != null) try {
            var tree = json.readTree(publish.validationResult()); var apply = tree.has("rollback") ? tree.path("rollback")
                    : tree.has("apply") ? tree.path("apply") : tree;
            result = new PublishApplyResult(Protocol.VERSION, publish.id(), apply.path("success").asBoolean(),
                    apply.path("activeRevision").asText(publish.baseRevision()),
                    empty(apply.has("safetyBackupId") ? apply.path("safetyBackupId").asText() : apply.path("backupId").asText()),
                    empty(apply.path("error").asText()));
        } catch (Exception ignored) {}
        return new PublishStatusResponse(publish.id(), publish.draftId(), publish.baseRevision(), publish.proposedRevision(),
                publish.status().name(), publish.requestedAt(), publish.completedAt(),
                result == null ? null : result.activeRevision(), result == null ? null : result.backupId(),
                result == null ? null : result.error());
    }
    private HostedDraft draft(EditorSession session, UUID id) {
        HostedDraft draft = metadata.draft(id).orElse(null);
        if (draft == null || !draft.sessionId().equals(session.id()) || !draft.installationId().equals(session.installationId()))
            throw hidden();
        return draft;
    }
    private PublishRequest requirePublish(EditorSession session, UUID id) {
        PublishRequest value = metadata.publishRequest(id).orElse(null);
        if (value == null || !value.sessionId().equals(session.id()) || !value.installationId().equals(session.installationId()))
            throw hidden();
        return value;
    }
    private static void requireCapability(EditorSession session) {
        if (!session.capabilities().contains(Capability.CONTENT_PUBLISH))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This session cannot publish content");
    }
    private PublishRequest copy(PublishRequest source, PublishRequest.Status status, Instant completed, String validation) {
        return new PublishRequest(source.id(), source.installationId(), source.sessionId(), source.draftId(),
                source.baseRevision(), source.proposedRevision(), status, source.requestedAt(), completed,
                validation, source.semanticDiff(), source.rollbackRevision());
    }
    private String randomCode() { StringBuilder value = new StringBuilder(12); for (int i=0;i<12;i++) value.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]); return value.toString(); }
    private static String normalizeCode(String code) {
        String value = code.replace("-", "").trim().toUpperCase(Locale.ROOT);
        if (value.length() != 12 || !value.chars().allMatch(c -> new String(CODE_ALPHABET).indexOf(c) >= 0))
            throw denied("Confirmation code is invalid or expired");
        return value;
    }
    private static String codeKey(UUID sessionId, String code) { return "publish-code:" + sessionId + ":" + hash(code); }
    private static String proofKey(UUID id) { return "publish-proof:" + id; }
    private static String rollbackKey(UUID id) { return "rollback-proof:" + id; }
    private com.fasterxml.jackson.databind.JsonNode applyNode(PublishRequest publish) {
        try { var tree = json.readTree(publish.validationResult()); return tree.has("apply") ? tree.path("apply") : tree; }
        catch (Exception error) { throw conflict("Publication result metadata is unavailable"); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String empty(String value) { return value == null || value.isBlank() ? null : value; }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException denied(String message) { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message); }
    private static ResponseStatusException hidden() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Publish request does not exist"); }
}
