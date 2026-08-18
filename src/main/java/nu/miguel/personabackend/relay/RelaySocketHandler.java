package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.Capability;
import nu.miguel.persona.editor.protocol.Protocol;
import nu.miguel.persona.editor.protocol.SocketMessage;
import nu.miguel.persona.editor.protocol.ValidationRequest;
import nu.miguel.persona.editor.protocol.ValidationResult;
import nu.miguel.persona.editor.protocol.LiveSubscribeRequest;
import nu.miguel.persona.editor.protocol.LiveUnsubscribeRequest;
import nu.miguel.persona.editor.protocol.LiveStateSnapshot;
import nu.miguel.persona.editor.protocol.BehaviorMutationRequest;
import nu.miguel.persona.editor.protocol.MemoryMutationRequest;
import nu.miguel.persona.editor.protocol.LiveMutationResult;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.session.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import nu.miguel.personabackend.security.QuotaProperties;
import nu.miguel.personabackend.security.RateLimitService;
import nu.miguel.personabackend.security.EditorAuthorization;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.domain.AuditEvent;
import nu.miguel.personabackend.storage.InMemoryHostedMetadataStore;
import nu.miguel.personabackend.validation.ValidationService;

public final class RelaySocketHandler extends TextWebSocketHandler {
    public enum Role { PLUGIN, BROWSER }

    private final Role role;
    private final SessionService sessions;
    private final ObjectMapper json;
    private final RelayHub hub;
    private final RateLimitService limits;
    private final QuotaProperties quotas;
    private final EditorAuthorization authorization;
    private final AuditService audit;
    private final ValidationService validation;
    private final LiveSubscriptionService live;
    private final Map<String, EditorSession> authenticated = new ConcurrentHashMap<>();

    public RelaySocketHandler(Role role, SessionService sessions, ObjectMapper json, RelayHub hub) {
        this(role, sessions, json, hub, new RateLimitService(), QuotaProperties.defaults(), new EditorAuthorization(),
                new AuditService(new InMemoryHostedMetadataStore(), json), null,null);
    }

    public RelaySocketHandler(Role role, SessionService sessions, ObjectMapper json, RelayHub hub,
                              RateLimitService limits, QuotaProperties quotas, EditorAuthorization authorization,
                              AuditService audit, ValidationService validation) {
        this(role,sessions,json,hub,limits,quotas,authorization,audit,validation,null);
    }
    public RelaySocketHandler(Role role, SessionService sessions, ObjectMapper json, RelayHub hub,
                              RateLimitService limits, QuotaProperties quotas, EditorAuthorization authorization,
                              AuditService audit, ValidationService validation,LiveSubscriptionService live) {
        this.role = role; this.sessions = sessions; this.json = json; this.hub = hub;
        this.limits = limits; this.quotas = quotas;
        this.authorization = authorization;
        this.audit = audit;
        this.validation = validation;
        this.live=live;
    }

