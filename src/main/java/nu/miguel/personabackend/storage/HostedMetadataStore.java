package nu.miguel.personabackend.storage;

import nu.miguel.personabackend.domain.*;

import java.time.Instant;
import java.util.*;

public interface HostedMetadataStore {
    Optional<byte[]> installationKey(UUID installationId);
    void registerInstallation(ServerInstallation installation);
    void touchInstallation(UUID installationId, Instant seenAt);
    void createSession(HostedEditorSession session);
    Optional<HostedEditorSession> session(UUID sessionId);
    void bindBrowser(BrowserIdentity identity);
    Optional<BrowserIdentity> browserIdentity(UUID sessionId);
    Set<nu.miguel.persona.editor.protocol.Capability> activeCapabilityGrants(UUID sessionId);
    void replaceCapabilityGrants(UUID sessionId, Collection<CapabilityGrant> grants, Instant revokedAt);
    void revokeSession(UUID sessionId, Instant revokedAt);
    void saveRevision(ContentRevision revision);
    Optional<ContentRevision> revision(UUID installationId, String revision);
    Optional<ContentRevision> latestRevision(UUID installationId);
    Optional<ContentRevision> latestRevisionForSession(UUID sessionId);
    void saveDraft(HostedDraft draft);
    Optional<HostedDraft> draft(UUID draftId);
    List<HostedDraft> drafts(UUID sessionId);
    boolean deleteDraft(UUID draftId, UUID sessionId);
    void savePublishRequest(PublishRequest request);
    Optional<PublishRequest> publishRequest(UUID id);
    Optional<PublishRequest> firstPublishRequest(UUID sessionId, PublishRequest.Status status);
    void saveSubscription(LiveSubscription subscription);
    Optional<LiveSubscription> subscription(UUID id);
    boolean deleteSubscription(UUID id,UUID sessionId);
    void appendAudit(AuditEvent event);
    RetentionResult purge(RetentionPolicy policy);
}
