/** Converts trusted runtime records to stable semantic node keys without changing graph structure. */
export function liveNodeKeys(kind, definition, liveData) {
  if (kind === 'dialogue') return new Set([...liveData.dialogues.values()]
    .filter(runtime => runtime.dialogueId === definition)
    .flatMap(runtime => [runtime.nodeId, ...(runtime.eligibleChoices || [])]).filter(Boolean));
  if (kind === 'quest') return new Set([...liveData.quests.values()].filter(runtime => runtime.questId === definition)
    .flatMap(runtime => [runtime.phaseId, ...(runtime.objectives || []).map(value => value.objectiveId)]).filter(Boolean));
  if (kind === 'npc') {
    const runtimes = [...liveData.npcs.values()].filter(runtime => runtime.definitionId === definition), result = new Set();
    if (runtimes.length) result.add(definition);
    for (const runtime of runtimes) if (runtime.anchor) result.add(runtime.anchor);
    return result;
  }
  if (kind === 'behavior') return new Set([...liveData.behaviors.values()]
    .filter(runtime => !definition || runtime.behaviorId === definition)
    .flatMap(runtime => runtime.runningPath || []).map(path => path.split('/').at(-1)));
  return new Set();
}
