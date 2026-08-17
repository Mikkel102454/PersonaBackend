import test from 'node:test';
import assert from 'node:assert/strict';
import { createWorkspaceState } from '../../main/resources/static/editor/modules/workspace-state.js';
import { liveNodeKeys } from '../../main/resources/static/editor/modules/live-overlays.js';
import { nestedProjection } from '../../main/resources/static/editor/modules/graph-projection.js';
import { publicationReady, validationHeading, diagnosticLabel } from '../../main/resources/static/editor/modules/validation.js';
import { deterministicLayout, normalizeViewport } from '../../main/resources/static/editor/modules/graph-layout.js';
import { defaultNodeRenderers } from '../../main/resources/static/editor/modules/node-renderer.js';

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
  assert.match(renderers.describePin({ direction: 'input', label: 'in', semanticType: 'execution',
    cardinality: 'single', required: true }).ariaLabel, /required/);
});
