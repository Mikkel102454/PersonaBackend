package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.domain.*;
import nu.miguel.personabackend.security.*;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.storage.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Service
public final class LiveSubscriptionService {
    private static final int MAX_FILTER_VALUES=256,MAX_ITEMS=2_000;
    private final HostedMetadataStore metadata;private final ExpiringStateStore state;private final ObjectMapper json;private final AuditService audit;private final RateLimitService limits;private final QuotaProperties quotas;
    public LiveSubscriptionService(HostedMetadataStore metadata,ExpiringStateStore state,ObjectMapper json,AuditService audit,RateLimitService limits,QuotaProperties quotas){this.metadata=metadata;this.state=state;this.json=json;this.audit=audit;this.limits=limits;this.quotas=quotas;}
    public void subscribe(EditorSession session,LiveSubscribeRequest request){
        if(request==null||request.protocolVersion()!=Protocol.VERSION||request.subscriptionId()==null||request.topics().isEmpty()
                ||request.refreshMillis()<250||request.refreshMillis()>5_000||filterSize(request.filter())>MAX_FILTER_VALUES)throw bad("Invalid live subscription");
        if(request.topics().contains(LiveTopic.MEMORIES)&&!session.capabilities().contains(Capability.MEMORY_VIEW))throw bad("Memory subscription requires memory permission");
        requireScope(session,request.filter());limits.check("live-subscription",session.id().toString(),Math.max(1,quotas.messagesPerSession()/10),quotas.window());
        Map<String,Object> filters=json.convertValue(request.filter(),Map.class);filters=new LinkedHashMap<>(filters);filters.put("refreshMillis",request.refreshMillis());
        metadata.saveSubscription(new LiveSubscription(request.subscriptionId(),session.id(),request.topics().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")),filters,Instant.now(),session.expiresAt()));
        audit.record(session,AuditEvent.ActorType.BROWSER,session.browserDescription(),AuditEvent.EventType.SUBSCRIPTION,AuditEvent.Outcome.SUCCESS,Map.of("operation","subscribe","subscription-id",request.subscriptionId(),"topics",request.topics()),request.subscriptionId().toString());
    }
    public void unsubscribe(EditorSession session,LiveUnsubscribeRequest request){if(request==null||request.protocolVersion()!=Protocol.VERSION||request.subscriptionId()==null)throw bad("Invalid unsubscribe request");metadata.deleteSubscription(request.subscriptionId(),session.id());state.delete(sequenceKey(request.subscriptionId()));audit.record(session,AuditEvent.ActorType.BROWSER,session.browserDescription(),AuditEvent.EventType.SUBSCRIPTION,AuditEvent.Outcome.SUCCESS,Map.of("operation","unsubscribe","subscription-id",request.subscriptionId()),request.subscriptionId().toString());}
    public void accept(EditorSession session,LiveStateSnapshot snapshot){
        if(snapshot==null||snapshot.protocolVersion()!=Protocol.VERSION||snapshot.subscriptionId()==null||snapshot.revision()<1||snapshot.capturedAt()<1
                ||snapshot.capturedAt()>System.currentTimeMillis()+30_000||items(snapshot)>MAX_ITEMS)throw bad("Invalid live state snapshot");
        LiveSubscription subscription=metadata.subscription(snapshot.subscriptionId()).filter(value->value.sessionId().equals(session.id())&&value.expiresAt().isAfter(Instant.now())).orElseThrow(()->bad("Live subscription is unavailable"));
        Set<String> topics=new HashSet<>(Arrays.asList(subscription.type().split(",")));if(!allowedTopics(topics,snapshot))throw bad("Snapshot contains unsubscribed topics");
        Duration ttl=Duration.between(Instant.now(),subscription.expiresAt());String expected=Long.toString(snapshot.revision()-1);
        if(snapshot.revision()==1){if(!state.putIfAbsent(sequenceKey(snapshot.subscriptionId()),"1",ttl))throw bad("Live snapshot sequence was replayed");}
        else if(!state.consumeIfEquals(sequenceKey(snapshot.subscriptionId()),expected))throw bad("Live snapshot sequence is not contiguous");
        if(snapshot.revision()>1)state.put(sequenceKey(snapshot.subscriptionId()),Long.toString(snapshot.revision()),ttl);
    }
    private static boolean allowedTopics(Set<String> topics,LiveStateSnapshot value){return (value.players().isEmpty()||topics.contains("PLAYERS"))&&(value.npcs().isEmpty()||topics.contains("NPCS"))&&(value.behaviors().isEmpty()||topics.contains("BEHAVIORS"))&&(value.quests().isEmpty()||topics.contains("QUESTS"))&&(value.dialogues().isEmpty()||topics.contains("DIALOGUES"))&&(value.memories().isEmpty()||topics.contains("MEMORIES"))&&(value.server()==null||topics.contains("SERVER"));}
    private static int items(LiveStateSnapshot value){return value.players().size()+value.npcs().size()+value.behaviors().size()+value.quests().size()+value.dialogues().size()+value.memories().size()+value.removedKeys().size();}
    private static int filterSize(LiveFilter value){return value.playerIds().size()+value.npcDefinitions().size()+value.npcInstances().size()+value.worlds().size();}
    private static void requireScope(EditorSession session,LiveFilter filter){var allowed=session.restrictions();if(!allowed.playerIds().isEmpty()&&!allowed.playerIds().containsAll(filter.playerIds().stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet())))throw bad("Player filter exceeds session scope");if(!allowed.worlds().isEmpty()&&!allowed.worlds().containsAll(filter.worlds()))throw bad("World filter exceeds session scope");Set<String> requestedNpcs=new HashSet<>(filter.npcDefinitions());requestedNpcs.addAll(filter.npcInstances());if(!allowed.npcIds().isEmpty()&&!allowed.npcIds().containsAll(requestedNpcs))throw bad("NPC filter exceeds session scope");}
    private static String sequenceKey(UUID id){return "live-sequence:"+id;}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
