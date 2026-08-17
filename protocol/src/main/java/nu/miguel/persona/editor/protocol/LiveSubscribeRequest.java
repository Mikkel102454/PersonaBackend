package nu.miguel.persona.editor.protocol;

import java.util.Set;
import java.util.UUID;

public record LiveSubscribeRequest(int protocolVersion,UUID subscriptionId,Set<LiveTopic> topics,
                                   LiveFilter filter,int refreshMillis) {
    public LiveSubscribeRequest { topics=topics==null?Set.of():Set.copyOf(topics);filter=filter==null?LiveFilter.ALL:filter; }
}
