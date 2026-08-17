/** Data-only node registry shared by the toolbar, command palette, and pin-drop palette. */
export function nodeDefinitions(kind, schemas = []) {
  const extensions = [...schemas];
  if (kind === 'behavior') return [
    ['Sequence', 'sequence'], ['Selector', 'selector'], ['Priority selector', 'priority-selector'],
    ['Parallel', 'parallel'], ['Action', 'action'], ['Condition', 'condition'],
    ['Checkpoint', 'checkpoint'], ['Wait', 'wait'], ['Cooldown', 'cooldown']
  ].map(([label, nodeKind]) => ({ label, nodeKind, inputType: 'execution', destination: 'children' }))
    .concat(extensions.filter(schema => ['behavior-action', 'behavior-condition'].includes(schema.contentType))
      .map(schema => ({ label: `Extension · ${schema.typeId}`, nodeKind: schema.contentType === 'behavior-action'
        ? 'extension-action' : 'extension-condition', inputType: 'execution', destination: 'children',
        extensionType: schema.typeId })));
  if (kind === 'dialogue') return [
    { label: 'Dialogue entry', nodeKind: 'dialogue-entry', inputType: 'dialogue-flow', requiresKey: true, destination: 'root' },
    ...scriptDefinitions(true),
    ...extensionCommands(extensions, 'Extension') ];
  if (kind === 'quest') return [
    { label: 'Quest phase', nodeKind: 'quest-phase', inputType: 'phase-flow', requiresKey: true, destination: 'root' },
    { label: 'Wait objective', nodeKind: 'quest-objective', inputType: 'objective', requiresKey: true, destination: 'objectives' },
    ...extensions.filter(schema => schema.contentType === 'objective').map(schema => ({ label: `Extension · ${schema.typeId}`,
      nodeKind: 'extension-objective', inputType: 'objective', requiresKey: true, destination: 'objectives', extensionType: schema.typeId })),
    ...lifecycleDefinitions(),
    ...extensionCommands(extensions, 'Lifecycle extension') ];
  if (kind === 'npc') return [
    { label: 'NPC anchor', nodeKind: 'npc-anchor', inputType: 'anchor', requiresKey: true, destination: 'anchors' },
    ...lifecycleDefinitions(),
    ...extensionCommands(extensions, 'Lifecycle extension') ];
  if (kind === 'script') return scriptDefinitions(false)
    .concat(extensionCommands(extensions, 'Extension'));
  return [];
}

export function compatibleDefinitions(definitions, sourcePin, canInsert = () => true) {
  return definitions.filter(definition => (!sourcePin || sourcePin.semanticType === definition.inputType)
    && canInsert(definition));
}

function extensionCommands(schemas, prefix) {
  return schemas.filter(schema => schema.contentType === 'command').map(schema => ({ label: `${prefix} · ${schema.typeId}`,
    nodeKind: 'extension-command', inputType: 'execution', destination: 'script', extensionType: schema.typeId }));
}

function scriptDefinitions(dialogue) {
  const values = [['Say line', 'say'], ['Wait', 'wait'], ['Conditional branch', 'if'], ['Choice', 'choice'],
    ['Random branch', 'random'], ['Run reusable script', 'run-script'], ['Transfer', 'goto'],
    [dialogue ? 'End dialogue' : 'Stop', dialogue ? 'end-dialogue' : 'stop']];
  return values.map(([label, nodeKind]) => ({ label, nodeKind, inputType: 'execution', destination: 'script' }));
}

function lifecycleDefinitions() {
  return scriptDefinitions(false).map(definition => ({ ...definition,
    label: `Lifecycle · ${definition.label}`, nodeKind: `script-${definition.nodeKind}` }));
}
