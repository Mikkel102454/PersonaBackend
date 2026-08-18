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
    ...commonGraphDefinitions(true),
    ...extensionCommands(extensions, 'Extension') ];
  if (kind === 'quest') return [
    { label: 'Quest phase', nodeKind: 'quest-phase', inputType: 'quest-phase-flow', inputTypes: ['quest-phase-flow', 'phase-flow'], requiresKey: true, destination: 'root' },
    { label: 'Wait objective', nodeKind: 'quest-objective', inputType: 'quest-objective', inputTypes: ['quest-objective', 'objective'], requiresKey: true, destination: 'objectives' },
    ...extensions.filter(schema => schema.contentType === 'objective').map(schema => ({ label: `Extension · ${schema.typeId}`,
      nodeKind: 'extension-objective', inputType: 'quest-objective', inputTypes: ['quest-objective', 'objective'], requiresKey: true, destination: 'objectives', extensionType: schema.typeId })),
    ...commonGraphDefinitions(false),
    ...extensionCommands(extensions, 'Quest lifecycle extension') ];
  if (kind === 'npc') return [
    { label: 'NPC anchor', nodeKind: 'npc-anchor', inputType: 'anchor', requiresKey: true, destination: 'anchors' },
    ...commonGraphDefinitions(false),
    ...extensionCommands(extensions, 'NPC interaction extension') ];
  if (kind === 'script') return commonGraphDefinitions(false).concat([
    { label: 'Convert integer to number', nodeKind: 'integer-to-number', inputType: 'data:integer', targetPinLabel: 'value', destination: 'graph' },
    { label: 'Convert string to text', nodeKind: 'string-to-text', inputType: 'data:string', targetPinLabel: 'value', destination: 'graph' },
    ...['boolean', 'integer', 'number', 'world', 'material', 'entity-type', 'sound', 'particle', 'npc',
      'npc-instance', 'behavior', 'dialogue', 'quest', 'quest-objective', 'script', 'anchor']
      .map(valueType => ({ label: `Convert ${valueType} to string`, nodeKind: 'to-string', inputType: `data:${valueType}`,
        targetPinLabel: 'value', destination: 'graph', valueType })),
    ...[...new Set(extensions.flatMap(schema => Object.keys(schema.schema?.['x-persona-value-types'] || {})))]
      .map(valueType => ({ label: `Convert ${valueType} to string`, nodeKind: 'to-string', inputType: `data:${valueType}`,
        targetPinLabel: 'value', destination: 'graph', valueType }))
  ])
    .concat(extensionCommands(extensions, 'Extension'));
  return [];
}

export function matchingDefinitionInput(definition, sourcePin) {
  if (!definition || !sourcePin) return null;
  const channel = String(sourcePin.channel || '').toUpperCase();
  const semanticTypes = [...new Set([channel === 'DATA' && sourcePin.valueType ? `data:${sourcePin.valueType}` : null,
    sourcePin.semanticType, channel === 'EXECUTION' ? 'execution' : null].filter(Boolean))];
  return (definition.inputPins || []).find(input => semanticTypes.includes(input.semanticType))
    || (definition.inputTypes || [definition.inputType]).filter(Boolean)
      .map(type => ({ semanticType: type, label: type === definition.inputType ? definition.targetPinLabel : null }))
      .find(input => semanticTypes.includes(input.semanticType)) || null;
}

export function compatibleDefinitions(definitions, sourcePin, canInsert = () => true, contextSensitive = true) {
  if (!contextSensitive) return definitions.slice();
  return definitions.filter(definition => (!sourcePin || matchingDefinitionInput(definition, sourcePin))
    && canInsert(definition));
}

function extensionCommands(schemas, prefix) {
  return schemas.filter(schema => schema.contentType === 'command').map(schema => ({ label: `${prefix} · ${schema.typeId}`,
    nodeKind: 'extension-command', inputType: 'execution', targetPinLabel: 'exec', destination: 'graph', extensionType: schema.typeId }));
}

