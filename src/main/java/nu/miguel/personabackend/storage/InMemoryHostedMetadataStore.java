package nu.miguel.personabackend.storage;

import nu.miguel.personabackend.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "persona.editor.infrastructure", havingValue = "memory")
public final class InMemoryHostedMetadataStore implements HostedMetadataStore {
    private final Map<UUID, ServerInstallation> installations = new ConcurrentHashMap<>();
    private final Map<UUID, HostedEditorSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, BrowserIdentity> browsers = new ConcurrentHashMap<>();
    private final Map<UUID, List<CapabilityGrant>> grants = new ConcurrentHashMap<>();
    private final Map<String, ContentRevision> revisions = new ConcurrentHashMap<>();
    private final Map<UUID, HostedDraft> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, PublishRequest> publishes = new ConcurrentHashMap<>();
    private final Map<UUID, LiveSubscription> subscriptions = new ConcurrentHashMap<>();
    private final List<AuditEvent> audit = Collections.synchronizedList(new ArrayList<>());

    @Override public Optional<byte[]> installationKey(UUID id) {
        ServerInstallation value = installations.get(id);
        return value == null ? Optional.empty() : Optional.of(value.publicKey());
    }
    @Override public void registerInstallation(ServerInstallation value) {
        installations.compute(value.id(), (id, existing) -> {
            if (existing != null && !Arrays.equals(existing.publicKey(), value.publicKey()))
                throw new IllegalStateException("Installation identity changed");
            return existing == null ? value : new ServerInstallation(id, existing.publicKey(), existing.createdAt(), value.lastSeenAt());
        });
    }
    @Override public void touchInstallation(UUID id, Instant seenAt) {
        installations.computeIfPresent(id, (ignored, value) ->
                new ServerInstallation(value.id(), value.publicKey(), value.createdAt(), seenAt));
    }
    @Override public void createSession(HostedEditorSession value) { sessions.put(value.id(), value); }
    @Override public Optional<HostedEditorSession> session(UUID id) { return Optional.ofNullable(sessions.get(id)); }
    @Override public void bindBrowser(BrowserIdentity value) { browsers.put(value.sessionId(), value); }
    @Override public Optional<BrowserIdentity> browserIdentity(UUID id) { return Optional.ofNullable(browsers.get(id)); }
    @Override public Set<nu.miguel.persona.editor.protocol.Capability> activeCapabilityGrants(UUID id) {
        List<CapabilityGrant> values = grants.getOrDefault(id, List.of());
        return values.stream().filter(value -> value.revokedAt() == null).map(CapabilityGrant::capability)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public void replaceCapabilityGrants(UUID id, Collection<CapabilityGrant> values, Instant revokedAt) {
        grants.compute(id, (ignored, existing) -> {
            List<CapabilityGrant> result = new ArrayList<>();
            if (existing != null) existing.forEach(value -> result.add(value.revokedAt() == null
                    ? new CapabilityGrant(value.sessionId(), value.capability(), value.grantedAt(), revokedAt) : value));
            result.addAll(values); return List.copyOf(result);
        });
    }
    @Override public void revokeSession(UUID id, Instant revokedAt) {
        sessions.computeIfPresent(id, (ignored, value) -> new HostedEditorSession(value.id(), value.installationId(),
                value.initiatorId(), value.initiatorName(), value.scope(), value.restrictions(),
                value.requestedCapabilities(), value.createdAt(), value.expiresAt(), revokedAt));
    }
    @Override public void saveRevision(ContentRevision value) { revisions.putIfAbsent(key(value.installationId(), value.revision()), value); }
    @Override public Optional<ContentRevision> revision(UUID id, String revision) { return Optional.ofNullable(revisions.get(key(id, revision))); }
    @Override public Optional<ContentRevision> latestRevision(UUID id) {
        return revisions.values().stream().filter(value -> value.installationId().equals(id))
                .max(Comparator.comparing(ContentRevision::createdAt));
    }
    @Override public Optional<ContentRevision> latestRevisionForSession(UUID sessionId) {
        return revisions.values().stream().filter(value -> sessionId.equals(value.sourceSessionId()))
                .max(Comparator.comparing(ContentRevision::createdAt));
    }
    @Override public void saveDraft(HostedDraft value) { drafts.put(value.id(), value); }
    @Override public Optional<HostedDraft> draft(UUID id) { return Optional.ofNullable(drafts.get(id)); }
    @Override public List<HostedDraft> drafts(UUID sessionId) {
        return drafts.values().stream().filter(value -> value.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(HostedDraft::updatedAt).reversed()).toList();
    }
    @Override public boolean deleteDraft(UUID id, UUID sessionId) {
        HostedDraft value = drafts.get(id);
        return value != null && value.sessionId().equals(sessionId) && drafts.remove(id, value);
    }
    @Override public void savePublishRequest(PublishRequest value) { publishes.put(value.id(), value); }
    @Override public Optional<PublishRequest> publishRequest(UUID id) { return Optional.ofNullable(publishes.get(id)); }
    @Override public void saveSubscription(LiveSubscription value) { subscriptions.put(value.id(), value); }
    @Override public Optional<LiveSubscription> subscription(UUID id){return Optional.ofNullable(subscriptions.get(id));}
    @Override public boolean deleteSubscription(UUID id,UUID sessionId){LiveSubscription value=subscriptions.get(id);return value!=null&&value.sessionId().equals(sessionId)&&subscriptions.remove(id,value);}
    @Override public void appendAudit(AuditEvent value) { audit.add(value); }
    @Override public RetentionResult purge(RetentionPolicy policy) {
        Set<UUID> protectedDrafts = publishes.values().stream().map(PublishRequest::draftId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        int publishCount = remove(publishes, value -> value.requestedAt().isBefore(policy.publishesBefore())
                && terminal(value.status()));
        protectedDrafts = publishes.values().stream().map(PublishRequest::draftId).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> finalProtectedDrafts = protectedDrafts;
        int draftCount = remove(drafts, value -> value.updatedAt().isBefore(policy.draftsBefore())
                && !finalProtectedDrafts.contains(value.id()));
        Set<String> protectedRevisions = new HashSet<>();
        drafts.values().forEach(value -> protectedRevisions.add(key(value.installationId(), value.baseRevision())));
        publishes.values().forEach(value -> { protectedRevisions.add(key(value.installationId(), value.baseRevision()));
            protectedRevisions.add(key(value.installationId(), value.proposedRevision()));
            if (value.rollbackRevision() != null) protectedRevisions.add(key(value.installationId(), value.rollbackRevision())); });
        Set<String> keep = revisions.values().stream().collect(java.util.stream.Collectors.groupingBy(ContentRevision::installationId))
                .values().stream().flatMap(values -> values.stream().sorted(Comparator.comparing(ContentRevision::createdAt).reversed())
                        .limit(policy.maximumRevisionsPerInstallation()).map(value -> key(value.installationId(), value.revision())))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> latest = revisions.values().stream().collect(java.util.stream.Collectors.groupingBy(ContentRevision::installationId))
                .values().stream().flatMap(values -> values.stream().max(Comparator.comparing(ContentRevision::createdAt)).stream())
                .map(value -> key(value.installationId(), value.revision())).collect(java.util.stream.Collectors.toSet());
        int revisionCount = remove(revisions, value -> !protectedRevisions.contains(key(value.installationId(), value.revision()))
                && !latest.contains(key(value.installationId(), value.revision()))
                && (value.createdAt().isBefore(policy.revisionsBefore()) || !keep.contains(key(value.installationId(), value.revision()))));
        int subscriptionCount = remove(subscriptions, value -> value.expiresAt().isBefore(policy.subscriptionsBefore()));
        int auditCount;
        synchronized (audit) { int before = audit.size(); audit.removeIf(value -> value.occurredAt().isBefore(policy.auditBefore())); auditCount = before - audit.size(); }
        return new RetentionResult(revisionCount, draftCount, publishCount, auditCount, subscriptionCount, 0);
    }

    public List<AuditEvent> auditEvents() { return List.copyOf(audit); }
    private static String key(UUID id, String revision) { return id + ":" + revision; }
    private static <K,V> int remove(Map<K,V> values, java.util.function.Predicate<V> predicate) {
        int before = values.size(); values.entrySet().removeIf(entry -> predicate.test(entry.getValue())); return before - values.size();
    }
    private static boolean terminal(PublishRequest.Status status) { return Set.of(PublishRequest.Status.PUBLISHED,
            PublishRequest.Status.REJECTED, PublishRequest.Status.FAILED, PublishRequest.Status.ROLLED_BACK,
            PublishRequest.Status.ROLLBACK_FAILED).contains(status); }
}
