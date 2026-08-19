import test from 'node:test';
import assert from 'node:assert/strict';
import { nodeDefinitions, compatibleDefinitions, matchingDefinitionInput, automaticNodeId, yamlMappingKeys } from '../../main/resources/static/editor/modules/node-registry.js';
import { connectionCompatibility } from '../../main/resources/static/editor/modules/connection-rules.js';

test('signed schema descriptors become data-only compatible palette entries', () => {
  const schemas = [
    { contentType: 'behavior-action', typeId: 'vendor:wave' },
    { contentType: 'behavior-condition', typeId: 'vendor:nearby' },
    { contentType: 'command', typeId: 'vendor:toast', schema: { 'x-persona-value-types': { 'vendor:receipt': {} } } },
    { contentType: 'objective', typeId: 'vendor:visit' }
  ];
  const behavior = nodeDefinitions('behavior', schemas);
  assert.ok(behavior.some(item => item.nodeKind === 'extension-action' && item.extensionType === 'vendor:wave'));
  assert.ok(behavior.some(item => item.nodeKind === 'extension-condition' && item.extensionType === 'vendor:nearby'));
  assert.deepEqual(compatibleDefinitions(behavior, { semanticType: 'objective' }), []);
  assert.ok(compatibleDefinitions(nodeDefinitions('quest', schemas), { semanticType: 'objective' })
    .some(item => item.extensionType === 'vendor:visit'));
  assert.ok(nodeDefinitions('script', schemas).some(item => item.extensionType === 'vendor:toast'));
  const integerConverters = compatibleDefinitions(nodeDefinitions('script', schemas), { semanticType: 'data:integer' });
  assert.ok(integerConverters.some(item => item.nodeKind === 'integer-to-number'));
  assert.ok(integerConverters.some(item => item.nodeKind === 'to-string' && item.valueType === 'integer'));
  assert.equal(integerConverters.some(item => item.nodeKind === 'string-to-text'), false);
  assert.ok(compatibleDefinitions(nodeDefinitions('script', schemas), { semanticType: 'data:vendor:receipt' })
    .some(item => item.nodeKind === 'to-string' && item.valueType === 'vendor:receipt'));
  assert.equal(JSON.stringify(behavior).includes('function'), false);
  assert.ok(nodeDefinitions('npc', schemas).some(item => item.label === 'Say line' && item.destination === 'graph'));
  assert.ok(nodeDefinitions('dialogue', schemas).some(item => item.nodeKind === 'for-each'));
  assert.ok(nodeDefinitions('quest', schemas).some(item => item.nodeKind === 'gate'));
  assert.equal(nodeDefinitions('npc', schemas).some(item => item.label.startsWith('Lifecycle ·')), false);
});

test('context-sensitive node search can be disabled without losing graph-kind entries', () => {
  const definitions = [
    { label: 'Wait', inputType: 'execution' },
    { label: 'Integer converter', inputType: 'data:integer' },
    { label: 'Unavailable', inputType: 'execution' }
  ];
  const source = { semanticType: 'execution' };
  const insertable = definition => definition.label !== 'Unavailable';
  assert.deepEqual(compatibleDefinitions(definitions, source, insertable, true).map(value => value.label), ['Wait']);
  assert.deepEqual(compatibleDefinitions(definitions, source, insertable, false), definitions);
});

test('context-sensitive node search matches execution and typed data outputs to real input pins', () => {
  const definitions = nodeDefinitions('script');
  const booleanConsumers = compatibleDefinitions(definitions,
    { channel: 'DATA', valueType: 'boolean', semanticType: 'stale-contract-value' });
  const branch = booleanConsumers.find(definition => definition.nodeKind === 'branch');
  assert.ok(branch);
  assert.deepEqual(matchingDefinitionInput(branch, { channel: 'DATA', valueType: 'boolean' }),
    { semanticType: 'data:boolean', label: 'condition' });
  const numberConsumers = compatibleDefinitions(definitions, { channel: 'DATA', valueType: 'number' });
  assert.ok(numberConsumers.some(definition => definition.nodeKind === 'play-sound'));
  assert.ok(compatibleDefinitions(definitions, { channel: 'EXECUTION', semanticType: 'legacy-exec' })
    .some(definition => definition.nodeKind === 'wait'));
  assert.ok(compatibleDefinitions(nodeDefinitions('quest'), { channel: 'EXECUTION', semanticType: 'phase-flow' })
    .some(definition => definition.nodeKind === 'quest-phase'));
});