function scriptDefinitions(dialogue) {
  const values = [['Say line', 'say'], ['Wait', 'wait'], ['Conditional branch', 'if'], ['Choice', 'choice'],
    ['Random branch', 'random'], ['Run reusable script', 'run-script'], ['Transfer', 'goto'],
    [dialogue ? 'End dialogue' : 'Stop', dialogue ? 'end-dialogue' : 'stop']];
  return values.map(([label, nodeKind]) => ({ label, nodeKind, inputType: 'execution', targetPinLabel: 'exec', destination: 'script' }));
}

export function automaticNodeId(definition, nodes = []) {
  const preferred = definition?.extensionType?.split(':').at(-1) || definition?.label || definition?.nodeKind || 'node';
  const base = String(preferred).toLowerCase().replace(/[^a-z0-9_.-]+/g, '-').replace(/^[^a-z0-9]+|[-.]+$/g, '')
    .slice(0, 120) || 'node';
  const used = new Set(nodes.flatMap(node => [node?.title, node?.yamlPath?.split('/').at(-1)]).filter(Boolean));
  if (!used.has(base)) return base;
  let suffix = 2;
  while (used.has(`${base}-${suffix}`)) suffix++;
  return `${base}-${suffix}`;
}

/** Conservative collision guard for server-generated simple mapping keys. */
export function yamlMappingKeys(content) {
  const keys = [];
  for (const match of String(content || '').matchAll(/^\s*(?:([A-Za-z0-9_.:-]+)|["']([^"']+)["']):(?:\s|$)/gm))
    keys.push(match[1] || match[2]);
  return keys;
}

function commonGraphDefinitions(dialogue) {
  const execution = [
    ['Say line', 'say'], ['Wait', 'wait'], ['Boolean branch', 'branch'], ['Choice', 'choice'],
    ['Sequence', 'sequence'], ['Switch', 'switch'], ['Random branch', 'random'], ['Gate', 'gate'],
    ['Do once', 'do-once'], ['Do N', 'do-n'], ['For loop', 'for'], ['For each', 'for-each'],
    ['While loop', 'while'], ['Run reusable script', 'run-script'],
    [dialogue ? 'End dialogue' : 'Stop', dialogue ? 'end-dialogue' : 'stop'],
    ...explicitScriptDefinitions().map(definition => [definition.label, definition.nodeKind])
  ];
  return [...new Map(execution.map(value => [value[1], value])).values()]
    .map(([label, nodeKind]) => ({ label, nodeKind, inputType: 'execution', targetPinLabel: 'exec', destination: 'graph' }))
    .concat([
      { label: 'Get player flag', nodeKind: 'get-player-flag', inputType: null, destination: 'graph' },
      { label: 'Set player flag', nodeKind: 'set-player-flag', inputType: 'execution', targetPinLabel: 'exec', destination: 'graph' },
      { label: 'Get player string', nodeKind: 'get-player-string', inputType: null, destination: 'graph' },
      { label: 'Set player string', nodeKind: 'set-player-string', inputType: 'execution', targetPinLabel: 'exec', destination: 'graph' },
      { label: 'Convert integer to number', nodeKind: 'integer-to-number', inputType: 'data:integer', targetPinLabel: 'value', destination: 'graph' },
      { label: 'Convert string to text', nodeKind: 'string-to-text', inputType: 'data:string', targetPinLabel: 'value', destination: 'graph' }
    ]).map(withKnownInputs);
}