    @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        try {
            UUID sessionId = UUID.fromString(query(socket, "session", null));
            String lease = role == Role.PLUGIN ? bearer(socket.getHandshakeHeaders()) : query(socket, "lease", null);
            EditorSession editor = role == Role.PLUGIN
                    ? sessions.authenticatePlugin(sessionId, lease)
                    : sessions.authenticateBrowser(sessionId, lease);
            limits.check("socket-connect-installation", editor.installationId().toString(),
                    quotas.connectionsPerInstallation(), quotas.window());
            limits.check("socket-connect-session", editor.id() + ":" + role,
                    quotas.connectionsPerInstallation(), quotas.window());
            authenticated.put(socket.getId(), editor);
            hub.register(role, sessionId, socket, nonNegativeLong(query(socket, "after", "0")));
            audit.record(editor, role == Role.PLUGIN ? AuditEvent.ActorType.INSTALLATION : AuditEvent.ActorType.BROWSER,
                    role == Role.PLUGIN ? editor.installationId().toString() : editor.browserDescription(),
                    AuditEvent.EventType.CONNECTION, AuditEvent.Outcome.SUCCESS,
                    Map.of("role", role.name(), "operation", "connected"), socket.getId());
        } catch (RuntimeException e) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Authentication failed"));
        }
    }

    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage text) throws Exception {
        EditorSession editor = authenticated.get(socket.getId());
        if (editor == null) { socket.close(CloseStatus.POLICY_VIOLATION); return; }
        limits.check("socket-message", editor.id() + ":" + role, quotas.messagesPerSession(), quotas.window());
        if (text.getPayloadLength() > Protocol.MAX_MESSAGE_BYTES) {
            socket.close(CloseStatus.TOO_BIG_TO_PROCESS.withReason("Message too big")); return;
        }
        SocketMessage message;
        try { message = json.readValue(text.getPayload(), SocketMessage.class); }
        catch (Exception e) { socket.close(CloseStatus.BAD_DATA); return; }
        String envelopeError = envelopeError(editor, message);
        if (envelopeError != null) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason(envelopeError)); return;
        }
        boolean sequenceAccepted = role == Role.PLUGIN
                ? editor.acceptPluginSequence(message.sequence())
                : editor.acceptBrowserSequence(message.sequence());
        if (!sequenceAccepted) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Replayed or non-increasing sequence")); return;
        }
        if (role == Role.PLUGIN) editor.touchPlugin(); else editor.touchBrowser();
        try {
            if (Protocol.VALIDATION_REQUEST.equals(message.type())) {
                if (validation == null) throw new IllegalStateException("Validation service unavailable");
                validation.request(editor, json.convertValue(message.payload(), ValidationRequest.class));
            } else if (Protocol.VALIDATION_RESULT.equals(message.type())) {
                if (validation == null) throw new IllegalStateException("Validation service unavailable");
                validation.complete(editor, json.convertValue(message.payload(), ValidationResult.class));
            } else if(Protocol.LIVE_SUBSCRIBE.equals(message.type())){
                if(live==null)throw new IllegalStateException("Live subscription service unavailable");live.subscribe(editor,json.convertValue(message.payload(),LiveSubscribeRequest.class));
            } else if(Protocol.LIVE_UNSUBSCRIBE.equals(message.type())){
                if(live==null)throw new IllegalStateException("Live subscription service unavailable");live.unsubscribe(editor,json.convertValue(message.payload(),LiveUnsubscribeRequest.class));
            } else if(Protocol.LIVE_SNAPSHOT.equals(message.type())||Protocol.LIVE_DELTA.equals(message.type())){
                if(live==null)throw new IllegalStateException("Live subscription service unavailable");live.accept(editor,json.convertValue(message.payload(),LiveStateSnapshot.class));
            } else if(Protocol.BEHAVIOR_MUTATION_REQUEST.equals(message.type())){
                BehaviorMutationRequest request=json.convertValue(message.payload(),BehaviorMutationRequest.class);validateBehaviorMutation(request);limits.check("live-mutation",editor.id().toString(),Math.max(1,quotas.messagesPerSession()/20),quotas.window());
            } else if(Protocol.MEMORY_MUTATION_REQUEST.equals(message.type())){
                MemoryMutationRequest request=json.convertValue(message.payload(),MemoryMutationRequest.class);validateMemoryMutation(request);limits.check("live-mutation",editor.id().toString(),Math.max(1,quotas.messagesPerSession()/20),quotas.window());
            } else if(Protocol.LIVE_MUTATION_RESULT.equals(message.type())){
                LiveMutationResult result=json.convertValue(message.payload(),LiveMutationResult.class);if(result.protocolVersion()!=Protocol.VERSION||result.requestId()==null)throw new IllegalArgumentException("Invalid mutation result");AuditEvent.EventType event=result.mutationType().equals("memory")?AuditEvent.EventType.MEMORY_MUTATION:AuditEvent.EventType.SIGNAL;audit.record(editor,AuditEvent.ActorType.INSTALLATION,editor.installationId().toString(),event,result.success()?AuditEvent.Outcome.SUCCESS:AuditEvent.Outcome.FAILED,Map.of("operation",result.operation(),"target-kind",result.mutationType(),"message",result.message()),result.requestId().toString());
            }
        } catch (RuntimeException invalid) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid typed exchange")); return;
        }
        hub.publish(role, editor.id(), message.sequence(), text);
    }

    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        EditorSession editor = authenticated.remove(socket.getId());
        if (editor != null) {
            hub.unregister(role, editor.id(), socket);
            audit.record(editor, role == Role.PLUGIN ? AuditEvent.ActorType.INSTALLATION : AuditEvent.ActorType.BROWSER,
                    role == Role.PLUGIN ? editor.installationId().toString() : editor.browserDescription(),
                    AuditEvent.EventType.CONNECTION, AuditEvent.Outcome.SUCCESS,
                    Map.of("role", role.name(), "operation", "disconnected", "close-status", status.getCode(),
                            "close-reason", Objects.toString(status.getReason(), "")), socket.getId());
        }
    }

    private String envelopeError(EditorSession editor, SocketMessage message) {
        if (message.protocolVersion() != Protocol.VERSION) return "Protocol version mismatch";
        if (!editor.id().equals(message.sessionId())) return "Session identity mismatch";
        if (!allowedType(message.type())) return "Message type is not allowed for this socket role";
        if (!allowedByCapability(editor, message.type())) return "Message capability is not granted";
        if (!validPayload(message)) return "Invalid typed message payload";
        if (!validSignature(editor, message)) return "Invalid message signature";
        return null;
    }

    private boolean allowedType(String type) {
        if (role == Role.PLUGIN) return Set.of(Protocol.HEARTBEAT, Protocol.SNAPSHOT_CHANGED,
                Protocol.VALIDATION_RESULT,Protocol.CATALOG_RESULT,Protocol.LIVE_SUBSCRIPTION_ACK,Protocol.LIVE_SNAPSHOT,Protocol.LIVE_DELTA,
                Protocol.LIVE_MUTATION_RESULT).contains(type);
        return Set.of(Protocol.HEARTBEAT, Protocol.RESYNC_REQUEST, Protocol.VALIDATION_REQUEST,Protocol.CATALOG_REQUEST,
                Protocol.LIVE_SUBSCRIBE,Protocol.LIVE_UNSUBSCRIBE,Protocol.BEHAVIOR_MUTATION_REQUEST,Protocol.MEMORY_MUTATION_REQUEST).contains(type);
    }

    private boolean allowedByCapability(EditorSession editor, String type) {
        if (Protocol.HEARTBEAT.equals(type)) return true;
        if (Protocol.VALIDATION_REQUEST.equals(type) || Protocol.VALIDATION_RESULT.equals(type))
            return authorization.hasCapability(editor, Capability.DRAFT_EDIT);
        if(Set.of(Protocol.LIVE_SUBSCRIBE,Protocol.LIVE_UNSUBSCRIBE,Protocol.LIVE_SUBSCRIPTION_ACK,Protocol.LIVE_SNAPSHOT,Protocol.LIVE_DELTA).contains(type))
            return authorization.hasCapability(editor,Capability.PLAYER_VIEW);
        if(Set.of(Protocol.BEHAVIOR_MUTATION_REQUEST,Protocol.MEMORY_MUTATION_REQUEST,Protocol.LIVE_MUTATION_RESULT).contains(type))
            return authorization.hasCapability(editor,Capability.LIVE_MUTATE);
        return authorization.hasCapability(editor, Capability.CONTENT_VIEW);
    }

    private boolean validPayload(SocketMessage message) {
        if (message.payload() == null || message.payload().size() > 16) return false;
        return switch (message.type()) {
            case Protocol.HEARTBEAT -> message.payload().size() == 1 && message.payload().get("at") instanceof Number;
            case Protocol.SNAPSHOT_CHANGED -> message.payload().size() == 1
                    && message.payload().get("revision") instanceof String revision && revision.matches("[0-9a-f]{64}");
            case Protocol.RESYNC_REQUEST -> message.payload().isEmpty();
            case Protocol.VALIDATION_REQUEST -> message.payload().keySet().equals(
                    Set.of("protocolVersion", "requestId", "draftId"));
            case Protocol.VALIDATION_RESULT -> message.payload().keySet().equals(
                    Set.of("protocolVersion", "requestId", "draftId", "valid", "proposedRevision",
                            "contentFormatVersion", "diagnostics"));
            case Protocol.CATALOG_REQUEST -> message.payload().keySet().equals(Set.of("protocolVersion","requestId","catalogId",
                    "expectedRevision","search","page","pageSize","dependencies"))
                    && message.payload().get("page") instanceof Number page&&page.intValue()>=0
                    && message.payload().get("pageSize") instanceof Number size&&size.intValue()>0&&size.intValue()<=200;
            case Protocol.CATALOG_RESULT -> message.payload().keySet().equals(Set.of("protocolVersion","requestId","catalogId",
                    "revision","status","values","page","hasMore","message"))
                    && message.payload().get("values") instanceof Collection<?> values&&values.size()<=200;
            case Protocol.LIVE_SUBSCRIBE -> message.payload().keySet().equals(Set.of("protocolVersion","subscriptionId","topics","filter","refreshMillis"));
            case Protocol.LIVE_UNSUBSCRIBE -> message.payload().keySet().equals(Set.of("protocolVersion","subscriptionId"));
            case Protocol.LIVE_SUBSCRIPTION_ACK -> message.payload().keySet().equals(Set.of("protocolVersion","subscriptionId","accepted","refreshMillis","message"));
            case Protocol.LIVE_SNAPSHOT,Protocol.LIVE_DELTA -> message.payload().keySet().equals(Set.of("protocolVersion","subscriptionId","revision","capturedAt","full","players","npcs","behaviors","quests","dialogues","memories","traces","server","removedKeys"));
            case Protocol.BEHAVIOR_MUTATION_REQUEST -> message.payload().keySet().equals(Set.of("protocolVersion","requestId","operation","npcDefinition","npcInstance","playerId","signal","data"));
            case Protocol.MEMORY_MUTATION_REQUEST -> message.payload().keySet().equals(Set.of("protocolVersion","requestId","operation","playerId","npcDefinition","npcInstance","key","valueType","value","amount","expiresAt","expectedUpdatedAt"));
            case Protocol.LIVE_MUTATION_RESULT -> message.payload().keySet().equals(Set.of("protocolVersion","requestId","mutationType","operation","success","message","target","oldValue","newValue","completedAt"));
            default -> false;
        };
    }

    private static void validateBehaviorMutation(BehaviorMutationRequest request){if(request.protocolVersion()!=Protocol.VERSION||request.requestId()==null||request.operation()==null||invalidId(request.npcDefinition())||request.npcInstance()==null||request.npcInstance().isBlank()||request.npcInstance().length()>128||request.data().size()>16)throw new IllegalArgumentException("Invalid behavior mutation");if(request.operation()==BehaviorMutationRequest.Operation.SIGNAL&&(request.signal()==null||!request.signal().matches("[a-z0-9][a-z0-9_.:-]{0,63}")))throw new IllegalArgumentException("Invalid behavior signal");}
    private static void validateMemoryMutation(MemoryMutationRequest request){if(request.protocolVersion()!=Protocol.VERSION||request.requestId()==null||request.operation()==null||invalidId(request.npcDefinition())||request.npcInstance()==null||request.npcInstance().isBlank()||request.npcInstance().length()>128||request.key()==null||!request.key().matches("[a-z0-9][a-z0-9_.:-]{0,127}"))throw new IllegalArgumentException("Invalid memory mutation");}
    private static boolean invalidId(String value){return value==null||!value.matches("[a-z0-9][a-z0-9_.:-]{0,127}");}

    private boolean validSignature(EditorSession editor, SocketMessage message) {
        try {
            String payload = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(message.payload())));
            String input = message.protocolVersion() + "\n" + message.sessionId() + "\n" + message.sequence()
                    + "\n" + message.type() + "\n" + payload;
            PublicKey key = role == Role.PLUGIN ? editor.installationKey() : browserKey(editor.browserKey());
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key); signature.update(input.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(message.signature()));
        } catch (GeneralSecurityException | IllegalArgumentException | java.io.IOException e) { return false; }
    }

    private PublicKey browserKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
    private String bearer(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (value == null || !value.startsWith("Bearer ")) throw new IllegalArgumentException("Missing lease");
        return value.substring(7);
    }
    private String query(WebSocketSession socket, String name, String fallback) {
        String query = Objects.requireNonNull(socket.getUri()).getRawQuery();
        if (query == null) throw new IllegalArgumentException("Missing query");
        return Arrays.stream(query.split("&")).map(pair -> pair.split("=", 2))
                .filter(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name))
                .map(pair -> pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "")
                .findFirst().orElseGet(() -> {
                    if (fallback != null) return fallback;
                    throw new IllegalArgumentException("Missing " + name);
                });
    }
    private long nonNegativeLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) throw new IllegalArgumentException("Negative sequence");
        return parsed;
    }
}
