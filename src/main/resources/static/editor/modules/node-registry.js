/** Data-only node registry shared by the toolbar, command palette, and pin-drop palette. */
export function nodeDefinitions(kind, schemas = []) {
  const extensions = [...schemas];
  if (kind === 'behavior') return [
    ['Sequence', 'sequence'], ['Selector', 'selector'], ['Priority selector', 'priority-selector'],
    ['Parallel', 'parallel'], ['Action', 'action'], ['Condition', 'condition'],
    ['Checkpoint', 'checkpoint'], ['Wait', 'wait'], ['Cooldown', 'cooldown']
  ].map(([label, nodeKind]) => ({ label, nodeKind, inputType: 'behavior-child',
    inputTypes: ['behavior-child', 'execution'], destination: 'children' }))
    .concat(extensions.filter(schema => ['behavior-action', 'behavior-condition'].includes(schema.contentType))
      .map(schema => ({ label: `Extension · ${schema.typeId}`, nodeKind: schema.contentType === 'behavior-action'
        ? 'extension-action' : 'extension-condition', inputType: 'behavior-child',
        inputTypes: ['behavior-child', 'execution'], destination: 'children',
        extensionType: schema.typeId })));
  if (kind === 'dialogue') return [
    { label: 'Dialogue entry', nodeKind: 'dialogue-entry', inputType: 'dialogue-flow', requiresKey: true, destination: 'root' },
    ...scriptDefinitions(true),
    ...extensionCommands(extensions, 'Extension') ];
  if (kind === 'quest') return [
    { label: 'Quest phase', nodeKind: 'quest-phase', inputType: 'quest-phase-flow', inputTypes: ['quest-phase-flow', 'phase-flow'], requiresKey: true, destination: 'root' },
    { label: 'Wait objective', nodeKind: 'quest-objective', inputType: 'quest-objective', inputTypes: ['quest-objective', 'objective'], requiresKey: true, destination: 'objectives' },
    ...extensions.filter(schema => schema.contentType === 'objective').map(schema => ({ label: `Extension · ${schema.typeId}`,
      nodeKind: 'extension-objective', inputType: 'quest-objective', inputTypes: ['quest-objective', 'objective'], requiresKey: true, destination: 'objectives', extensionType: schema.typeId })),
    ...lifecycleDefinitions('Quest lifecycle'),
    ...extensionCommands(extensions, 'Quest lifecycle extension') ];
  if (kind === 'npc') return [
    { label: 'NPC anchor', nodeKind: 'npc-anchor', inputType: 'anchor', requiresKey: true, destination: 'anchors' },
    ...lifecycleDefinitions('NPC interaction'),
    ...extensionCommands(extensions, 'NPC interaction extension') ];
  if (kind === 'script') return explicitScriptDefinitions().concat([
    { label: 'Convert integer to number', nodeKind: 'integer-to-number', inputType: 'data:integer', targetPinLabel: 'value', destination: 'script' },
    { label: 'Convert string to text', nodeKind: 'string-to-text', inputType: 'data:string', targetPinLabel: 'value', destination: 'script' },
    ...['boolean', 'integer', 'number', 'world', 'material', 'entity-type', 'sound', 'particle', 'npc',
      'npc-instance', 'behavior', 'dialogue', 'quest', 'quest-objective', 'script', 'anchor']
      .map(valueType => ({ label: `Convert ${valueType} to string`, nodeKind: 'to-string', inputType: `data:${valueType}`,
        targetPinLabel: 'value', destination: 'script', valueType })),
    ...[...new Set(extensions.flatMap(schema => Object.keys(schema.schema?.['x-persona-value-types'] || {})))]
      .map(valueType => ({ label: `Convert ${valueType} to string`, nodeKind: 'to-string', inputType: `data:${valueType}`,
        targetPinLabel: 'value', destination: 'script', valueType }))
  ])
    .concat(extensionCommands(extensions, 'Extension'));
  return [];
}

export function compatibleDefinitions(definitions, sourcePin, canInsert = () => true) {
  return definitions.filter(definition => (!sourcePin || (definition.inputTypes || [definition.inputType]).includes(sourcePin.semanticType))
    && canInsert(definition));
}

function extensionCommands(schemas, prefix) {
  return schemas.filter(schema => schema.contentType === 'command').map(schema => ({ label: `${prefix} · ${schema.typeId}`,
    nodeKind: 'extension-command', inputType: 'execution', targetPinLabel: 'exec', destination: 'script', extensionType: schema.typeId }));
}

function scriptDefinitions(dialogue) {
  const values = [['Say line', 'say'], ['Wait', 'wait'], ['Conditional branch', 'if'], ['Choice', 'choice'],
    ['Random branch', 'random'], ['Run reusable script', 'run-script'], ['Transfer', 'goto'],
    [dialogue ? 'End dialogue' : 'Stop', dialogue ? 'end-dialogue' : 'stop']];
  return values.map(([label, nodeKind]) => ({ label, nodeKind, inputType: 'execution', targetPinLabel: 'exec', destination: 'script' }));
}

function explicitScriptDefinitions() {
  return [
    ['Say line', 'say'], ['Wait', 'wait'], ['Boolean branch', 'branch'], ['Run reusable script', 'run-script'], ['Stop', 'stop'],
    ['Start quest', 'start-quest'], ['Finish quest', 'finish-quest'], ['Deliver items', 'deliver-items'], ['Set flag', 'set-flag'],
    ['Set variable', 'set-variable'], ['Message', 'message'], ['Action bar', 'action-bar'], ['Title', 'title'],
    ['Play sound', 'play-sound'], ['Particle', 'particle'], ['Give item', 'give-item'], ['Take item', 'take-item'],
    ['Give experience', 'give-experience'], ['Run command', 'run-command'], ['Teleport', 'teleport'],
    ['Lightning effect', 'lightning-effect'], ['Potion effect', 'potion-effect'], ['Broadcast', 'broadcast'],
    ['Spawn entity', 'spawn-entity'], ['Set block', 'set-block'], ['NPC animation', 'npc-animation'],
    ['NPC speak', 'npc-speak'], ['NPC move', 'npc-move']
  ].map(([label, nodeKind]) => ({ label, nodeKind, inputType: 'execution', targetPinLabel: 'exec', destination: 'script' }));
}

function lifecycleDefinitions(prefix = 'Lifecycle') {
  return scriptDefinitions(false).map(definition => ({ ...definition,
    label: `${prefix} · ${definition.label}`, nodeKind: `script-${definition.nodeKind}` }));
}
