import test from 'node:test';
import assert from 'node:assert/strict';
import { nodeDefinitions, compatibleDefinitions } from '../../main/resources/static/editor/modules/node-registry.js';
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
  assert.ok(nodeDefinitions('npc', schemas).some(item => item.label === 'NPC interaction · Say line'));
  assert.equal(nodeDefinitions('npc', schemas).some(item => item.label.startsWith('Lifecycle ·')), false);
});

test('pin compatibility explains direction, semantics, cardinality replacement, and cycles', () => {
  const output = { id: 'a:out', nodeId: 'a', direction: 'output', channel: 'EXECUTION', valueType: 'execution', semanticType: 'execution', cardinality: 'many' };
  const input = { id: 'b:in', nodeId: 'b', direction: 'input', channel: 'EXECUTION', valueType: 'execution', semanticType: 'execution', cardinality: 'single' };
  assert.deepEqual(connectionCompatibility(output, input, { incoming: [{ id: 'old' }] }),
    { valid: true, replace: [{ id: 'old' }] });
  assert.match(connectionCompatibility(input, output).reason, /output pin/);
  assert.match(connectionCompatibility(output, { ...input, channel: 'DATA', valueType: 'quest', semanticType: 'data:quest' }).reason, /cannot connect/);
  assert.match(connectionCompatibility(output, input, { wouldCycle: true }).reason, /cycle/);
  const dataOutput = { ...output, channel: 'DATA', valueType: 'script', semanticType: 'data:script' };
  assert.equal(connectionCompatibility(dataOutput,
    { ...input, channel: 'DATA', valueType: 'script', semanticType: 'data:script' }).valid, true);
  assert.equal(connectionCompatibility(dataOutput,
    { ...input, channel: 'DATA', valueType: 'quest', semanticType: 'data:quest' }).valid, false);
});