const KNOWN_DATA_INPUTS = {
  say: [['text', 'text'], ['string', 'delay']], wait: [['duration', 'duration']],
  branch: [['boolean', 'condition']], switch: [['string', 'value']], 'do-n': [['integer', 'n']],
  for: [['integer', 'first'], ['integer', 'last'], ['integer', 'step']],
  'for-each': [['list:string', 'items']], while: [['boolean', 'condition']],
  'get-player-flag': [['string', 'name']], 'set-player-flag': [['string', 'name'], ['boolean', 'value']],
  'get-player-string': [['string', 'name']], 'set-player-string': [['string', 'name'], ['string', 'value']],
  'start-quest': [['quest', 'quest']], 'finish-quest': [['quest', 'quest']],
  'deliver-items': [['quest', 'quest'], ['quest-objective', 'objective']],
  'set-flag': [['string', 'flag'], ['boolean', 'value']],
  'set-variable': [['string', 'variable'], ['string', 'name'], ['string', 'value'], ['string', 'operation']],
  message: [['text', 'text'], ['string', 'audience'], ['number', 'radius'], ['location', 'location']],
  'action-bar': [['text', 'text'], ['string', 'audience'], ['number', 'radius'], ['location', 'location']],
  broadcast: [['text', 'text'], ['string', 'audience'], ['number', 'radius'], ['location', 'location']],
  'npc-speak': [['text', 'text'], ['string', 'audience'], ['number', 'radius'], ['location', 'location']],
  title: [['text', 'title'], ['text', 'subtitle'], ['duration', 'fade-in'], ['duration', 'stay'], ['duration', 'fade-out'],
    ['string', 'audience'], ['number', 'radius'], ['location', 'location']],
  'play-sound': [['sound', 'sound'], ['number', 'volume'], ['number', 'pitch'], ['string', 'audience'],
    ['number', 'radius'], ['location', 'location']],
  particle: [['particle', 'particle'], ['integer', 'count'], ['number', 'offset-x'], ['number', 'offset-y'],
    ['number', 'offset-z'], ['number', 'extra'], ['string', 'audience'], ['number', 'radius'], ['location', 'location']],
  'give-item': [['material', 'material'], ['integer', 'amount']],
  'take-item': [['material', 'material'], ['integer', 'amount']], 'give-experience': [['integer', 'amount']],
  'run-command': [['string', 'command'], ['string', 'as']], teleport: [['location', 'location']],
  'lightning-effect': [['location', 'location']], 'npc-move': [['location', 'location']],
  'potion-effect': [['string', 'effect'], ['duration', 'duration'], ['integer', 'amplifier'],
    ['boolean', 'ambient'], ['boolean', 'particles']],
  'spawn-entity': [['entity-type', 'entity'], ['location', 'location']],
  'set-block': [['material', 'material'], ['location', 'location']], 'npc-animation': [['string', 'animation']]
};
const PLAYER_TARGET_NODES = new Set(['start-quest', 'finish-quest', 'deliver-items', 'set-flag', 'set-variable',
  'message', 'action-bar', 'title', 'play-sound', 'particle', 'give-item', 'take-item', 'give-experience',
  'run-command', 'teleport', 'potion-effect', 'npc-speak']);

function withKnownInputs(definition) {
  const declared = (definition.inputTypes || [definition.inputType]).filter(Boolean)
    .map(semanticType => ({ semanticType, label: semanticType === definition.inputType
      ? definition.targetPinLabel || (semanticType === 'execution' ? 'exec' : null) : null }));
  const knownData = [...(PLAYER_TARGET_NODES.has(definition.nodeKind) ? [['player', 'player']] : []),
    ...(KNOWN_DATA_INPUTS[definition.nodeKind] || [])];
  const data = knownData
    .map(([valueType, label]) => ({ semanticType: `data:${valueType}`, label }));
  const inputPins = [...declared, ...data].filter((input, index, values) =>
    values.findIndex(value => value.semanticType === input.semanticType && value.label === input.label) === index);
  return { ...definition, inputPins, inputTypes: [...new Set(inputPins.map(input => input.semanticType))] };
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
  ].map(([label, nodeKind]) => ({ label, nodeKind, inputType: 'execution', targetPinLabel: 'exec', destination: 'graph' }));
}

function lifecycleDefinitions(prefix = 'Lifecycle') {
  return scriptDefinitions(false).map(definition => ({ ...definition,
    label: `${prefix} · ${definition.label}`, nodeKind: `script-${definition.nodeKind}` }));
}
