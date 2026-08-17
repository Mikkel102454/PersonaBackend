package nu.miguel.persona.editor.protocol;

import java.util.UUID;

/** Structured plugin-authoritative outcome returned for every live mutation. */
public record LiveMutationResult(int protocolVersion,UUID requestId,String mutationType,String operation,boolean success,
                                 String message,String target,MemoryState oldValue,MemoryState newValue,long completedAt) {
    public record MemoryState(boolean exists,String type,String value,long createdAt,long updatedAt,Long expiresAt,String source) {}
}
