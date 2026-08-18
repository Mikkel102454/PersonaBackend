import { normalizeViewport } from './graph-viewport.js';
export { normalizeViewport } from './graph-viewport.js';

export function deterministicLayout(projection) {
  const nodes = projection.nodes || [];
  const incoming = new Map(nodes.map(node => [node.id, 0]));
  const outgoing = new Map(nodes.map(node => [node.id, []]));
  const nodeForPin = new Map();
  for (const node of nodes) for (const pin of node.pins || []) nodeForPin.set(pin.id, node.id);
  for (const edge of projection.edges || []) {
    const source = nodeForPin.get(edge.sourcePinId), target = nodeForPin.get(edge.targetPinId);
    if (!source || !target || source === target) continue;
    outgoing.get(source)?.push(target);
    incoming.set(target, (incoming.get(target) || 0) + 1);
  }
  const queue = nodes.filter(node => !incoming.get(node.id)).map(node => node.id).sort();
  const level = new Map(queue.map(id => [id, 0]));
  let queueIndex = 0;
  while (queueIndex < queue.length) {
    const id = queue[queueIndex++];
    for (const target of [...new Set(outgoing.get(id) || [])].sort()) {
      level.set(target, Math.max(level.get(target) || 0, (level.get(id) || 0) + 1));
      incoming.set(target, incoming.get(target) - 1);
      if (incoming.get(target) === 0) queue.push(target);
    }
  }
  let cycleColumn = Math.max(0, ...level.values()) + 1;
  for (const node of [...nodes].sort((a, b) => a.id.localeCompare(b.id)))
    if (!level.has(node.id)) level.set(node.id, cycleColumn);
  const rows = new Map();
  const result = {};
  for (const node of [...nodes].sort((a, b) => {
    const difference = level.get(a.id) - level.get(b.id);
    return difference || a.range.startOffset - b.range.startOffset || a.id.localeCompare(b.id);
  })) {
    const column = level.get(node.id), row = rows.get(column) || 0;
    result[node.id] = { x: 80 + column * 350, y: 70 + row * 180 };
    rows.set(column, row + 1);
  }
  return result;
}

