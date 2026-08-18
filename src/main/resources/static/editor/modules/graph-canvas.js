import { deterministicLayout } from './graph-layout.js';
import { fitViewport, normalizeViewport } from './graph-viewport.js';
import { defaultNodeRenderers } from './node-renderer.js';
import { connectionCompatibility } from './connection-rules.js';
import { normalizeProjection } from './graph-projection.js';
import { GraphSelection } from './graph-selection.js';
import { renderGraphMinimap } from './graph-minimap.js';

const SVG = 'http://www.w3.org/2000/svg';
const NODE_WIDTH = 220, NODE_HEIGHT = 130;

export class GraphCanvas {
  constructor(options) {
    this.options = options;
    this.renderers = options.renderers || defaultNodeRenderers();
    this.canvas = document.querySelector('#graph-canvas');
    this.plane = document.querySelector('#graph-plane');
    this.nodesLayer = document.querySelector('#graph-nodes');
    this.wires = document.querySelector('#graph-wires');
    this.minimap = document.querySelector('#graph-minimap');
    this.marquee = document.querySelector('#graph-marquee');
    this.empty = document.querySelector('#graph-empty');
    this.zoomOutput = document.querySelector('#graph-zoom');
    this.projection = null;
    this.positions = {};
    this.viewport = normalizeViewport(null);
    this.selection = new GraphSelection();
    this.comments = [];
    this.groups = [];
    this.bookmarks = new Set();
    this.colors = {};
    this.collapsed = new Set();
    this.reroutes = {};
    this.liveNodeKeys = new Set();
    this.diagnosticPaths = new Set();
    this.focusedNodes = null;
    this.pendingPin = null;
    this.pendingReconnectEdge = null;
    this.previewPath = null;
    this.renderFrame = null;
    this.minimapFrame = null;
    this.bind();
  }

  bind() {
    this.canvas.addEventListener('wheel', event => this.zoom(event), { passive: false });
    this.canvas.addEventListener('pointerdown', event => this.pointerDown(event));
    this.canvas.addEventListener('keydown', event => this.keydown(event));
    this.canvas.addEventListener('dragover',event=>{if(event.dataTransfer?.types?.includes('application/x-persona-resource')&&this.projection?.resourceKind==='script'){event.preventDefault();event.dataTransfer.dropEffect='copy';}});
    this.canvas.addEventListener('drop',event=>{const raw=event.dataTransfer?.getData('application/x-persona-resource');if(!raw||this.projection?.resourceKind!=='script')return;event.preventDefault();try{const resource=JSON.parse(raw),bounds=this.canvas.getBoundingClientRect(),element=document.elementFromPoint(event.clientX,event.clientY)?.closest?.('.graph-pin'),target=element?this.pin(element.dataset.pinId):null,source={id:'dragged-resource',nodeId:'dragged-resource',direction:'output',channel:'DATA',valueType:resource.kind,semanticType:`data:${resource.kind}`,cardinality:'single'};if(target){const check=this.compatible(source,target);if(!check.valid){this.options.onConnectionError?.(check.reason);return;}}this.options.onResourceDrop?.(resource,{x:(event.clientX-bounds.left-this.viewport.x)/this.viewport.zoom,y:(event.clientY-bounds.top-this.viewport.y)/this.viewport.zoom},target);}catch{this.options.onConnectionError?.('The dragged resource payload is invalid.');}});
    this.canvas.addEventListener('contextmenu', event => {
      if (event.target.closest('.graph-node-card, .graph-pin')) return;
      event.preventDefault();
      this.options.onPalette?.({ clientX: event.clientX, clientY: event.clientY, sourcePin: null });
    });
    document.querySelector('#graph-fit').addEventListener('click', () => this.zoomToFit());
    document.querySelector('#graph-reset').addEventListener('click', () => { this.viewport = normalizeViewport(null); this.applyViewport(); this.changed(); });
    document.querySelector('#graph-grid').addEventListener('click', event => {
      const enabled = this.canvas.classList.toggle('grid');
      event.currentTarget.setAttribute('aria-pressed', String(enabled));
    });
    this.minimap.addEventListener('pointerdown', event => this.panFromMinimap(event));
    this.minimap.addEventListener('keydown', event => {
      if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
      event.preventDefault(); const amount = event.shiftKey ? 160 : 40;
      if (event.key === 'ArrowLeft') this.viewport.x += amount;
      if (event.key === 'ArrowRight') this.viewport.x -= amount;
      if (event.key === 'ArrowUp') this.viewport.y += amount;
      if (event.key === 'ArrowDown') this.viewport.y -= amount;
      this.applyViewport(); this.schedule(); this.changed();
    });
    new ResizeObserver(() => this.schedule()).observe(this.canvas);
  }

