package nu.miguel.persona.editor.protocol;

import java.util.*;

/** A bounded behavior-runtime control. It cannot carry executable commands or content. */
public record BehaviorMutationRequest(int protocolVersion,UUID requestId,Operation operation,String npcDefinition,
                                      String npcInstance,UUID playerId,String signal,Map<String,String> data) {
    public enum Operation { PAUSE,RESUME,RESTART,WAKE,SIGNAL }
    public BehaviorMutationRequest { data=data==null?Map.of():Map.copyOf(data); }
}
