package nu.miguel.persona.editor.protocol;

import java.util.*;

/** Typed, immutable, subscription-scoped runtime state. Values are observational only. */
public record LiveStateSnapshot(int protocolVersion,UUID subscriptionId,long revision,long capturedAt,boolean full,
                                List<Player> players,List<Npc> npcs,List<Behavior> behaviors,List<Quest> quests,
                                List<Dialogue> dialogues,List<Memory> memories,List<GraphTrace> traces,
                                Server server,List<String> removedKeys) {
    public LiveStateSnapshot { players=copy(players);npcs=copy(npcs);behaviors=copy(behaviors);quests=copy(quests);dialogues=copy(dialogues);memories=copy(memories);traces=copy(traces);removedKeys=copy(removedKeys); }
    public LiveStateSnapshot(int protocolVersion,UUID subscriptionId,long revision,long capturedAt,boolean full,
                             List<Player> players,List<Npc> npcs,List<Behavior> behaviors,List<Quest> quests,
                             List<Dialogue> dialogues,List<Memory> memories,Server server,List<String> removedKeys) {
        this(protocolVersion,subscriptionId,revision,capturedAt,full,players,npcs,behaviors,quests,dialogues,
                memories,List.of(),server,removedKeys);
    }
    private static <T> List<T> copy(List<T> value){return value==null?List.of():List.copyOf(value);}
    public record Player(UUID playerId,String world,List<String> activeQuests,int activeNpcRuntimes){public Player{activeQuests=copy(activeQuests);}}
    public record Npc(String definitionId,String instanceId,Integer citizensActorId,UUID playerId,String presentation,
                      String anchor,Position position,boolean visible,String projectionState,double viewerDistance,
                      Navigation navigation,String entityName,String entityType,String skin,Map<String,String> equipment,
                      Integer age,String pose) {public Npc{equipment=equipment==null?Map.of():Map.copyOf(equipment);}}
    public record Position(String world,double x,double y,double z,float yaw,float pitch) {}
    public record Navigation(String target,long startedAt,String status,String result,String reason) {}
    public record Behavior(String definitionId,String instanceId,UUID playerId,String behaviorId,String treeHash,
                           String status,List<String> runningPath,String checkpoint,long nextWakeAt,Map<String,Long> deadlines,
                           List<Outcome> recentOutcomes,List<Condition> recentConditions,List<Event> inbox,long droppedEvents,
                           Navigation navigation) {
        public Behavior { runningPath=copy(runningPath);deadlines=deadlines==null?Map.of():Map.copyOf(deadlines);recentOutcomes=copy(recentOutcomes);recentConditions=copy(recentConditions);inbox=copy(inbox); }
    }
    public record Outcome(long at,String node,String status,String detail) {}
    public record Condition(long at,String node,Map<String,Object> safeInputs,String safeOutput,String explanation){public Condition{safeInputs=safeInputs==null?Map.of():Map.copyOf(safeInputs);}}
    public record Event(UUID id,String type,long occurredAt,String policy,boolean current) {}
    public record Quest(UUID playerId,String questId,String phaseId,List<Objective> objectives,Long timerDeadline,
                        int completionCount,List<String> recentEvents){public Quest{objectives=copy(objectives);recentEvents=copy(recentEvents);}}
    public record Objective(String objectiveId,String type,long current,long required,boolean optional,boolean hidden) {}
    public record Dialogue(UUID playerId,String dialogueId,String nodeId,String state,String npcDefinition,String npcInstance,
                           String currentLine,List<String> eligibleChoices,Long waitDeadline,String cancellationReason) {
        public Dialogue { eligibleChoices=copy(eligibleChoices); }
    }
    public record Memory(UUID playerId,String npcDefinition,String npcInstance,String key,String type,String value,
                         long createdAt,long updatedAt,Long expiresAt,String source,String scope,boolean redacted) {}
    public record GraphTrace(long sequence,long at,String graphId,String tracepointId,String node,String status,
                             UUID playerId,String npcInstance,Map<String,String> watchedValues,String detail) {
        public GraphTrace { watchedValues=watchedValues==null?Map.of():Map.copyOf(watchedValues); }
    }
    public record Server(long behaviorEvaluations,long behaviorTickNanos,int wakeQueue,long inboxDrops,int persistenceQueue,
                         int activeProjections,int projectionLimit,boolean stale) {}
}
