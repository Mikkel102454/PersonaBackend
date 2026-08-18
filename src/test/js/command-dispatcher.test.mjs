import test from 'node:test';
import assert from 'node:assert/strict';
import { CommandDispatcher } from '../../main/resources/static/editor/modules/command-dispatcher.js';

test('dispatches every entry point through one enabled command registry', () => {
  const calls = [], dispatcher = new CommandDispatcher();
  dispatcher.register('graph.connect', { label: 'Connect nodes', keywords: 'wire pins',
    enabled: payload => payload?.allowed !== false, run: payload => calls.push(payload.value) });
  assert.equal(dispatcher.execute('graph.connect', { value: 1, allowed: true }), true);
  assert.equal(dispatcher.execute('graph.connect', { value: 2, allowed: false }), false);
  assert.deepEqual(calls, [1]);
  assert.equal(dispatcher.entries('pins')[0].id, 'graph.connect');
  dispatcher.register('graph.delete', { label: 'Delete', enabled: () => false,
    disabledReason: () => 'Select a node.', run() {} });
  assert.deepEqual(dispatcher.entries('delete').map(value => [value.available, value.reason]), [[false, 'Select a node.']]);
  assert.throws(() => dispatcher.register('graph.connect', { run() {} }), /duplicate/);
});
