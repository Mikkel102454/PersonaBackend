import test from 'node:test';
import assert from 'node:assert/strict';
import { createWorkspaceState } from '../../main/resources/static/editor/modules/workspace-state.js';
import { liveNodeKeys } from '../../main/resources/static/editor/modules/live-overlays.js';
import { nestedProjection, normalizeProjection } from '../../main/resources/static/editor/modules/graph-projection.js';
import { publicationReady, validationHeading, diagnosticLabel } from '../../main/resources/static/editor/modules/validation.js';
import { deterministicLayout, normalizeViewport } from '../../main/resources/static/editor/modules/graph-layout.js';
import { defaultNodeRenderers } from '../../main/resources/static/editor/modules/node-renderer.js';
import { GraphSelection } from '../../main/resources/static/editor/modules/graph-selection.js';
import { fitViewport } from '../../main/resources/static/editor/modules/graph-viewport.js';
import { connectionsForNode } from '../../main/resources/static/editor/modules/graph-connections.js';
import { boundedResources, resourceMatches } from '../../main/resources/static/editor/modules/content-browser.js';
import { closeTabsToRight, reorderTabs } from '../../main/resources/static/editor/modules/resource-tabs.js';
import { inlineDefaultOperation } from '../../main/resources/static/editor/modules/graph-mutations.js';

test('keeps normalized editor domains separate and bounds forged viewport metadata', () => {
  const state = createWorkspaceState();
  assert.notEqual(state.files, state.documentModels);
  assert.notEqual(state.graphProjections, state.histories);
  assert.deepEqual(normalizeViewport({ x: Infinity, y: -999999, zoom: 99 }), { x: 40, y: -100000, zoom: 2.5 });
});

test('derives live overlays and nested views without mutating authoritative projections', () => {
  const liveData = createWorkspaceState().liveData;
  liveData.quests.set('one', { questId: 'demo:q', phaseId: 'start', objectives: [{ objectiveId: 'collect' }] });
  assert.deepEqual([...liveNodeKeys('quest', 'demo:q', liveData)], ['start', 'collect']);
  const full = { resourceIdentity: 'quest:demo:q', nodes: [
    { id: 'phase', yamlPath: '/phases/0', range: { startOffset: 0 } },
    { id: 'objective', yamlPath: '/phases/0/objectives/0', range: { startOffset: 1 } },
    { id: 'other', yamlPath: '/phases/1', range: { startOffset: 2 } }], edges: [], diagnostics: [] };
  const result = nestedProjection(full, { resourceIdentity: full.resourceIdentity, ownerNodeId: 'phase', rootYamlPath: '/phases/0' });
  assert.deepEqual(result.projection.nodes.map(node => node.id), ['phase', 'objective']);
  assert.equal(full.nodes.length, 3);
});

test('provides deterministic layout and pure validation labels', () => {
  const projection = { nodes: [
    { id: 'b', range: { startOffset: 2 }, pins: [{ id: 'b:in' }] },
    { id: 'a', range: { startOffset: 1 }, pins: [{ id: 'a:out' }] }],
  edges: [{ sourcePinId: 'a:out', targetPinId: 'b:in' }] };
  assert.deepEqual(deterministicLayout(projection), deterministicLayout(projection));
  const state = createWorkspaceState(); state.verified = { capabilities: ['CONTENT_PUBLISH'] };
  state.draftId = 'draft'; state.validationResult = { valid: true, proposedRevision: 'revision' };
  assert.equal(publicationReady(state), true); assert.equal(publicationReady(state, true), false);
  assert.match(validationHeading({ valid: false, diagnostics: [{}] }), /1 error/);
  assert.match(diagnosticLabel({ path: 'a.yml', line: 2, column: 3, message: 'bad' }), /a\.yml:2:3/);
});

test('uses one renderer interface for built-in, custom, and schema-owned nodes and typed pins', () => {
  const renderers = defaultNodeRenderers();
  assert.deepEqual(renderers.describeNode({ title: 'Wait', kind: 'wait' }).classes, []);
  assert.ok(renderers.describeNode({ title: 'Wave', kind: 'action', extensionOwner: 'vendor' }).badges.includes('vendor'));
  assert.ok(renderers.describeNode({ title: 'Raw', kind: 'custom-yaml', custom: true }).badges.includes('custom data'));
  assert.deepEqual(renderers.describeNode({ title: 'line-17', subtitle: 'say', kind: 'script-say' }),
    { title: 'Say line', subtitle: '', classes: [], badges: [] });
  assert.equal(renderers.describeNode({ title: 'reward-3', subtitle: 'give-item', kind: 'script-give-item' }).title,
    'Give Item');
  assert.equal(renderers.describeNode({ title: 'Input', subtitle: 'demo:flow', kind: 'script-input' }).title, 'Input');
  assert.match(renderers.describePin({ direction: 'input', label: 'in', semanticType: 'execution',
    cardinality: 'single', required: true }).ariaLabel, /required/);
});

test('uses pin-default mutations for keyed graphs embedded in every resource kind', () => {
  const pin = { id: 'pin', nodeId: 'node', yamlPath: '/nodes/start/graph/nodes/reward/material' };
  const dialogue = { resourceKind: 'dialogue', nodes: [{ id: 'node', yamlPath: '/nodes/start/graph/nodes/reward' }] };
  assert.deepEqual(inlineDefaultOperation(dialogue, pin, 'DIAMOND'),
    { type: 'SET_PIN_DEFAULT', targetPinId: 'pin', value: 'DIAMOND' });
  assert.deepEqual(inlineDefaultOperation({ resourceKind: 'behavior', nodes: [{ id: 'node', yamlPath: '/root' }] }, pin, '2s'),
    { type: 'EDIT_FIELD', yamlPath: pin.yamlPath, value: '2s' });
});

test('fails closed on unsupported projection protocols and normalizes v3 ports', () => {
  assert.throws(() => normalizeProjection({ graphVersion: 2, nodes: [] }), /Unsupported graph projection version/);
  const projection = normalizeProjection({ graphVersion: 3, nodes: [{ id: 'node', pins: [{ id: 'in', nodeId: 'node',
    direction: 'INPUT', cardinality: 'EXACTLY_ONE' }] }], ports: [] });
  assert.equal(projection.nodes[0].pins[0].direction, 'input');
  assert.equal(projection.nodes[0].pins[0].cardinality, 'single');
});

test('keeps extracted graph and shell helpers bounded and deterministic', () => {
  const selection = new GraphSelection(['a', 'b'], 2); selection.add('c');
  assert.deepEqual([...selection], ['a', 'b']); selection.retain(new Set(['b'])); assert.deepEqual([...selection], ['b']);
  assert.deepEqual(fitViewport([{ x: 0, y: 0 }], 800, 600), { zoom: 1.5, x: 40, y: 40 });
  const projection = { ports: [{ id: 'a:out', nodeId: 'a' }, { id: 'b:in', nodeId: 'b' }],
    edges: [{ sourcePinId: 'a:out', targetPinId: 'b:in' }] };
  assert.equal(connectionsForNode(projection, 'a').length, 1);
  assert.equal(resourceMatches({ search: 'quest demo:path' }, 'demo:path'), true);
  assert.equal(boundedResources(Array.from({ length: 250 }), 1).length, 200);
  assert.deepEqual(reorderTabs(['a', 'b', 'c'], 'a', 2), ['b', 'c', 'a']);
  assert.deepEqual(closeTabsToRight(['a', 'b', 'c'], 1), { kept: ['a', 'b'], closed: ['c'] });
});
