package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.personabackend.audit.AuditService;
import nu.miguel.personabackend.security.*;
import nu.miguel.personabackend.session.EditorSession;
import nu.miguel.personabackend.storage.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveSubscriptionServiceTest {
    private final InMemoryHostedMetadataStore metadata=new InMemoryHostedMetadataStore();
    private final InMemoryExpiringStateStore state=new InMemoryExpiringStateStore();
    private final LiveSubscriptionService service=new LiveSubscriptionService(metadata,state,new ObjectMapper(),new AuditService(metadata,new ObjectMapper()),new RateLimitService(state),QuotaProperties.defaults());

    @Test void persistsScopedSubscriptionAndAcceptsOnlyContiguousBoundedTopics(){
        UUID sessionId=UUID.randomUUID(),playerId=UUID.randomUUID(),subscriptionId=UUID.randomUUID();EditorSession session=session(sessionId,Set.of(Capability.PLAYER_VIEW),new SessionRestrictions(Set.of("world"),Set.of(playerId.toString()),Set.of("story:keeper"),Set.of()));
        LiveSubscribeRequest request=new LiveSubscribeRequest(Protocol.VERSION,subscriptionId,Set.of(LiveTopic.PLAYERS),new LiveFilter(Set.of(playerId),Set.of("story:keeper"),Set.of(),Set.of("world")),500);
        service.subscribe(session,request);assertTrue(metadata.subscription(subscriptionId).isPresent());
        LiveStateSnapshot first=snapshot(subscriptionId,1,List.of(new LiveStateSnapshot.Player(playerId,"world",List.of("story:quest"),1)),List.of());
        assertDoesNotThrow(()->service.accept(session,first));assertDoesNotThrow(()->service.accept(session,snapshot(subscriptionId,2,List.of(),List.of("player:"+playerId))));
        assertThrows(ResponseStatusException.class,()->service.accept(session,snapshot(subscriptionId,2,List.of(),List.of())));
        LiveStateSnapshot wrongTopic=new LiveStateSnapshot(Protocol.VERSION,subscriptionId,3,System.currentTimeMillis(),false,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),new LiveStateSnapshot.Server(0,0,0,0,0,0,0,false),List.of());
        assertThrows(ResponseStatusException.class,()->service.accept(session,wrongTopic));
        service.unsubscribe(session,new LiveUnsubscribeRequest(Protocol.VERSION,subscriptionId));assertTrue(metadata.subscription(subscriptionId).isEmpty());
    }

    @Test void rejectsScopeExpansionAndMemoryWithoutPermission(){
        UUID sessionId=UUID.randomUUID(),allowed=UUID.randomUUID();EditorSession session=session(sessionId,Set.of(Capability.PLAYER_VIEW),new SessionRestrictions(Set.of("world"),Set.of(allowed.toString()),Set.of(),Set.of()));
        assertThrows(ResponseStatusException.class,()->service.subscribe(session,new LiveSubscribeRequest(Protocol.VERSION,UUID.randomUUID(),Set.of(LiveTopic.PLAYERS),new LiveFilter(Set.of(UUID.randomUUID()),Set.of(),Set.of(),Set.of("world")),500)));
        assertThrows(ResponseStatusException.class,()->service.subscribe(session,new LiveSubscribeRequest(Protocol.VERSION,UUID.randomUUID(),Set.of(LiveTopic.MEMORIES),LiveFilter.ALL,500)));
    }

    private static EditorSession session(UUID id,Set<Capability> capabilities,SessionRestrictions restrictions){EditorSession session=mock(EditorSession.class);when(session.id()).thenReturn(id);when(session.installationId()).thenReturn(UUID.randomUUID());when(session.browserDescription()).thenReturn("browser");when(session.capabilities()).thenReturn(capabilities);when(session.restrictions()).thenReturn(restrictions);when(session.expiresAt()).thenReturn(Instant.now().plusSeconds(300));return session;}
    private static LiveStateSnapshot snapshot(UUID id,long revision,List<LiveStateSnapshot.Player> players,List<String> removed){return new LiveStateSnapshot(Protocol.VERSION,id,revision,System.currentTimeMillis(),revision==1,players,List.of(),List.of(),List.of(),List.of(),List.of(),null,removed);}
}
