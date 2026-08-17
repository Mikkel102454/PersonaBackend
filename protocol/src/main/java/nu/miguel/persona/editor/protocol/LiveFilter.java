package nu.miguel.persona.editor.protocol;

import java.util.Set;
import java.util.UUID;

public record LiveFilter(Set<UUID> playerIds,Set<String> npcDefinitions,Set<String> npcInstances,Set<String> worlds) {
    public static final LiveFilter ALL=new LiveFilter(null,null,null,null);
    public LiveFilter { playerIds=copy(playerIds);npcDefinitions=copy(npcDefinitions);npcInstances=copy(npcInstances);worlds=copy(worlds); }
    private static <T> Set<T> copy(Set<T> value){return value==null?Set.of():Set.copyOf(value);}
}
