package nu.miguel.personabackend.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.personabackend.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.persona.editor.protocol.EditorScope;
import nu.miguel.persona.editor.protocol.SessionRestrictions;

@Component
@ConditionalOnProperty(name = "persona.editor.infrastructure", havingValue = "postgres-redis", matchIfMissing = true)
public final class JdbcHostedMetadataStore implements HostedMetadataStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcHostedMetadataStore(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc; this.transactions = transactions; this.json = json;
    }

    @Override public Optional<byte[]> installationKey(UUID id) {
        return jdbc.sql("SELECT public_key FROM server_installation WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getBytes(1)).optional();
    }
    @Override public void registerInstallation(ServerInstallation value) {
        int changed = jdbc.sql("""
                INSERT INTO server_installation(id, public_key, created_at, last_seen_at)
                VALUES (:id, :key, :created, :seen)
                ON CONFLICT (id) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                WHERE server_installation.public_key = EXCLUDED.public_key
                """).param("id", value.id()).param("key", value.publicKey())
                .param("created", databaseTime(value.createdAt())).param("seen", databaseTime(value.lastSeenAt())).update();
        if (changed == 0 && installationKey(value.id()).filter(key -> Arrays.equals(key, value.publicKey())).isEmpty())
            throw new IllegalStateException("Installation identity changed");
    }
    @Override public void touchInstallation(UUID id, Instant seenAt) {
        jdbc.sql("UPDATE server_installation SET last_seen_at = :seen WHERE id = :id")
                .param("seen", databaseTime(seenAt)).param("id", id).update();
    }
    @Override public void createSession(HostedEditorSession value) {
        jdbc.sql("""
                INSERT INTO editor_session(id, installation_id, initiator_id, initiator_name, content_scope,
                  restrictions, requested_capabilities, created_at, expires_at)
                VALUES (:id, :installation, :initiator, :name, :scope, CAST(:restrictions AS jsonb),
                  CAST(:capabilities AS jsonb), :created, :expires)
                """).param("id", value.id()).param("installation", value.installationId())
                .param("initiator", value.initiatorId()).param("name", value.initiatorName())
                .param("scope", value.scope().name()).param("restrictions", json(value.restrictions()))
                .param("capabilities", json(value.requestedCapabilities().stream().map(Enum::name).sorted().toList()))
                .param("created", databaseTime(value.createdAt())).param("expires", databaseTime(value.expiresAt())).update();
    }
    @Override public Optional<HostedEditorSession> session(UUID id) {
        return jdbc.sql("SELECT * FROM editor_session WHERE id = :id").param("id", id).query((rs, row) -> {
            try {
                SessionRestrictions restrictions = json.readValue(rs.getString("restrictions"), SessionRestrictions.class);
                List<String> names = json.readValue(rs.getString("requested_capabilities"), new TypeReference<>() {});
                Set<Capability> capabilities = names.stream().map(Capability::valueOf)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                return new HostedEditorSession(rs.getObject("id", UUID.class), rs.getObject("installation_id", UUID.class),
                        rs.getString("initiator_id"), rs.getString("initiator_name"),
                        EditorScope.valueOf(rs.getString("content_scope")), restrictions, capabilities,
                        instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "revoked_at"));
            } catch (JsonProcessingException error) { throw new SQLException("Invalid stored session JSON", error); }
        }).optional();
    }
    @Override public void bindBrowser(BrowserIdentity value) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO browser_identity(session_id, public_key, bound_at) VALUES (:session, :key, :bound)
                    ON CONFLICT (session_id) DO NOTHING
                    """).param("session", value.sessionId()).param("key", value.publicKey())
                    .param("bound", databaseTime(value.boundAt())).update();
            jdbc.sql("UPDATE editor_session SET browser_verified = true, browser_description = :description WHERE id = :id")
                    .param("description", value.description()).param("id", value.sessionId()).update();
        });
    }
    @Override public Optional<BrowserIdentity> browserIdentity(UUID id) {
        return jdbc.sql("""
                SELECT b.session_id, b.public_key, s.browser_description, b.bound_at
                FROM browser_identity b JOIN editor_session s ON s.id = b.session_id WHERE b.session_id = :id
                """).param("id", id).query((rs, row) -> new BrowserIdentity(rs.getObject("session_id", UUID.class),
                rs.getBytes("public_key"), rs.getString("browser_description"), instant(rs, "bound_at"))).optional();
    }
    @Override public Set<Capability> activeCapabilityGrants(UUID id) {
        return Set.copyOf(jdbc.sql("SELECT capability FROM capability_grant WHERE session_id = :id AND revoked_at IS NULL")
                .param("id", id).query((rs, row) -> Capability.valueOf(rs.getString(1))).list());
    }
    @Override public void replaceCapabilityGrants(UUID id, Collection<CapabilityGrant> values, Instant revokedAt) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("UPDATE capability_grant SET revoked_at = :revoked WHERE session_id = :id AND revoked_at IS NULL")
                    .param("revoked", databaseTime(revokedAt)).param("id", id).update();
            for (CapabilityGrant value : values)
                jdbc.sql("INSERT INTO capability_grant(id, session_id, capability, granted_at) VALUES (:grant, :id, :capability, :at)")
                        .param("grant", UUID.randomUUID()).param("id", id).param("capability", value.capability().name())
                        .param("at", databaseTime(value.grantedAt())).update();
        });
    }
    @Override public void revokeSession(UUID id, Instant revokedAt) {
        jdbc.sql("UPDATE editor_session SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
                .param("at", databaseTime(revokedAt)).param("id", id).update();
    }
    @Override public void saveRevision(ContentRevision value) {
        transactions.executeWithoutResult(status -> {
            int inserted = jdbc.sql("""
                    INSERT INTO content_revision(installation_id, revision, content_format_version, created_at,
                      signed_created_at, source_session_id, signature)
                    VALUES (:installation, :revision, :format, :created, :signedCreated, :session, :signature)
                    ON CONFLICT (installation_id, revision) DO NOTHING
                    """).param("installation", value.installationId()).param("revision", value.revision())
                    .param("format", value.contentFormatVersion()).param("created", databaseTime(value.createdAt()))
                    .param("signedCreated", value.createdAt().toString())
                    .param("session", value.sourceSessionId()).param("signature", value.signature()).update();
            if (inserted > 0) for (ContentFile file : value.files())
                jdbc.sql("""
                        INSERT INTO content_revision_file(installation_id, revision, path, sha256, content)
                        VALUES (:installation, :revision, :path, :sha256, :content)
                        """).param("installation", value.installationId()).param("revision", value.revision())
                        .param("path", file.path()).param("sha256", file.sha256()).param("content", file.content()).update();
            if(value.sourceSessionId()!=null)jdbc.sql("""
                    INSERT INTO content_snapshot_envelope(session_id, installation_id, revision, created_at,
                      signed_created_at, content_format_version, signature)
                    VALUES (:session, :installation, :revision, :created, :signedCreated, :format, :signature)
                    ON CONFLICT (session_id) DO UPDATE SET installation_id=EXCLUDED.installation_id,
                      revision=EXCLUDED.revision, created_at=EXCLUDED.created_at,
                      signed_created_at=EXCLUDED.signed_created_at,
                      content_format_version=EXCLUDED.content_format_version, signature=EXCLUDED.signature
                    """).param("session",value.sourceSessionId()).param("installation",value.installationId())
                    .param("revision",value.revision()).param("created",databaseTime(value.createdAt()))
                    .param("signedCreated",value.createdAt().toString()).param("format",value.contentFormatVersion())
                    .param("signature",value.signature()).update();
        });
    }
    @Override public Optional<ContentRevision> revision(UUID id, String revision) {
        return jdbc.sql("""
                SELECT r.installation_id, r.revision, r.content_format_version, r.created_at, r.signed_created_at, r.source_session_id,
                  encode(i.public_key, 'base64') AS public_key, r.signature
                FROM content_revision r JOIN server_installation i ON i.id = r.installation_id
                WHERE r.installation_id = :id AND r.revision = :revision
                """).param("id", id).param("revision", revision).query(this::revisionRow).optional()
                .map(value -> withFiles(value, revisionFiles(id, revision)));
    }
    @Override public Optional<ContentRevision> latestRevision(UUID id) {
        return jdbc.sql("""
                SELECT r.installation_id, r.revision, r.content_format_version, r.created_at, r.signed_created_at, r.source_session_id,
                  encode(i.public_key, 'base64') AS public_key, r.signature
                FROM content_revision r JOIN server_installation i ON i.id = r.installation_id
                WHERE r.installation_id = :id ORDER BY r.created_at DESC LIMIT 1
                """).param("id", id).query(this::revisionRow).optional()
                .map(value -> withFiles(value, revisionFiles(id, value.revision())));
    }
    @Override public Optional<ContentRevision> latestRevisionForSession(UUID sessionId) {
        return jdbc.sql("""
                SELECT r.installation_id, r.revision, e.content_format_version, e.created_at, e.signed_created_at,
                  e.session_id AS source_session_id, encode(i.public_key, 'base64') AS public_key, e.signature
                FROM content_snapshot_envelope e
                JOIN content_revision r ON r.installation_id=e.installation_id AND r.revision=e.revision
                JOIN server_installation i ON i.id = r.installation_id
                WHERE e.session_id = :session
                """).param("session", sessionId).query(this::revisionRow).optional()
                .map(value -> withFiles(value, revisionFiles(value.installationId(), value.revision())));
    }
    @Override public void saveDraft(HostedDraft value) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO draft(id, installation_id, session_id, author_id, author_name, base_revision, created_at, updated_at)
                    VALUES (:id, :installation, :session, :author, :name, :base, :created, :updated)
                    ON CONFLICT (id) DO UPDATE SET updated_at = EXCLUDED.updated_at
                    WHERE draft.session_id = EXCLUDED.session_id AND draft.base_revision = EXCLUDED.base_revision
                    """).param("id", value.id()).param("installation", value.installationId())
                    .param("session", value.sessionId()).param("author", value.authorId()).param("name", value.authorName())
                    .param("base", value.baseRevision()).param("created", databaseTime(value.createdAt())).param("updated", databaseTime(value.updatedAt())).update();
            jdbc.sql("DELETE FROM draft_file WHERE draft_id = :id").param("id", value.id()).update();
            for (ContentFile file : value.files()) jdbc.sql("""
                    INSERT INTO draft_file(draft_id, path, sha256, content) VALUES (:id, :path, :sha256, :content)
                    """).param("id", value.id()).param("path", file.path()).param("sha256", file.sha256())
                    .param("content", file.content()).update();
        });
    }
    @Override public Optional<HostedDraft> draft(UUID id) {
        return jdbc.sql("SELECT * FROM draft WHERE id = :id").param("id", id).query(this::draftRow).optional()
                .map(value -> withFiles(value, draftFiles(value.id())));
    }
    @Override public List<HostedDraft> drafts(UUID sessionId) {
        return jdbc.sql("SELECT * FROM draft WHERE session_id = :session ORDER BY updated_at DESC")
                .param("session", sessionId).query(this::draftRow).list().stream()
                .map(value -> withFiles(value, draftFiles(value.id()))).toList();
    }
    @Override public boolean deleteDraft(UUID id, UUID sessionId) {
        return jdbc.sql("DELETE FROM draft WHERE id = :id AND session_id = :session")
                .param("id", id).param("session", sessionId).update() == 1;
    }
    @Override public void savePublishRequest(PublishRequest value) {
        jdbc.sql("""
                INSERT INTO publish_request(id, installation_id, session_id, draft_id, base_revision,
                  proposed_revision, status, requested_at, completed_at, validation_result, semantic_diff, rollback_revision)
                VALUES (:id, :installation, :session, :draft, :base, :proposed, :status, :requested, :completed,
                  CAST(:validation AS jsonb), CAST(:diff AS jsonb), :rollback)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, completed_at = EXCLUDED.completed_at,
                  validation_result = EXCLUDED.validation_result, semantic_diff = EXCLUDED.semantic_diff,
                  rollback_revision = EXCLUDED.rollback_revision
                """).param("id", value.id()).param("installation", value.installationId())
                .param("session", value.sessionId()).param("draft", value.draftId()).param("base", value.baseRevision())
                .param("proposed", value.proposedRevision()).param("status", value.status().name())
                .param("requested", databaseTime(value.requestedAt())).param("completed", databaseTime(value.completedAt()))
                .param("validation", jsonOrNull(value.validationResult())).param("diff", jsonOrNull(value.semanticDiff()))
                .param("rollback", value.rollbackRevision()).update();
    }
    @Override public Optional<PublishRequest> publishRequest(UUID id) {
        return jdbc.sql("SELECT * FROM publish_request WHERE id = :id").param("id", id)
                .query(this::publishRow).optional();
    }
    @Override public Optional<PublishRequest> firstPublishRequest(UUID sessionId, PublishRequest.Status status) {
        return jdbc.sql("SELECT * FROM publish_request WHERE session_id = :session AND status = :status ORDER BY requested_at ASC LIMIT 1")
                .param("session", sessionId).param("status", status.name()).query(this::publishRow).optional();
    }
    @Override public void saveSubscription(LiveSubscription value) {
        jdbc.sql("""
                INSERT INTO subscription_definition(id, session_id, subscription_type, filters, created_at, expires_at)
                VALUES (:id, :session, :type, CAST(:filters AS jsonb), :created, :expires)
                ON CONFLICT (id) DO UPDATE SET filters = EXCLUDED.filters, expires_at = EXCLUDED.expires_at
                """).param("id", value.id()).param("session", value.sessionId()).param("type", value.type())
                .param("filters", json(value.filters())).param("created", databaseTime(value.createdAt())).param("expires", databaseTime(value.expiresAt())).update();
    }
    @Override public Optional<LiveSubscription> subscription(UUID id){return jdbc.sql("SELECT id, session_id, subscription_type, filters, created_at, expires_at FROM subscription_definition WHERE id = :id")
            .param("id",id).query((rs,row)->new LiveSubscription(rs.getObject("id",UUID.class),rs.getObject("session_id",UUID.class),rs.getString("subscription_type"),readMap(rs.getString("filters")),instant(rs,"created_at"),instant(rs,"expires_at"))).optional();}
    @Override public boolean deleteSubscription(UUID id,UUID sessionId){return jdbc.sql("DELETE FROM subscription_definition WHERE id = :id AND session_id = :session").param("id",id).param("session",sessionId).update()>0;}
    @Override public void appendAudit(AuditEvent value) {
        jdbc.sql("""
                INSERT INTO audit_event(id, installation_id, session_id, actor_type, actor_id, event_type,
                  outcome, occurred_at, details, correlation_id)
                VALUES (:id, :installation, :session, :actor_type, :actor_id, :event_type, :outcome,
                  :occurred, CAST(:details AS jsonb), :correlation)
                """).param("id", value.id()).param("installation", value.installationId())
                .param("session", value.sessionId()).param("actor_type", value.actorType().name())
                .param("actor_id", value.actorId()).param("event_type", value.eventType().name())
                .param("outcome", value.outcome().name()).param("occurred", databaseTime(value.occurredAt()))
                .param("details", json(value.details())).param("correlation", value.correlationId()).update();
    }
    @Override public RetentionResult purge(RetentionPolicy policy) {
        int[] counts = new int[5];
        transactions.executeWithoutResult(status -> {
            counts[2] = jdbc.sql("""
                    DELETE FROM publish_request WHERE requested_at < :before
                    AND status IN ('PUBLISHED','REJECTED','FAILED','ROLLED_BACK','ROLLBACK_FAILED')
                    """).param("before", databaseTime(policy.publishesBefore())).update();
            counts[1] = jdbc.sql("""
                    DELETE FROM draft d WHERE d.updated_at < :before
                    AND NOT EXISTS (SELECT 1 FROM publish_request p WHERE p.draft_id = d.id)
                    """).param("before", databaseTime(policy.draftsBefore())).update();
            counts[0] = jdbc.sql("""
                    WITH ranked AS (
                      SELECT installation_id, revision, created_at,
                        row_number() OVER (PARTITION BY installation_id ORDER BY created_at DESC, revision DESC) AS position
                      FROM content_revision
                    ), removable AS (
                      SELECT r.installation_id, r.revision FROM ranked r
                      WHERE r.position > 1 AND (r.created_at < :before OR r.position > :maximum)
                      AND NOT EXISTS (SELECT 1 FROM draft d WHERE d.installation_id = r.installation_id AND d.base_revision = r.revision)
                      AND NOT EXISTS (SELECT 1 FROM publish_request p WHERE p.installation_id = r.installation_id
                        AND (p.base_revision = r.revision OR p.proposed_revision = r.revision OR p.rollback_revision = r.revision))
                    )
                    DELETE FROM content_revision c USING removable r
                    WHERE c.installation_id = r.installation_id AND c.revision = r.revision
                    """).param("before", databaseTime(policy.revisionsBefore()))
                    .param("maximum", policy.maximumRevisionsPerInstallation()).update();
            counts[4] = jdbc.sql("DELETE FROM subscription_definition WHERE expires_at < :before")
                    .param("before", databaseTime(policy.subscriptionsBefore())).update();
            counts[3] = jdbc.sql("DELETE FROM audit_event WHERE occurred_at < :before")
                    .param("before", databaseTime(policy.auditBefore())).update();
        });
        return new RetentionResult(counts[0], counts[1], counts[2], counts[3], counts[4], 0);
    }

    private ContentRevision revisionRow(ResultSet rs, int row) throws SQLException {
        String signedCreatedAt=rs.getString("signed_created_at");
        return new ContentRevision(rs.getObject("installation_id", UUID.class), rs.getString("revision"),
                rs.getInt("content_format_version"), signedCreatedAt==null?instant(rs,"created_at"):Instant.parse(signedCreatedAt),
                rs.getObject("source_session_id", UUID.class), rs.getString("public_key"),
                rs.getString("signature"), List.of());
    }
    private HostedDraft draftRow(ResultSet rs, int row) throws SQLException {
        return new HostedDraft(rs.getObject("id", UUID.class), rs.getObject("installation_id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getString("author_id"), rs.getString("author_name"),
                rs.getString("base_revision"), instant(rs, "created_at"), instant(rs, "updated_at"), List.of());
    }
    private PublishRequest publishRow(ResultSet rs, int row) throws SQLException {
        return new PublishRequest(rs.getObject("id", UUID.class), rs.getObject("installation_id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getObject("draft_id", UUID.class),
                rs.getString("base_revision"), rs.getString("proposed_revision"),
                PublishRequest.Status.valueOf(rs.getString("status")), instant(rs, "requested_at"),
                instant(rs, "completed_at"), rs.getString("validation_result"),
                rs.getString("semantic_diff"), rs.getString("rollback_revision"));
    }
    private List<ContentFile> revisionFiles(UUID id, String revision) {
        return jdbc.sql("SELECT path, sha256, content FROM content_revision_file WHERE installation_id = :id AND revision = :revision ORDER BY path")
                .param("id", id).param("revision", revision)
                .query((rs, row) -> new ContentFile(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }
    private List<ContentFile> draftFiles(UUID id) {
        return jdbc.sql("SELECT path, sha256, content FROM draft_file WHERE draft_id = :id ORDER BY path")
                .param("id", id).query((rs, row) -> new ContentFile(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }
    private static ContentRevision withFiles(ContentRevision value, List<ContentFile> files) {
        return new ContentRevision(value.installationId(), value.revision(), value.contentFormatVersion(), value.createdAt(),
                value.sourceSessionId(), value.installationPublicKey(), value.signature(), files);
    }
    private static HostedDraft withFiles(HostedDraft value, List<ContentFile> files) {
        return new HostedDraft(value.id(), value.installationId(), value.sessionId(), value.authorId(), value.authorName(),
                value.baseRevision(), value.createdAt(), value.updatedAt(), files);
    }
    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Value is not JSON serializable", error); }
    }
    @SuppressWarnings("unchecked")private Map<String,Object> readMap(String value){try{return json.readValue(value,Map.class);}catch(JsonProcessingException error){throw new IllegalStateException("Stored JSON is invalid",error);}}
    private static String jsonOrNull(String value) { return value == null ? "null" : value; }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static OffsetDateTime databaseTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
