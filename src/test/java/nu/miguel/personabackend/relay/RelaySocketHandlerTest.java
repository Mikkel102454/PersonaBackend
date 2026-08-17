package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.security.*;
import nu.miguel.personabackend.storage.InMemoryHostedMetadataStore;
import nu.miguel.personabackend.validation.ValidationService;

class RelaySocketHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EditorProperties properties = new EditorProperties(
            "https://editor.example", "wss://editor.example", Duration.ofMinutes(5), Duration.ofMinutes(1), 3,
            Duration.ofSeconds(45), 8);

    @Test void authenticatesRolesAndForwardsOnlyValidSignedTypedEnvelopes() throws Exception {
        SessionService sessions = new SessionService(properties);
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = sessions.create(createRequest(installation));
        KeyPair browserKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse browserLease = sessions.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browserKeys.getPublic().getEncoded()), "Test Browser"));
        RelayHub hub = new RelayHub(json, properties);
        RelaySocketHandler pluginHandler = new RelaySocketHandler(RelaySocketHandler.Role.PLUGIN, sessions, json, hub);
        RelaySocketHandler browserHandler = new RelaySocketHandler(RelaySocketHandler.Role.BROWSER, sessions, json, hub);
        List<WebSocketMessage<?>> pluginOutput = new ArrayList<>(), browserOutput = new ArrayList<>();
        WebSocketSession plugin = socket("plugin", "/ws/v1/plugin?session=" + created.sessionId(),
                created.pluginLeaseToken(), pluginOutput);
        WebSocketSession browser = socket("browser", "/ws/v1/browser?session=" + created.sessionId()
                + "&lease=" + browserLease.browserLeaseToken(), null, browserOutput);
        pluginHandler.afterConnectionEstablished(plugin);
        browserHandler.afterConnectionEstablished(browser);
        pluginOutput.clear(); browserOutput.clear();

        SocketMessage valid = signed(installation.getPrivate(), created.sessionId(), 1,
                Protocol.HEARTBEAT, Map.of("at", 123L));
        pluginHandler.handleTextMessage(plugin, new TextMessage(json.writeValueAsString(valid)));

        assertEquals(1, browserOutput.size());
        assertEquals(Protocol.HEARTBEAT,
                json.readTree(((TextMessage) browserOutput.getFirst()).getPayload()).path("type").asText());

        SocketMessage unsupported = signed(installation.getPrivate(), created.sessionId(), 2,
                "PUBLISH", Map.of());
        pluginHandler.handleTextMessage(plugin, new TextMessage(json.writeValueAsString(unsupported)));
        verify(plugin).close(argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        assertEquals(1, browserOutput.size());
    }

    @Test void badSignatureDoesNotConsumeTheExpectedSequence() throws Exception {
        SessionService sessions = new SessionService(properties);
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = sessions.create(createRequest(installation));
        RelayHub hub = new RelayHub(json, properties);
        RelaySocketHandler handler = new RelaySocketHandler(RelaySocketHandler.Role.PLUGIN, sessions, json, hub);
        WebSocketSession first = socket("first", "/ws/v1/plugin?session=" + created.sessionId(),
                created.pluginLeaseToken(), new ArrayList<>());
        handler.afterConnectionEstablished(first);
        SocketMessage forged = new SocketMessage(Protocol.VERSION, created.sessionId(), 1, Protocol.HEARTBEAT,
                Map.of("at", 1L), Base64.getEncoder().encodeToString(new byte[64]));
        handler.handleTextMessage(first, new TextMessage(json.writeValueAsString(forged)));
        handler.afterConnectionClosed(first, CloseStatus.POLICY_VIOLATION);

        WebSocketSession second = socket("second", "/ws/v1/plugin?session=" + created.sessionId(),
                created.pluginLeaseToken(), new ArrayList<>());
        handler.afterConnectionEstablished(second);
        handler.handleTextMessage(second, new TextMessage(json.writeValueAsString(signed(
                installation.getPrivate(), created.sessionId(), 1, Protocol.HEARTBEAT, Map.of("at", 2L)))));

        verify(second, never()).close(argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
    }

    @Test void forwardsOnlyCorrelatedSignedValidationExchange() throws Exception {
        SessionService sessions = new SessionService(properties);
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created = sessions.create(createRequest(installation,
                Set.of(Capability.CONTENT_VIEW, Capability.DRAFT_EDIT)));
        KeyPair browserKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse browserLease = sessions.verify(created.sessionId(), new SessionVerifyRequest(
                created.verificationCode(), Base64.getEncoder().encodeToString(browserKeys.getPublic().getEncoded()), "Browser"));
        sessions.grant(created.sessionId(), created.pluginLeaseToken(),
                new CapabilityGrantRequest(Protocol.VERSION, Set.of(Capability.DRAFT_EDIT)));
        RelayHub hub = new RelayHub(json, properties);
        ValidationService validation = mock(ValidationService.class);
        AuditService audit = new AuditService(new InMemoryHostedMetadataStore(), json);
        RelaySocketHandler pluginHandler = new RelaySocketHandler(RelaySocketHandler.Role.PLUGIN, sessions, json, hub,
                new RateLimitService(), QuotaProperties.defaults(), new EditorAuthorization(), audit, validation);
        RelaySocketHandler browserHandler = new RelaySocketHandler(RelaySocketHandler.Role.BROWSER, sessions, json, hub,
                new RateLimitService(), QuotaProperties.defaults(), new EditorAuthorization(), audit, validation);
        List<WebSocketMessage<?>> pluginOutput = new ArrayList<>(), browserOutput = new ArrayList<>();
        WebSocketSession plugin = socket("plugin-validation", "/ws/v1/plugin?session=" + created.sessionId(),
                created.pluginLeaseToken(), pluginOutput);
        WebSocketSession browser = socket("browser-validation", "/ws/v1/browser?session=" + created.sessionId()
                + "&lease=" + browserLease.browserLeaseToken(), null, browserOutput);
        pluginHandler.afterConnectionEstablished(plugin); browserHandler.afterConnectionEstablished(browser);
        pluginOutput.clear(); browserOutput.clear();
        UUID requestId = UUID.randomUUID(), draftId = UUID.randomUUID();
        Map<String, Object> request = json.convertValue(new ValidationRequest(Protocol.VERSION, requestId, draftId), Map.class);
        browserHandler.handleTextMessage(browser, new TextMessage(json.writeValueAsString(signed(
                browserKeys.getPrivate(), created.sessionId(), 1, Protocol.VALIDATION_REQUEST, request))));
        verify(validation).request(any(), eq(new ValidationRequest(Protocol.VERSION, requestId, draftId)));
        assertEquals(Protocol.VALIDATION_REQUEST, json.readTree(((TextMessage) pluginOutput.getFirst()).getPayload()).path("type").asText());

        ValidationResult outcome = new ValidationResult(Protocol.VERSION, requestId, draftId, true,
                "a".repeat(64), 1, List.of());
        Map<String, Object> result = json.convertValue(outcome, Map.class);
        pluginHandler.handleTextMessage(plugin, new TextMessage(json.writeValueAsString(signed(
                installation.getPrivate(), created.sessionId(), 1, Protocol.VALIDATION_RESULT, result))));
        verify(validation).complete(any(), eq(outcome));
        assertEquals(Protocol.VALIDATION_RESULT, json.readTree(((TextMessage) browserOutput.getFirst()).getPayload()).path("type").asText());
    }

    @Test void forwardsBoundedTypedCatalogRequestsAndResults() throws Exception {
        SessionService sessions=new SessionService(properties);KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created=sessions.create(createRequest(installation));KeyPair browserKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse browserLease=sessions.verify(created.sessionId(),new SessionVerifyRequest(created.verificationCode(),Base64.getEncoder().encodeToString(browserKeys.getPublic().getEncoded()),"Browser"));
        RelayHub hub=new RelayHub(json,properties);RelaySocketHandler pluginHandler=new RelaySocketHandler(RelaySocketHandler.Role.PLUGIN,sessions,json,hub),browserHandler=new RelaySocketHandler(RelaySocketHandler.Role.BROWSER,sessions,json,hub);
        List<WebSocketMessage<?>> pluginOutput=new ArrayList<>(),browserOutput=new ArrayList<>();WebSocketSession plugin=socket("catalog-plugin","/ws/v1/plugin?session="+created.sessionId(),created.pluginLeaseToken(),pluginOutput),browser=socket("catalog-browser","/ws/v1/browser?session="+created.sessionId()+"&lease="+browserLease.browserLeaseToken(),null,browserOutput);
        pluginHandler.afterConnectionEstablished(plugin);browserHandler.afterConnectionEstablished(browser);pluginOutput.clear();browserOutput.clear();
        UUID requestId=UUID.randomUUID();EditorCatalogRequest request=new EditorCatalogRequest(Protocol.VERSION,requestId,"assets:items","rev-1","bell",0,50,Map.of("namespace","village"));
        browserHandler.handleTextMessage(browser,new TextMessage(json.writeValueAsString(signed(browserKeys.getPrivate(),created.sessionId(),1,Protocol.CATALOG_REQUEST,json.convertValue(request,Map.class)))));
        assertEquals(Protocol.CATALOG_REQUEST,json.readTree(((TextMessage)pluginOutput.getFirst()).getPayload()).path("type").asText());
        EditorCatalogResult result=new EditorCatalogResult(Protocol.VERSION,requestId,"assets:items","rev-1",EditorCatalogResult.Status.LIVE,List.of(new EditorCatalogResult.Value("village:bell","Bell","","village","BELL",false)),0,false,"");
        pluginHandler.handleTextMessage(plugin,new TextMessage(json.writeValueAsString(signed(installation.getPrivate(),created.sessionId(),1,Protocol.CATALOG_RESULT,json.convertValue(result,Map.class)))));
        assertEquals(Protocol.CATALOG_RESULT,json.readTree(((TextMessage)browserOutput.getFirst()).getPayload()).path("type").asText());
    }

    @Test void persistsAndForwardsTypedLiveSubscriptionSnapshot() throws Exception {
        SessionService sessions=new SessionService(properties);KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created=sessions.create(createRequest(installation,Set.of(Capability.CONTENT_VIEW,Capability.PLAYER_VIEW)));KeyPair browserKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse browserLease=sessions.verify(created.sessionId(),new SessionVerifyRequest(created.verificationCode(),Base64.getEncoder().encodeToString(browserKeys.getPublic().getEncoded()),"Browser"));sessions.grant(created.sessionId(),created.pluginLeaseToken(),new CapabilityGrantRequest(Protocol.VERSION,Set.of(Capability.PLAYER_VIEW)));
        InMemoryHostedMetadataStore metadata=new InMemoryHostedMetadataStore();var expiring=new nu.miguel.personabackend.storage.InMemoryExpiringStateStore();AuditService audit=new AuditService(metadata,json);LiveSubscriptionService live=new LiveSubscriptionService(metadata,expiring,json,audit,new RateLimitService(expiring),QuotaProperties.defaults());
        RelayHub hub=new RelayHub(json,properties);RelaySocketHandler pluginHandler=new RelaySocketHandler(RelaySocketHandler.Role.PLUGIN,sessions,json,hub,new RateLimitService(),QuotaProperties.defaults(),new EditorAuthorization(),audit,null,live),browserHandler=new RelaySocketHandler(RelaySocketHandler.Role.BROWSER,sessions,json,hub,new RateLimitService(),QuotaProperties.defaults(),new EditorAuthorization(),audit,null,live);
        List<WebSocketMessage<?>> pluginOutput=new ArrayList<>(),browserOutput=new ArrayList<>();WebSocketSession plugin=socket("live-plugin","/ws/v1/plugin?session="+created.sessionId(),created.pluginLeaseToken(),pluginOutput),browser=socket("live-browser","/ws/v1/browser?session="+created.sessionId()+"&lease="+browserLease.browserLeaseToken(),null,browserOutput);pluginHandler.afterConnectionEstablished(plugin);browserHandler.afterConnectionEstablished(browser);pluginOutput.clear();browserOutput.clear();
        UUID subscription=UUID.randomUUID(),player=UUID.randomUUID();LiveSubscribeRequest request=new LiveSubscribeRequest(Protocol.VERSION,subscription,Set.of(LiveTopic.PLAYERS),LiveFilter.ALL,500);
        browserHandler.handleTextMessage(browser,new TextMessage(json.writeValueAsString(signed(browserKeys.getPrivate(),created.sessionId(),1,Protocol.LIVE_SUBSCRIBE,json.convertValue(request,Map.class)))));
        assertTrue(metadata.subscription(subscription).isPresent());assertEquals(Protocol.LIVE_SUBSCRIBE,json.readTree(((TextMessage)pluginOutput.getFirst()).getPayload()).path("type").asText());
        LiveStateSnapshot snapshot=new LiveStateSnapshot(Protocol.VERSION,subscription,1,System.currentTimeMillis(),true,List.of(new LiveStateSnapshot.Player(player,"world",List.of(),0)),List.of(),List.of(),List.of(),List.of(),List.of(),null,List.of());
        pluginHandler.handleTextMessage(plugin,new TextMessage(json.writeValueAsString(signed(installation.getPrivate(),created.sessionId(),1,Protocol.LIVE_SNAPSHOT,json.convertValue(snapshot,Map.class)))));
        assertEquals(Protocol.LIVE_SNAPSHOT,json.readTree(((TextMessage)browserOutput.getFirst()).getPayload()).path("type").asText());
    }

    @Test void forwardsOnlyCapabilityGrantedTypedMutationsAndAuditsPluginResult() throws Exception {
        SessionService sessions=new SessionService(properties);KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionCreateResponse created=sessions.create(createRequest(installation,Set.of(Capability.CONTENT_VIEW,Capability.LIVE_MUTATE)));KeyPair browserKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SessionVerifyResponse browserLease=sessions.verify(created.sessionId(),new SessionVerifyRequest(created.verificationCode(),Base64.getEncoder().encodeToString(browserKeys.getPublic().getEncoded()),"Browser"));sessions.grant(created.sessionId(),created.pluginLeaseToken(),new CapabilityGrantRequest(Protocol.VERSION,Set.of(Capability.LIVE_MUTATE)));
        InMemoryHostedMetadataStore metadata=new InMemoryHostedMetadataStore();AuditService audit=new AuditService(metadata,json);RelayHub hub=new RelayHub(json,properties);RelaySocketHandler pluginHandler=new RelaySocketHandler(RelaySocketHandler.Role.PLUGIN,sessions,json,hub,new RateLimitService(),QuotaProperties.defaults(),new EditorAuthorization(),audit,null),browserHandler=new RelaySocketHandler(RelaySocketHandler.Role.BROWSER,sessions,json,hub,new RateLimitService(),QuotaProperties.defaults(),new EditorAuthorization(),audit,null);
        List<WebSocketMessage<?>> pluginOutput=new ArrayList<>(),browserOutput=new ArrayList<>();WebSocketSession plugin=socket("mutation-plugin","/ws/v1/plugin?session="+created.sessionId(),created.pluginLeaseToken(),pluginOutput),browser=socket("mutation-browser","/ws/v1/browser?session="+created.sessionId()+"&lease="+browserLease.browserLeaseToken(),null,browserOutput);pluginHandler.afterConnectionEstablished(plugin);browserHandler.afterConnectionEstablished(browser);pluginOutput.clear();browserOutput.clear();
        UUID requestId=UUID.randomUUID();BehaviorMutationRequest request=new BehaviorMutationRequest(Protocol.VERSION,requestId,BehaviorMutationRequest.Operation.WAKE,"story:keeper","one",null,null,Map.of());browserHandler.handleTextMessage(browser,new TextMessage(json.writeValueAsString(signed(browserKeys.getPrivate(),created.sessionId(),1,Protocol.BEHAVIOR_MUTATION_REQUEST,json.convertValue(request,Map.class)))));assertEquals(Protocol.BEHAVIOR_MUTATION_REQUEST,json.readTree(((TextMessage)pluginOutput.getFirst()).getPayload()).path("type").asText());
        LiveMutationResult result=new LiveMutationResult(Protocol.VERSION,requestId,"behavior","WAKE",true,"Applied to 1 runtime(s)","story:keeper/one",null,null,System.currentTimeMillis());pluginHandler.handleTextMessage(plugin,new TextMessage(json.writeValueAsString(signed(installation.getPrivate(),created.sessionId(),1,Protocol.LIVE_MUTATION_RESULT,json.convertValue(result,Map.class)))));assertEquals(Protocol.LIVE_MUTATION_RESULT,json.readTree(((TextMessage)browserOutput.getFirst()).getPayload()).path("type").asText());assertTrue(metadata.auditEvents().stream().anyMatch(event->event.eventType()==nu.miguel.personabackend.domain.AuditEvent.EventType.SIGNAL&&event.correlationId().equals(requestId.toString())));
    }

    private SessionCreateRequest createRequest(KeyPair keys) throws Exception {
        return createRequest(keys, Set.of(Capability.CONTENT_VIEW));
    }

    private SessionCreateRequest createRequest(KeyPair keys, Set<Capability> capabilities) throws Exception {
        SessionCreateRequest unsigned = new SessionCreateRequest(Protocol.VERSION, UUID.randomUUID(),
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), "console", "CONSOLE",
                EditorScope.ALL, SessionRestrictions.UNRESTRICTED, capabilities, System.currentTimeMillis(), UUID.randomUUID().toString(), "");
        return new SessionCreateRequest(unsigned.protocolVersion(), unsigned.installationId(), unsigned.installationPublicKey(),
                unsigned.initiatorId(), unsigned.initiatorName(), unsigned.scope(), unsigned.restrictions(), unsigned.requestedCapabilities(),
                unsigned.issuedAt(), unsigned.nonce(),
                sign(keys.getPrivate(), unsigned.signingInput()));
    }

    private SocketMessage signed(PrivateKey key, UUID id, long sequence, String type, Map<String, Object> payload)
            throws Exception {
        String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(payload)));
        String input = Protocol.VERSION + "\n" + id + "\n" + sequence + "\n" + type + "\n" + digest;
        return new SocketMessage(Protocol.VERSION, id, sequence, type, payload, sign(key, input));
    }

    private WebSocketSession socket(String id, String path, String bearer, List<WebSocketMessage<?>> output)
            throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) headers.setBearerAuth(bearer);
        when(socket.getId()).thenReturn(id);
        when(socket.getUri()).thenReturn(URI.create("wss://editor.example" + path));
        when(socket.getHandshakeHeaders()).thenReturn(headers);
        when(socket.isOpen()).thenReturn(true);
        doAnswer(invocation -> { output.add(invocation.getArgument(0)); return null; })
                .when(socket).sendMessage(any(WebSocketMessage.class));
        return socket;
    }

    private static String sign(PrivateKey key, String input) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key); signature.update(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