test('offers typed comparison and boolean logic nodes from dragged data pins', () => {
  const numbers = nodeDefinitions('script', [], 'number');
  const greater = compatibleDefinitions(numbers, { channel: 'DATA', valueType: 'number' })
    .find(definition => definition.nodeKind === 'greater-than');
  assert.ok(greater);
  assert.deepEqual(greater.inputPins.filter(pin => pin.semanticType === 'data:number').map(pin => pin.label), ['left', 'right']);
  assert.equal(greater.valueType, 'number');
  const booleans = compatibleDefinitions(nodeDefinitions('script', [], 'boolean'), { channel: 'DATA', valueType: 'boolean' });
  assert.ok(booleans.some(definition => definition.nodeKind === 'equals'));
  assert.ok(booleans.some(definition => definition.nodeKind === 'or'));
  assert.equal(booleans.some(definition => definition.nodeKind === 'greater-than'), false);
});

test('generates readable collision-free node IDs without user input', () => {
  assert.equal(automaticNodeId({ label: 'Give item' }, []), 'give-item');
  assert.equal(automaticNodeId({ label: 'Give item' }, [{ title: 'give-item' }, { title: 'give-item-2' }]), 'give-item-3');
  assert.equal(automaticNodeId({ label: 'Extension · Toast', extensionType: 'vendor:toast' }, []), 'toast');
});

test('finds raw YAML mapping keys when the visual projection is stale', () => {
  const keys = yamlMappingKeys("on-click:\n  nodes:\n    give-item: { type: give-item }\n    'quoted-node': {}\n");
  assert.deepEqual(keys, ['on-click', 'nodes', 'give-item', 'quoted-node']);
  assert.equal(automaticNodeId({ label: 'Give item' }, keys.map(title => ({ title }))), 'give-item-2');
});

test('pin compatibility explains direction, semantics, cardinality replacement, and cycles', () => {
  const output = { id: 'a:out', nodeId: 'a', direction: 'output', channel: 'EXECUTION', valueType: 'execution', semanticType: 'execution', cardinality: 'many' };
  const input = { id: 'b:in', nodeId: 'b', direction: 'input', channel: 'EXECUTION', valueType: 'execution', semanticType: 'execution', cardinality: 'single' };
  assert.deepEqual(connectionCompatibility(output, input, { incoming: [{ id: 'old' }] }),
    { valid: true, replace: [{ id: 'old' }] });
  const boundedOutput = { ...output, cardinality: 'ZERO_OR_ONE' };
  assert.deepEqual(connectionCompatibility(boundedOutput, input,
    { incoming: [{ id: 'incoming' }], outgoing: [{ id: 'outgoing' }] }).replace.map(edge => edge.id),
    ['incoming', 'outgoing']);
  assert.match(connectionCompatibility(input, output).reason, /output pin/);
  assert.match(connectionCompatibility(output, { ...input, channel: 'DATA', valueType: 'quest', semanticType: 'data:quest' }).reason, /cannot connect/);
  assert.match(connectionCompatibility(output, input, { wouldCycle: true }).reason, /cycle/);
  const dataOutput = { ...output, channel: 'DATA', valueType: 'script', semanticType: 'data:script' };
  assert.equal(connectionCompatibility(dataOutput,
    { ...input, channel: 'DATA', valueType: 'script', semanticType: 'data:script' }).valid, true);
  assert.equal(connectionCompatibility(dataOutput,
    { ...input, channel: 'DATA', valueType: 'quest', semanticType: 'data:quest' }).valid, false);
});