  setProjection(projection, layout) {
    projection = normalizeProjection(projection);
    this.projection = projection;
    this.focusedNodes = null;
    const defaults = deterministicLayout(projection);
    this.positions = { ...defaults, ...(layout?.positions || {}) };
    this.viewport = normalizeViewport(layout?.viewport);
    this.selection = new GraphSelection((layout?.selection || []).filter(id => projection.nodes.some(node => node.id === id)));
    const ids = new Set(projection.nodes.map(node => node.id));
    this.comments = Array.isArray(layout?.comments) ? layout.comments.slice(0, 200) : [];
    this.groups = Array.isArray(layout?.groups) ? layout.groups.map(group => ({ ...group,
      nodeIds: (group.nodeIds || []).filter(id => ids.has(id)).slice(0, 500) })).filter(group => group.nodeIds.length).slice(0, 100) : [];
    this.bookmarks = new Set((layout?.bookmarks || []).filter(id => ids.has(id)));
    this.colors = Object.fromEntries(Object.entries(layout?.colors || {}).filter(([id, color]) => ids.has(id) && /^#[0-9a-f]{6}$/i.test(color)));
    this.collapsed = new Set((layout?.collapsed || []).filter(id => ids.has(id)));
    const edgeIds = new Set((projection.edges || []).map(edge => edge.id));
    this.reroutes = Object.fromEntries(Object.entries(layout?.reroutes || {}).filter(([id]) => edgeIds.has(id)));
    this.cancelPinConnection();
    this.empty.hidden = Boolean(projection.nodes?.length);
    this.schedule();
    this.options.onSelection(projection.nodes.filter(node => this.selection.has(node.id)));
  }

  clear() {
    this.projection = null; this.positions = {}; this.selection.clear(); this.comments = []; this.groups = [];
    this.bookmarks.clear(); this.colors = {}; this.collapsed.clear();
    this.focusedNodes = null;
    this.reroutes = {};
    this.cancelPinConnection();
    if (this.minimapFrame) cancelAnimationFrame(this.minimapFrame); this.minimapFrame = null;
    this.nodesLayer.replaceChildren(); this.wires.replaceChildren(); this.minimap.replaceChildren();
    this.empty.hidden = false; this.options.onSelection([]);
  }

  restoreLayout(layout) {
    if (!this.projection || !layout) return;
    const defaults = deterministicLayout(this.projection), ids = new Set(this.projection.nodes.map(node => node.id));
    this.positions = { ...defaults, ...Object.fromEntries(Object.entries(layout.positions || {})
      .filter(([id, point]) => ids.has(id) && Number.isFinite(point?.x) && Number.isFinite(point?.y))) };
    this.viewport = normalizeViewport(layout.viewport);
    this.selection = new GraphSelection((layout.selection || []).filter(id => ids.has(id)));
    this.comments = Array.isArray(layout.comments) ? structuredClone(layout.comments.slice(0, 200)) : this.comments;
    this.groups = Array.isArray(layout.groups) ? structuredClone(layout.groups.slice(0, 100)) : this.groups;
    this.bookmarks = new Set(Array.isArray(layout.bookmarks) ? layout.bookmarks.filter(id => ids.has(id)) : this.bookmarks);
    this.colors = layout.colors ? { ...layout.colors } : this.colors;
    this.collapsed = new Set(Array.isArray(layout.collapsed) ? layout.collapsed.filter(id => ids.has(id)) : this.collapsed);
    this.reroutes = layout.reroutes ? structuredClone(layout.reroutes) : this.reroutes;
    this.schedule(); this.applyViewport(); this.changed();
    this.options.onSelection(this.projection.nodes.filter(node => this.selection.has(node.id)));
  }

  setStale(stale, message) {
    this.canvas.inert = Boolean(stale);
    this.canvas.classList.toggle('stale', Boolean(stale));
    const notice = document.querySelector('#graph-stale');
    if (message) notice.textContent = message;
    notice.hidden = !stale;
  }

  setLiveNodeKeys(keys) { this.liveNodeKeys = new Set(keys || []); this.schedule(); }
  setDiagnosticPaths(paths) { this.diagnosticPaths = new Set(paths || []); this.schedule(); }

  schedule() {
    if (this.renderFrame) return;
    this.renderFrame = requestAnimationFrame(() => { this.renderFrame = null; this.render(); });
  }

  render() {
    if (!this.projection) { this.clear(); return; }
    const focusedNodeId = document.activeElement?.closest?.('.graph-node-card')?.dataset.nodeId;
    const visible = this.visibleNodeIds();
    const fragment = document.createDocumentFragment();
    for (const group of this.groups) fragment.append(this.groupElement(group));
    for (const comment of this.comments) fragment.append(this.commentElement(comment));
    for (const node of this.projection.nodes) if (visible.has(node.id)) fragment.append(this.nodeElement(node));
    this.nodesLayer.replaceChildren(fragment);
    if (focusedNodeId) this.nodesLayer.querySelector('[data-node-id="' + CSS.escape(focusedNodeId) + '"]')?.focus();
    this.renderWires(visible);
    this.applyViewport();
  }

  visibleNodeIds() {
    if (!this.projection) return new Set();
    const margin = 500 / this.viewport.zoom;
    const left = -this.viewport.x / this.viewport.zoom - margin;
    const top = -this.viewport.y / this.viewport.zoom - margin;
    const right = (this.canvas.clientWidth - this.viewport.x) / this.viewport.zoom + margin;
    const bottom = (this.canvas.clientHeight - this.viewport.y) / this.viewport.zoom + margin;
    const visible = new Set([...this.selection, ...this.bookmarks]);
    for (const node of this.projection.nodes) {
      const point = this.positions[node.id];
      if (point && point.x + NODE_WIDTH >= left && point.x <= right && point.y + NODE_HEIGHT >= top && point.y <= bottom)
        visible.add(node.id);
    }
    return visible;
  }

  scheduleMinimap() {
    if (this.minimapFrame) return;
    this.minimapFrame = requestAnimationFrame(() => { this.minimapFrame = null; this.renderMinimap(); });
  }

  nodeElement(node) {
    const position = this.positions[node.id] || { x: 0, y: 0 };
    const card = document.createElement('article');
    const presentation = this.renderers.describeNode(node);
    card.className = ['graph-node-card', node.kind, ...presentation.classes].join(' ');
    if (this.focusedNodes && !this.focusedNodes.has(node.id)) card.classList.add('graph-dimmed');
    if ((node.badges || []).includes('start')) card.classList.add('start-node');
    if (this.bookmarks.has(node.id)) card.classList.add('bookmarked');
    if (this.collapsed.has(node.id)) card.classList.add('collapsed');
    if (this.colors[node.id]) card.style.setProperty('--node-label-color', this.colors[node.id]);
    const live = this.liveNodeKeys.has(node.title) || this.liveNodeKeys.has(node.yamlPath);
    if (live) card.classList.add('live-active');
    const nodeIssues = (this.projection.diagnostics || []).filter(issue => issue.nodeId === node.id
      || issue.yamlPath && (issue.yamlPath === node.yamlPath || issue.yamlPath.startsWith(node.yamlPath + '/')));
    if (this.diagnosticPaths.has(node.title)
        || [...this.diagnosticPaths].some(path => path && (path === node.yamlPath || path.startsWith(node.yamlPath + '/'))))
      card.classList.add('has-diagnostic');
    card.dataset.nodeId = node.id; card.dataset.yamlPath = node.yamlPath;
    card.style.transform = 'translate(' + position.x + 'px,' + position.y + 'px)';
    card.tabIndex = 0; card.setAttribute('role', 'group');
    card.setAttribute('aria-label', [node.title, node.kind,
      nodeIssues.length ? `${nodeIssues.length} validation ${nodeIssues.length === 1 ? 'issue' : 'issues'}` : 'no validation issues',
      this.selection.has(node.id) ? 'selected' : 'not selected', live ? 'live' : 'not live',
      this.projection.editable ? 'editable' : `read only${this.projection.readOnlyReason ? `: ${this.projection.readOnlyReason}` : ''}`,
      'YAML ' + (node.yamlPath || 'synthetic')].join(', '));
    if (this.selection.has(node.id)) card.setAttribute('aria-current', 'true');
    const header = document.createElement('header'); header.className = 'graph-node-header';
    const titles = document.createElement('span'); titles.className = 'graph-node-title';
    const title = document.createElement('strong'); title.textContent = presentation.title;
    const subtitle = document.createElement('small'); subtitle.textContent = presentation.subtitle;
    titles.append(title, subtitle);
    const badges = document.createElement('span'); badges.className = 'graph-badges';
    const badgeValues = new Set(node.badges || []);
    for (const badge of presentation.badges) badgeValues.add(badge);
    for (const issue of nodeIssues) badgeValues.add(String(issue.severity || 'diagnostic').toLowerCase());
    if (live) badgeValues.add('live');
    if (node.custom) badgeValues.add('custom data');
    if (node.extensionOwner) badgeValues.add(node.extensionOwner);
    if (this.options.isDirtyNode?.(node)) badgeValues.add('dirty');
    if (this.bookmarks.has(node.id)) badgeValues.add('bookmark');
    for (const value of badgeValues) { const badge = document.createElement('span'); badge.className = 'graph-badge'; badge.textContent = value; badges.append(badge); }
    const actions = document.createElement('span'); actions.className = 'graph-card-actions';
    const actionTrigger=document.createElement('button');actionTrigger.type='button';actionTrigger.className='graph-card-menu-trigger';actionTrigger.textContent='⋯';actionTrigger.setAttribute('aria-label',`Actions for ${node.title}`);actionTrigger.setAttribute('aria-haspopup','menu');actionTrigger.setAttribute('aria-expanded','false');
    const actionMenu=document.createElement('span');actionMenu.className='graph-card-menu';actionMenu.setAttribute('role','menu');actionMenu.hidden=true;actions.append(actionTrigger,actionMenu);
    actionTrigger.addEventListener('pointerdown',event=>event.stopPropagation());actionTrigger.addEventListener('click',event=>{event.stopPropagation();actionMenu.hidden=!actionMenu.hidden;actionTrigger.setAttribute('aria-expanded',String(!actionMenu.hidden));if(!actionMenu.hidden)actionMenu.querySelector('button')?.focus();});actionTrigger.addEventListener('keydown',event=>{if(event.key==='Escape'){actionMenu.hidden=true;actionTrigger.setAttribute('aria-expanded','false');actionTrigger.focus();}});
    const action = (label, type) => {
      const button = document.createElement('button'); button.type = 'button'; button.textContent = label;button.setAttribute('role','menuitem');
      button.addEventListener('pointerdown', event => event.stopPropagation());
      button.addEventListener('click', event => { event.stopPropagation();actionMenu.hidden=true;actionTrigger.setAttribute('aria-expanded','false'); this.options.onNodeAction?.({ type, node }); });
      actionMenu.append(button);
    };
    if (node.kind === 'dialogue-entry' && !(node.badges || []).includes('start')) action('Set as start', 'SET_DIALOGUE_START');
    if (node.kind === 'quest-phase') action('Open objectives', 'OPEN_QUEST_OBJECTIVES');
    if (node.kind === 'script-input' || node.kind === 'script-output') action('Add parameter…', 'ADD_SCRIPT_PARAMETER');
    if (node.kind === 'resource-reference') action('Open resource', 'OPEN_REFERENCED_RESOURCE');
    if (node.kind === 'npc-anchor') action('Paste coordinates', 'PASTE_ANCHOR_COORDINATES');
    if (node.kind === 'npc') {
      action('Create and assign player behavior', 'CREATE_ASSIGN_PLAYER_BEHAVIOR');
      action('Create and assign shared behavior', 'CREATE_ASSIGN_SHARED_BEHAVIOR');
      action('Create and assign dialogue', 'CREATE_ASSIGN_DIALOGUE');
    }
    if ((node.kind === 'script-goto' || node.kind === 'goto')
        && (node.fields || []).some(field => field.label === 'dialogue')) action('Open dialogue', 'OPEN_TRANSFER_DIALOGUE');
    if (node.kind === 'reusable-script') action('Show callers', 'SHOW_SCRIPT_CALLERS');
    if ((node.kind.startsWith('script-') || node.kind === 'extension-command') && node.yamlPath)
      action('Extract to reusable script', 'EXTRACT_TO_SCRIPT');
    action(this.bookmarks.has(node.id) ? 'Unbookmark' : 'Bookmark', 'TOGGLE_BOOKMARK');
    action(this.collapsed.has(node.id) ? 'Expand' : 'Collapse', 'TOGGLE_COLLAPSE');
    const unresolved = (this.projection.diagnostics || []).find(issue => issue.nodeId === node.id && issue.relatedResourceKind)
      || (node.badges || []).includes('unresolved');
    if (node.kind === 'missing-reference' || unresolved) action('Create missing', 'CREATE_MISSING_RESOURCE');
    const resourceRoot = node.kind === 'npc' || node.kind === 'quest' || node.kind === 'reusable-script'
      || node.kind === 'script-input' || node.kind === 'script-output'
      || this.projection.resourceKind === 'behavior' && node.yamlPath === `${this.projection.rootYamlPath}/root`.replace(/^\/\//, '/');
    if (this.projection.editable && node.yamlPath && !node.custom && !resourceRoot) {
      action('Duplicate', 'DUPLICATE_NODE'); action('Delete', 'DELETE_NODE');
      if (this.projection.resourceKind === 'behavior') {
        const parent = node.yamlPath.substring(0, node.yamlPath.lastIndexOf('/'));
        const siblings = this.projection.nodes.filter(value => value.yamlPath
          && value.yamlPath.substring(0, value.yamlPath.lastIndexOf('/')) === parent
          && /^\d+$/.test(value.yamlPath.split('/').at(-1)))
          .sort((left, right) => Number(left.yamlPath.split('/').at(-1)) - Number(right.yamlPath.split('/').at(-1)));
        const siblingIndex = siblings.findIndex(value => value.id === node.id);
        if (siblingIndex > 0) action('Move earlier', 'MOVE_NODE_EARLIER');
        if (siblingIndex >= 0 && siblingIndex < siblings.length - 1) action('Move later', 'MOVE_NODE_LATER');
        action('Wrap', 'WRAP_NODE');
        if (['sequence', 'selector', 'priority-selector', 'parallel', 'invert', 'repeat', 'retry', 'timeout', 'cooldown', 'checkpoint'].includes(node.kind))
          action('Unwrap', 'UNWRAP_NODE');
      }
    }
    header.append(titles, badges, actions);
    if(node.kind==='script-input'||node.kind==='script-output'){const add=document.createElement('button');add.type='button';add.className='script-parameter-add';add.textContent='+';add.setAttribute('aria-label',`Add ${node.kind==='script-input'?'input':'output'} parameter`);add.addEventListener('pointerdown',event=>event.stopPropagation());add.addEventListener('click',event=>{event.stopPropagation();this.options.onNodeAction?.({type:'ADD_SCRIPT_PARAMETER',node});});header.append(add);}
    header.addEventListener('pointerdown', event => this.startNodeDrag(event, node.id));
    const fields = document.createElement('ul'); fields.className = 'graph-node-fields';
    for (const field of (node.fields || []).slice(0, 4)) {
      const item = document.createElement('li'), label = document.createElement('span'), value = document.createElement('span');
      label.textContent = field.label; value.textContent = field.value ?? field.valueType;
      item.append(label, value); fields.append(item);
    }
    const pins = document.createElement('div'); pins.className = 'graph-pins';
    const inputs = document.createElement('div'); inputs.className = 'graph-pin-column inputs';
    const outputs = document.createElement('div'); outputs.className = 'graph-pin-column outputs';
    for (const pin of node.pins || []) {
      const column = pin.direction === 'input' ? inputs : outputs; column.append(this.pinElement(pin));
      if (pin.direction === 'input' && pin.channel === 'DATA' && !this.incoming(pin.id).length)
        column.append(this.inlineDefaultElement(pin));
    }
    pins.append(inputs, outputs);
    card.append(header, fields, pins);
    card.addEventListener('click', event => {
      if (event.target.closest('.graph-pin')) return;
      this.select(node.id, event.ctrlKey || event.metaKey || event.shiftKey);
    });
    card.addEventListener('dblclick', () => this.options.onNavigateSource(node));
    card.addEventListener('pointerdown', () => { card.dataset.pointerFocus = 'true'; }, { capture: true });
    card.addEventListener('pointerup', () => { setTimeout(() => { delete card.dataset.pointerFocus; }, 0); }, { capture: true });
    card.addEventListener('pointercancel', () => { delete card.dataset.pointerFocus; }, { capture: true });
    card.addEventListener('focus', () => {
      if (card.dataset.pointerFocus !== 'true' && !this.selection.has(node.id)) this.select(node.id, false);
    });
    return card;
  }

  commentElement(comment) {
    const element = document.createElement('aside'); element.className = 'graph-layout-comment';
    element.textContent = comment.text; element.style.transform = `translate(${comment.x}px,${comment.y}px)`;
    element.setAttribute('aria-label', `Graph comment: ${comment.text}`); return element;
  }

  groupElement(group) {
    const points = group.nodeIds.map(id => this.positions[id]).filter(Boolean);
    const element = document.createElement('section'); element.className = 'graph-layout-group';
    if (!points.length) return element;
    const minX = Math.min(...points.map(point => point.x)) - 24, minY = Math.min(...points.map(point => point.y)) - 38;
    const maxX = Math.max(...points.map(point => point.x + NODE_WIDTH)) + 24, maxY = Math.max(...points.map(point => point.y + 120)) + 24;
    element.style.transform = `translate(${minX}px,${minY}px)`; element.style.width = `${maxX - minX}px`;
    element.style.height = `${maxY - minY}px`; element.style.setProperty('--group-color', group.color || '#5d77a8');
    const label = document.createElement('strong'); label.textContent = group.label; element.append(label);
    element.setAttribute('aria-label', `Graph group ${group.label}`); return element;
  }

  pinElement(pin) {
    const button = document.createElement('button'); button.type = 'button';
    const presentation = this.renderers.describePin(pin);
    button.className = presentation.className; button.dataset.pinId = pin.id; button.dataset.type = pin.semanticType;
    button.dataset.channel = pin.channel || 'DATA'; button.dataset.valueType = pin.valueType || pin.semanticType;
    button.textContent = presentation.text; button.title = presentation.title;
    const connectionCount = (this.projection?.edges || []).filter(edge =>
      edge.sourcePinId === pin.id || edge.targetPinId === pin.id).length;
    button.classList.toggle('wired', connectionCount > 0);
    button.setAttribute('aria-label', `${presentation.ariaLabel}, ${connectionCount
      ? `${connectionCount} current connection${connectionCount === 1 ? '' : 's'}` : 'not connected'}`);
    button.addEventListener('pointerdown', event => this.startPinConnection(event, pin, button));
    button.addEventListener('click', event => {
      event.stopPropagation();
      if (button.dataset.dragged === 'true') { delete button.dataset.dragged; return; }
      this.activatePin(pin, button);
    });
    return button;
  }

  inlineDefaultElement(pin) {
    const options = this.options.resourceOptions?.(pin.resourceKind) || [];
    const editor = options.length ? document.createElement('select') : document.createElement('input'); editor.className = 'graph-inline-pin-default';
    editor.dataset.pinDefault = pin.id; editor.setAttribute('aria-label', `${pin.label} default, ${pin.valueType}`);
    if (options.length) {
      const empty = document.createElement('option'); empty.value = ''; empty.textContent = `Select ${pin.resourceKind}`; editor.append(empty);
      for (const value of options) { const option = document.createElement('option'); option.value = value.id; option.textContent = value.label || value.id; editor.append(option); }
      editor.value = pin.literal?.defaultValue ?? pin.literal?.value ?? '';
    }
    else if (pin.valueType === 'boolean') { editor.type = 'checkbox'; editor.checked = String(pin.literal?.defaultValue ?? pin.literal?.value) === 'true'; }
    else { editor.type = ['integer', 'number'].includes(pin.valueType) ? 'number' : 'text';
      if (pin.valueType === 'integer') editor.step = '1'; if (pin.valueType === 'duration') editor.placeholder = 'e.g. 500ms';
      if (pin.resourceKind) { editor.setAttribute('role', 'combobox'); editor.placeholder = `Select ${pin.resourceKind}`; }
      editor.value = pin.literal?.defaultValue ?? pin.literal?.value ?? ''; }
    const commit = () => this.options.onInlineDefault?.(pin, editor.type === 'checkbox' ? String(editor.checked) : editor.value);
    editor.addEventListener('change', commit); editor.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); commit(); } });
    editor.addEventListener('pointerdown', event => event.stopPropagation()); return editor;
  }

  pin(pinId) {
    for (const node of this.projection?.nodes || []) {
      const pin = (node.pins || []).find(value => value.id === pinId);
      if (pin) return pin;
    }
    return null;
  }

  incoming(pinId) {
    return (this.projection?.edges || []).filter(edge => edge.targetPinId === pinId);
  }

  compatible(source, target) {
    return connectionCompatibility(source, target, { incoming: target ? this.incoming(target.id) : [],
      wouldCycle: Boolean(source && target && this.wouldCycle(source.nodeId, target.nodeId)),
      capabilities: this.projection?.capabilities || [], resourceScope: 'CURRENT_RESOURCE' });
  }

  wouldCycle(sourceNodeId, targetNodeId) {
    const owners = new Map();
    for (const node of this.projection?.nodes || []) for (const pin of node.pins || []) owners.set(pin.id, node.id);
    const adjacency = new Map();
    for (const edge of this.projection?.edges || []) {
      const source = owners.get(edge.sourcePinId), target = owners.get(edge.targetPinId);
      if (!source || !target) continue;
      if (!adjacency.has(source)) adjacency.set(source, []);
      adjacency.get(source).push(target);
    }
    const queue = [targetNodeId], visited = new Set();
    while (queue.length) {
      const current = queue.shift();
      if (current === sourceNodeId) return true;
      if (!visited.has(current)) { visited.add(current); queue.push(...(adjacency.get(current) || [])); }
    }
    return false;
  }

  activatePin(pin, button) {
    this.options.onPin?.(pin, button);
    if (!this.projection?.editable || !this.projection.capabilities?.includes('CONNECT')) return;
    if (!this.pendingPin) {
      let source = pin, reconnect = null;
      if (pin.direction === 'input') {
        const incoming = this.incoming(pin.id);
        if (incoming.length !== 1) {
          this.options.onConnectionError?.('An input can start reconnect only when it has exactly one existing connection.'); return;
        }
        reconnect = incoming[0]; source = this.pin(reconnect.sourcePinId);
      }
      if (!source || source.direction !== 'output') return;
      this.pendingPin = source; this.pendingReconnectEdge = reconnect;
      button.setAttribute('aria-pressed', 'true');
      this.options.onConnectionPending?.(source);
      return;
    }
    const source = this.pendingPin;
    const reconnect = this.pendingReconnectEdge;
    this.cancelPinConnection();
    if (source.id === pin.id) return;
    this.connectPins(source, pin, reconnect);
  }

  startPinConnection(event, pin, button) {
    if (event.pointerType !== 'touch' && event.button !== 0 || !this.projection?.editable) return;
    let source = pin, reconnect = null;
    if (pin.direction === 'input') {
      const incoming = this.incoming(pin.id);
      if (incoming.length !== 1) return;
      reconnect = incoming[0]; source = this.pin(reconnect.sourcePinId);
    }
    if (!source || source.direction !== 'output') return;
    event.stopPropagation();
    const startX = event.clientX, startY = event.clientY;
    button.setPointerCapture(event.pointerId);
    const move = current => {
      if (Math.hypot(current.clientX - startX, current.clientY - startY) < 4 && !this.previewPath) return;
      button.dataset.dragged = 'true';
      this.drawConnectionPreview(source, current.clientX, current.clientY);
      this.highlightCompatiblePins(source);
    };
    const end = current => {
      button.removeEventListener('pointermove', move);
      this.clearPinHighlights();
      const dragged = Boolean(this.previewPath);
      this.removeConnectionPreview();
      if (!dragged) return;
      const targetElement = document.elementFromPoint(current.clientX, current.clientY)?.closest?.('.graph-pin');
      const target = targetElement ? this.pin(targetElement.dataset.pinId) : null;
      if (target) this.connectPins(source, target, reconnect);
      else this.options.onPalette?.({ clientX: current.clientX, clientY: current.clientY, sourcePin: source });
    };
    button.addEventListener('pointermove', move);
    button.addEventListener('pointerup', end, { once: true });
    button.addEventListener('pointercancel', end, { once: true });
  }

  connectPins(source, target, reconnectEdge = null) {
    const check = this.compatible(source, target);
    if (!check.valid) { this.options.onConnectionError?.(check.reason); return; }
    const operations = [];
    if (reconnectEdge) {
      operations.push({ type: 'RECONNECT', edgeId: reconnectEdge.id,
        sourcePinId: source.id, targetPinId: target.id });
      this.options.onConnect?.({ source, target, operations }); return;
    }
    for (const edge of this.projection.resourceKind === 'behavior' ? [] : check.replace || []) {
      if (edge.sourcePinId === source.id) continue;
      operations.push({ type: 'RECONNECT', edgeId: edge.id,
        sourcePinId: source.id, targetPinId: target.id });
    }
    if (!operations.length) operations.push({ type: 'CONNECT', sourcePinId: source.id, targetPinId: target.id,
      key: `wire-${crypto.randomUUID().replaceAll('-', '').slice(0, 12)}` });
    this.options.onConnect?.({ source, target, operations });
  }

  highlightCompatiblePins(source) {
    this.nodesLayer.querySelectorAll('.graph-pin.input').forEach(element => {
      const check = this.compatible(source, this.pin(element.dataset.pinId));
      element.classList.toggle('compatible', check.valid);
      element.classList.toggle('incompatible', !check.valid);
    });
  }

  clearPinHighlights() {
    this.nodesLayer.querySelectorAll('.graph-pin').forEach(element => element.classList.remove('compatible', 'incompatible'));
  }

  drawConnectionPreview(source, clientX, clientY) {
    const start = this.pinGraphCenter(source.id); if (!start) return;
    if (!this.previewPath) {
      this.previewPath = document.createElementNS(SVG, 'path');
      this.previewPath.setAttribute('class', 'graph-wire preview');
      this.wires.append(this.previewPath);
    }
    const bounds = this.canvas.getBoundingClientRect();
    const x1 = start.x, y1 = start.y;
    const x2 = (clientX - bounds.left - this.viewport.x) / this.viewport.zoom;
    const y2 = (clientY - bounds.top - this.viewport.y) / this.viewport.zoom;
    const bend = Math.max(70, Math.abs(x2 - x1) * .45);
    this.previewPath.setAttribute('d', `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`);
  }

  removeConnectionPreview() { this.previewPath?.remove(); this.previewPath = null; }

  pinGraphCenter(pinId) {
    return this.pinGraphCenters().get(pinId) || null;
  }

  pinGraphCenters() {
    const result=new Map(),canvas=this.canvas.getBoundingClientRect();
    for(const element of this.nodesLayer?.querySelectorAll('[data-pin-id]')||[]){const pin=element.getBoundingClientRect();result.set(element.dataset.pinId,{x:(pin.left+pin.width/2-canvas.left-this.viewport.x)/this.viewport.zoom,y:(pin.top+pin.height/2-canvas.top-this.viewport.y)/this.viewport.zoom});}
    return result;
  }

  cancelPinConnection() {
    this.pendingPin = null;
    this.pendingReconnectEdge = null;
    this.nodesLayer?.querySelectorAll('.graph-pin[aria-pressed="true"]').forEach(pin => pin.removeAttribute('aria-pressed'));
    this.clearPinHighlights(); this.removeConnectionPreview();
  }

  renderWires(visibleNodeIds = null) {
    const pinOwner = new Map();
    for (const node of this.projection.nodes) for (const pin of node.pins || []) pinOwner.set(pin.id, node.id);
    const fragment = document.createDocumentFragment();
    const pinCenters=this.pinGraphCenters();
    for (const edge of this.projection.edges || []) {
      const sourceId = pinOwner.get(edge.sourcePinId), targetId = pinOwner.get(edge.targetPinId);
      if (visibleNodeIds && !visibleNodeIds.has(sourceId) && !visibleNodeIds.has(targetId)) continue;
      const source = pinCenters.get(edge.sourcePinId), target = pinCenters.get(edge.targetPinId);
      if (!source || !target) continue;
      const x1 = source.x, y1 = source.y, x2 = target.x, y2 = target.y;
      const route = [{ x: x1, y: y1 }, ...(this.reroutes[edge.id] || []), { x: x2, y: y2 }];
      const path = document.createElementNS(SVG, 'path');
      path.setAttribute('d', this.routePath(route));
      path.setAttribute('class', 'graph-wire' + (!edge.resolved ? ' unresolved' : '') + (edge.cyclic ? ' cyclic' : ''));
      if (this.focusedNodes && (!this.focusedNodes.has(sourceId) || !this.focusedNodes.has(targetId)))
        path.classList.add('graph-dimmed');
      path.dataset.type = edge.semanticType; path.dataset.edgeId = edge.id;
      const title = document.createElementNS(SVG, 'title');
      title.textContent = edge.label + ', ' + edge.semanticType + (edge.resolved ? '' : ', unresolved');
      path.append(title); fragment.append(path);
      if (this.projection.editable && this.projection.capabilities?.includes('DISCONNECT')) {
        const hit = path.cloneNode(false);
        hit.setAttribute('class', 'graph-wire-hit');
        hit.addEventListener('click', () => this.options.onDisconnect?.(edge));
        fragment.append(hit);
        const control = document.createElementNS(SVG, 'circle');
        control.setAttribute('class', 'graph-wire-control'); control.setAttribute('cx', String((x1 + x2) / 2));
        control.setAttribute('cy', String((y1 + y2) / 2)); control.setAttribute('r', '10');
        control.setAttribute('tabindex', '0'); control.setAttribute('role', 'button');
        control.setAttribute('aria-label', 'Disconnect ' + (edge.label || edge.semanticType) + ' connection');
        control.addEventListener('click', () => this.options.onDisconnect?.(edge));
        control.addEventListener('keydown', event => {
          if (event.key === 'Enter' || event.key === ' ' || event.key === 'Delete' || event.key === 'Backspace') {
            event.preventDefault(); this.options.onDisconnect?.(edge);
          }
        });
        fragment.append(control);
      }
      if (this.projection.editable && this.projection.resourceKind === 'behavior') {
        const insert = document.createElementNS(SVG, 'circle');
        insert.setAttribute('class', 'graph-wire-insert'); insert.setAttribute('cx', String((x1 + x2) / 2));
        insert.setAttribute('cy', String((y1 + y2) / 2 - 22)); insert.setAttribute('r', '7');
        insert.setAttribute('tabindex', '0'); insert.setAttribute('role', 'button');
        insert.setAttribute('aria-label', `Insert node on ${edge.label || edge.semanticType} connection`);
        const open = () => this.options.onInsertWire?.(edge, this.pin(edge.sourcePinId));
        insert.addEventListener('click', open); insert.addEventListener('keydown', event => {
          if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); }
        }); fragment.append(insert);
      }
      for (let routeIndex = 0; routeIndex < (this.reroutes[edge.id] || []).length; routeIndex++) {
        const point = this.reroutes[edge.id][routeIndex], control = document.createElementNS(SVG, 'circle');
        control.setAttribute('class', 'graph-reroute'); control.setAttribute('cx', String(point.x));
        control.setAttribute('cy', String(point.y)); control.setAttribute('r', '8'); control.setAttribute('tabindex', '0');
        control.setAttribute('role', 'button'); control.setAttribute('aria-label', `Reroute ${routeIndex + 1} for ${edge.label || edge.semanticType}`);
        control.addEventListener('pointerdown', event => this.startRerouteDrag(event, edge.id, routeIndex, control));
        control.addEventListener('keydown', event => {
          if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); this.removeReroute(edge.id, routeIndex); }
        }); fragment.append(control);
      }
      const addReroute = document.createElementNS(SVG, 'circle');
      addReroute.setAttribute('class', 'graph-reroute-add'); addReroute.setAttribute('cx', String((x1 + x2) / 2));
      // Keep the add affordance below wire labels and the canvas' adjacent toolbar in every engine.
      addReroute.setAttribute('cy', String((y1 + y2) / 2 + 52)); addReroute.setAttribute('r', '7');
      addReroute.setAttribute('tabindex', '0'); addReroute.setAttribute('role', 'button');
      addReroute.setAttribute('aria-label', `Add layout-only reroute to ${edge.label || edge.semanticType}`);
      const add = () => this.addReroute(edge.id, { x: (x1 + x2) / 2, y: (y1 + y2) / 2 });
      addReroute.addEventListener('click', add); addReroute.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); add(); }
      }); fragment.append(addReroute);
      if (this.projection.resourceKind === 'relationship') {
        const addOpenControl = (direction, nodeId, offset) => {
          const control = document.createElementNS(SVG, 'circle');
          control.setAttribute('class', 'graph-wire-control relationship-action');
          control.setAttribute('cx', String((x1 + x2) / 2)); control.setAttribute('cy', String((y1 + y2) / 2 + offset));
          control.setAttribute('r', '10'); control.setAttribute('tabindex', '0'); control.setAttribute('role', 'button');
          control.setAttribute('aria-label', `Open relationship ${direction}`);
          const open = () => this.options.onOpenRelationshipNode?.(this.projection.nodes.find(node => node.id === nodeId), direction);
          control.addEventListener('click', open);
          control.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); } });
          fragment.append(control);
        };
        addOpenControl('source', sourceId, -12); addOpenControl('target', targetId, 12);
      }
      if (edge.label) {
        const label = document.createElementNS(SVG, 'text'); label.setAttribute('class', 'wire-label');
        label.setAttribute('x', String((x1 + x2) / 2)); label.setAttribute('y', String((y1 + y2) / 2 - 7));
        label.setAttribute('text-anchor', 'middle'); label.textContent = edge.label; fragment.append(label);
      }
    }
    this.wires.setAttribute('viewBox', '-4000 -4000 12000 12000');
    this.wires.replaceChildren(fragment);
  }

  renderMinimap() {
    renderGraphMinimap({ element: this.minimap, positions: this.positions, projection: this.projection,
      viewport: this.viewport, canvasWidth: this.canvas.clientWidth, canvasHeight: this.canvas.clientHeight,
      selection: this.selection, liveNodeKeys: this.liveNodeKeys, diagnosticPaths: this.diagnosticPaths,
      nodeWidth: NODE_WIDTH, nodeHeight: NODE_HEIGHT });
  }

  panFromMinimap(event) {
    event.preventDefault(); this.minimap.setPointerCapture(event.pointerId);
    const move = current => {
      const bounds = this.minimap.getBoundingClientRect(), box = this.minimap.viewBox.baseVal;
      if (!bounds.width || !bounds.height) return;
      const worldX = box.x + (current.clientX - bounds.left) / bounds.width * box.width;
      const worldY = box.y + (current.clientY - bounds.top) / bounds.height * box.height;
      this.viewport.x = this.canvas.clientWidth / 2 - worldX * this.viewport.zoom;
      this.viewport.y = this.canvas.clientHeight / 2 - worldY * this.viewport.zoom;
      this.applyViewport(); this.schedule();
    };
    const end = () => { this.minimap.removeEventListener('pointermove', move); this.changed(); };
    move(event); this.minimap.addEventListener('pointermove', move);
    this.minimap.addEventListener('pointerup', end, { once: true });
    this.minimap.addEventListener('pointercancel', end, { once: true });
  }

  routePath(points) {
    let result = `M ${points[0].x} ${points[0].y}`;
    for (let index = 1; index < points.length; index++) {
      const from = points[index - 1], to = points[index], bend = Math.max(35, Math.abs(to.x - from.x) * .45);
      result += ` C ${from.x + bend} ${from.y}, ${to.x - bend} ${to.y}, ${to.x} ${to.y}`;
    }
    return result;
  }

  addReroute(edgeId, point) {
    const total = Object.values(this.reroutes).reduce((sum, values) => sum + values.length, 0);
    if (total >= 2000 || (this.reroutes[edgeId]?.length || 0) >= 16) return;
    const before = this.snapshot();
    this.reroutes[edgeId] = [...(this.reroutes[edgeId] || []), point]; this.schedule(); this.layoutCommand(before);
  }

  removeReroute(edgeId, index) {
    const before = this.snapshot(), values = [...(this.reroutes[edgeId] || [])]; values.splice(index, 1);
    if (values.length) this.reroutes[edgeId] = values; else delete this.reroutes[edgeId];
    this.schedule(); this.layoutCommand(before);
  }

  startRerouteDrag(event, edgeId, index, target) {
    if (event.pointerType !== 'touch' && event.button !== 0) return; event.preventDefault(); event.stopPropagation();
    const before = this.snapshot(), start = { x: event.clientX, y: event.clientY }, point = this.reroutes[edgeId][index];
    const initial = { ...point }; let moved = false; target.setPointerCapture(event.pointerId);
    const move = current => { moved = true; point.x = initial.x + (current.clientX - start.x) / this.viewport.zoom;
      point.y = initial.y + (current.clientY - start.y) / this.viewport.zoom;
      target.setAttribute('cx', String(point.x)); target.setAttribute('cy', String(point.y)); };
    const end = current => { target.removeEventListener('pointermove', move);
      if (target.hasPointerCapture(current.pointerId)) target.releasePointerCapture(current.pointerId);
      if (moved) { this.schedule(); this.layoutCommand(before); } };
    target.addEventListener('pointermove', move); target.addEventListener('pointerup', end, { once: true });
    target.addEventListener('pointercancel', end, { once: true });
  }

  select(id, additive) {
    if (!additive) this.selection.clear();
    this.selection.add(id);
    this.nodesLayer.querySelectorAll('.graph-node-card').forEach(card => {
      if (this.selection.has(card.dataset.nodeId)) card.setAttribute('aria-current', 'true');
      else card.removeAttribute('aria-current');
    });
    this.scheduleMinimap();
    this.options.onSelection(this.projection.nodes.filter(node => this.selection.has(node.id)));
  }

  selectYamlPath(path) {
    if (!this.projection || !path) return;
    const matches = this.projection.nodes.filter(node => path === node.yamlPath || path.startsWith(node.yamlPath + '/'));
    matches.sort((a, b) => b.yamlPath.length - a.yamlPath.length);
    if (matches[0]) { this.select(matches[0].id, false); this.focusNode(matches[0].id); }
  }

  focusNode(id) {
    const point = this.positions[id]; if (!point) return;
    this.viewport.x = this.canvas.clientWidth / 2 - (point.x + NODE_WIDTH / 2) * this.viewport.zoom;
    this.viewport.y = this.canvas.clientHeight / 2 - (point.y + 60) * this.viewport.zoom;
    this.applyViewport(); this.schedule();
    requestAnimationFrame(() => this.nodesLayer.querySelector('[data-node-id="' + CSS.escape(id) + '"]')?.focus());
  }

  pointerDown(event) {
    if (event.target !== this.canvas && event.target !== this.plane && event.target !== this.nodesLayer) return;
    if (event.shiftKey) { this.startMarquee(event); return; }
    const start = { x: event.clientX, y: event.clientY, vx: this.viewport.x, vy: this.viewport.y };
    this.canvas.setPointerCapture(event.pointerId);
    const move = current => { this.viewport.x = start.vx + current.clientX - start.x; this.viewport.y = start.vy + current.clientY - start.y; this.applyViewport(); this.schedule(); };
    const end = () => { this.canvas.removeEventListener('pointermove', move); this.changed(); };
    this.canvas.addEventListener('pointermove', move);
    this.canvas.addEventListener('pointerup', end, { once: true });
    this.canvas.addEventListener('pointercancel', end, { once: true });
  }

  startMarquee(event) {
    const bounds = this.canvas.getBoundingClientRect(), startX = event.clientX - bounds.left, startY = event.clientY - bounds.top;
    this.marquee.hidden = false; this.canvas.setPointerCapture(event.pointerId);
    const move = current => {
      const x = current.clientX - bounds.left, y = current.clientY - bounds.top;
      Object.assign(this.marquee.style, { left: Math.min(startX, x) + 'px', top: Math.min(startY, y) + 'px',
        width: Math.abs(x - startX) + 'px', height: Math.abs(y - startY) + 'px' });
    };
    const end = current => {
      this.canvas.removeEventListener('pointermove', move); this.marquee.hidden = true;
      const endX = current.clientX - bounds.left, endY = current.clientY - bounds.top;
      const left = (Math.min(startX, endX) - this.viewport.x) / this.viewport.zoom;
      const right = (Math.max(startX, endX) - this.viewport.x) / this.viewport.zoom;
      const top = (Math.min(startY, endY) - this.viewport.y) / this.viewport.zoom;
      const bottom = (Math.max(startY, endY) - this.viewport.y) / this.viewport.zoom;
      this.selection.clear();
      for (const [id, point] of Object.entries(this.positions))
        if (point.x + NODE_WIDTH >= left && point.x <= right && point.y + 120 >= top && point.y <= bottom) this.selection.add(id);
      this.schedule(); this.options.onSelection(this.projection.nodes.filter(node => this.selection.has(node.id)));
    };
    this.canvas.addEventListener('pointermove', move);
    this.canvas.addEventListener('pointerup', end, { once: true });
    this.canvas.addEventListener('pointercancel', end, { once: true });
  }

  startNodeDrag(event, nodeId) {
    if (event.pointerType !== 'touch' && event.button !== 0 || event.target.closest('button')) return;
    event.stopPropagation();
    if (!this.selection.has(nodeId)) this.select(nodeId, event.ctrlKey || event.metaKey);
    const before = this.snapshot();
    const selected = [...this.selection], initial = Object.fromEntries(selected.map(id => [id, { ...this.positions[id] }]));
    const elements = new Map(selected.map(id => [id,
      this.nodesLayer.querySelector('[data-node-id="' + CSS.escape(id) + '"]')]).filter(([, element]) => element));
    const startX = event.clientX, startY = event.clientY, target = event.currentTarget; let committed = false;
    target.setPointerCapture(event.pointerId);
    const move = current => {
      const dx = (current.clientX - startX) / this.viewport.zoom, dy = (current.clientY - startY) / this.viewport.zoom;
      for (const id of selected) {
        this.positions[id] = { x: initial[id].x + dx, y: initial[id].y + dy };
        const point = this.positions[id];
        if (elements.has(id)) elements.get(id).style.transform = `translate(${point.x}px,${point.y}px)`;
      }
      committed = committed || dx !== 0 || dy !== 0;
      this.scheduleMinimap();
    };
    const end = current => { target.removeEventListener('pointermove', move);
      if (target.hasPointerCapture(current.pointerId)) target.releasePointerCapture(current.pointerId);
      if (committed) { this.schedule(); this.layoutCommand(before); } };
    target.addEventListener('pointermove', move);
    target.addEventListener('pointerup', end, { once: true });
    target.addEventListener('pointercancel', end, { once: true });
  }

  zoom(event) {
    event.preventDefault();
    const bounds = this.canvas.getBoundingClientRect(), x = event.clientX - bounds.left, y = event.clientY - bounds.top;
    const worldX = (x - this.viewport.x) / this.viewport.zoom, worldY = (y - this.viewport.y) / this.viewport.zoom;
    const next = Math.max(.2, Math.min(2.5, this.viewport.zoom * Math.exp(-event.deltaY * .001)));
    this.viewport.x = x - worldX * next; this.viewport.y = y - worldY * next; this.viewport.zoom = next;
    this.applyViewport(); this.schedule(); this.changed();
  }

  applyViewport() {
    this.plane.style.transform = 'translate(' + this.viewport.x + 'px,' + this.viewport.y + 'px) scale(' + this.viewport.zoom + ')';
    this.zoomOutput.value = Math.round(this.viewport.zoom * 100) + '%';
    this.zoomOutput.textContent = this.zoomOutput.value;
    this.scheduleMinimap();
  }

  zoomToFit() {
    const points = Object.values(this.positions); if (!points.length) return;
    this.viewport = fitViewport(points, this.canvas.clientWidth, this.canvas.clientHeight, NODE_WIDTH, NODE_HEIGHT);
    this.applyViewport(); this.changed();
  }

  autoLayout() {
    if (!this.projection) return;
    const before = this.snapshot();
    this.positions = deterministicLayout(this.projection); this.schedule(); this.zoomToFit();
    this.layoutCommand(before);
  }

  align(mode) {
    const ids = [...this.selection].filter(id => this.positions[id]); if (ids.length < 2) return;
    const before = this.snapshot(), points = ids.map(id => this.positions[id]);
    const value = mode === 'left' ? Math.min(...points.map(point => point.x))
      : mode === 'center' ? points.reduce((sum, point) => sum + point.x + NODE_WIDTH / 2, 0) / points.length
      : mode === 'right' ? Math.max(...points.map(point => point.x + NODE_WIDTH))
      : mode === 'top' ? Math.min(...points.map(point => point.y))
      : mode === 'middle' ? points.reduce((sum, point) => sum + point.y + NODE_HEIGHT / 2, 0) / points.length
      : Math.max(...points.map(point => point.y + NODE_HEIGHT));
    for (const id of ids) {
      if (mode === 'left') this.positions[id].x = value;
      else if (mode === 'center') this.positions[id].x = value - NODE_WIDTH / 2;
      else if (mode === 'right') this.positions[id].x = value - NODE_WIDTH;
      else if (mode === 'top') this.positions[id].y = value;
      else if (mode === 'middle') this.positions[id].y = value - NODE_HEIGHT / 2;
      else this.positions[id].y = value - NODE_HEIGHT;
    }
    this.schedule(); this.layoutCommand(before);
  }

  distribute(axis) {
    const ids = [...this.selection].filter(id => this.positions[id]); if (ids.length < 3) return;
    const before = this.snapshot(), coordinate = id => this.positions[id][axis === 'horizontal' ? 'x' : 'y'];
    ids.sort((left, right) => coordinate(left) - coordinate(right) || left.localeCompare(right));
    const start = coordinate(ids[0]), end = coordinate(ids.at(-1)), interval = (end - start) / (ids.length - 1);
    ids.forEach((id, index) => { this.positions[id][axis === 'horizontal' ? 'x' : 'y'] = start + interval * index; });
    this.schedule(); this.layoutCommand(before);
  }

  snapSelection(size = 16) {
    if (!this.selection.size) return;
    const before = this.snapshot();
    for (const id of this.selection) {
      const point = this.positions[id]; if (!point) continue;
      point.x = Math.round(point.x / size) * size; point.y = Math.round(point.y / size) * size;
    }
    this.schedule(); this.layoutCommand(before);
  }

  tidySelection() {
    const ids = [...this.selection].filter(id => this.positions[id]).sort(); if (!ids.length) return;
    const before = this.snapshot(), left = Math.min(...ids.map(id => this.positions[id].x));
    const top = Math.min(...ids.map(id => this.positions[id].y)), columns = Math.max(1, Math.ceil(Math.sqrt(ids.length)));
    ids.forEach((id, index) => { this.positions[id] = { x: left + index % columns * 260, y: top + Math.floor(index / columns) * 170 }; });
    this.schedule(); this.layoutCommand(before);
  }

  addComment(text) {
    const value = String(text || '').trim().slice(0, 500); if (!value || this.comments.length >= 200) return;
    const before = this.snapshot();
    this.comments.push({ id: crypto.randomUUID(), text: value,
      x: (this.canvas.clientWidth / 2 - this.viewport.x) / this.viewport.zoom,
      y: (this.canvas.clientHeight / 2 - this.viewport.y) / this.viewport.zoom });
    this.schedule(); this.layoutCommand(before);
  }

  groupSelection(label, color = '#5d77a8') {
    const nodeIds = [...this.selection];
    const value = String(label || '').trim().slice(0, 120);
    if (nodeIds.length < 2 || !value || this.groups.length >= 100) return;
    const before = this.snapshot();
    this.groups.push({ id: crypto.randomUUID(), label: value, color: /^#[0-9a-f]{6}$/i.test(color) ? color : '#5d77a8', nodeIds });
    this.schedule(); this.layoutCommand(before);
  }

  colorSelection(color) {
    if (!/^#[0-9a-f]{6}$/i.test(color) || !this.selection.size) return;
    const before = this.snapshot();
    for (const id of this.selection) {
      if (!Object.hasOwn(this.colors, id) && Object.keys(this.colors).length >= 2000) break;
      this.colors[id] = color;
    }
    this.schedule(); this.layoutCommand(before);
  }

  toggleBookmark(id) {
    if (!id) return; const before = this.snapshot();
    if (this.bookmarks.has(id)) this.bookmarks.delete(id); else if (this.bookmarks.size < 200) this.bookmarks.add(id);
    this.schedule(); this.layoutCommand(before);
  }

  toggleCollapse(id) {
    if (!id) return; const before = this.snapshot();
    if (this.collapsed.has(id)) this.collapsed.delete(id); else if (this.collapsed.size < 2000) this.collapsed.add(id);
    this.schedule(); this.layoutCommand(before);
  }

  focusRelated(direction) {
    if (!this.selection.size) return;
    const owners = new Map();
    for (const node of this.projection.nodes) for (const pin of node.pins || []) owners.set(pin.id, node.id);
    const forward = new Map(), reverse = new Map();
    for (const edge of this.projection.edges || []) {
      const source = owners.get(edge.sourcePinId), target = owners.get(edge.targetPinId);
      if (!source || !target) continue;
      if (!forward.has(source)) forward.set(source, []); forward.get(source).push(target);
      if (!reverse.has(target)) reverse.set(target, []); reverse.get(target).push(source);
    }
    const related = new Set(this.selection), queue = [...this.selection];
    while (queue.length) {
      const current = queue.shift();
      const next = direction === 'upstream' ? reverse.get(current) || []
        : direction === 'downstream' ? forward.get(current) || []
          : [...(forward.get(current) || []), ...(reverse.get(current) || [])];
      for (const value of next) if (!related.has(value)) { related.add(value); queue.push(value); }
    }
    this.focusedNodes = related; this.schedule();
  }

  zoomToFitSelection(ids) {
    const points = ids.map(id => this.positions[id]).filter(Boolean); if (!points.length) return;
    this.viewport = fitViewport(points, this.canvas.clientWidth, this.canvas.clientHeight, NODE_WIDTH, NODE_HEIGHT);
    this.applyViewport(); this.changed();
  }

  keydown(event) {
    if (!this.projection) return;
    if (event.key === 'Escape' && this.focusedNodes) {
      event.preventDefault(); this.focusedNodes = null; this.schedule(); return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c') {
      event.preventDefault(); this.options.onCommand?.({ type: 'COPY', nodeIds: [...this.selection] }); return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') {
      event.preventDefault(); this.options.onCommand?.({ type: 'PASTE' }); return;
    }
    if (event.key === 'Escape' && this.pendingPin) { event.preventDefault(); this.cancelPinConnection(); return; }
    if ((event.ctrlKey || event.metaKey) && ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
      event.preventDefault(); const before = this.snapshot(), step = event.shiftKey ? 10 : 1;
      for (const id of this.selection) {
        const point = this.positions[id];
        if (event.key === 'ArrowLeft') point.x -= step; if (event.key === 'ArrowRight') point.x += step;
        if (event.key === 'ArrowUp') point.y -= step; if (event.key === 'ArrowDown') point.y += step;
      }
      this.schedule(); this.layoutCommand(before); return;
    }
    if (!event.ctrlKey && !event.metaKey && !event.altKey && event.key.toLowerCase() === 'f') {
      event.preventDefault(); this.selection.size ? this.zoomToFitSelection([...this.selection]) : this.zoomToFit(); return;
    }
    if (!event.ctrlKey && !event.metaKey && !event.altKey && event.key === '0') {
      event.preventDefault(); this.viewport = normalizeViewport(null); this.applyViewport(); this.changed(); return;
    }
    if (!event.ctrlKey && !event.metaKey && !event.altKey && ['+', '=', '-'].includes(event.key)) {
      event.preventDefault(); const factor = event.key === '-' ? .9 : 1.1;
      this.viewport.zoom = Math.max(.2, Math.min(2.4, this.viewport.zoom * factor));
      this.applyViewport(); this.changed(); return;
    }
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault(); this.options.onCommand?.({ type: 'DELETE', nodeIds: [...this.selection] }); return;
    }
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key) || !this.selection.size) return;
    const current = [...this.selection].at(-1), pinOwner = new Map();
    for (const node of this.projection.nodes) for (const pin of node.pins || []) pinOwner.set(pin.id, node.id);
    const candidates = [];
    for (const edge of this.projection.edges || []) {
      const source = pinOwner.get(edge.sourcePinId), target = pinOwner.get(edge.targetPinId);
      if ((event.key === 'ArrowRight' || event.key === 'ArrowDown') && source === current) candidates.push(target);
      if ((event.key === 'ArrowLeft' || event.key === 'ArrowUp') && target === current) candidates.push(source);
    }
    const target = candidates.filter(Boolean).sort()[0]; if (target) { event.preventDefault(); this.select(target, false); this.focusNode(target); }
  }

  changed() {
    clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.options.onLayoutChange?.({
      positions: this.positions, viewport: this.viewport, comments: this.comments, groups: this.groups,
      bookmarks: [...this.bookmarks], colors: this.colors, collapsed: [...this.collapsed], reroutes: this.reroutes
    }), 150);
  }

  layoutCommand(before) {
    this.options.onCommand?.({ type: 'LAYOUT', before, after: this.snapshot() });
    this.changed();
  }

  snapshot() {
    return { positions: structuredClone(this.positions), viewport: { ...this.viewport }, selection: [...this.selection],
      comments: structuredClone(this.comments), groups: structuredClone(this.groups), bookmarks: [...this.bookmarks],
      colors: { ...this.colors }, collapsed: [...this.collapsed], reroutes: structuredClone(this.reroutes) };
  }
}