export class GraphLayoutStore {
  constructor(scope) { this.scope = scope; this.database = null; this.writes = 0; this.pruned = false; }
  key(projection) {
    return [this.scope, projection.resourceKind,
      projection.resourceId].map(encodeURIComponent).join(':');
  }
  async load(projection) {
    if (!globalThis.indexedDB) return null;
    try {
      const database = await this.open();
      if (!this.pruned) { this.pruned = true; await this.prune(database); }
      const exact = await request(database.transaction('layouts').objectStore('layouts').get(this.key(projection)));
      let value = exact;
      if (!value) {
        const all = await request(database.transaction('layouts').objectStore('layouts').getAll());
        const installation = this.scope.split(':')[0];
        value = all.filter(item => item.installation === installation
            && item.resourceKind === projection.resourceKind && item.resourceId === projection.resourceId)
          .sort((left, right) => right.savedAt - left.savedAt)[0] || null;
      }
      if (!value || value.graphVersion !== projection.graphVersion) return null;
      const ids = new Set(projection.nodes.map(node => node.id));
      const positions = Object.fromEntries(Object.entries(value.positions || {})
        .filter(([id, point]) => ids.has(id) && Number.isFinite(point?.x) && Number.isFinite(point?.y))
        .map(([id, point]) => [id, { x: Math.max(-100000, Math.min(100000, point.x)),
          y: Math.max(-100000, Math.min(100000, point.y)) }]));
      const comments = (Array.isArray(value.comments) ? value.comments : []).filter(comment => comment
          && typeof comment.text === 'string' && Number.isFinite(comment.x) && Number.isFinite(comment.y))
        .slice(0, 200).map(comment => ({ id: String(comment.id || '').slice(0, 100), text: comment.text.slice(0, 500),
          x: Math.max(-100000, Math.min(100000, comment.x)), y: Math.max(-100000, Math.min(100000, comment.y)) }));
      const groups = (Array.isArray(value.groups) ? value.groups : []).filter(group => group
          && typeof group.label === 'string' && Array.isArray(group.nodeIds))
        .map(group => ({ id: String(group.id || '').slice(0, 100), label: group.label.slice(0, 120),
          color: /^#[0-9a-f]{6}$/i.test(group.color) ? group.color : '#5d77a8',
          nodeIds: group.nodeIds.filter(id => ids.has(id)).slice(0, 500) }))
        .filter(group => group.nodeIds.length).slice(0, 100);
      const colors = Object.fromEntries(Object.entries(value.colors || {})
        .filter(([id, color]) => ids.has(id) && /^#[0-9a-f]{6}$/i.test(color)).slice(0, 2000));
      const edgeIds = new Set((projection.edges || []).map(edge => edge.id));
      let rerouteCount = 0;
      const reroutes = Object.fromEntries(Object.entries(value.reroutes || {}).filter(([edgeId, points]) =>
        edgeIds.has(edgeId) && Array.isArray(points)).map(([edgeId, points]) => [edgeId, points.filter(point =>
          Number.isFinite(point?.x) && Number.isFinite(point?.y) && rerouteCount++ < 2000).slice(0, 16)
        .map(point => ({ x: Math.max(-100000, Math.min(100000, point.x)),
          y: Math.max(-100000, Math.min(100000, point.y)) }))]).filter(([, points]) => points.length));
      return { graphVersion: projection.graphVersion, positions, viewport: normalizeViewport(value.viewport),
        comments, groups, reroutes,
        bookmarks: Array.isArray(value.bookmarks) ? value.bookmarks.filter(id => ids.has(id)).slice(0, 200) : [],
        colors,
        collapsed: Array.isArray(value.collapsed) ? value.collapsed.filter(id => ids.has(id)).slice(0, 2000) : [] };
    } catch { return null; }
  }
  async save(projection, layout) {
    if (!globalThis.indexedDB) return;
    const value = { key: this.key(projection), installation: this.scope.split(':')[0],
      projectRevision: this.scope.substring(this.scope.indexOf(':') + 1),
      resourceKind: projection.resourceKind, resourceId: projection.resourceId,
      graphVersion: projection.graphVersion, positions: layout.positions,
      viewport: normalizeViewport(layout.viewport), comments: layout.comments || [], groups: layout.groups || [],
      reroutes: layout.reroutes || {},
      bookmarks: layout.bookmarks || [], colors: layout.colors || {}, collapsed: layout.collapsed || [],
      savedAt: Date.now() };
    try {
      const database = await this.open();
      await transactionDone(database.transaction('layouts', 'readwrite'), store => store.put(value));
      if (++this.writes % 100 === 0) await this.prune(database);
    } catch { /* Layout persistence is optional and never falls back to project content storage. */ }
  }
  async prune(database) {
    const all = await request(database.transaction('layouts').objectStore('layouts').getAll());
    all.sort((left, right) => right.savedAt - left.savedAt);
    let bytes = 0;
    const expired = Date.now() - 90 * 24 * 60 * 60 * 1000;
    const remove = [];
    for (let index = 0; index < all.length; index++) {
      const size = JSON.stringify(all[index]).length * 2; bytes += size;
      if (all[index].savedAt < expired || index >= 2000 || bytes > 20 * 1024 * 1024) remove.push(all[index].key);
    }
    if (remove.length) await transactionDone(database.transaction('layouts', 'readwrite'),
      store => remove.forEach(key => store.delete(key)));
  }
  open() {
    if (this.database) return this.database;
    this.database = new Promise((resolve, reject) => {
      const opening = indexedDB.open('persona-graph-layout', 1);
      opening.onupgradeneeded = () => {
        const store = opening.result.createObjectStore('layouts', { keyPath: 'key' });
        store.createIndex('savedAt', 'savedAt');
      };
      opening.onsuccess = () => resolve(opening.result);
      opening.onerror = () => reject(opening.error);
    });
    return this.database;
  }
}

function request(value) {
  return new Promise((resolve, reject) => {
    value.onsuccess = () => resolve(value.result);
    value.onerror = () => reject(value.error);
  });
}
function transactionDone(transaction, operation) {
  return new Promise((resolve, reject) => {
    operation(transaction.objectStore('layouts'));
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}
