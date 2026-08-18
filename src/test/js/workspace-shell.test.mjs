import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveResources, kindForPath } from '../../main/resources/static/editor/modules/workspace-shell.js';
import { normalizeLayout } from '../../main/resources/static/editor/modules/layout-store.js';
import { deterministicLayout, normalizeViewport } from '../../main/resources/static/editor/modules/graph-layout.js';

test('groups all Persona content kinds and exposes individual reusable-script files', () => {
  const files = new Map([
    ['npcs/guide.yml', 'id: village:guide\ndisplay-name: "Village Guide"\nplayer-behavior: village:walk\n'],
    ['dialogues/hello.yml', 'id: village:hello\nstart: start\nnodes: {}\n'],
    ['quests/tour.yml', 'id: village:tour\ntitle: "Village Tour"\nphases: []\n'],
    ['behaviors/walk.yml', 'id: village:walk\nscope: player\nroot: {}\n'],
    ['scripts/welcome.yml', 'content-version: 2\nid: welcome\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\nconnections: {}\n'],
    ['scripts/nested/farewell.yml', 'content-version: 2\nid: farewell\ninputs: {}\noutputs: {}\nvariables: {}\nnodes: {}\nconnections: {}\n'],
    ['extensions/custom.yaml', 'vendor: !custom value\n']
  ]);
  const resources = deriveResources(files);
  assert.deepEqual(resources.map(item => item.kind),
    ['npc', 'dialogue', 'quest', 'behavior', 'script', 'script', 'other']);
  assert.equal(resources.find(item => item.id === 'village:guide').label, 'Village Guide');
  assert.equal(resources.find(item => item.id === 'welcome').yamlPath, '');
  assert.match(resources.find(item => item.id === 'village:guide').search, /village:walk/);
});

test('path classification never guesses unsupported YAML as a Persona resource', () => {
  assert.equal(kindForPath('behaviors/a.yml'), 'behavior');
  assert.equal(kindForPath('scripts/a.yml'), 'script');
  assert.equal(kindForPath('scripts/nested/a.yml'), 'script');
  assert.equal(kindForPath('scripts.yml'), 'other');
  assert.equal(kindForPath('vendor/data.yml'), 'other');
  assert.equal(kindForPath('behaviors-not/a.yml'), 'other');
});

test('layout preferences are versioned and bounded', () => {
  assert.deepEqual(normalizeLayout({ version: 0, browserWidth: 9999 }), {
    version: 2, browserWidth: 280, inspectorWidth: 320, dockHeight: 0,
    browserCollapsed: false, inspectorCollapsed: false, dockCollapsed: true,
    inspectorTab: 'inspector', dockTab: 'yaml', centerSplit: 'visual'
  });
  assert.deepEqual(normalizeLayout({ version: 2, browserWidth: -20, inspectorWidth: 9999,
    dockHeight: 9999, browserCollapsed: 1, dockCollapsed: false, inspectorTab: 'forged',
    dockTab: 'secrets', centerSplit: 'unknown' }, 800), {
    version: 2, browserWidth: 220, inspectorWidth: 520, dockHeight: 440,
    browserCollapsed: true, inspectorCollapsed: false, dockCollapsed: false,
    inspectorTab: 'inspector', dockTab: 'yaml', centerSplit: 'visual'
  });
});

test('graph auto-layout is deterministic for branches and cycles', () => {
  const node = id => ({ id, range: { startOffset: id.charCodeAt(0) }, pins: [
    { id: id + ':in', nodeId: id }, { id: id + ':out', nodeId: id }
  ] });
  const projection = { nodes: [node('c'), node('a'), node('b')],
    edges: [
      { sourcePinId: 'a:out', targetPinId: 'b:in' },
      { sourcePinId: 'b:out', targetPinId: 'a:in' },
      { sourcePinId: 'b:out', targetPinId: 'c:in' }
    ] };
  assert.deepEqual(deterministicLayout(projection), deterministicLayout(structuredClone(projection)));
  assert.equal(Object.keys(deterministicLayout(projection)).length, 3);
  assert.deepEqual(normalizeViewport({ x: Infinity, y: -200000, zoom: 9 }), { x: 40, y: -100000, zoom: 2.5 });
});

test('large-project indexing and two-thousand-node layout remain within accepted local budgets', () => {
  const files = new Map(Array.from({ length: 2000 }, (_, index) => [
    `npcs/npc-${index}.yml`, `id: perf:npc-${index}\ndisplay-name: NPC ${index}\n`
  ]));
  let started = performance.now();
  const resources = deriveResources(files);
  assert.equal(resources.length, 2000);
  assert.ok(performance.now() - started < 1500);

  const nodes = Array.from({ length: 2000 }, (_, index) => ({ id: `node-${index}` }));
  const edges = Array.from({ length: 1999 }, (_, index) => ({
    sourcePinId: `node-${index}:out`, targetPinId: `node-${index + 1}:in`
  }));
  const projection = { nodes: nodes.map(node => ({ ...node, pins: [
    { id: `${node.id}:in`, nodeId: node.id }, { id: `${node.id}:out`, nodeId: node.id }
  ] })), edges };
  started = performance.now();
  const layout = deterministicLayout(projection);
  assert.equal(Object.keys(layout).length, 2000);
  assert.ok(performance.now() - started < 2000);
});
