package nu.miguel.personabackend.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcHostedMetadataStoreTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    private JdbcHostedMetadataStore store;
    private JdbcTemplate sql;

    @BeforeEach void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        sql = new JdbcTemplate(dataSource);
        store = new JdbcHostedMetadataStore(JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
    }

    @Test void migrationCreatesExplicitDomainTablesAndDurablyRoundTripsRawAuthoringData() throws Exception {
        for (String table : List.of("server_installation", "editor_session", "browser_identity",
                "capability_grant", "content_revision", "content_revision_file", "draft", "draft_file",
                "publish_request", "subscription_definition", "audit_event"))
            assertEquals(1, sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=?", Integer.class, table), table);

        UUID installationId = UUID.randomUUID(), sessionId = UUID.randomUUID();
        byte[] installationKey = new byte[44]; Arrays.fill(installationKey, (byte) 7);
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        store.registerInstallation(new ServerInstallation(installationId, installationKey, now, now));
        store.createSession(new HostedEditorSession(sessionId, installationId, "console", "CONSOLE",
                EditorScope.ALL, SessionRestrictions.UNRESTRICTED,
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT), now, now.plusSeconds(300), null));
        store.bindBrowser(new BrowserIdentity(sessionId, new byte[44], "Test Browser", now));
        store.replaceCapabilityGrants(sessionId, List.of(
                new CapabilityGrant(sessionId, Capability.CONTENT_VIEW, now, null),
                new CapabilityGrant(sessionId, Capability.DRAFT_EDIT, now, null)), now);

        String yaml = "# author comment\nid: story:tree\nextension-owned:\n  future: &value yes\ncopy: *value\n";
        ContentFile file = file("behaviors/story.yml", yaml);
        String revisionId = file.sha256();
        ContentRevision revision = new ContentRevision(installationId, revisionId, 1, now, sessionId,
                Base64.getEncoder().encodeToString(installationKey), "signed-revision", List.of(file));
        store.saveRevision(revision);
        UUID draftId = UUID.randomUUID();
        store.saveDraft(new HostedDraft(draftId, installationId, sessionId, "console", "CONSOLE",
                revisionId, now, now.plusSeconds(1), List.of(file)));

        assertArrayEquals(installationKey, store.installationKey(installationId).orElseThrow());
        assertEquals(yaml, store.latestRevision(installationId).orElseThrow().files().getFirst().content());
        assertEquals(yaml, store.draft(draftId).orElseThrow().files().getFirst().content());
        assertEquals(List.of(draftId), store.drafts(sessionId).stream().map(HostedDraft::id).toList());
    }

    @Test void recordsPublishSubscriptionAndStructuredAuditWithoutRuntimeState() {
        UUID installationId = UUID.randomUUID(), sessionId = UUID.randomUUID(), draftId = UUID.randomUUID();
        Instant now = Instant.now();
        byte[] key = new byte[44]; Arrays.fill(key, (byte) 3);
        store.registerInstallation(new ServerInstallation(installationId, key, now, now));
        store.createSession(new HostedEditorSession(sessionId, installationId, "console", "CONSOLE",
                EditorScope.CONTENT, SessionRestrictions.UNRESTRICTED, Set.of(Capability.CONTENT_VIEW),
                now, now.plusSeconds(300), null));
        UUID publishId = UUID.randomUUID();
        store.savePublishRequest(new PublishRequest(publishId, installationId, sessionId, null,
                "a".repeat(64), "b".repeat(64), PublishRequest.Status.REQUESTED, now, null,
                null, null, null));
        store.saveSubscription(new LiveSubscription(UUID.randomUUID(), sessionId, "behavior-runtime",
                Map.of("npc", "story:keeper"), now, now.plusSeconds(60)));
        store.appendAudit(new AuditEvent(UUID.randomUUID(), installationId, sessionId,
                AuditEvent.ActorType.INSTALLATION, installationId.toString(), AuditEvent.EventType.CONNECTION,
                AuditEvent.Outcome.SUCCESS, now, Map.of("transport", "websocket"), "trace-1"));

        assertEquals(1, sql.queryForObject("SELECT count(*) FROM publish_request", Integer.class));
        assertEquals(PublishRequest.Status.REQUESTED, store.publishRequest(publishId).orElseThrow().status());
        assertEquals(publishId, store.firstPublishRequest(sessionId, PublishRequest.Status.REQUESTED).orElseThrow().id());
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM subscription_definition", Integer.class));
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM audit_event", Integer.class));
        assertEquals(0, sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('content_revision','audit_event') AND column_name LIKE '%runtime%'", Integer.class));
    }

    @Test void preservesTheExactNanosecondTimestampCoveredByARevisionSignature() {
        UUID installationId=UUID.randomUUID(),sessionId=UUID.randomUUID();
        Instant signedAt=Instant.parse("2026-08-17T12:00:00.123456789Z");byte[] key=new byte[44];
        store.registerInstallation(new ServerInstallation(installationId,key,signedAt,signedAt));
        store.createSession(new HostedEditorSession(sessionId,installationId,"console","CONSOLE",EditorScope.ALL,
                SessionRestrictions.UNRESTRICTED,Set.of(Capability.CONTENT_VIEW),signedAt,signedAt.plusSeconds(300),null));
        ContentRevision revision=new ContentRevision(installationId,"a".repeat(64),1,signedAt,sessionId,
                Base64.getEncoder().encodeToString(key),"signature-over-exact-instant",List.of());

        store.saveRevision(revision);

        ContentRevision restored=store.latestRevisionForSession(sessionId).orElseThrow();
        assertEquals(signedAt,restored.createdAt());
        assertEquals("signature-over-exact-instant",restored.signature());
    }

    @Test void preservesSeparateSignedEnvelopesWhenTwoSessionsUploadIdenticalContent() {
        UUID installation=UUID.randomUUID(),firstSession=UUID.randomUUID(),secondSession=UUID.randomUUID();
        byte[] key=new byte[44];Instant firstAt=Instant.parse("2026-08-17T12:00:00.123456789Z"),secondAt=firstAt.plusNanos(7);
        store.registerInstallation(new ServerInstallation(installation,key,firstAt,firstAt));
        for(UUID session:List.of(firstSession,secondSession))store.createSession(new HostedEditorSession(session,installation,
                "console","CONSOLE",EditorScope.ALL,SessionRestrictions.UNRESTRICTED,Set.of(Capability.CONTENT_VIEW),firstAt,firstAt.plusSeconds(300),null));
        ContentRevision first=new ContentRevision(installation,"b".repeat(64),1,firstAt,firstSession,"key","first-signature",List.of());
        ContentRevision second=new ContentRevision(installation,"b".repeat(64),2,secondAt,secondSession,"key","second-signature",List.of());

        store.saveRevision(first);store.saveRevision(second);

        assertEquals(firstAt,store.latestRevisionForSession(firstSession).orElseThrow().createdAt());
        assertEquals("first-signature",store.latestRevisionForSession(firstSession).orElseThrow().signature());
        assertEquals(secondAt,store.latestRevisionForSession(secondSession).orElseThrow().createdAt());
        assertEquals(2,store.latestRevisionForSession(secondSession).orElseThrow().contentFormatVersion());
        assertEquals("second-signature",store.latestRevisionForSession(secondSession).orElseThrow().signature());
    }

    @Test void purgesExpiredRowsTransactionallyButRetainsLatestRevision() {
        UUID installation = UUID.randomUUID(), session = UUID.randomUUID(), draft = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-17T12:00:00Z"), old = now.minus(Duration.ofDays(400));
        byte[] key = new byte[44]; store.registerInstallation(new ServerInstallation(installation, key, old, now));
        store.createSession(new HostedEditorSession(session, installation, "console", "CONSOLE", EditorScope.ALL,
                SessionRestrictions.UNRESTRICTED, Set.of(Capability.CONTENT_VIEW), old, now.plusSeconds(300), null));
        ContentRevision expired = new ContentRevision(installation, "1".repeat(64), 1, old, session, "key", "sig", List.of());
        ContentRevision latest = new ContentRevision(installation, "2".repeat(64), 1, now, session, "key", "sig", List.of());
        store.saveRevision(expired); store.saveRevision(latest);
        store.saveDraft(new HostedDraft(draft, installation, session, "console", "CONSOLE", expired.revision(), old, old, List.of()));
        store.savePublishRequest(new PublishRequest(UUID.randomUUID(), installation, session, draft,
                expired.revision(), "3".repeat(64), PublishRequest.Status.PUBLISHED, old, old, "{}", "{}", expired.revision()));
        store.saveSubscription(new LiveSubscription(UUID.randomUUID(), session, "runtime", Map.of(), old, old.plusSeconds(1)));
        store.appendAudit(new AuditEvent(UUID.randomUUID(), installation, session, AuditEvent.ActorType.SYSTEM,
                "test", AuditEvent.EventType.CONNECTION, AuditEvent.Outcome.SUCCESS, old, Map.of(), "old"));

        RetentionResult result = store.purge(new RetentionPolicy(now.minus(Duration.ofDays(30)),
                now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(180)), now.minus(Duration.ofDays(365)), now, 100));

        assertEquals(new RetentionResult(1, 1, 1, 1, 1, 0), result);
        assertTrue(store.revision(installation, latest.revision()).isPresent());
    }

    private static ContentFile file(String path, String content) throws Exception {
        return new ContentFile(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))), content);
    }
}
