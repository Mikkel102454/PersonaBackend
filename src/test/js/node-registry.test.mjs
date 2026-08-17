import test from 'node:test';
import assert from 'node:assert/strict';
import { nodeDefinitions, compatibleDefinitions } from '../../main/resources/static/editor/modules/node-registry.js';
import { connectionCompatibility } from '../../main/resources/static/editor/modules/connection-rules.js';

test('signed schema descriptors become data-only compatible palette entries', () => {
  const schemas = [
    { contentType: 'behavior-action', typeId: 'vendor:wave' },
    { contentType: 'behavior-condition', typeId: 'vendor:nearby' },
    { contentType: 'command', typeId: 'vendor:toast' },
    { contentType: 'objective', typeId: 'vendor:visit' }
  ];
  const behavior = nodeDefinitions('behavior', schemas);
  assert.ok(behavior.some(item => item.nodeKind === 'extension-action' && item.extensionType === 'vendor:wave'));
  assert.ok(behavior.some(item => item.nodeKind === 'extension-condition' && item.extensionType === 'vendor:nearby'));
  assert.deepEqual(compatibleDefinitions(behavior, { semanticType: 'objective' }), []);
  assert.ok(compatibleDefinitions(nodeDefinitions('quest', schemas), { semanticType: 'objective' })
    .some(item => item.extensionType === 'vendor:visit'));
  assert.ok(nodeDefinitions('script', schemas).some(item => item.extensionType === 'vendor:toast'));
  assert.equal(JSON.stringify(behavior).includes('function'), false);
});

test('pin compatibility explains direction, semantics, cardinality replacement, and cycles', () => {
  const output = { id: 'a:out', nodeId: 'a', direction: 'output', semanticType: 'execution', cardinality: 'many' };
  const input = { id: 'b:in', nodeId: 'b', direction: 'input', semanticType: 'execution', cardinality: 'single' };
  assert.deepEqual(connectionCompatibility(output, input, { incoming: [{ id: 'old' }] }),
    { valid: true, replace: [{ id: 'old' }] });
  assert.match(connectionCompatibility(input, output).reason, /output pin/);
  assert.match(connectionCompatibility(output, { ...input, semanticType: 'objective' }).reason, /cannot connect/);
  assert.match(connectionCompatibility(output, input, { wouldCycle: true }).reason, /cycle/);
  assert.equal(connectionCompatibility({ ...output, semanticType: 'reference' },
    { ...input, semanticType: 'reference:script' }).valid, true);
});
