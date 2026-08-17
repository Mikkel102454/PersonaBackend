package nu.miguel.persona.editor.protocol;

import java.util.UUID;

/** Typed optimistic memory mutation. Values remain strings until Persona validates the declared type. */
public record MemoryMutationRequest(int protocolVersion,UUID requestId,Operation operation,UUID playerId,
                                    String npcDefinition,String npcInstance,String key,String valueType,String value,
                                    Double amount,Long expiresAt,Long expectedUpdatedAt) {
    public enum Operation { SET,INCREMENT,EXPIRE,DELETE }
}
