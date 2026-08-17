import { WorkspaceShell, deriveResources } from './modules/workspace-shell.js';
import { PanelLayout } from './modules/layout-store.js';
import { GraphLayoutStore } from './modules/graph-layout.js';
import { GraphCanvas } from './modules/graph-canvas.js';
import { GraphInspector } from './modules/graph-inspector.js';
import { GraphMutationClient, contractMessage } from './modules/graph-mutations.js';
import { CommandDispatcher } from './modules/command-dispatcher.js';
import { nodeDefinitions, compatibleDefinitions } from './modules/node-registry.js';
import { createWorkspaceState } from './modules/workspace-state.js';
import { SessionTransport } from './modules/transport.js';
import { findModelNode } from './modules/yaml-documents.js';
import { liveNodeKeys } from './modules/live-overlays.js';
import { nestedProjection } from './modules/graph-projection.js';
import { publicationReady, validationHeading, diagnosticLabel } from './modules/validation.js';

const sessionId = location.pathname.match(/^\/editor\/session\/([0-9a-f-]+)$/i)?.[1];
if (!sessionId) throw new Error('The Persona editor requires a server-created session URL.');
const connectPanel = document.querySelector('#connect');
const reconnectPanel = document.querySelector('#reconnect');
const reconnectNow = document.querySelector('#reconnect-now');
const verifyForm = document.querySelector('#verify');
const status = document.querySelector('#status');
const workspace = document.querySelector('#workspace');
const source = document.querySelector('#source');
const fileName = document.querySelector('#file-name');
const download = document.querySelector('#download');
const exportAll = document.querySelector('#export-all');
const exportChanged = document.querySelector('#export-changed');
const undoButton = document.querySelector('#undo');
const redoButton = document.querySelector('#redo');
const copyButton = document.querySelector('#copy');
const pasteButton = document.querySelector('#paste');
const diffToggle = document.querySelector('#diff-toggle');
const diff = document.querySelector('#diff');
const palette = document.querySelector('#palette');
const paletteOpen = document.querySelector('#palette-open');
const paletteSearch = document.querySelector('#palette-search');
const paletteResults = document.querySelector('#palette-results');
const visual = document.querySelector('#visual');
const yamlStatus = document.querySelector('#yaml-status');
const validationPanel = document.querySelector('#validation-panel');
const validationSummary = document.querySelector('#validation-summary');
const validationList = document.querySelector('#validation-list');
const visualTools=document.querySelector('#visual-tools'),visualContainer=document.querySelector('#visual-container'),visualTemplate=document.querySelector('#visual-template');
const insightsTitle=document.querySelector('#visual-insights-title'),visualGraph=document.querySelector('#visual-graph'),visualPreview=document.querySelector('#visual-preview'),simulationDialog=document.querySelector('#simulation-dialog'),simulationInput=document.querySelector('#simulation-input'),simulationOutput=document.querySelector('#simulation-output');
const referencesOpen = document.querySelector('#references-open');
const referencesDialog = document.querySelector('#references-dialog');
const referencesSummary = document.querySelector('#references-summary');
const referencesList = document.querySelector('#references-list');
const renameForm = document.querySelector('#rename-preview-form');
const renameResult = document.querySelector('#rename-result');
const semanticDiffOpen = document.querySelector('#semantic-diff-open');
const relationshipMapOpen = document.querySelector('#relationship-map-open');
const semanticDiffDialog = document.querySelector('#semantic-diff-dialog');
const semanticDiffSummary = document.querySelector('#semantic-diff-summary');
const semanticDiffList = document.querySelector('#semantic-diff-list');
const publishButton = document.querySelector('#publish-request');
const createOpen = document.querySelector('#create-open');
const duplicateResourceButton = document.querySelector('#duplicate-resource');
const renameResourceButton = document.querySelector('#rename-resource');
const moveResourceButton = document.querySelector('#move-resource');
const deleteResourceButton = document.querySelector('#delete-resource');
const liveOpen=document.querySelector('#live-open'),liveDialog=document.querySelector('#live-dialog'),liveStatus=document.querySelector('#live-status');
const liveControls=document.querySelector('#live-controls'),liveMode=document.querySelector('#live-mode'),behaviorMutationTarget=document.querySelector('#behavior-mutation-target'),memoryMutationTarget=document.querySelector('#memory-mutation-target'),mutationConfirm=document.querySelector('#mutation-confirm'),mutationDetails=document.querySelector('#mutation-confirm-details'),mutationResult=document.querySelector('#mutation-result');
const encoder = new TextEncoder();
const protocolVersion = 3;
const editorElement = document.querySelector('.editor');
const commandDispatcher = new CommandDispatcher();
const state = createWorkspaceState();
const transport = new SessionTransport(sessionId, state);

const workspaceShell = new WorkspaceShell({
  beforeOpen: identity => {
    if (graphCanvas?.projection?.resourceIdentity === identity) state.graphTabContexts.set(identity, graphCanvas.snapshot());
  },
  open: resource => {
    state.nestedGraph = null;
    workspaceShell.setNestedBreadcrumbs();
    state.pendingYamlPath = resource.yamlPath || null;
    selectFile(resource.path);
  },
  empty: () => { state.selected = null; source.value = ''; source.disabled = true; fileName.textContent = 'Select a resource'; },
  dirty: resource => state.files.get(resource.path) !== state.original.get(resource.path),
  invalid: resource => state.documentValidity.get(resource.path) === false,
  referenced: resource => state.referenceGraph.references.some(value => value.targetType === resource.kind && value.targetId === resource.id),
  missing: resource => state.referenceGraph.references.some(value => !value.resolved && value.sourceType === resource.kind && value.sourceId === resource.id),
  live: resource => resource.kind === 'npc'
    ? [...state.liveData.npcs.values()].some(value => value.definitionId === resource.id)
    : resource.kind === 'behavior' ? [...state.liveData.behaviors.values()].some(value => value.behaviorId === resource.id) : false,
  restoreFocus: () => document.querySelector('#content-search')?.focus()
});
const panelLayout = new PanelLayout('persona:panel-layout:' + sessionId);
const graphLayoutStore = new GraphLayoutStore(sessionId);
const graphInspector = new GraphInspector({
  onSelectSource: (path, range) => {
    if (!path || !range) return;
    if (state.relationshipMode) { status.textContent = 'Double-click a resolved resource node to open its source.'; return; }
    state.selectedNode = path; source.focus(); source.setSelectionRange(range.startOffset, range.endOffset);
    selectVisualNode(path);
  },
  onEditField: (field, value) => applyVisualEdit(field.yamlPath, value),
  suggestions: field => field.label === 'world' ? [
    ...[...state.liveData.players.values()].map(player => player.world),
    ...[...state.liveData.npcs.values()].map(npc => npc.position?.world)
  ] : [],
  inputForField: field => {
    const modelNode = findModelNode(state.documentModels.get(state.selected)?.root, field.yamlPath);
    const rule = modelNode ? fieldSchema(modelNode) : null;
    return rule ? schemaInput(modelNode, rule) : null;
  }
});
let graphMutationClient;
const graphCanvas = new GraphCanvas({
  onSelection: nodes => {
    graphInspector.render(nodes);
    if (graphCanvas.projection?.resourceKind !== 'relationship' && nodes.length === 1 && nodes[0].yamlPath) {
      if (editorElement.dataset.view === 'visual') setEditorView('split');
      state.selectedNode = nodes[0].yamlPath;
      source.setSelectionRange(nodes[0].range.startOffset, nodes[0].range.endOffset);
      selectVisualNode(nodes[0].yamlPath, false);
    }
  },
  onNavigateSource: node => {
    if (graphCanvas.projection?.resourceKind === 'relationship' && node.kind.startsWith('relationship-')) {
      const kind = node.kind.substring('relationship-'.length);
      const resource = deriveResources(state.files).find(item => item.kind === kind && item.id === node.title);
      if (resource) workspaceShell.openResource(resource);
      return;
    }
    if (node.kind === 'resource-reference' && ['behavior', 'dialogue', 'quest', 'npc', 'script'].includes(node.subtitle)) {
      const resource = deriveResources(state.files).find(item => item.kind === node.subtitle && item.id === node.title);
      if (resource) { workspaceShell.openResource(resource); return; }
      yamlStatus.textContent = `Missing ${node.subtitle} ${node.title}. Use Create missing to add it safely.`;
    }
    if (!node.yamlPath) return; source.focus();
    source.setSelectionRange(node.range.startOffset, node.range.endOffset);
  },
  onPin: pin => { status.textContent = pin.direction + ' pin ' + pin.label + ' accepts ' + pin.semanticType + ' (' + pin.cardinality + ').'; },
  onConnectionPending: pin => { status.textContent = `Selected ${pin.label} output. Choose a compatible input; Escape cancels.`; },
  onConnectionError: message => { yamlStatus.textContent = `Connection rejected before changing YAML: ${message}`; status.textContent = message; },
  onConnect: gesture => commandDispatcher.execute('graph.connect', gesture),
  onDisconnect: edge => commandDispatcher.execute('graph.disconnect', edge),
  onInsertWire: (edge, sourcePin) => commandDispatcher.execute('graph.add', { sourcePin, edge }),
  onPalette: context => commandDispatcher.execute('graph.add', context),
  onNodeAction: action => commandDispatcher.execute('graph.node-action', action),
  onOpenRelationshipNode: node => openRelationshipNode(node),
  isDirtyNode: node => (node.fields || []).some(field => {
    const original = findModelNode(state.originalModels.get(state.selected)?.root, field.yamlPath);
    const current = findModelNode(state.documentModels.get(state.selected)?.root, field.yamlPath);
    return Boolean(current && (!original || original.kind !== current.kind || original.value !== current.value));
  }) || Boolean(node.yamlPath && !findModelNode(state.originalModels.get(state.selected)?.root, node.yamlPath)
    && findModelNode(state.documentModels.get(state.selected)?.root, node.yamlPath)),
  onLayoutChange: layout => {
    if (graphCanvas.projection) graphLayoutStore.save(graphCanvas.projection, layout);
  },
  onCommand: command => {
    if (command.type === 'DELETE') commandDispatcher.execute('graph.delete', command.nodeIds);
    if (command.type === 'LAYOUT') recordHistory(state.selected, command.before);
    if (command.type === 'COPY') commandDispatcher.execute('graph.copy', command.nodeIds);
    if (command.type === 'PASTE') commandDispatcher.execute('graph.paste');
  }
});

function refreshWorkspaceResources(activePath = state.selected) {
  const resources = deriveResources(state.files);
  const active = resources.find(resource => resource.path === activePath
    && (!state.pendingYamlPath || resource.yamlPath === state.pendingYamlPath))
    || resources.find(resource => resource.path === activePath);
  workspaceShell.update(resources, active?.identity);
  return { resources, active };
}

connectPanel.hidden = false;
reconnectPanel.hidden = true;

const sessionApi = path => transport.api(path);
const authorizedHeaders = (values = {}) => transport.headers(values);
const requireConnection = () => transport.requireConnection();
const draftEditAllowed = () => state.connected && state.verified?.capabilities?.includes('DRAFT_EDIT');
const draftEditRequiredMessage = 'Creating content requires Draft Edit trust. Approve DRAFT_EDIT in Minecraft, then wait for this session to refresh.';

function requireDraftEdit() {
  requireConnection();
  if (!draftEditAllowed()) throw new Error(draftEditRequiredMessage);
}

graphMutationClient = new GraphMutationClient({
  endpoint: () => sessionApi('/documents/mutate'),
  headers: authorizedHeaders,
  context: async () => {
    requireConnection(); flushSelected();
    const resource = workspaceShell.activeResource(), projection = graphCanvas.projection;
    if (!resource || !projection || projection.resourceIdentity !== resource.identity)
      throw new Error('Wait for the authoritative graph projection before editing.');
    const selected = state.selected, connectionGeneration = state.connectionGeneration,
      resourceIdentity = resource.identity, content = state.files.get(selected) ?? '';
    return { resource, projection, content, projectFiles: await contentFiles(), selected,
      isCurrent: () => state.connected && state.connectionGeneration === connectionGeneration
        && state.selected === selected && workspaceShell.activeResource()?.identity === resourceIdentity };
  },
  recordHistory: context => recordHistory(context.selected),
  rollbackHistory: context => { state.histories.get(context.selected)?.undo.pop(); updateHistoryButtons(); },
  onApplied: applyGraphMutationResult,
  onConflict: error => {
    yamlStatus.textContent = `Graph conflict: ${contractMessage(error)} Refreshing from the current authoritative YAML.`;
    status.textContent = 'The graph gesture conflicted with a newer YAML digest; the authoritative projection is being refreshed.';
    parseSelected();
  },
  onContractError: (label, error) => {
    yamlStatus.textContent = `${label} rejected without changing YAML: ${contractMessage(error)}`;
    if (error.yamlPath) selectVisualNode(error.yamlPath);
  },
  onError: (label, error) => { yamlStatus.textContent = `${label} failed without changing YAML: ${error.message}`; },
  onSettled: () => { state.graphMutationInFlight = false; updateLifecycleButtons(); }
});
commandDispatcher
  .register('graph.connect', { label: 'Connect nodes', enabled: gesture => Boolean(gesture?.operations?.length),
    run: gesture => graphMutationClient.mutate(gesture.operations, 'Connect nodes') })
  .register('graph.disconnect', { label: 'Disconnect nodes', enabled: edge => Boolean(edge), run: edge =>
    graphMutationClient.mutate([{ type: 'DISCONNECT', yamlPath: edge.sourceYamlPath,
      sourcePinId: edge.sourcePinId, targetPinId: edge.targetPinId }], 'Disconnect nodes') })
  .register('graph.add', { label: 'Add graph node', enabled: () => state.connected && Boolean(graphCanvas.projection?.editable),
    run: context => openGraphPalette(context || { sourcePin: null }) })
  .register('graph.delete', { label: 'Delete selected graph nodes', enabled: nodeIds => Boolean(nodeIds?.length),
    run: deleteGraphNodes })
  .register('graph.copy', { label: 'Copy selected behavior node', enabled: nodeIds => Boolean(nodeIds?.length),
    run: copyGraphNode })
  .register('graph.paste', { label: 'Paste compatible behavior node', enabled: () => Boolean(state.graphClipboard),
    run: pasteGraphNode })
  .register('graph.align-left', { label: 'Align selected nodes left', enabled: () => graphCanvas.selection.size >= 2,
    run: () => graphCanvas.align('left') })
  .register('graph.align-top', { label: 'Align selected nodes top', enabled: () => graphCanvas.selection.size >= 2,
    run: () => graphCanvas.align('top') })
  .register('graph.distribute-horizontal', { label: 'Distribute selected nodes horizontally', enabled: () => graphCanvas.selection.size >= 3,
    run: () => graphCanvas.distribute('horizontal') })
  .register('graph.distribute-vertical', { label: 'Distribute selected nodes vertically', enabled: () => graphCanvas.selection.size >= 3,
    run: () => graphCanvas.distribute('vertical') })
  .register('graph.auto-layout', { label: 'Auto-layout graph', enabled: () => Boolean(graphCanvas.projection),
    run: () => graphCanvas.autoLayout() })
  .register('graph.comment', { label: 'Add graph comment', enabled: () => Boolean(graphCanvas.projection), run: () => {
    const text = prompt('Graph comment (stored only in browser layout metadata)'); if (text) graphCanvas.addComment(text);
  } })
  .register('graph.group', { label: 'Group selected nodes', enabled: () => graphCanvas.selection.size >= 2, run: () => {
    const label = prompt('Group label (stored only in browser layout metadata)', 'Group');
    if (label) graphCanvas.groupSelection(label);
  } })
  .register('graph.color', { label: 'Color-label selected nodes', enabled: () => graphCanvas.selection.size > 0, run: () => {
    const color = prompt('Six-digit CSS color for the selected nodes', '#5d77a8');
    if (color) graphCanvas.colorSelection(color);
  } })
  .register('graph.focus-upstream', { label: 'Focus upstream nodes', enabled: () => graphCanvas.selection.size > 0,
    run: () => graphCanvas.focusRelated('upstream') })
  .register('graph.focus-downstream', { label: 'Focus downstream nodes', enabled: () => graphCanvas.selection.size > 0,
    run: () => graphCanvas.focusRelated('downstream') })
  .register('graph.node-action', { label: 'Graph node action', enabled: action => Boolean(action?.type), run: handleGraphNodeAction })
  .register('history.undo', { label: 'Undo', keywords: 'history', enabled: () => Boolean(state.histories.get(state.selected)?.undo.length),
    run: () => restoreHistory('undo') })
  .register('history.redo', { label: 'Redo', keywords: 'history', enabled: () => Boolean(state.histories.get(state.selected)?.redo.length),
    run: () => restoreHistory('redo') })
  .register('yaml.copy', { label: 'Copy YAML selection', run: () => copyButton.click() })
  .register('yaml.paste', { label: 'Paste YAML', enabled: () => state.connected, run: () => pasteButton.click() })
  .register('changes.text', { label: 'Show textual changes', run: () => { if (diff.hidden) diffToggle.click(); } })
  .register('download.file', { label: 'Download current file', run: () => download.click() })
  .register('download.project', { label: 'Download complete project', run: () => exportAll.click() })
  .register('download.changed', { label: 'Download changed files', run: () => exportChanged.click() })
  .register('references.open', { label: 'Show references and rename preview', run: () => referencesOpen.click() })
  .register('changes.semantic', { label: 'Show semantic project diff', run: () => semanticDiffOpen.click() })
  .register('publish.request', { label: 'Request validated publication', enabled: () => !publishButton.disabled,
    run: () => publishButton.click() });
function lockWorkspace(message) {
  if (state.connected) persistRecovery();
  state.connected = false;
  state.connectionGeneration++;
  clearTimeout(state.autosaveTimer);
  clearTimeout(state.parseTimer);
  clearTimeout(state.recoveryTimer);
  clearTimeout(state.capabilityTimer);
  clearTimeout(state.publishTimer);
  clearTimeout(state.heartbeat);
  state.pendingMutation = null;
  state.catalogRequests.clear();
  state.mutationRequests.clear();
  for (const dialog of document.querySelectorAll('dialog[open]')) dialog.close();
  workspace.inert = true;
  workspace.hidden = true;
  source.disabled = true;
  connectPanel.hidden = true;
  reconnectPanel.hidden = false;
  status.textContent = message;
  updateLifecycleButtons();
}

const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const fromB64 = value => Uint8Array.from(atob(value), character => character.charCodeAt(0));
const hex = bytes => [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('');

function flushSelected() {
  if (state.selected) state.files.set(state.selected, source.value);
}

function renderProject(files, message, revision = null) {
  requireConnection();
  state.files = new Map(files.map(file => [file.path, file.content]));
  state.original = new Map(state.files);
  state.baseRevision = revision;
  state.currentRevision = revision;
  state.documentModels.clear(); state.originalModels.clear(); state.documentValidity.clear(); state.histories.clear();
  state.graphProjections.clear(); graphCanvas.clear();
  const recoveryKey = `persona:recovery:${sessionId}`;
  try {
    const recovered = JSON.parse(sessionStorage.getItem(recoveryKey));
    if (recovered?.revision === revision) {
      const changes = recovered.version === 2 && recovered.changes && typeof recovered.changes === 'object'
        ? recovered.changes : recovered.files && typeof recovered.files === 'object' ? recovered.files : null;
      const entries = changes ? Object.entries(changes) : [];
      let bytes = 0;
      const bounded = entries.length <= 2048 && entries.every(([path, content]) => {
        bytes += typeof content === 'string' ? encoder.encode(content).length : 0;
        return path.length <= 240 && !path.startsWith('/') && !path.includes('\\') && !path.includes('\0')
          && /\.ya?ml$/.test(path) && path.split('/').every(part => part && part !== '.' && part !== '..')
          && (typeof content === 'string' || recovered.version === 2 && content === null) && bytes <= 10 * 1024 * 1024;
      });
      if (bounded) {
        const candidate = new Map(state.files);
        for (const [path, content] of entries) {
          if (content === null) candidate.delete(path);
          else if (recovered.version === 2 || candidate.has(path)) candidate.set(path, content);
        }
        const folded = new Set([...candidate.keys()].map(path => path.toLowerCase()));
        const candidateBytes = [...candidate.values()].reduce((total, content) => total + encoder.encode(content).length, 0);
        if (candidate.size <= 2048 && folded.size === candidate.size && candidateBytes <= 10 * 1024 * 1024)
          state.files = candidate;
      }
      if ([...new Set([...state.original.keys(), ...state.files.keys()])]
          .some(path => state.files.get(path) !== state.original.get(path)))
        message += ' Recovered unsaved changes from this browser tab.';
    }
  } catch { sessionStorage.removeItem(recoveryKey); }
  state.selected = null;
  source.value = '';
  const { resources } = refreshWorkspaceResources(null);
  workspace.hidden = false;
  workspace.inert = false;
  connectPanel.hidden = true;
  reconnectPanel.hidden = true;
  status.textContent = message;
  const restored = resources.find(resource => resource.identity === workspaceShell.active) || resources[0];
  if (restored) workspaceShell.openResource(restored, false);
  refreshProjectReferences();
}

function dirty() {
  flushSelected();
  return [...new Set([...state.original.keys(), ...state.files.keys()])]
    .some(path => state.files.get(path) !== state.original.get(path));
}

function selectFile(path) {
  if (!state.connected) return;
  state.relationshipMode = false;
  flushSelected(); state.selected = path;
  source.value = state.files.get(path) ?? '';
  source.disabled = false; download.disabled = false; fileName.textContent = path;
  copyButton.disabled = false; pasteButton.disabled = false; diffToggle.disabled = false;
  if (!state.histories.has(path)) state.histories.set(path, { undo: [], redo: [] });
  renderDocument(state.documentModels.get(path)); yamlStatus.textContent = '';
  const cachedGraph = state.graphProjections.get(workspaceShell.activeResource()?.identity);
  if (cachedGraph) {
    graphLayoutStore.scope = (state.installationIdentity || sessionId) + ':' + (state.baseRevision || 'unknown');
    const tabContext = state.graphTabContexts.get(cachedGraph.resourceIdentity);
    graphCanvas.setProjection(cachedGraph, tabContext || null);
    graphLayoutStore.load(cachedGraph).then(layout => {
      if (layout && workspaceShell.activeResource()?.identity === cachedGraph.resourceIdentity && !tabContext)
        graphCanvas.setProjection(cachedGraph, layout);
    });
  } else graphCanvas.clear();
  refreshWorkspaceResources(path);
  updateLifecycleButtons();
  refreshDirty(); parseSelected(); updateHistoryButtons(); source.focus();
}

function nodeLabel(node) {
  return node.key ?? (node.path === '' ? 'Document' : node.path.split('/').at(-1));
}

function parentNode(model, path) {
  if (!model?.root || !path) return null;
  const slash = path.lastIndexOf('/');
  return findModelNode(model.root, slash < 0 ? '' : path.slice(0, slash));
}

function extensionSchemaFor(node) {
  const model = state.documentModels.get(state.selected); let context = node;
  while (context) {
    const fields = new Map((context.children || []).filter(child => child.key != null).map(child => [child.key, child.value]));
    let typeId = null, contentType = null;
    if (fields.get('type') === 'action' && fields.get('action')) { typeId = fields.get('action'); contentType = 'behavior-action'; }
    else if (fields.get('type') === 'condition' && fields.get('condition')) { typeId = fields.get('condition'); contentType = 'behavior-condition'; }
    else if (fields.get('type')?.includes(':')) typeId = fields.get('type');
    if (typeId) {
      if (contentType) return state.editorSchemas.get(`${contentType}:${typeId}`) || null;
      const matches = [...state.editorSchemas.values()].filter(value => value.typeId === typeId);
      if (matches.length === 1) return matches[0];
      const preferred = state.selected?.startsWith('quests/') ? ['objective', 'condition', 'command']
        : state.selected?.startsWith('behaviors/') ? ['behavior-action', 'behavior-condition', 'command', 'condition']
          : ['command', 'condition', 'objective'];
      for (const kind of preferred) { const found = matches.find(value => value.contentType === kind); if (found) return found; }
    }
    context = parentNode(model, context.path);
  }
  return null;
}

function fieldSchema(node) {
  const descriptor = extensionSchemaFor(node); if (!descriptor || node.key == null) return null;
  let schema = descriptor.schema;
  const mergeBranch = branch => {
    if (!Array.isArray(branch)) return;
    for (const choice of branch) {
      const required = choice?.properties || {};
      const parent = parentNode(state.documentModels.get(state.selected), node.path);
      const values = new Map((parent?.children || []).map(child => [child.key, child.value]));
      const matches = Object.entries(required).every(([key, rule]) => rule?.const == null || String(rule.const) === values.get(key));
      if (matches) schema = { ...schema, ...choice, properties: { ...(schema.properties || {}), ...(choice.properties || {}) } };
    }
  };
  mergeBranch(schema.oneOf); mergeBranch(schema.anyOf);
  const property = schema.properties?.[node.key];
  return property ? { ...property, required: Array.isArray(schema.required) && schema.required.includes(node.key), descriptor } : null;
}

function schemaInput(node, rule) {
  let input;
  if (Array.isArray(rule.enum)) {
    input = document.createElement('select');
    for (const optionValue of rule.enum) { const option = document.createElement('option'); option.value = String(optionValue); option.textContent = String(optionValue); input.append(option); }
    input.value = node.value ?? '';
  } else {
    input = document.createElement('input');
    const widget = rule['x-persona-widget'];
    if (rule.type === 'boolean' || widget === 'checkbox') { input.type = 'checkbox'; input.checked = node.value === 'true'; }
    else if (widget === 'color') input.type = 'color';
    else if (rule.type === 'integer' || rule.type === 'number') { input.type = widget === 'slider' ? 'range' : 'number'; input.step = rule.type === 'integer' ? '1' : 'any'; }
    else input.type = 'text';
    if (input.type !== 'checkbox') input.value = node.value ?? '';
    if (rule.minimum != null) input.min = rule.minimum;if (rule.maximum != null) input.max = rule.maximum;
    if (rule.minLength != null) input.minLength = rule.minLength;if (rule.maxLength != null) input.maxLength = rule.maxLength;
    if (rule.pattern) input.pattern = rule.pattern;if (rule.default != null) input.placeholder = `Default: ${rule.default}`;
  }
  input.dataset.path = node.path;input.title = rule.description || rule.title || '';
  input.setAttribute('aria-label', `${rule.title || node.key}${rule.required ? ' (required)' : ''}`);
  input.addEventListener('change', () => applyVisualEdit(node.path, input.type === 'checkbox' ? String(input.checked) : input.value));
  if (rule['x-persona-catalog']) bindCatalogInput(input, node, rule);
  return input;
}

function catalogDependencies(node, metadata) {
  const parent = parentNode(state.documentModels.get(state.selected), node.path);
  const fields = new Map((parent?.children || []).map(child => [child.key, child.value]));
  return Object.fromEntries((metadata.dependencyFields || []).filter(field => fields.has(field)).map(field => [field, fields.get(field)]));
}

function bindCatalogInput(input, node, rule) {
  const metadata = state.editorCatalogs.get(rule['x-persona-catalog']); if (!metadata) { input.setCustomValidity('Live catalog is unavailable'); input.classList.add('invalid-catalog'); return; }
  const list = document.createElement('datalist'); list.id = `catalog-${crypto.randomUUID()}`; input.setAttribute('list', list.id); input._catalogList = list;
  const refresh = () => requestCatalog(input, node, metadata, input.value);
  input.addEventListener('focus', refresh, { once: true });
  input.addEventListener('input', () => { clearTimeout(input._catalogTimer); input._catalogTimer = setTimeout(refresh, 250); });
  requestCatalog(input, node, metadata, input.value);
}

async function requestCatalog(input, node, metadata, search) {
  const dependencies = catalogDependencies(node, metadata);
  const cacheKey = `${state.installationIdentity || sessionId}:${metadata.extensionVersion}:${metadata.catalogId}:${metadata.revision}:${JSON.stringify(dependencies)}:${search}:0`;
  const cached = state.catalogCache.get(cacheKey) || (metadata.cachePolicy !== 'NONE' ? JSON.parse(sessionStorage.getItem(`persona:catalog:${cacheKey}`) || 'null') : null);
  if (cached) { applyCatalogResult(input, metadata, cached, true); return; }
  const requestId = crypto.randomUUID(); state.catalogRequests.set(requestId, { input, metadata, cacheKey });
  input.dataset.catalogState = 'loading';
  if (!await sendSocket('CATALOG_REQUEST', { protocolVersion, requestId, catalogId: metadata.catalogId,
    expectedRevision: metadata.revision, search, page: 0, pageSize: 100, dependencies })) {
    state.catalogRequests.delete(requestId); input.dataset.catalogState = 'unavailable';
  }
}

function receiveCatalogResult(result) {
  const pending = state.catalogRequests.get(result.requestId); if (!pending) return; state.catalogRequests.delete(result.requestId);
  if (result.status === 'LIVE') {
    state.catalogCache.set(pending.cacheKey, result);
    if (pending.metadata.cachePolicy !== 'NONE') sessionStorage.setItem(`persona:catalog:${pending.cacheKey}`, JSON.stringify(result));
  }
  applyCatalogResult(pending.input, pending.metadata, result, false);
}

function applyCatalogResult(input, metadata, result, cached) {
  if (!input.isConnected) return; input.dataset.catalogState = cached ? 'cached' : result.status.toLowerCase();
  const badge=input.closest('.yaml-field')?.querySelector('.catalog-state');
  if(badge)badge.textContent=cached?'catalog cached':result.status==='LIVE'?'catalog live':result.status==='STALE'?'catalog stale':result.status==='DENIED'?'catalog denied':'catalog unavailable';
  const current = input.value; input._catalogList?.replaceChildren(...(result.values || []).map(value => {
    const option = document.createElement('option'); option.value = value.id;
    option.label = `${value.label || value.id}${value.group ? ` — ${value.group}` : ''}${value.deprecated ? ' (deprecated)' : ''}`;
    option.title = value.description || ''; return option;
  }));
  const known = (result.values || []).some(value => value.id === current && !value.deprecated);
  const authoritative = result.status === 'LIVE' && !result.hasMore;
  const rejected = authoritative && current && !known && metadata.missingValuePolicy === 'REJECT';
  input.classList.toggle('invalid-catalog', rejected); input.setCustomValidity(rejected ? `Value ${current} is no longer available in ${metadata.catalogId}` : '');
  updatePublishButton();
}

function renderVisualNode(node) {
  const container = document.createElement('div');
  container.className = 'yaml-node'; container.dataset.path = node.path;
  const model=state.documentModels.get(state.selected),parent=parentNode(model,node.path),orderedItem=parent?.kind==='sequence';
  if(orderedItem){container.draggable=true;container.classList.add('draggable-node');container.addEventListener('dragstart',event=>{state.dragPath=node.path;event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('text/plain',node.path);});container.addEventListener('dragover',event=>{if(state.dragPath&&state.dragPath!==node.path){event.preventDefault();event.dataTransfer.dropEffect='move';container.classList.add('drop-target');}});container.addEventListener('dragleave',()=>container.classList.remove('drop-target'));container.addEventListener('drop',event=>{event.preventDefault();container.classList.remove('drop-target');const sourcePath=state.dragPath||event.dataTransfer.getData('text/plain');state.dragPath=null;if(sourcePath&&sourcePath!==node.path)applyStructure(event.offsetY>container.offsetHeight/2?'MOVE_AFTER':'MOVE_BEFORE',sourcePath,node.path);});container.addEventListener('dragend',()=>{state.dragPath=null;document.querySelectorAll('.drop-target').forEach(value=>value.classList.remove('drop-target'));});}
  const row = document.createElement('div'); row.className = 'yaml-field';
  const rule = fieldSchema(node);
  const label = document.createElement('span'); label.className = 'yaml-key'; label.textContent = rule?.title || nodeLabel(node);
  if (rule?.required) label.textContent += ' *';
  if (rule?.deprecated) { label.textContent += ' (deprecated)'; label.classList.add('deprecated'); }
  const kind = document.createElement('span'); kind.className = 'yaml-kind'; kind.textContent = node.kind;
  const heading = document.createElement('span'); heading.append(label, document.createElement('br'), kind);
  if(node.kind==='mapping'){const fields=new Map((node.children||[]).filter(child=>child.key!=null).map(child=>[child.key,child.value])),type=fields.get('type'),id=fields.get('id');if(type){const badge=document.createElement('small');badge.className='node-semantic';let detail=type;if(parent?.kind==='sequence')detail+=` · order ${Number(node.key?.match(/\d+/)?.[0]||0)+1}`;if(type==='priority-selector')detail+=' · first success wins';if(type==='parallel')detail+=` · success ${fields.get('success-threshold')||'all'} · failure ${fields.get('failure-threshold')||'1'}`;if(type==='checkpoint')detail+=' · durable boundary';badge.textContent=detail;heading.append(document.createElement('br'),badge);container.classList.add(`node-type-${type}`);}if(id&&state.duplicateNodeIds?.has(id)){container.classList.add('node-error');const warning=document.createElement('small');warning.className='scope-warning';warning.textContent=`Duplicate stable node ID: ${id}`;heading.append(document.createElement('br'),warning);}const rootScope=state.documentModels.get(state.selected)?.root?.children?.find(child=>child.key==='scope')?.value;if(editorKind()==='behavior'&&rootScope==='shared'){const action=fields.get('action'),condition=fields.get('condition'),memoryScope=fields.get('scope');if(['command','script','set-anchor','set-visible','private-navigate','begin-private-presentation'].includes(action)||['quest-state','item-count','flag','variable','permission','world'].includes(condition)||memoryScope==='player'){container.classList.add('node-error');const warning=document.createElement('small');warning.className='scope-warning';warning.textContent='Player-only node is not valid in a shared behavior';heading.append(document.createElement('br'),warning);}}if(type==='subtree'&&fields.get('subtree')){const jump=document.createElement('button');jump.type='button';jump.className='subtree-jump';jump.textContent=`Open ${fields.get('subtree')}`;jump.addEventListener('click',event=>{event.stopPropagation();openBehavior(fields.get('subtree'));});heading.append(document.createElement('br'),jump);}}
  row.append(heading);
  if(orderedItem){const actions=document.createElement('span');actions.className='node-actions';const duplicate=document.createElement('button');duplicate.type='button';duplicate.textContent='Duplicate';duplicate.title='Duplicate this complete branch with a new stable ID';duplicate.addEventListener('click',event=>{event.stopPropagation();applyStructure('DUPLICATE_AFTER',node.path,null);});actions.append(duplicate);if(editorKind()==='behavior'&&node.kind==='mapping'){const extract=document.createElement('button');extract.type='button';extract.textContent='Extract subtree';extract.addEventListener('click',event=>{event.stopPropagation();extractSubtree(node.path);});actions.append(extract);}const remove=document.createElement('button');remove.type='button';remove.textContent='Delete';remove.addEventListener('click',event=>{event.stopPropagation();if(confirm(`Delete ${nodeLabel(node)} and its complete branch?`))applyStructure('DELETE',node.path,null);});actions.append(remove);heading.append(actions);}
  const original = state.originalModels.get(state.selected);
  const originalNode = original ? findModelNode(original.root, node.path) : null;
  if (originalNode && (originalNode.kind !== node.kind || originalNode.value !== node.value)) row.classList.add('changed');
  if (node.editable) {
    const input = rule ? schemaInput(node, rule) : document.createElement('input');
    if (!rule) { input.dataset.path = node.path;if (node.kind === 'boolean') { input.type = 'checkbox'; input.checked = node.value === 'true'; }
      else { input.type = node.kind === 'integer' || node.kind === 'number' ? 'number' : 'text'; input.value = node.value ?? ''; }
      input.addEventListener('change', () => applyVisualEdit(node.path, input.type === 'checkbox' ? String(input.checked) : input.value)); }
    if (rule?.description) { const help=document.createElement('small');help.className='field-help';help.textContent=rule.description;heading.append(document.createElement('br'),help); }
    if (rule?.['x-persona-catalog']) { const badge=document.createElement('small');badge.className='catalog-state';badge.textContent=state.editorCatalogs.has(rule['x-persona-catalog'])?'catalog loading':'catalog unavailable';heading.append(document.createElement('br'),badge); }
    row.append(input);if (input._catalogList) row.append(input._catalogList);
  } else {
    const summary = document.createElement('span');
    summary.className = node.kind === 'custom' || node.kind === 'alias' ? 'custom-value' : '';
    summary.textContent = node.value ?? `${node.children.length} item${node.children.length === 1 ? '' : 's'}`;
    row.append(summary);
  }
  row.addEventListener('click', event => {
    if (event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return;
    source.focus(); source.setSelectionRange(node.startOffset, node.endOffset);
    selectVisualNode(node.path);
  });
  container.append(row);
  for (const child of node.children) container.append(renderVisualNode(child));
  return container;
}

function selectVisualNode(path, syncGraph = true) {
  state.selectedNode = path;
  document.querySelectorAll('.yaml-field').forEach(row => row.classList.toggle('selected', row.parentElement.dataset.path === path));
  const selected = visual.querySelector(`.yaml-node[data-path="${CSS.escape(path)}"]`);
  selected?.scrollIntoView({ block: 'nearest' });
  if (syncGraph) graphCanvas.selectYamlPath(path);
}

function renderDocument(model) {
  if (!model?.root) { visual.replaceChildren();visualTools.hidden=true; return; }
  const ids=[];const gather=node=>{if(node.kind==='mapping'){const id=node.children?.find(child=>child.key==='id')?.value;if(id)ids.push(id);}for(const child of node.children||[])gather(child);};gather(model.root);state.duplicateNodeIds=new Set(ids.filter((id,index)=>ids.indexOf(id)!==index));
  visual.replaceChildren(renderVisualNode(model.root));
  refreshVisualTools(model);
  renderInsights(model);
  if (state.selectedNode) selectVisualNode(state.selectedNode);
  overlayLiveBehaviorNodes();
}
function modelValue(node){if(!node)return null;if(node.kind==='mapping')return Object.fromEntries(node.children.map(child=>[child.key,modelValue(child)]));if(node.kind==='sequence')return node.children.map(modelValue);if(node.kind==='boolean')return node.value==='true';if(node.kind==='integer'||node.kind==='number')return Number(node.value);if(node.kind==='null')return null;return node.value;}
function graphCard(label,problem=false){const card=document.createElement('span');card.className=`graph-node${problem?' problem':''}`;card.textContent=label;return card;}
function nestedSteps(value,result=[]){if(Array.isArray(value))for(const step of value){if(step&&typeof step==='object'){result.push(step);for(const key of ['then','else','script','on-success','on-failure'])nestedSteps(step[key],result);for(const option of step.options||[])nestedSteps(option.script,result);}}return result;}
function renderInsights(model){const value=modelValue(model.root),kind=editorKind();insightsTitle.textContent=`${kind[0].toUpperCase()+kind.slice(1)} structure`;visualGraph.replaceChildren();visualPreview.replaceChildren();if(kind==='behavior')renderBehaviorInsights(value);else if(kind==='dialogue')renderDialogueInsights(value);else if(kind==='quest')renderQuestInsights(value);else if(kind==='npc')renderNpcInsights(value);else renderScriptInsights(value);}
function renderBehaviorInsights(value){const nodes=[];const walk=node=>{if(!node||typeof node!=='object')return;nodes.push(node);for(const child of node.children||[])walk(child);walk(node.child);};walk(value.root);for(const node of nodes){const persistence=['checkpoint','wait','cooldown'].includes(node.type)?' · durable checkpoint/deadline':['action','condition'].includes(node.type)?' · transient':'',semantics=node.type==='sequence'?' · ordered':node.type?.includes('selector')?' · priority':node.type==='parallel'?` · success ${node['success-threshold']||'all'}, failure ${node['failure-threshold']||1}`:'';visualGraph.append(graphCard(`${node.id||'?'} · ${node.type||'?'}${semantics}${persistence}`));}visualPreview.textContent=`${value.scope||'player'} scope · ${nodes.length} nodes · placeholders: <player>, <npc>, <memory:key>, <npc-memory:key>, <event:key>`;}
function dialogueEdges(nodes){const edges=new Map();for(const [id,node] of Object.entries(nodes||{})){const outgoing=nestedSteps(node.script||[]).filter(step=>step.type==='goto').map(step=>step.dialogue?`${step.dialogue}/${step.node||'start'}`:step.node).filter(Boolean);edges.set(id,outgoing);}return edges;}
function renderDialogueInsights(value){const nodes=value.nodes||{},edges=dialogueEdges(nodes),reachable=new Set(),visiting=new Set(),loops=new Set(),visit=id=>{if(!id||!nodes[id])return;if(visiting.has(id)){loops.add(id);return;}if(reachable.has(id))return;reachable.add(id);visiting.add(id);for(const next of edges.get(id)||[])if(!next.includes('/'))visit(next);visiting.delete(id);};visit(value.start);for(const [id,outgoing] of edges){const missing=outgoing.some(next=>!next.includes('/')&&!nodes[next]),unreachable=!reachable.has(id),dead=!nestedSteps(nodes[id].script||[]).some(step=>['goto','end-dialogue'].includes(step.type)),loop=loops.has(id)||outgoing.some(next=>loops.has(next));visualGraph.append(graphCard(`${id}${outgoing.length?` → ${outgoing.join(', ')}`:''}${unreachable?' · unreachable':''}${dead?' · implicit end':''}${loop?' · transfer loop':''}`,missing||unreachable||loop));}const lines=nestedSteps(Object.values(nodes).flatMap(node=>node.script||[])).filter(step=>step.type==='say').map(step=>{const translations=Object.entries(step.translations||{}).map(([locale,text])=>`${locale}=${text}`).join(', ');return`${step.text||step['text-key']||'(weighted variants)'}${translations?` {${translations}}`:''}${step.delay?` [${step.delay}]`:''}`;});visualPreview.textContent=`Preview lines / localization keys:\n${lines.join('\n')||'(none)'}\nPlaceholders: <player>, <npc>, <dialogue>, <quest>, <phase>, <objective>, <current>, <required>, <memory:key>`;}
function renderQuestInsights(value){const phases=value.phases||[],ids=new Set(phases.map(phase=>phase.id)),reachable=new Set();const visit=id=>{if(!id||id==='end'||reachable.has(id)||!ids.has(id))return;reachable.add(id);const index=phases.findIndex(phase=>phase.id===id),phase=phases[index];for(const branch of phase.branches||[])visit(branch['next-phase']);if(index+1<phases.length)visit(phases[index+1].id);};visit(phases[0]?.id);phases.forEach((phase,index)=>{const destinations=(phase.branches||[]).map(branch=>branch['next-phase']).filter(Boolean),invalid=destinations.some(id=>id!=='end'&&!ids.has(id)),impossible=(phase.branches||[]).some(branch=>branch.when?.type==='chance'&&Number(branch.when.chance)<=0),unreachable=!reachable.has(phase.id);visualGraph.append(graphCard(`${phase.id||'?'} → ${destinations.join(', ')||phases[index+1]?.id||'end'} · ${(phase.objectives||[]).length} objectives${impossible?' · impossible branch':''}${unreachable?' · unreachable':''}`,invalid||impossible||unreachable));});const objectives=phases.flatMap(phase=>(phase.objectives||[]).map(objective=>`${phase.id}/${objective.id}: ${objective.type}, ${objective.optional?'optional':'required'}, ${objective.hidden?'hidden':'visible'}, target ${objective.amount||objective.duration||1}`));visualPreview.textContent=`Requirements: ${JSON.stringify(value.when||value.requirements||'none')}\nTimer: ${value['time-limit']||'none'} · repeatable: ${value.repeatable||false} · cooldown: ${value.cooldown||'none'} · maximum completions: ${value['maximum-completions']||'unlimited'}\n${objectives.join('\n')}\nPlaceholders: <player>, <quest>, <phase>, <objective>, <current>, <required>, <memory:key>`;}
function renderNpcInsights(value){const definition=value.id,live=[...state.liveData.npcs.values()].filter(npc=>npc.definitionId===definition),anchors=Object.entries(value.anchors||{}),table=document.createElement('table');table.className='anchor-table';table.innerHTML='<tr><th>Anchor</th><th>World / coordinates</th><th></th></tr>';for(const [name,anchor] of anchors){const actor=live.find(npc=>!npc.playerId),far=actor?.position&&actor.position.world===anchor.world?Math.hypot(actor.position.x-anchor.x,actor.position.y-anchor.y,actor.position.z-anchor.z)>48:false,row=document.createElement('tr'),label=document.createElement('td'),position=document.createElement('td'),action=document.createElement('td'),button=document.createElement('button');label.textContent=name+(far?' ⚠ far from actor':'');position.textContent=`${anchor.world} ${anchor.x} ${anchor.y} ${anchor.z} ${anchor.yaw||0} ${anchor.pitch||0}`;button.type='button';button.textContent='Paste coordinates';button.addEventListener('click',()=>importAnchor(name));action.append(button);row.append(label,position,action);table.append(row);}visualGraph.append(table);if(anchors.length){const map=document.createElement('div');map.className='anchor-map';const xs=anchors.map(([,a])=>Number(a.x)),zs=anchors.map(([,a])=>Number(a.z)),minX=Math.min(...xs),maxX=Math.max(...xs),minZ=Math.min(...zs),maxZ=Math.max(...zs);for(const [name,anchor] of anchors){const point=document.createElement('span');point.className='anchor-point';point.style.left=`${5+90*(Number(anchor.x)-minX)/(maxX-minX||1)}%`;point.style.top=`${5+90*(Number(anchor.z)-minZ)/(maxZ-minZ||1)}%`;point.textContent=name;point.title=`${anchor.world}: ${anchor.x}, ${anchor.y}, ${anchor.z}`;map.append(point);}visualGraph.append(map);}const presentations=live.map(npc=>`${npc.playerId||'shared'}: ${npc.presentation}/${npc.projectionState}, ${npc.entityName||value['display-name']||''} ${npc.entityType||''}, skin ${npc.skin||'none'}, equipment ${JSON.stringify(npc.equipment||{})}, age ${npc.age??'n/a'}, pose ${npc.pose||'n/a'}`);visualPreview.textContent=`Definition ${definition||'?'} · display ${value['display-name']||''}\nshared behavior ${value['shared-behavior']||'none'} · player behavior ${value['player-behavior']||'none'}\n${presentations.join('\n')||'Open a trusted live subscription to preview shared/private Citizens presentation.'}`;}
function renderScriptInsights(value){const scripts=value.scripts||{};for(const [id,steps] of Object.entries(scripts))visualGraph.append(graphCard(`${id} · ${(steps||[]).length} blocks`));visualPreview.textContent='Available placeholders depend on the caller: common <player>; dialogue <npc>/<dialogue>; quest <quest>/<phase>/<objective>/<current>/<required>; memory <memory:key>/<npc-memory:key>.';}
async function importAnchor(name){const raw=prompt('Paste “x y z [yaw pitch]” or a Minecraft /tp command');if(!raw)return;const numbers=raw.match(/-?\d+(?:\.\d+)?/g)?.map(Number);if(!numbers||numbers.length<3){yamlStatus.textContent='Could not find at least x, y, and z coordinates.';return;}const values=numbers.slice(-5),xyz=values.length>=5?values:values.slice(0,3);for(const [field,value] of [['x',xyz[0]],['y',xyz[1]],['z',xyz[2]],['yaw',values.length>=5?values[3]:0],['pitch',values.length>=5?values[4]:0]])await applyVisualEdit(`/anchors/${name.replaceAll('~','~0').replaceAll('/','~1')}/${field}`,String(value));}
function runSimulation(input, output) { try { const mocks=JSON.parse(input.value),model=state.documentModels.get(state.selected),value=modelValue(model.root);output.textContent=JSON.stringify(simulate(editorKind(),value,mocks),null,2); } catch(error) { output.textContent=`Simulation input error: ${error.message}`; } }
document.querySelector('#simulate-open').addEventListener('click',()=>showOutput('simulation'));document.querySelector('#simulation-close').addEventListener('click',()=>simulationDialog.close());document.querySelector('#simulation-run').addEventListener('click',()=>runSimulation(simulationInput,simulationOutput));
document.querySelector('#simulation-dock-run').addEventListener('click',()=>runSimulation(document.querySelector('#simulation-dock-input'),document.querySelector('#simulation-dock-output')));
function testCondition(condition,mocks){if(!condition)return true;if(Array.isArray(condition))return condition.every(item=>testCondition(item,mocks));switch(condition.type){case'all':return(condition.conditions||[]).every(item=>testCondition(item,mocks));case'any':return(condition.conditions||[]).some(item=>testCondition(item,mocks));case'not':return!testCondition(condition.when||condition.condition,mocks);case'flag':return Boolean(mocks.flags?.[condition.name])===Boolean(condition.value??true);case'variable':return String(mocks.variables?.[condition.name]??'')===String(condition.value??'');case'quest-state':return String(mocks.quests?.[condition.quest]??'not-started')===String(condition.state);case'memory':return String(mocks.memories?.[condition.key]??'')===String(condition.value??true);case'event':return(mocks.events||[]).includes(condition.event||condition.name);case'chance':return Number(mocks.chance??0.5)<Number(condition.chance??0);default:return Boolean(mocks.conditions?.[condition.type]);}}
function simulate(kind,value,mocks){if(kind==='behavior')return simulateBehavior(value,mocks);if(kind==='dialogue')return simulateDialogue(value,mocks);if(kind==='quest')return simulateQuest(value,mocks);return{kind,steps:nestedSteps(kind==='script'?Object.values(value.scripts||{}).flat():[value]).map(step=>step.type),note:'Preview is deterministic and performs no server mutations.'};}
function simulateBehavior(value,mocks){const trace=[];const run=node=>{if(!node)return'FAILURE';let status='SUCCESS';switch(node.type){case'condition':status=testCondition({...node,type:node.condition},mocks)?'SUCCESS':'FAILURE';break;case'wait':status='RUNNING';break;case'action':status=String(mocks.actions?.[node.action]||'SUCCESS');break;case'sequence':for(const child of node.children||[]){status=run(child);if(status!=='SUCCESS')break;}break;case'selector':case'priority-selector':status='FAILURE';for(const child of node.children||[]){const next=run(child);if(next!=='FAILURE'){status=next;break;}}break;case'parallel':{const results=(node.children||[]).map(run),success=results.filter(item=>item==='SUCCESS').length,failure=results.filter(item=>item==='FAILURE').length;status=success>=Number(node['success-threshold']||results.length)?'SUCCESS':failure>=Number(node['failure-threshold']||1)?'FAILURE':'RUNNING';break;}case'invert':status=run(node.child);status=status==='SUCCESS'?'FAILURE':status==='FAILURE'?'SUCCESS':status;break;case'repeat':case'retry':case'timeout':case'cooldown':case'checkpoint':status=run(node.child);break;case'subtree':status=String(mocks.subtrees?.[node.subtree]||'RUNNING');break;default:status='FAILURE';}trace.push({id:node.id,type:node.type,status});return status;};return{status:run(value.root),trace,mockMemories:mocks.memories||{},events:mocks.events||[]};}
function interpolate(text,mocks){return String(text??'').replace(/<([^>]+)>/g,(all,key)=>{const [group,name]=key.split(':',2);return name!=null?mocks[group]?.[name]??all:mocks[key]??all;});}
function simulateDialogue(value,mocks){const transcript=[],visited=[];let nodeId=value.start,steps=0;while(nodeId&&value.nodes?.[nodeId]&&steps++<64){if(visited.includes(nodeId))return{status:'TRANSFER_LOOP',visited:[...visited,nodeId],transcript};visited.push(nodeId);let jump=null;for(const step of value.nodes[nodeId].script||[]){if(step.type==='say')transcript.push({line:interpolate(step.text||'(weighted variant)',mocks),delay:step.delay||'default'});else if(step.type==='choice'){const eligible=(step.options||[]).filter(option=>testCondition(option.when,mocks));transcript.push({choices:eligible.map(option=>interpolate(option.text,mocks))});const selected=eligible[Number(mocks.choice||0)];for(const nested of selected?.script||[])if(nested.type==='goto')jump=nested.node;}else if(step.type==='if'){const chosen=testCondition(step.when,mocks)?step.then:step.else;for(const nested of chosen||[])if(nested.type==='goto')jump=nested.node;}else if(step.type==='goto')jump=step.dialogue?`${step.dialogue}/${step.node||'start'}`:step.node;else if(step.type==='end-dialogue')return{status:'ENDED',visited,transcript};}if(jump?.includes('/'))return{status:'TRANSFER',target:jump,visited,transcript};nodeId=jump;}return{status:steps>=64?'TRANSITION_LIMIT':'IMPLICIT_END',visited,transcript};}
function simulateQuest(value,mocks){const transitions=[],memoryChanges=[];for(const phase of value.phases||[]){const objectives=(phase.objectives||[]).map(objective=>({id:objective.id,type:objective.type,current:Number(mocks.objectives?.[objective.id]||0),required:Number(objective.amount||objective['required-progress']||parseFloat(objective.duration)||1),optional:Boolean(objective.optional),hidden:Boolean(objective.hidden)}));const complete=objectives.filter(item=>!item.optional).every(item=>item.current>=item.required);transitions.push({phase:phase.id,objectives,complete});if(!complete)break;const branch=(phase.branches||[]).find(item=>testCondition(item.when,mocks));if(branch)transitions.at(-1).next=branch['next-phase'];for(const step of nestedSteps(phase['on-complete']||[]))if(['set-variable','set-flag'].includes(step.type))memoryChanges.push({type:step.type,name:step.name||step.flag,value:step.value});if(branch?.['next-phase']==='end')break;}return{requirements:testCondition(value.when||value.requirements,mocks),transitions,resultingChanges:memoryChanges,repeatable:Boolean(value.repeatable),timer:value['time-limit']||null};}
function openBehavior(id){for(const [path,model] of state.documentModels){if(path.startsWith('behaviors/')&&model.root?.children?.find(child=>child.key==='id')?.value===id){selectFile(path);return;}}for(const path of state.files.keys())if(path.startsWith('behaviors/')){const text=state.files.get(path);if(new RegExp(`^id:\\s*["']?${id.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')}["']?\\s*$`,'m').test(text)){selectFile(path);return;}}yamlStatus.textContent=`Referenced subtree ${id} is not present in this project.`;}

function editorKind(){return state.selected?.startsWith('behaviors/')?'behavior':state.selected?.startsWith('npcs/')?'npc':state.selected?.startsWith('dialogues/')?'dialogue':state.selected?.startsWith('quests/')?'quest':state.selected?.endsWith('scripts.yml')?'script':'generic';}
function collectContainers(node,result=[]){if(node.kind==='sequence'||node.kind==='mapping')result.push(node);for(const child of node.children||[])collectContainers(child,result);return result;}
function visualTemplates(){const id=()=>`node-${crypto.randomUUID().slice(0,8)}`,kind=editorKind(),items=[];if(kind==='behavior')items.push(
  {label:'Composite · sequence',kind:'sequence',yaml:()=>`- id: ${id()}\n  type: sequence\n  children:\n    - id: ${id()}\n      type: action\n      action: set-visible\n      visible: true`},
  {label:'Composite · selector',kind:'sequence',yaml:()=>`- id: ${id()}\n  type: selector\n  children:\n    - id: ${id()}\n      type: condition\n      condition: chance\n      chance: 1.0`},
  {label:'Decorator · invert',kind:'sequence',yaml:()=>`- id: ${id()}\n  type: invert\n  child:\n    id: ${id()}\n    type: condition\n    condition: chance\n    chance: 0.5`},
  {label:'Leaf · action',kind:'sequence',yaml:()=>`- id: ${id()}\n  type: action\n  action: set-visible\n  visible: true`},
  {label:'Leaf · condition',kind:'sequence',yaml:()=>`- id: ${id()}\n  type: condition\n  condition: chance\n  chance: 0.5`},
  {label:'Subtree reference',kind:'sequence',yaml:()=>`- id: ${id()}\n  type: subtree\n  subtree: namespace:behavior`});
if(kind==='npc')items.push({label:'Dialogue registration',kind:'sequence',yaml:()=>'- id: namespace:dialogue\n  priority: 0'},{label:'Hook script block',kind:'sequence',yaml:()=>'- type: message\n  text: "New NPC hook"'},{label:'Named anchor',kind:'mapping',key:()=>prompt('Stable anchor name','new-anchor'),yaml:()=>'{ world: world, x: 0, y: 64, z: 0, yaw: 0, pitch: 0 }'});
if(kind==='dialogue')items.push({label:'Dialogue node',kind:'mapping',key:()=>prompt('Stable node ID',id()),yaml:()=>`script:\n  - type: say\n    text: "New dialogue line"`},{label:'Say block',kind:'sequence',yaml:()=>'- type: say\n  text: "New dialogue line"'},{label:'Choice block',kind:'sequence',yaml:()=>'- type: choice\n  options:\n    - text: "Continue"\n      script:\n        - type: end-dialogue'},{label:'Transfer block',kind:'sequence',yaml:()=>'- type: goto\n  node: destination'});
if(kind==='quest')items.push({label:'Quest phase',kind:'sequence',yaml:()=>`- id: ${id()}\n  title: "New phase"\n  objectives:\n    - id: ${id()}\n      type: wait\n      duration: 1s`},{label:'Objective · collect item',kind:'sequence',yaml:()=>`- id: ${id()}\n  type: collect-item\n  material: minecraft:stone\n  amount: 1`},{label:'Phase branch',kind:'sequence',yaml:()=>'- next-phase: end\n  when:\n    type: chance\n    chance: 1.0'},{label:'Lifecycle script block',kind:'sequence',yaml:()=>'- type: message\n  text: "Quest updated"'});
if(kind==='script'||['npc','dialogue','quest'].includes(kind))items.push({label:'Script · message',kind:'sequence',yaml:()=>'- type: message\n  text: "New message"'},{label:'Script · if',kind:'sequence',yaml:()=>'- type: if\n  when:\n    type: chance\n    chance: 1.0\n  then:\n    - type: message\n      text: "Condition passed"'},{label:'Script · random',kind:'sequence',yaml:()=>'- type: random\n  options:\n    - weight: 1\n      script:\n        - type: message\n          text: "Random branch"'},{label:'Reusable script',kind:'mapping',key:()=>prompt('Reusable script ID','new-script'),yaml:()=>'- type: message\n  text: "New reusable script"'});
for(const schema of state.editorSchemas.values()){const label=`Extension · ${schema.contentType} · ${schema.typeId}`;if(kind==='behavior'&&schema.contentType==='behavior-action')items.push({label,kind:'sequence',yaml:()=>`- id: ${id()}\n  type: action\n  action: ${schema.typeId}`});else if(kind==='behavior'&&schema.contentType==='behavior-condition')items.push({label,kind:'sequence',yaml:()=>`- id: ${id()}\n  type: condition\n  condition: ${schema.typeId}`});else if(schema.contentType==='command')items.push({label,kind:'sequence',yaml:()=>`- type: ${schema.typeId}`});else if(kind==='quest'&&schema.contentType==='objective')items.push({label,kind:'sequence',yaml:()=>`- id: ${id()}\n  type: ${schema.typeId}`});}
return items;}
function refreshVisualTools(){visualTools.hidden=true;visualContainer.replaceChildren();visualTemplate.replaceChildren();}
visualTools.addEventListener('submit',event=>{event.preventDefault();commandDispatcher.execute('graph.add',{sourcePin:null});});

function overlayLiveBehaviorNodes(){
  visual.querySelectorAll('.yaml-node.live-active').forEach(node=>node.classList.remove('live-active'));
  const model=state.documentModels.get(state.selected),definition=model?.root?.children?.find(node=>node.key==='id')?.value;
  const kind=editorKind(),active=liveNodeKeys(kind,definition,state.liveData);
  graphCanvas.setLiveNodeKeys(active);
  if(kind!=='behavior')return;
  const runtimes=[...state.liveData.behaviors.values()].filter(runtime=>!definition||runtime.behaviorId===definition);
  if(!active.size)return;
  const visit=node=>{const id=node.children?.find(child=>child.key==='id')?.value,element=visual.querySelector(`.yaml-node[data-path="${CSS.escape(node.path)}"]`);if(id&&active.has(id))element?.classList.add('live-active');if(id&&element){const details=runtimes.flatMap(runtime=>[...(runtime.recentOutcomes||[]),...(runtime.recentConditions||[])]).filter(item=>item.node?.split('/').at(-1)===id).slice(-3).map(item=>item.detail||item.explanation||item.status).filter(Boolean);if(details.length)element.title=details.join('\n');}for(const child of node.children||[])visit(child);};visit(model.root);
}

async function parseSelected() {
  if (!state.connected || !state.selected) return;
  const path = state.selected, content = source.value, generation = ++state.parseGeneration;
  try {
    const response = await fetch(sessionApi('/documents/parse'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ content })
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const model = await response.json();
    if (generation !== state.parseGeneration || path !== state.selected) return;
    if (model.valid) {
      state.documentValidity.set(path, true); state.documentModels.set(path, model); yamlStatus.textContent = ''; renderDocument(model);
      if (state.pendingYamlPath) { selectVisualNode(state.pendingYamlPath); state.pendingYamlPath = null; }
      refreshWorkspaceResources(path);
      graphCanvas.setStale(false);
      refreshGraphProjection(path);
      if (!state.originalModels.has(path)) parseOriginal(path);
    } else {
      state.documentValidity.set(path, false); refreshWorkspaceResources(path);
      graphCanvas.setStale(true, 'The YAML is invalid. This is the last valid graph and visual editing is disabled.');
      const issue = model.diagnostics[0];
      yamlStatus.textContent = `YAML ${issue.line}:${issue.column} — ${issue.message}. Showing the last valid visual model.`;
    }
  } catch (error) {
    if (generation === state.parseGeneration) yamlStatus.textContent = `YAML analysis unavailable: ${error.message}`;
  }
}

async function parseOriginal(path) {
  if (!state.connected) return;
  try {
    const response = await fetch(sessionApi('/documents/parse'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ content: state.original.get(path) ?? '' })
    });
    if (!response.ok) return;
    const model = await response.json();
    if (model.valid) { state.originalModels.set(path, model); if (path === state.selected) renderDocument(state.documentModels.get(path)); }
  } catch { /* Current parsing status already reports endpoint availability. */ }
}

function scheduleParse() {
  if (!state.connected) return;
  clearTimeout(state.parseTimer);
  state.parseTimer = setTimeout(parseSelected, 250);
}

function visibleGraphProjection(full) {
  const nested = state.nestedGraph;
  const result = nestedProjection(full, nested);
  if (!result.valid) {
    state.nestedGraph = null; workspaceShell.setNestedBreadcrumbs(); return full;
  }
  return result.projection;
}

function showGraphProjection(full, layout) {
  const visible = visibleGraphProjection(full);
  graphCanvas.setProjection(visible, layout);
  graphCanvas.setDiagnosticPaths(visible.diagnostics.map(issue => issue.yamlPath));
}

async function applyGraphMutationResult(result, { label, context }) {
  if (!context.isCurrent()) return;
  const layout = graphCanvas.snapshot();
  source.value = result.content;
  state.files.set(context.selected, result.content);
  state.documentModels.set(context.selected, result.document);
  state.documentValidity.set(context.selected, true);
  state.graphProjections.set(context.resource.identity, result.projection);
  yamlStatus.textContent = `${label} applied as ${result.appliedOperationCount} authoritative operation${result.appliedOperationCount === 1 ? '' : 's'}; unrelated YAML was retained.`;
  renderDocument(result.document);
  showGraphProjection(result.projection, layout);
  graphCanvas.setStale(false);
  refreshDirty(); scheduleAutosave(); scheduleRecovery(); updateHistoryButtons();
}

function deleteGraphNodes(nodeIds) {
  if (!state.connected || graphMutationClient?.inFlight) return;
  const selected = (graphCanvas.projection?.nodes || [])
    .filter(node => nodeIds.includes(node.id) && node.yamlPath && !node.custom)
    .sort((left, right) => right.range.startOffset - left.range.startOffset);
  if (!selected.length) { yamlStatus.textContent = 'No selected graph node can be safely deleted visually.'; return; }
  if (!confirm(`Delete ${selected.length} selected node${selected.length === 1 ? '' : 's'} and their complete YAML branches?`)) return;
  graphMutationClient.mutate(selected.map(node => ({ type: 'DELETE', yamlPath: node.yamlPath })), 'Delete graph selection');
}

function copyGraphNode(nodeIds = [...graphCanvas.selection]) {
  const node = (graphCanvas.projection?.nodes || []).find(value => nodeIds.includes(value.id));
  const resource = workspaceShell.activeResource();
  if (!resource || resource.kind !== 'behavior' || !node?.yamlPath?.startsWith('/root/children/') || node.custom) {
    yamlStatus.textContent = 'Visual copy currently accepts one complete behavior child node.'; return;
  }
  state.graphClipboard = { resourceKind: 'behavior', sourceFilePath: resource.path,
    yamlPath: node.yamlPath, nodeKind: node.kind, title: node.title };
  status.textContent = `Copied behavior node ${node.title}; choose a compatible behavior container and paste.`;
}

function pasteGraphNode() {
  const copied = state.graphClipboard;
  if (!copied || graphCanvas.projection?.resourceKind !== 'behavior') {
    yamlStatus.textContent = 'The copied graph node is not compatible with this resource.'; return;
  }
  const insertion = graphInsertion({ nodeKind: copied.nodeKind, destination: 'children' }, null);
  if (!insertion) { yamlStatus.textContent = 'Select a compatible behavior container before pasting.'; return; }
  const key = prompt('Stable ID for the copied node', `${copied.title}-copy`)?.trim();
  if (!key) return;
  if (!/^[a-z0-9][a-z0-9_.-]{0,127}$/.test(key)) {
    yamlStatus.textContent = 'Node IDs use lowercase letters, digits, dot, underscore, and hyphen.'; return;
  }
  graphMutationClient.mutate([{ type: 'COPY', yamlPath: copied.yamlPath,
    sourceFilePath: copied.sourceFilePath, parentYamlPath: insertion.parentYamlPath,
    key, index: insertion.index }], 'Paste copied behavior node');
}

function handleGraphNodeAction({ type, node }) {
  if (type === 'DUPLICATE_NODE') {
    graphMutationClient.mutate([{ type: 'DUPLICATE', yamlPath: node.yamlPath }], 'Duplicate graph node'); return;
  }
  if (type === 'DELETE_NODE') { deleteGraphNodes([node.id]); return; }
  if (type === 'WRAP_NODE') {
    const nodeKind = prompt('Wrapper type: sequence, selector, priority-selector, parallel, invert, repeat, retry, timeout, cooldown, or checkpoint', 'sequence')?.trim();
    if (!nodeKind) return;
    const key = prompt('Stable wrapper ID', `wrapper-${crypto.randomUUID().slice(0, 8)}`)?.trim();
    if (!key) return;
    graphMutationClient.mutate([{ type: 'WRAP', yamlPath: node.yamlPath, nodeKind, key }], 'Wrap graph node'); return;
  }
  if (type === 'UNWRAP_NODE') {
    graphMutationClient.mutate([{ type: 'UNWRAP', yamlPath: node.yamlPath }], 'Unwrap graph node'); return;
  }
  if (type === 'SET_DIALOGUE_START') {
    graphMutationClient.mutate([{ type: 'EDIT_FIELD', yamlPath: '/start', value: node.title }], 'Set dialogue start');
    return;
  }
  if (type === 'OPEN_REFERENCED_RESOURCE') {
    const resource = deriveResources(state.files).find(item => item.kind === node.subtitle && item.id === node.title);
    if (resource) workspaceShell.openResource(resource);
    else {
      yamlStatus.textContent = `Missing ${node.subtitle} ${node.title}. The creation flow has been prefilled.`;
      if (['npc', 'dialogue', 'quest', 'behavior', 'script'].includes(node.subtitle)) openCreation(node.subtitle, node.title);
    }
    return;
  }
  if (type === 'PASTE_ANCHOR_COORDINATES') { importAnchor(node.title); return; }
  if (type === 'OPEN_TRANSFER_DIALOGUE') {
    const dialogueId = node.fields?.find(field => field.label === 'dialogue')?.value;
    const resource = deriveResources(state.files).find(item => item.kind === 'dialogue' && item.id === dialogueId);
    if (resource) workspaceShell.openResource(resource);
    else if (dialogueId) openCreation('dialogue', dialogueId);
    return;
  }
  if (type === 'SHOW_SCRIPT_CALLERS') {
    document.querySelector('#rename-type').value = 'script';
    document.querySelector('#rename-current').value = node.title;
    referencesOpen.click();
    status.textContent = `Showing typed callers for reusable script ${node.title}.`;
    return;
  }
  if (type === 'EXTRACT_TO_SCRIPT') {
    const scriptId = prompt('New reusable script ID', `script-${crypto.randomUUID().slice(0, 8)}`)?.trim();
    if (!scriptId) return;
    const resource = workspaceShell.activeResource();
    executeProjectOperation('extract-script', { sourcePath: resource.path,
      sourceYamlPath: node.yamlPath, scriptId }, 'script', scriptId)
      .catch(error => { status.textContent = `Reusable-script extraction failed: ${error.message}`; });
    return;
  }
  if (type === 'CREATE_ASSIGN_PLAYER_BEHAVIOR' || type === 'CREATE_ASSIGN_SHARED_BEHAVIOR' || type === 'CREATE_ASSIGN_DIALOGUE') {
    const assignment = type === 'CREATE_ASSIGN_DIALOGUE' ? 'npc-dialogue' : 'npc-player-behavior';
    const resolvedAssignment = type === 'CREATE_ASSIGN_SHARED_BEHAVIOR' ? 'npc-shared-behavior' : assignment;
    const targetKind = type === 'CREATE_ASSIGN_DIALOGUE' ? 'dialogue' : 'behavior';
    const targetId = prompt(`New ${targetKind} ID to create and assign`, 'namespace:new-resource')?.trim();
    if (!targetId) return;
    const resource = workspaceShell.activeResource();
    executeProjectOperation('create-and-assign', { sourcePath: resource.path, assignment: resolvedAssignment, targetId }, targetKind, targetId)
      .catch(error => { status.textContent = `Create and assign failed: ${error.message}`; });
    return;
  }
  if (type === 'TOGGLE_BOOKMARK') { graphCanvas.toggleBookmark(node.id); return; }
  if (type === 'TOGGLE_COLLAPSE') { graphCanvas.toggleCollapse(node.id); return; }
  if (type === 'CREATE_MISSING_RESOURCE') {
    const diagnostic = (graphCanvas.projection?.diagnostics || []).find(issue =>
      (issue.nodeId === node.id || issue.relatedResourceId === node.title) && issue.relatedResourceKind);
    const kind = diagnostic?.relatedResourceKind || node.subtitle?.replace(/^Missing\s+/i, '').toLowerCase();
    const id = diagnostic?.relatedResourceId || node.title;
    const yamlPath = diagnostic?.yamlPath || node.yamlPath;
    if (['npc', 'dialogue', 'quest', 'behavior', 'script'].includes(kind) && id && yamlPath) {
      const resource = workspaceShell.activeResource();
      executeProjectOperation('create-and-assign', { sourcePath: diagnostic?.filePath || resource.path, assignment: 'typed-reference',
        sourceYamlPath: yamlPath, targetKind: kind, targetId: id }, kind, id)
        .catch(error => { status.textContent = `Create missing resource failed: ${error.message}`; });
    }
    else if (['npc', 'dialogue', 'quest', 'behavior', 'script'].includes(kind) && id) openCreation(kind, id);
    else yamlStatus.textContent = 'The authoritative reference diagnostic does not identify a creatable resource kind.';
    return;
  }
  if (type === 'OPEN_QUEST_OBJECTIVES') {
    const full = state.graphProjections.get(workspaceShell.activeResource()?.identity);
    if (!full) return;
    const layout = graphCanvas.snapshot();
    state.graphTabContexts.set(full.resourceIdentity, layout);
    state.nestedGraph = { resourceIdentity: full.resourceIdentity, ownerNodeId: node.id,
      rootYamlPath: node.yamlPath, label: node.title };
    workspaceShell.setNestedBreadcrumbs([
      { label: `Phase ${node.title}`, action: () => graphCanvas.focusNode(node.id) },
      { label: 'Objectives & lifecycle', action: () => graphCanvas.zoomToFit() }
    ]);
    showGraphProjection(full, layout);
    status.textContent = `Opened nested objectives and lifecycle graph for phase ${node.title}.`;
  }
}

function openRelationshipNode(node) {
  if (!node?.kind?.startsWith('relationship-')) {
    status.textContent = 'The unresolved relationship target has no resource to open.'; return;
  }
  const kind = node.kind.substring('relationship-'.length);
  const resource = deriveResources(state.files).find(item => item.kind === kind && item.id === node.title);
  if (resource) workspaceShell.openResource(resource);
  else status.textContent = `Relationship ${kind} ${node.title} is no longer present in the project.`;
}

async function applyVisualEdit(path, value) {
  if (!state.selected || !state.connected) return;
  await graphMutationClient.mutate([{ type: 'EDIT_FIELD', yamlPath: path, value }], 'Edit graph field');
  selectVisualNode(path);
}

async function applyStructure(operation, path, targetPath) {
  if (!state.selected || !state.connected) return;
  if (operation === 'DELETE') return graphMutationClient.mutate([{ type: 'DELETE', yamlPath: path }], 'Delete graph node');
  if (operation === 'DUPLICATE_AFTER') return graphMutationClient.mutate([{ type: 'DUPLICATE', yamlPath: path }], 'Duplicate graph node');
  if (operation === 'MOVE_BEFORE' || operation === 'MOVE_AFTER') {
    const model = state.documentModels.get(state.selected), target = findModelNode(model?.root, targetPath), parent = parentNode(model, targetPath);
    if (!target || parent?.kind !== 'sequence') { yamlStatus.textContent = 'Reorder rejected: destination is not an ordered YAML sequence.'; return; }
    const withoutSource = parent.children.filter(child => child.path !== path);
    const targetIndex = withoutSource.findIndex(child => child.path === targetPath);
    if (targetIndex < 0) { yamlStatus.textContent = 'Reorder rejected: destination changed before the gesture completed.'; return; }
    const index = targetIndex + (operation === 'MOVE_AFTER' ? 1 : 0);
    return graphMutationClient.mutate([{ type: 'REORDER', yamlPath: path, parentYamlPath: parent.path, index }], 'Reorder graph nodes');
  }
  yamlStatus.textContent = `Unsupported structural graph command: ${operation}`;
}
async function extractSubtree(path){if(!state.connected)return;const behaviorId=prompt('New namespaced subtree behavior ID','namespace:subtree');if(!behaviorId)return;const scope=state.documentModels.get(state.selected)?.root?.children?.find(child=>child.key==='scope')?.value||'player';let filename;try{filename=await requestSafePath('behavior',behaviorId);}catch(error){yamlStatus.textContent=`Cannot extract: ${error.message}`;return;}if(state.files.has(filename)){yamlStatus.textContent=`Cannot extract: ${filename} already exists.`;return;}recordHistory();try{const response=await fetch(sessionApi('/documents/extract-subtree'),{method:'POST',headers:authorizedHeaders({'Content-Type':'application/json'}),body:JSON.stringify({content:source.value,path,behaviorId,scope})});if(!response.ok)throw new Error(await response.text()||`HTTP ${response.status}`);const result=await response.json();source.value=result.source.content;state.files.set(state.selected,result.source.content);state.documentModels.set(state.selected,result.source);state.files.set(filename,result.extractedContent);renderDocument(result.source);refreshDirty();scheduleAutosave();scheduleRecovery();refreshProjectReferences();const resource=deriveResources(state.files).find(item=>item.kind==='behavior'&&item.id===behaviorId);if(resource)workspaceShell.openResource(resource);}catch(error){state.histories.get(state.selected)?.undo.pop();yamlStatus.textContent=`Subtree extraction rejected: ${error.message}`;}}

function refreshDirty() {
  flushSelected();
  refreshWorkspaceResources();
  exportChanged.disabled = !dirtyFiles().length;
  renderDiff();
}

function dirtyFiles() {
  flushSelected();
  return [...state.files].filter(([path, content]) => content !== state.original.get(path));
}

function historyEntry(path, layout) {
  const active = workspaceShell.activeResource();
  const currentLayout = layout || (path === state.selected && active
    && graphCanvas.projection?.resourceIdentity === active.identity ? graphCanvas.snapshot() : null);
  return { content: state.files.get(path) ?? '', layout: currentLayout };
}

function recordHistory(path = state.selected, layout = null) {
  if (!path) return;
  const history = state.histories.get(path) ?? { undo: [], redo: [] };
  history.undo.push(historyEntry(path, layout));
  if (history.undo.length > 200) history.undo.shift();
  history.redo.length = 0; state.histories.set(path, history); updateHistoryButtons();
}

function restoreHistory(direction) {
  if (!state.selected) return;
  const history = state.histories.get(state.selected), from = history?.[direction];
  if (!from?.length) return;
  const other = direction === 'undo' ? history.redo : history.undo;
  other.push(historyEntry(state.selected));
  const stored = from.pop(), target = typeof stored === 'string' ? { content: stored, layout: null } : stored;
  const contentChanged = source.value !== target.content;
  source.value = target.content; state.files.set(state.selected, source.value);
  if (target.layout) graphCanvas.restoreLayout(target.layout);
  if (contentChanged) { refreshDirty(); scheduleParse(); scheduleAutosave(); scheduleRecovery(); }
  updateHistoryButtons();
}

function updateHistoryButtons() {
  const history = state.histories.get(state.selected);
  undoButton.disabled = !history?.undo.length; redoButton.disabled = !history?.redo.length;
}

function persistRecovery() {
  flushSelected();
  const key = `persona:recovery:${sessionId}`;
  const changed = {};
  for (const path of new Set([...state.original.keys(), ...state.files.keys()])) {
    const before = state.original.get(path), after = state.files.get(path);
    if (before !== after) changed[path] = state.files.has(path) ? after : null;
  }
  if (Object.keys(changed).length) sessionStorage.setItem(key,
    JSON.stringify({ version: 2, revision: state.baseRevision, changes: changed }));
  else sessionStorage.removeItem(key);
}

function updateLifecycleButtons() {
  const active = workspaceShell.activeResource();
  const canEdit = draftEditAllowed();
  const editable = canEdit && Boolean(active) && active.kind !== 'other';
  createOpen.disabled = !canEdit;
  createOpen.title = canEdit ? 'Create content (Ctrl/Cmd+N)'
    : 'Requires Draft Edit trust in Minecraft';
  duplicateResourceButton.disabled = !editable;
  renameResourceButton.disabled = !editable;
  moveResourceButton.disabled = !editable || active.kind === 'script';
  deleteResourceButton.disabled = !editable;
}

function scheduleRecovery() {
  if (!state.connected) return;
  clearTimeout(state.recoveryTimer);
  state.recoveryTimer = setTimeout(persistRecovery, 300);
}

function renderDiff() {
  if (diff.hidden || !state.selected) return;
  const before = (state.original.get(state.selected) ?? '').split('\n');
  const after = source.value.split('\n');
  let prefix = 0;
  while (prefix < before.length && prefix < after.length && before[prefix] === after[prefix]) prefix++;
  let suffix = 0;
  while (suffix < before.length - prefix && suffix < after.length - prefix
      && before[before.length - 1 - suffix] === after[after.length - 1 - suffix]) suffix++;
  const contextStart = Math.max(0, prefix - 3);
  const beforeEnd = Math.min(before.length, before.length - suffix + 3);
  const afterEnd = Math.min(after.length, after.length - suffix + 3);
  const lines = [`--- a/${state.selected}`, `+++ b/${state.selected}`,
    `@@ -${contextStart + 1},${beforeEnd - contextStart} +${contextStart + 1},${afterEnd - contextStart} @@`];
  for (let index = contextStart; index < prefix; index++) lines.push(` ${before[index]}`);
  for (let index = prefix; index < before.length - suffix; index++) lines.push(`-${before[index]}`);
  for (let index = prefix; index < after.length - suffix; index++) lines.push(`+${after[index]}`);
  for (let index = Math.max(prefix, before.length - suffix); index < beforeEnd; index++) lines.push(` ${before[index]}`);
  diff.textContent = before.join('\n') === after.join('\n') ? `No changes in ${state.selected}` : lines.join('\n');
}

async function loadSnapshot(verified) {
  const generation = state.connectionGeneration;
  const response = await fetch(sessionApi('/snapshot'), { headers: authorizedHeaders() });
  if (!response.ok) throw new Error(await response.text() || `Snapshot HTTP ${response.status}`);
  const snapshot = await response.json();
  const files = [...snapshot.files].sort((left, right) => left.path.localeCompare(right.path));
  const revision = [];
  for (const file of files) {
    const digest = hex(await crypto.subtle.digest('SHA-256', encoder.encode(file.content)));
    if (digest !== file.sha256) throw new Error(`Invalid file digest for ${file.path}`);
    revision.push(...encoder.encode(file.path), 0, ...encoder.encode(file.sha256), 0);
  }
  if (hex(await crypto.subtle.digest('SHA-256', new Uint8Array(revision))) !== snapshot.revision) throw new Error('Invalid project revision');
  let input = `${snapshot.protocolVersion}\n${snapshot.sessionId}\n${snapshot.revision}\n${snapshot.contentFormatVersion}\n${snapshot.createdAt}\n${snapshot.installationPublicKey}`;
  for (const file of files) input += `\n${file.path}\n${file.sha256}`;
  const key = await crypto.subtle.importKey('spki', fromB64(snapshot.installationPublicKey), { name: 'Ed25519' }, false, ['verify']);
  if (!await crypto.subtle.verify('Ed25519', key, fromB64(snapshot.signature), encoder.encode(input))) throw new Error('Invalid server signature');
  state.installationKey = key;
  state.installationIdentity = snapshot.installationPublicKey.slice(0, 32);
  await loadEditorMetadata(verified, snapshot.installationPublicKey, key);
  if (generation !== state.connectionGeneration || state.socket?.readyState !== WebSocket.OPEN)
    throw new Error('The server connection changed while refreshing project state');
  state.currentRevision = snapshot.revision;
  state.connected = true;
  renderProject(files, `Connected securely. Loaded ${files.length} signed content file${files.length === 1 ? '' : 's'}.`, snapshot.revision);
}

async function loadEditorMetadata(verified, installationPublicKey, installationKey) {
  const response = await fetch(sessionApi('/metadata'), { headers: authorizedHeaders() });
  if (!response.ok) { if (response.status === 404) return; throw new Error(await response.text() || `Metadata HTTP ${response.status}`); }
  const snapshot = await response.json();
  if (snapshot.protocolVersion !== protocolVersion || snapshot.sessionId !== sessionId || snapshot.installationPublicKey !== installationPublicKey) throw new Error('Invalid editor metadata identity');
  const manifest = [];
  for (const document of snapshot.schemas) {
    if (hex(await crypto.subtle.digest('SHA-256', encoder.encode(document.schemaJson))) !== document.schemaSha256) throw new Error(`Invalid schema digest for ${document.typeId}`);
    document.schema = JSON.parse(document.schemaJson);manifest.push(`schema\0${document.contentType}\0${document.typeId}\0${document.extensionId}\0${document.extensionVersion}\0${document.schemaSha256}`);
  }
  for (const document of snapshot.catalogs) {
    if (hex(await crypto.subtle.digest('SHA-256', encoder.encode(document.valueSchemaJson))) !== document.valueSchemaSha256) throw new Error(`Invalid catalog schema digest for ${document.catalogId}`);
    document.valueSchema = JSON.parse(document.valueSchemaJson);manifest.push(`catalog\0${document.catalogId}\0${document.extensionId}\0${document.extensionVersion}\0${document.revision}\0${document.valueSchemaSha256}\0${document.permission || ''}\0${document.cachePolicy}\0${document.dependencyFields.join(',')}\0${document.missingValuePolicy}`);
  }
  manifest.sort();const revisionBytes=[];for(const line of manifest)revisionBytes.push(...encoder.encode(line),10);
  if (hex(await crypto.subtle.digest('SHA-256',new Uint8Array(revisionBytes))) !== snapshot.revision) throw new Error('Invalid editor metadata revision');
  let input=`${snapshot.protocolVersion}\n${snapshot.sessionId}\n${snapshot.createdAt}\n${snapshot.installationPublicKey}\n${snapshot.revision}`;
  for(const line of manifest)input+=`\n${line}`;
  if(!await crypto.subtle.verify('Ed25519',installationKey,fromB64(snapshot.signature),encoder.encode(input)))throw new Error('Invalid editor metadata signature');
  state.editorSchemas=new Map(snapshot.schemas.map(document=>[`${document.contentType}:${document.typeId}`,document]));
  state.editorCatalogs=new Map(snapshot.catalogs.map(document=>[document.catalogId,document]));state.metadataRevision=snapshot.revision;
}

async function contentFiles(entries = [...state.files]) {
  flushSelected();
  const files = [];
  for (const [path, content] of [...entries].sort(([left], [right]) => left.localeCompare(right))) {
    files.push({ path, content, sha256: hex(await crypto.subtle.digest('SHA-256', encoder.encode(content))) });
  }
  return files;
}

async function projectRevision(files) {
  const bytes = [];
  for (const file of [...files].sort((left, right) => left.path.localeCompare(right.path)))
    bytes.push(...encoder.encode(file.path), 0, ...encoder.encode(file.sha256), 0);
  return hex(await crypto.subtle.digest('SHA-256', new Uint8Array(bytes)));
}

function applyProjectOperation(result, kind, id) {
  requireConnection();
  flushSelected();
  const previouslySelected = state.selected;
  const affected = new Set(result.affectedPaths || []);
  state.files = new Map(result.files.map(file => [file.path, file.content]));
  if (previouslySelected && state.files.has(previouslySelected)) source.value = state.files.get(previouslySelected);
  else state.selected = null;
  for (const path of affected) { state.documentModels.delete(path); state.histories.delete(path); }
  refreshWorkspaceResources();
  const resource = deriveResources(state.files).find(item => item.kind === kind && item.id === id);
  if (resource) workspaceShell.openResource(resource);
  else {
    state.selected = null; source.value = ''; source.disabled = true; graphCanvas.clear();
    const fallback = workspaceShell.activeResource() || deriveResources(state.files)[0];
    if (fallback) workspaceShell.openResource(fallback, false);
  }
  refreshDirty(); scheduleAutosave(); scheduleRecovery(); invalidateValidation();
  refreshProjectReferences();
  const warnings = (result.warnings || []).join(' ');
  status.textContent = `Project updated atomically (${affected.size} affected file${affected.size === 1 ? '' : 's'}).${warnings ? ` ${warnings}` : ''}`;
}

async function refreshProjectReferences() {
  if (!state.connected) return;
  const generation = state.connectionGeneration;
  try {
    const response = await fetch(sessionApi('/projects/references'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ files: await contentFiles() })
    });
    if (!response.ok) return;
    const graph = await response.json();
    if (!state.connected || generation !== state.connectionGeneration) return;
    state.referenceGraph = graph; refreshWorkspaceResources();
  } catch { /* The explicit References panel reports endpoint errors. */ }
}

async function draftChanges() {
  flushSelected(); const changes = [];
  const paths = new Set([...state.original.keys(), ...state.files.keys()]);
  for (const path of [...paths].sort()) {
    const before = state.original.get(path), after = state.files.get(path);
    if (before === after) continue;
    const baseSha256 = before == null ? null : hex(await crypto.subtle.digest('SHA-256', encoder.encode(before)));
    changes.push(after == null ? { path, baseSha256, content: null, sha256: null }
      : { path, baseSha256, content: after, sha256: hex(await crypto.subtle.digest('SHA-256', encoder.encode(after))) });
  }
  return changes;
}

function scheduleAutosave() {
  if (!state.connected || !state.verified?.capabilities?.includes('DRAFT_EDIT') || !state.baseRevision) return;
  invalidateValidation();
  clearTimeout(state.autosaveTimer);
  state.autosaveTimer = setTimeout(saveDraft, 750);
}

function updatePublishButton() {
  publishButton.disabled = !publicationReady(state, Boolean(document.querySelector('.invalid-catalog')));
}

function invalidateValidation() {
  state.validationResult = null; state.validationRequest = null; updatePublishButton();
  if (!validationPanel.hidden) validationSummary.textContent = 'Candidate changed; waiting for fresh Persona validation…';
}

async function refreshCapabilities() {
  clearTimeout(state.capabilityTimer);
  if (!state.connected || !state.verified || Date.now() >= Date.parse(state.verified.expiresAt)) return;
  try {
    const response = await fetch(sessionApi('/status'), {
      headers: authorizedHeaders()
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const session = await response.json();
    const before = state.verified.capabilities;
    state.verified.capabilities = session.grantedCapabilities;
    liveOpen.disabled=!session.grantedCapabilities.includes('PLAYER_VIEW');
    const mutable=session.grantedCapabilities.includes('LIVE_MUTATE');liveControls.hidden=!mutable;liveMode.textContent=mutable?'— elevated controls trusted':'— read only';
    updatePublishButton();
    updateLifecycleButtons();
    if (!before.includes('DRAFT_EDIT') && session.grantedCapabilities.includes('DRAFT_EDIT')) {
      status.textContent = 'In-game trust confirmed. Draft autosave is now enabled.';
      if (dirty()) scheduleAutosave();
    }
  } catch (error) {
    status.textContent = `Could not refresh session trust: ${error.message}`;
  } finally {
    state.capabilityTimer = setTimeout(refreshCapabilities, 3000);
  }
}

function liveKey(kind,value){switch(kind){case'players':return`player:${value.playerId}`;case'npcs':return`npc:${value.definitionId}:${value.instanceId}:${value.playerId||'shared'}`;case'behaviors':return`behavior:${value.definitionId}:${value.instanceId}:${value.playerId||'shared'}:${value.behaviorId}`;case'quests':return`quest:${value.playerId}:${value.questId}`;case'dialogues':return`dialogue:${value.playerId}`;case'memories':return`memory:${value.playerId||'global'}:${value.npcDefinition}:${value.npcInstance}:${value.key}`;}}
function applyLiveSnapshot(snapshot){if(!state.liveSubscription||snapshot.subscriptionId!==state.liveSubscription||snapshot.revision<=state.liveRevision)return;if(snapshot.full)for(const values of Object.values(state.liveData))if(values instanceof Map)values.clear();
  for(const removed of snapshot.removedKeys||[])for(const values of Object.values(state.liveData))if(values instanceof Map)values.delete(removed);
  for(const kind of ['players','npcs','behaviors','quests','dialogues','memories'])for(const value of snapshot[kind]||[])state.liveData[kind].set(liveKey(kind,value),value);
  if(snapshot.server)state.liveData.server=snapshot.server;state.liveRevision=snapshot.revision;renderLive();clearTimeout(state.liveStaleTimer);state.liveStaleTimer=setTimeout(()=>{liveStatus.textContent='Live data is stale; waiting for the connected server…';liveDialog.classList.add('stale');},5000);}
function renderLive(){liveDialog.classList.remove('stale');liveStatus.textContent=`Live revision ${state.liveRevision} · read only · updated ${new Date().toLocaleTimeString()}`;
  renderLiveList('live-players',[...state.liveData.players.values()],value=>`${value.playerId} · ${value.world} · quests ${value.activeQuests.join(', ')||'none'} · runtimes ${value.activeNpcRuntimes}`);
  renderLiveList('live-npcs',[...state.liveData.npcs.values()],value=>{const navigation=value.navigation||{};const elapsed=navigation.startedAt?`${Math.max(0,Math.floor((Date.now()-navigation.startedAt)/1000))}s`:'';return`${value.definitionId}/${value.instanceId} · ${value.playerId||'shared'} · ${value.presentation} · ${value.projectionState} · anchor ${value.anchor||'shared'} · navigation ${navigation.status||'IDLE'} ${navigation.target||''} ${elapsed} ${navigation.reason||''}`.trim();});
  renderLiveList('live-behaviors',[...state.liveData.behaviors.values()],value=>`${value.definitionId}/${value.instanceId} · ${value.playerId||'shared'} · ${value.behaviorId} ${value.status} · path ${value.runningPath.join(' → ')||'idle'} · checkpoint ${value.checkpoint||'none'} · wake ${value.nextWakeAt||'none'} · inbox ${value.inbox.length}/${value.droppedEvents} dropped`);
  renderLiveList('live-quests',[...state.liveData.quests.values()],value=>`${value.playerId} · ${value.questId}/${value.phaseId} · ${value.objectives.map(item=>`${item.objectiveId} ${item.current}/${item.required}`).join(', ')} · deadline ${value.timerDeadline||'none'} · completions ${value.completionCount} · events ${(value.recentEvents||[]).join(' | ')||'none'}`);
  renderLiveList('live-dialogues',[...state.liveData.dialogues.values()],value=>`${value.playerId} · ${value.dialogueId}/${value.nodeId} · ${value.state} · NPC ${value.npcDefinition}/${value.npcInstance} · line ${value.currentLine||'none'} · choices ${(value.eligibleChoices||[]).join(' | ')||'none'} · wait ${value.waitDeadline||'none'}${value.cancellationReason?` · cancelled: ${value.cancellationReason}`:''}`);
  renderLiveList('live-memories',[...state.liveData.memories.values()],value=>`${value.playerId||'global'} · ${value.npcDefinition}/${value.npcInstance} · ${value.key} = ${value.value} (${value.type}, ${value.scope}${value.redacted?', redacted':''})`);
  document.querySelector('#live-server').textContent=state.liveData.server?JSON.stringify(state.liveData.server,null,2):'Not subscribed to server metrics.';
  document.querySelector('#live-dock-status').textContent=liveStatus.textContent;
  const liveRows=[...state.liveData.behaviors.values()].map(value=>`Behavior ${value.behaviorId} · ${value.status} · ${(value.runningPath||[]).join(' → ')}`)
    .concat([...state.liveData.quests.values()].map(value=>`Quest ${value.questId} · phase ${value.phaseId}`),
      [...state.liveData.dialogues.values()].map(value=>`Dialogue ${value.dialogueId} · ${value.nodeId} · ${value.state}`),
      [...state.liveData.npcs.values()].map(value=>`NPC ${value.definitionId}/${value.instanceId} · ${value.presentation}/${value.projectionState}`));
  document.querySelector('#live-dock-list').replaceChildren(...liveRows.slice(0,500).map(value=>{const item=document.createElement('li');item.textContent=value;return item;}));
  renderMutationTargets();overlayLiveBehaviorNodes();refreshWorkspaceResources();}
function renderLiveList(id,values,label){const target=document.querySelector(`#${id}`);target.replaceChildren(...values.map(value=>{const item=document.createElement('li');item.textContent=label(value);return item;}));if(!values.length){const item=document.createElement('li');item.textContent='No matching live state.';target.append(item);}}
function renderMutationTargets(){const selectedBehavior=behaviorMutationTarget.value,selectedMemory=memoryMutationTarget.value;behaviorMutationTarget.replaceChildren(...[...state.liveData.behaviors.values()].map(value=>option(JSON.stringify({npcDefinition:value.definitionId,npcInstance:value.instanceId,playerId:value.playerId}),`${value.definitionId}/${value.instanceId} · ${value.playerId||'shared'} · ${value.behaviorId}`)));memoryMutationTarget.replaceChildren(...[...state.liveData.memories.values()].filter(value=>!value.redacted).map(value=>option(JSON.stringify(value),`${value.playerId||'global'} · ${value.npcDefinition}/${value.npcInstance} · ${value.key}`)));behaviorMutationTarget.value=selectedBehavior;memoryMutationTarget.value=selectedMemory;}
function option(value,label){const item=document.createElement('option');item.value=value;item.textContent=label;return item;}

document.querySelector('#behavior-mutation-form').addEventListener('submit',event=>{event.preventDefault();if(!behaviorMutationTarget.value)return;const target=JSON.parse(behaviorMutationTarget.value),operation=document.querySelector('#behavior-mutation-operation').value,signal=document.querySelector('#behavior-mutation-signal').value||null;const request={protocolVersion,requestId:crypto.randomUUID(),operation,...target,signal,data:{}};reviewMutation('behavior',request,{player:target.playerId||'shared',npc:`${target.npcDefinition}/${target.npcInstance}`,scope:target.playerId?'player':'shared',oldValue:'runtime state',newValue:operation+(signal?` ${signal}`:''),source:'verified editor session',expiry:'not applicable'});});
document.querySelector('#memory-mutation-form').addEventListener('submit',event=>{event.preventDefault();if(!memoryMutationTarget.value)return;const current=JSON.parse(memoryMutationTarget.value),operation=document.querySelector('#memory-mutation-operation').value,raw=document.querySelector('#memory-mutation-value').value,expiryRaw=document.querySelector('#memory-mutation-expiry').value,expiresAt=expiryRaw?new Date(expiryRaw).getTime():null;let value=null,amount=null;if(operation==='SET')value=raw;if(operation==='INCREMENT')amount=Number(raw);const request={protocolVersion,requestId:crypto.randomUUID(),operation,playerId:current.playerId,npcDefinition:current.npcDefinition,npcInstance:current.npcInstance,key:current.key,valueType:document.querySelector('#memory-mutation-type').value,value,amount,expiresAt,expectedUpdatedAt:current.updatedAt};let next=operation==='DELETE'?'unset':operation==='INCREMENT'?`${Number(current.value)+amount}`:operation==='EXPIRE'?current.value:value;reviewMutation('memory',request,{player:current.playerId||'global',npc:`${current.npcDefinition}/${current.npcInstance}`,scope:current.scope,oldValue:`${current.value} (${current.type})`,newValue:next,source:'verified editor session',expiry:expiresAt?new Date(expiresAt).toISOString():'unchanged / none'});});
function reviewMutation(type,request,details){state.pendingMutation={type,request};mutationDetails.textContent=Object.entries(details).map(([key,value])=>`${key}: ${value}`).join('\n');mutationConfirm.showModal();}
mutationConfirm.addEventListener('close',async()=>{if(mutationConfirm.returnValue!=='apply'||!state.pendingMutation){state.pendingMutation=null;return;}const pending=state.pendingMutation;state.pendingMutation=null;state.mutationRequests.set(pending.request.requestId,pending);mutationResult.textContent='Mutation sent; waiting for Persona validation…';const sent=await sendSocket(pending.type==='memory'?'MEMORY_MUTATION_REQUEST':'BEHAVIOR_MUTATION_REQUEST',pending.request);if(!sent){state.mutationRequests.delete(pending.request.requestId);mutationResult.textContent='Mutation was not sent because the live socket is disconnected.';}});
function receiveMutationResult(result){if(!state.mutationRequests.has(result.requestId))return;state.mutationRequests.delete(result.requestId);mutationResult.textContent=`${result.success?'Succeeded':'Rejected'}: ${result.message}`;}
async function subscribeLive(){if(state.liveSubscription)return;state.liveSubscription=crypto.randomUUID();state.liveRevision=0;const topics=['PLAYERS','NPCS','BEHAVIORS','QUESTS','DIALOGUES','SERVER'];if(state.verified.capabilities.includes('MEMORY_VIEW'))topics.push('MEMORIES');
  const sent=await sendSocket('LIVE_SUBSCRIBE',{protocolVersion,subscriptionId:state.liveSubscription,topics,filter:{playerIds:[],npcDefinitions:[],npcInstances:[],worlds:[]},refreshMillis:500});if(!sent){state.liveSubscription=null;throw new Error('Live socket is not connected');}liveStatus.textContent='Waiting for Persona to authorize the read-only subscription…';}
liveOpen.addEventListener('click',()=>{liveDialog.showModal();subscribeLive().catch(error=>{liveStatus.textContent=`Live subscription failed: ${error.message}`;});});
document.querySelector('#live-close').addEventListener('click',()=>liveDialog.close());
document.querySelector('#live-dock-subscribe').addEventListener('click',()=>subscribeLive().catch(error=>{document.querySelector('#live-dock-status').textContent=`Live subscription failed: ${error.message}`;}));
document.querySelector('#live-dock-expand').addEventListener('click',()=>liveOpen.click());

async function saveDraft() {
  if (!state.connected) return;
  const generation = state.connectionGeneration;
  if (state.saving) { state.saveAgain = true; return; }
  state.saving = true;
  try {
    if (!state.draftId) {
      const key = `persona:${sessionId}:draftId`;
      state.draftId = sessionStorage.getItem(key) || crypto.randomUUID();
      sessionStorage.setItem(key, state.draftId);
    }
    const response = await fetch(`/api/v1/editor/sessions/${sessionId}/drafts/${state.draftId}`, {
      method: 'PATCH',
      headers: { Authorization: `Bearer ${state.verified.browserLeaseToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ protocolVersion, baseRevision: state.baseRevision, changes: await draftChanges() })
    });
    if (!response.ok) throw new Error(await response.text() || `Draft HTTP ${response.status}`);
    const saved = await response.json();
    if (!state.connected || generation !== state.connectionGeneration) return;
    status.textContent = saved.stale
      ? 'Draft autosaved, but server content has changed; review before any future publish.'
      : `Draft autosaved at ${new Date(saved.updatedAt).toLocaleTimeString()}.`;
    requestValidation(saved.draftId);
  } catch (error) {
    if (generation !== state.connectionGeneration) return;
    status.textContent = `Draft autosave failed: ${error.message}`;
  } finally {
    state.saving = false;
    if (state.saveAgain) { state.saveAgain = false; scheduleAutosave(); }
  }
}

async function requestValidation(draftId) {
  const requestId = crypto.randomUUID();
  if (await sendSocket('VALIDATION_REQUEST', { protocolVersion, requestId, draftId })) {
    state.validationRequest = requestId;
    state.validationResult = null; updatePublishButton();
    validationPanel.hidden = false; validationSummary.textContent = 'Persona is validating this candidate…';
    validationList.replaceChildren();
  }
}

function diagnosticOffset(content, line, column) {
  const lines = content.split('\n'); let offset = 0;
  for (let index = 0; index < Math.max(0, line - 1) && index < lines.length; index++) offset += lines[index].length + 1;
  return Math.min(content.length, offset + Math.max(0, column - 1));
}

function renderValidation(result) {
  if (result.requestId !== state.validationRequest) return;
  state.validationResult = result; updatePublishButton();
  graphCanvas.setDiagnosticPaths(result.diagnostics.filter(issue => issue.path === state.selected)
    .map(issue => issue.nodeId).filter(Boolean));
  validationPanel.hidden = false;
  validationSummary.textContent = validationHeading(result);
  validationList.replaceChildren(...result.diagnostics.map(issue => {
    const item = document.createElement('li'), button = document.createElement('button');
    button.type = 'button';
    button.textContent = diagnosticLabel(issue);
    button.addEventListener('click', () => {
      if (!state.files.has(issue.path)) return;
      selectFile(issue.path); const offset = diagnosticOffset(source.value, issue.line, issue.column);
      source.focus(); source.setSelectionRange(offset, offset);
    });
    item.append(button); return item;
  }));
}

async function sendSocket(type, payload) {
  if (!state.connected || !state.socket || state.socket.readyState !== WebSocket.OPEN) return false;
  const sequence = ++state.socketSequence;
  const digest = b64(await crypto.subtle.digest('SHA-256', encoder.encode(JSON.stringify(payload))));
  const input = `${protocolVersion}\n${sessionId}\n${sequence}\n${type}\n${digest}`;
  const signature = b64(await crypto.subtle.sign('Ed25519', state.privateKey, encoder.encode(input)));
  state.socket.send(JSON.stringify({ protocolVersion, sessionId, sequence, type, payload, signature }));
  return true;
}

function scheduleHeartbeat() {
  clearTimeout(state.heartbeat);
  state.heartbeat = setTimeout(async () => {
    await sendSocket('HEARTBEAT', { at: Date.now() });
    scheduleHeartbeat();
  }, 20000);
}

function connectSocket() {
  clearTimeout(state.reconnectTimer);
  if (!state.verified || state.socket?.readyState === WebSocket.OPEN
      || state.socket?.readyState === WebSocket.CONNECTING) return;
  const verified = state.verified;
  const separator = verified.browserSocketUrl.includes('?') ? '&' : '?';
  state.socket = new WebSocket(`${verified.browserSocketUrl}${separator}lease=${encodeURIComponent(verified.browserLeaseToken)}&after=${state.peerSequence}`);
  state.socket.onopen = async () => {
    state.reconnectAttempt = 0;
    reconnectPanel.hidden = false;
    status.textContent = 'Server connected; refreshing authoritative project state…';
    try {
      await loadSnapshot(verified);
      scheduleHeartbeat();
      refreshCapabilities();
    } catch (error) {
      lockWorkspace(`Connected, but authoritative project refresh failed: ${error.message}`);
      state.socket.close();
    }
  };
  state.socket.onmessage = async event => {
    try {
      const message = JSON.parse(event.data);
      if (message.protocolVersion !== protocolVersion || message.sessionId !== sessionId) return;
      if (message.controlType === 'RESYNC_REQUIRED') {
        state.peerSequence = message.latestSequence;
        loadSnapshot(verified).catch(error => { status.textContent = `Full resynchronization failed: ${error.message}`; });
      } else if (message.controlType !== 'REPLAY_COMPLETE'
          && ['HEARTBEAT', 'SNAPSHOT_CHANGED', 'VALIDATION_RESULT', 'CATALOG_RESULT','LIVE_SUBSCRIPTION_ACK','LIVE_SNAPSHOT','LIVE_DELTA'].includes(message.type)
          && Number.isSafeInteger(message.sequence) && message.sequence > state.peerSequence) {
        if (!state.installationKey) return;
        const digest = b64(await crypto.subtle.digest('SHA-256', encoder.encode(JSON.stringify(message.payload))));
        const input = `${message.protocolVersion}\n${message.sessionId}\n${message.sequence}\n${message.type}\n${digest}`;
        if (!await crypto.subtle.verify('Ed25519', state.installationKey,
            fromB64(message.signature), encoder.encode(input))) return;
        state.peerSequence = message.sequence;
        if (message.type === 'SNAPSHOT_CHANGED') loadSnapshot(verified).catch(error => { status.textContent = `Snapshot refresh failed: ${error.message}`; });
        if (message.type === 'VALIDATION_RESULT'
            && message.payload?.protocolVersion === protocolVersion
            && Array.isArray(message.payload.diagnostics)) renderValidation(message.payload);
        if (message.type === 'CATALOG_RESULT' && message.payload?.protocolVersion === protocolVersion
            && Array.isArray(message.payload.values)) receiveCatalogResult(message.payload);
        if((message.type==='LIVE_SNAPSHOT'||message.type==='LIVE_DELTA')&&Array.isArray(message.payload?.players))applyLiveSnapshot(message.payload);
        if(message.type==='LIVE_SUBSCRIPTION_ACK'&&!message.payload?.accepted)liveStatus.textContent=message.payload?.message||'Live subscription rejected.';
        if(message.type==='LIVE_MUTATION_RESULT')receiveMutationResult(message.payload);
      }
    } catch { status.textContent = 'Ignored a malformed live relay message.'; }
  };
  state.socket.onclose = () => {
    clearTimeout(state.heartbeat);
    state.socket = null;
    if (Date.now() >= Date.parse(verified.expiresAt)) {
      lockWorkspace('Editor session expired. Open a new editor session from Persona.');
      return;
    }
    const delay = Math.min(30000, 1000 * (2 ** Math.min(state.reconnectAttempt++, 5)));
    lockWorkspace('Server connection interrupted; editing is locked while reconnecting…');
    if(state.liveSubscription){liveStatus.textContent='Live data is stale; reconnecting…';liveDialog.classList.add('stale');}
    state.reconnectTimer = setTimeout(connectSocket, delay);
  };
  state.socket.onerror = () => lockWorkspace('Server connection interrupted; editing is locked.');
}

reconnectNow.addEventListener('click', () => {
  if (!state.verified) return;
  if (state.socket && state.socket.readyState < WebSocket.CLOSING) state.socket.close();
  state.socket = null;
  status.textContent = 'Retrying the authenticated server connection…';
  connectSocket();
});

verifyForm.addEventListener('submit', async event => {
  event.preventDefault(); const button = verifyForm.querySelector('button'); button.disabled = true;
  status.textContent = 'Creating browser identity…';
  try {
    const keys = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
    const publicKey = b64(await crypto.subtle.exportKey('spki', keys.publicKey));
    const response = await fetch(sessionApi('/verify'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ verificationCode: document.querySelector('#code').value.replaceAll('-', '').trim().toUpperCase(), browserPublicKey: publicKey, browserDescription: navigator.userAgent }) });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const verified = await response.json();
    sessionStorage.setItem(`persona:${sessionId}:privateKey`, b64(await crypto.subtle.exportKey('pkcs8', keys.privateKey)));
    sessionStorage.setItem(`persona:${sessionId}:verified`, JSON.stringify(verified));
    state.verified = verified; state.privateKey = keys.privateKey; connectPanel.hidden = true; reconnectPanel.hidden = false; connectSocket();
  } catch (error) { status.textContent = `Verification failed: ${error.message}`; button.disabled = false; }
});

async function restoreBrowserSession() {
  try {
    const verified = JSON.parse(sessionStorage.getItem(`persona:${sessionId}:verified`) || 'null');
    const encodedKey = sessionStorage.getItem(`persona:${sessionId}:privateKey`);
    if (!verified || !encodedKey || Date.now() >= Date.parse(verified.expiresAt)) return;
    state.privateKey = await crypto.subtle.importKey('pkcs8', fromB64(encodedKey), { name: 'Ed25519' }, false, ['sign']);
    state.verified = verified;
    connectPanel.hidden = true;
    reconnectPanel.hidden = false;
    status.textContent = 'Restoring the authenticated server connection…';
    connectSocket();
  } catch {
    sessionStorage.removeItem(`persona:${sessionId}:verified`);
    sessionStorage.removeItem(`persona:${sessionId}:privateKey`);
  }
}

async function refreshGraphProjection(path) {
  if (!state.connected || path !== state.selected) return;
  const resource = workspaceShell.activeResource()
    || deriveResources(state.files).find(item => item.path === path);
  if (!resource) return;
  const generation = ++state.graphGeneration, connectionGeneration = state.connectionGeneration;
  const content = state.files.get(path) ?? '';
  try {
    const expectedDigest = hex(await crypto.subtle.digest('SHA-256', encoder.encode(content)));
    const response = await fetch(sessionApi('/documents/projection'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ path, resourceKind: resource.kind, resourceId: resource.id,
        yamlPath: resource.yamlPath || '', content, expectedDigest, projectFiles: await contentFiles() })
    });
    const projection = await response.json();
    if (!response.ok) throw new Error((projection.code ? projection.code + ': ' : '') + (projection.message || 'HTTP ' + response.status));
    if (!state.connected || connectionGeneration !== state.connectionGeneration || generation !== state.graphGeneration
        || path !== state.selected || workspaceShell.activeResource()?.identity !== resource.identity) return;
    state.graphProjections.set(resource.identity, projection);
    graphLayoutStore.scope = (state.installationIdentity || sessionId) + ':' + (state.baseRevision || 'unknown');
    const context = graphCanvas.projection?.resourceIdentity === projection.resourceIdentity
      ? graphCanvas.snapshot() : state.graphTabContexts.get(projection.resourceIdentity) || await graphLayoutStore.load(projection);
    showGraphProjection(projection, context);
    graphCanvas.setStale(false);
  } catch (error) {
    if (generation === state.graphGeneration) yamlStatus.textContent = 'Graph projection unavailable: ' + error.message;
  }
}
restoreBrowserSession();

source.addEventListener('beforeinput', () => {
  if (!state.recordingInput) { recordHistory(); state.recordingInput = true; }
});
source.addEventListener('input', () => {
  graphCanvas.setStale(true, 'Revalidating YAML. Visual editing is locked until the authoritative source is valid.');
  yamlStatus.textContent = 'Revalidating YAML before visual editing resumes…';
  state.files.set(state.selected, source.value); refreshDirty(); scheduleAutosave(); scheduleRecovery(); scheduleParse();
  clearTimeout(source._historyTimer); source._historyTimer = setTimeout(() => { state.recordingInput = false; }, 400);
});
function syncCursorToVisual() {
  const model = state.documentModels.get(state.selected);
  if (!model?.root) return;
  const offset = source.selectionStart;
  const visit = node => {
    for (const child of node.children) { const found = visit(child); if (found) return found; }
    return offset >= node.startOffset && offset <= node.endOffset ? node : null;
  };
  const node = visit(model.root); if (node) { if (editorElement.dataset.view === 'yaml') setEditorView('split'); selectVisualNode(node.path); }
}
source.addEventListener('click', syncCursorToVisual);
source.addEventListener('keyup', syncCursorToVisual);
source.addEventListener('keydown', event => {
  if (event.key === 'Tab') {
    event.preventDefault(); recordHistory(); const start = source.selectionStart, end = source.selectionEnd;
    source.setRangeText('  ', start, end, 'end'); state.files.set(state.selected, source.value);
    refreshDirty(); scheduleParse(); scheduleAutosave(); scheduleRecovery(); return;
  }
  if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'z') {
    event.preventDefault(); commandDispatcher.execute(event.shiftKey ? 'history.redo' : 'history.undo');
  }
});
download.addEventListener('click', () => {
  flushSelected(); if (!state.selected) return;
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([state.files.get(state.selected)], { type: 'application/yaml;charset=utf-8' }));
  link.download = state.selected.split('/').at(-1); link.click(); URL.revokeObjectURL(link.href);
});
commandDispatcher.bindButton(undoButton, 'history.undo');
commandDispatcher.bindButton(redoButton, 'history.redo');
copyButton.addEventListener('click', async () => {
  let text = source.value.substring(source.selectionStart, source.selectionEnd);
  if (!text && state.selectedNode) {
    const node = findModelNode(state.documentModels.get(state.selected)?.root, state.selectedNode);
    if (node) text = source.value.substring(node.startOffset, node.endOffset);
  }
  if (!text) text = source.value;
  try { await navigator.clipboard.writeText(text); status.textContent = 'Copied YAML to the clipboard.'; }
  catch { status.textContent = 'Clipboard access was denied by the browser.'; }
});
pasteButton.addEventListener('click', async () => {
  try {
    const text = await navigator.clipboard.readText(); recordHistory();
    source.setRangeText(text, source.selectionStart, source.selectionEnd, 'end');
    state.files.set(state.selected, source.value); refreshDirty(); scheduleParse(); scheduleAutosave(); scheduleRecovery();
  } catch { status.textContent = 'Clipboard access was denied by the browser.'; }
});
diffToggle.addEventListener('click', () => {
  diff.hidden = !diff.hidden; source.hidden = !diff.hidden; diffToggle.textContent = diff.hidden ? 'Changes' : 'YAML';
  renderDiff();
});

async function exportProject(entries, filename) {
  if (!state.connected || !entries.length) return;
  status.textContent = 'Preparing deterministic project archive…';
  try {
    const response = await fetch(sessionApi('/export'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ files: await contentFiles(entries) })
    });
    if (!response.ok) throw new Error(await response.text() || `Export HTTP ${response.status}`);
    const link = document.createElement('a'); link.href = URL.createObjectURL(await response.blob());
    link.download = filename; link.click(); URL.revokeObjectURL(link.href);
    status.textContent = `Downloaded ${entries.length} YAML file${entries.length === 1 ? '' : 's'} as ${filename}.`;
  } catch (error) { status.textContent = `Project export failed: ${error.message}`; }
}
exportAll.addEventListener('click', () => exportProject([...state.files], 'persona-project.zip'));
exportChanged.addEventListener('click', () => exportProject(dirtyFiles(), 'persona-changed-files.zip'));

async function loadReferenceGraph() {
  if (!state.connected) return;
  referencesSummary.textContent = 'Analyzing typed project references…'; referencesList.replaceChildren();
  try {
    const response = await fetch(sessionApi('/projects/references'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ files: await contentFiles() })
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const graph = await response.json();
    const inbound = new Map();
    for (const edge of graph.references) {
      const key = `${edge.targetType}:${edge.targetId}`;
      if (!inbound.has(key)) inbound.set(key, []); inbound.get(key).push(edge);
    }
    referencesSummary.textContent = `${graph.declarations.length} declarations · ${graph.references.length} references`;
    const rows = graph.declarations.map(declaration => {
      const item = document.createElement('li'), button = document.createElement('button');
      const edges = inbound.get(`${declaration.type}:${declaration.id}`) ?? [];
      button.type = 'button'; button.textContent = `${declaration.type}:${declaration.id} — ${edges.length} inbound`;
      button.addEventListener('click', () => {
        document.querySelector('#rename-type').value = declaration.type;
        document.querySelector('#rename-current').value = declaration.id;
        document.querySelector('#rename-replacement').focus();
      }); item.append(button);
      if (edges.length) {
        const edgeList = document.createElement('ul');
        edgeList.replaceChildren(...edges.map(edge => {
          const row = document.createElement('li'), open = document.createElement('button');
          open.type = 'button'; open.textContent = `${edge.sourceType}:${edge.sourceId} · ${edge.path}:${edge.line}`;
          open.addEventListener('click', () => {
            const resource = deriveResources(state.files).find(item => item.kind === edge.sourceType && item.id === edge.sourceId);
            if (resource) { referencesDialog.close(); workspaceShell.openResource(resource); selectVisualNode(edge.yamlPath); }
          }); row.append(open); return row;
        })); item.append(edgeList);
      }
      return item;
    });
    for (const edge of graph.references.filter(item => !item.resolved)) {
      const item = document.createElement('li'); item.className = 'missing-reference';
      item.textContent = `Missing ${edge.targetType}:${edge.targetId} referenced at ${edge.path}:${edge.line}:${edge.column}`;
      rows.push(item);
    }
    referencesList.replaceChildren(...rows);
    document.querySelector('#references-dock-summary').textContent = referencesSummary.textContent;
    document.querySelector('#references-dock-list').replaceChildren(...graph.references.slice(0, 500).map(edge => {
      const item = document.createElement('li'); item.textContent = `${edge.resolved ? 'Resolved' : 'Missing'} · ${edge.sourceType}:${edge.sourceId} → ${edge.targetType}:${edge.targetId} · ${edge.path}:${edge.line}`; return item;
    }));
  } catch (error) { referencesSummary.textContent = `Reference analysis failed: ${error.message}`; }
}

referencesOpen.addEventListener('click', () => { referencesDialog.showModal(); loadReferenceGraph(); });
relationshipMapOpen.addEventListener('click', async () => {
  if (!state.connected) return;
  const generation = ++state.graphGeneration, connectionGeneration = state.connectionGeneration;
  status.textContent = 'Building the typed project Relationship Map…';
  try {
    const files = await contentFiles(), expectedRevision = await projectRevision(files);
    const response = await fetch(sessionApi('/projects/relationship-projection'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ files, expectedRevision })
    });
    const projection = await response.json();
    if (!response.ok) throw new Error((projection.code ? projection.code + ': ' : '') + (projection.message || 'HTTP ' + response.status));
    if (!state.connected || connectionGeneration !== state.connectionGeneration || generation !== state.graphGeneration) return;
    state.relationshipMode = true; fileName.textContent = 'Project Relationship Map'; source.disabled = true;
    graphLayoutStore.scope = (state.installationIdentity || sessionId) + ':' + (state.baseRevision || 'unknown');
    graphCanvas.setProjection(projection, await graphLayoutStore.load(projection));
    graphCanvas.setDiagnosticPaths(projection.diagnostics.map(issue => issue.yamlPath));
    graphCanvas.setStale(false);
    status.textContent = projection.nodes.length + ' resources and ' + projection.edges.length + ' typed relationships.';
  } catch (error) { status.textContent = 'Relationship Map failed: ' + error.message; }
});
document.querySelector('#references-close').addEventListener('click', () => referencesDialog.close());
renameForm.addEventListener('submit', async event => {
  event.preventDefault(); renameResult.textContent = 'Calculating rename impact…';
  try {
    const request = { files: await contentFiles(), type: document.querySelector('#rename-type').value,
      currentId: document.querySelector('#rename-current').value.trim(),
      replacementId: document.querySelector('#rename-replacement').value.trim() };
    const response = await fetch(sessionApi('/projects/rename-preview'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(request)
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const preview = await response.json(), heading = document.createElement('strong'), list = document.createElement('ul');
    heading.textContent = preview.safe ? `${preview.occurrences.length} exact occurrence${preview.occurrences.length === 1 ? '' : 's'} would change.`
      : `Rename is unsafe: ${preview.conflicts.join(' ')}`;
    list.replaceChildren(...preview.occurrences.map(change => {
      const item = document.createElement('li');
      item.textContent = `${change.role}: ${change.path}:${change.line}:${change.column} (${change.yamlPath})`;
      return item;
    })); renameResult.replaceChildren(heading, list);
  } catch (error) { renameResult.textContent = `Rename preview failed: ${error.message}`; }
});

async function loadSemanticDiff(showDialog = false) {
  if (showDialog) semanticDiffDialog.showModal(); semanticDiffSummary.textContent = 'Comparing typed YAML values…';
  const dockSummary = document.querySelector('#semantic-dock-summary'), dockList = document.querySelector('#semantic-dock-list');
  dockSummary.textContent = 'Comparing typed YAML values…'; dockList.replaceChildren();
  semanticDiffList.replaceChildren();
  try {
    const response = await fetch(sessionApi('/projects/semantic-diff'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ before: await contentFiles([...state.original]), after: await contentFiles() })
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const result = await response.json();
    semanticDiffSummary.textContent = `${result.changes.length} semantic change${result.changes.length === 1 ? '' : 's'}; comments and formatting are excluded.`;
    dockSummary.textContent = semanticDiffSummary.textContent;
    semanticDiffList.replaceChildren(...result.changes.map(change => {
      const item = document.createElement('li');
      const values = change.change === 'CHANGED' ? `: ${change.beforeValue} → ${change.afterValue}` : '';
      item.textContent = `${change.category} · ${change.path}${change.yamlPath || ''} · ${change.change}${values}`;
      item.addEventListener('click', () => { if (state.files.has(change.path)) { semanticDiffDialog.close(); selectFile(change.path); } });
      return item;
    }));
    dockList.replaceChildren(...result.changes.map(change => { const item = document.createElement('li');
      item.textContent = `${change.category} · ${change.path}${change.yamlPath || ''} · ${change.change}`; return item; }));
  } catch (error) { semanticDiffSummary.textContent = `Semantic diff failed: ${error.message}`; dockSummary.textContent = semanticDiffSummary.textContent; }
}
semanticDiffOpen.addEventListener('click', () => loadSemanticDiff(true));
document.querySelector('#semantic-diff-close').addEventListener('click', () => semanticDiffDialog.close());

const createDialog = document.querySelector('#create-dialog');
const createForm = document.querySelector('#create-form');
const createKind = document.querySelector('#create-kind');
const createId = document.querySelector('#create-id');
const createPath = document.querySelector('#create-path');
const createTemplate = document.querySelector('#create-template');
const createPreview = document.querySelector('#create-preview');
const createError = document.querySelector('#create-error');
const createSubmit = document.querySelector('#create-submit');
let createPreviewGeneration = 0;

function configureCreateIdentity() {
  const script = createKind.value === 'script';
  createId.pattern = script ? '[a-z0-9][a-z0-9_.-]{0,127}'
    : '[a-z0-9][a-z0-9_.-]{0,62}:[a-z0-9][a-z0-9_.-]{0,62}';
  createId.placeholder = script ? 'welcome' : 'village:guide';
  previewCreation();
}

async function previewCreation() {
  const generation = ++createPreviewGeneration;
  createSubmit.disabled = true; createPath.value = ''; createPreview.value = ''; createError.textContent = '';
  if (!state.connected || !createId.checkValidity() || !createId.value) return;
  try {
    const query = new URLSearchParams({ kind: createKind.value, id: createId.value, template: createTemplate.value });
    const response = await fetch(`${sessionApi('/projects/template')}?${query}`, { headers: authorizedHeaders() });
    const value = await response.json();
    if (generation !== createPreviewGeneration) return;
    if (!response.ok) throw new Error(value.message || `HTTP ${response.status}`);
    createPath.value = value.path; createPreview.value = value.content; createSubmit.disabled = !draftEditAllowed();
  } catch (error) { if (generation === createPreviewGeneration) createError.textContent = error.message; }
}

function openCreation(kind = 'npc', id = '') {
  try { requireDraftEdit(); }
  catch (error) { status.textContent = error.message; return; }
  createForm.reset(); createKind.value = kind; createId.value = id; configureCreateIdentity();
  createDialog.showModal(); createId.focus();
}

createOpen.addEventListener('click', () => openCreation());
document.querySelector('#create-close').addEventListener('click', () => createDialog.close());
createKind.addEventListener('change', configureCreateIdentity);
createId.addEventListener('input', () => { clearTimeout(createId._previewTimer); createId._previewTimer = setTimeout(previewCreation, 180); });
createTemplate.addEventListener('change', previewCreation);
createForm.addEventListener('submit', async event => {
  event.preventDefault();
  try { requireDraftEdit(); }
  catch (error) { createError.textContent = error.message; return; }
  if (createSubmit.disabled) return;
  createSubmit.disabled = true; createError.textContent = '';
  const generation = state.connectionGeneration;
  try {
    const files = await contentFiles(), expectedRevision = await projectRevision(files);
    const response = await fetch(sessionApi('/projects/create'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ files, expectedRevision, kind: createKind.value, id: createId.value,
        path: createPath.value, template: createTemplate.value })
    });
    if (response.status === 403) throw new Error(draftEditRequiredMessage);
    const result = await response.json();
    if (!response.ok) throw new Error(`${result.code ? `${result.code}: ` : ''}${result.message || `HTTP ${response.status}`}`);
    if (!state.connected || generation !== state.connectionGeneration) return;
    const kind = createKind.value, id = createId.value;
    createDialog.close(); applyProjectOperation(result, kind, id);
  } catch (error) { createError.textContent = error.message; createSubmit.disabled = false; }
});

window.addEventListener('keydown', event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'n') { event.preventDefault(); openCreation(); }
});

async function executeProjectOperation(endpoint, payload, openKind, openId) {
  requireDraftEdit();
  const generation = state.connectionGeneration;
  const files = await contentFiles(), expectedRevision = await projectRevision(files);
  const response = await fetch(sessionApi(`/projects/${endpoint}`), {
    method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ files, expectedRevision, ...payload })
  });
  if (response.status === 403) throw new Error(draftEditRequiredMessage);
  const result = await response.json();
  if (!response.ok) throw new Error(`${result.code ? `${result.code}: ` : ''}${result.message || `HTTP ${response.status}`}`);
  if (!state.connected || generation !== state.connectionGeneration) return;
  applyProjectOperation(result, openKind, openId);
  return result;
}

async function requestSafePath(kind, id) {
  const query = new URLSearchParams({ kind, id });
  const response = await fetch(`${sessionApi('/projects/safe-path')}?${query}`, { headers: authorizedHeaders() });
  const result = await response.json();
  if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
  return result.path;
}

duplicateResourceButton.addEventListener('click', async () => {
  const resource = workspaceShell.activeResource(); if (!resource || duplicateResourceButton.disabled) return;
  const replacementId = prompt(`Duplicate ${resource.kind} ${resource.id} as:`, resource.kind === 'script' ? `${resource.id}-copy` : `${resource.id}-copy`);
  if (!replacementId) return;
  try {
    const outbound = state.referenceGraph.references.filter(reference => reference.sourceType === resource.kind
      && reference.sourceId === resource.id);
    const preview = outbound.length ? outbound.map(reference =>
      `${reference.targetType}:${reference.targetId} (${reference.path}${reference.yamlPath})`).join('\n') : 'No typed outbound references.';
    if (!confirm(`Create the lossless copy ${replacementId}? References in the copy remain pointed at the original targets:\n\n${preview}`)) return;
    const replacementPath = await requestSafePath(resource.kind, replacementId);
    await executeProjectOperation('duplicate', { kind: resource.kind, sourceId: resource.id, replacementId, replacementPath }, resource.kind, replacementId);
  } catch (error) { status.textContent = `Duplicate failed: ${error.message}`; }
});

renameResourceButton.addEventListener('click', async () => {
  const resource = workspaceShell.activeResource(); if (!resource || renameResourceButton.disabled) return;
  const replacementId = prompt(`Rename ${resource.kind} ${resource.id} and all typed references to:`, resource.id);
  if (!replacementId || replacementId === resource.id) return;
  try {
    const files = await contentFiles();
    const previewResponse = await fetch(sessionApi('/projects/rename-preview'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ files, type: resource.kind, currentId: resource.id, replacementId })
    });
    const preview = await previewResponse.json();
    if (!previewResponse.ok || !preview.safe) throw new Error(preview.conflicts?.join(' ') || preview.message || `HTTP ${previewResponse.status}`);
    const summary = preview.occurrences.map(item => `${item.role}: ${item.path}:${item.line} ${item.yamlPath}`).join('\n');
    if (!confirm(`Apply this atomic rename?\n\n${summary}`)) return;
    const renameFile = resource.kind !== 'script' && confirm('Also rename the file to the server-suggested safe path?');
    const replacementPath = renameFile ? await requestSafePath(resource.kind, replacementId) : resource.path;
    await executeProjectOperation('rename', { kind: resource.kind, currentId: resource.id, replacementId, renameFile, replacementPath }, resource.kind, replacementId);
  } catch (error) { status.textContent = `Rename failed: ${error.message}`; }
});

moveResourceButton.addEventListener('click', async () => {
  const resource = workspaceShell.activeResource(); if (!resource || moveResourceButton.disabled) return;
  try {
    const replacementPath = await requestSafePath(resource.kind, resource.id);
    if (replacementPath === resource.path) {
      status.textContent = `${resource.id} already uses its canonical server-approved path.`; return;
    }
    if (!confirm(`Move ${resource.id} from ${resource.path} to ${replacementPath}? The file bytes will not change.`)) return;
    await executeProjectOperation('move', { kind: resource.kind, id: resource.id, replacementPath }, resource.kind, resource.id);
  } catch (error) { status.textContent = `Move failed: ${error.message}`; }
});

deleteResourceButton.addEventListener('click', async () => {
  const resource = workspaceShell.activeResource(); if (!resource || deleteResourceButton.disabled) return;
  const inbound = state.referenceGraph.references.filter(reference => reference.targetType === resource.kind
    && reference.targetId === resource.id);
  if (inbound.length) {
    referencesDialog.showModal(); await loadReferenceGraph();
    status.textContent = `Delete blocked: ${inbound.length} typed caller${inbound.length === 1 ? '' : 's'}. Use the caller buttons to navigate.`;
    return;
  }
  if (!confirm(`Delete ${resource.kind} ${resource.id}? Deletion is blocked when typed inbound references exist.`)) return;
  try { await executeProjectOperation('delete', { kind: resource.kind, id: resource.id }, null, null); }
  catch (error) { status.textContent = `Delete failed: ${error.message}`; }
});

async function pollPublish(publishId) {
  clearTimeout(state.publishTimer);
  try {
    const response = await fetch(`/api/v1/editor/sessions/${sessionId}/publishes/${publishId}`, {
      headers: { Authorization: `Bearer ${state.verified.browserLeaseToken}` }
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const publish = await response.json();
    if (publish.status === 'PUBLISHED') {
      status.textContent = `Persona published revision ${publish.activeRevision}. Recoverable backup: ${publish.backupId}. To restore it, run /persona editor rollback ${sessionId.substring(0, 8)} ${publishId} confirm in Minecraft.`;
      publishButton.disabled = true; return;
    }
    if (publish.status === 'ROLLED_BACK') {
      status.textContent = `Publication was rolled back to ${publish.activeRevision}. Pre-rollback safety backup: ${publish.backupId}.`; return;
    }
    if (['FAILED', 'REJECTED', 'ROLLBACK_FAILED'].includes(publish.status)) {
      status.textContent = `Publication ${publish.status.toLowerCase()}: ${publish.error || 'see the audit log'}.`; return;
    }
    state.publishTimer = setTimeout(() => pollPublish(publishId), 2000);
  } catch (error) { status.textContent = `Could not refresh publication status: ${error.message}`; }
}

publishButton.addEventListener('click', async () => {
  if (publishButton.disabled || !state.validationResult?.valid) return;
  publishButton.disabled = true; status.textContent = 'Creating a one-time in-game publication confirmation…';
  try {
    const response = await fetch(`/api/v1/editor/sessions/${sessionId}/publishes`, {
      method: 'POST', headers: { Authorization: `Bearer ${state.verified.browserLeaseToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ protocolVersion, draftId: state.draftId,
        proposedRevision: state.validationResult.proposedRevision })
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const publish = await response.json(), shortSession = sessionId.substring(0, 8);
    validationPanel.hidden = false;
    validationSummary.textContent = `Publication ${publish.publishId.substring(0, 8)} awaits in-game confirmation. Run /persona editor apply ${shortSession} ${publish.confirmationCode} before ${new Date(publish.expiresAt).toLocaleTimeString()}.`;
    status.textContent = 'Candidate remains unapplied until the one-time code is confirmed in Minecraft.';
    pollPublish(publish.publishId);
  } catch (error) { status.textContent = `Publication request failed: ${error.message}`; updatePublishButton(); }
});

const outputDock = document.querySelector('#output-dock');
function setEditorView(view) {
  if (!['visual', 'split', 'yaml'].includes(view)) view = 'split';
  editorElement.dataset.view = view;
  for (const name of ['visual', 'split', 'yaml'])
    document.querySelector(`#view-${name}`).setAttribute('aria-pressed', String(name === view));
  localStorage.setItem('persona:editor-view:v1', view);
  graphCanvas.schedule();
}
for (const view of ['visual', 'split', 'yaml']) document.querySelector(`#view-${view}`).addEventListener('click', () => setEditorView(view));
setEditorView(localStorage.getItem('persona:editor-view:v1') || 'split');
const outputTabs = [...document.querySelectorAll('.output-tabs [data-output]')];
function showOutput(name) {
  outputDock.hidden = false;
  outputTabs.forEach(tab => tab.setAttribute('aria-selected', String(tab.dataset.output === name)));
  document.querySelectorAll('.output-panel').forEach(panel => panel.hidden = panel.dataset.panel !== name);
  document.querySelector('#output-collapse').setAttribute('aria-expanded', 'true');
}
outputTabs.forEach(tab => tab.addEventListener('click', () => showOutput(tab.dataset.output)));
document.querySelector('#output-collapse').addEventListener('click', event => {
  const expanded = event.currentTarget.getAttribute('aria-expanded') === 'true';
  document.querySelectorAll('.output-panel').forEach(panel => panel.hidden = true);
  event.currentTarget.setAttribute('aria-expanded', String(!expanded));
  if (expanded) {
    document.querySelector('.editor').style.gridTemplateRows = 'auto auto auto minmax(0,1fr) auto';
    event.currentTarget.textContent = 'Expand output';
  } else {
    document.querySelector('.editor').style.gridTemplateRows = '';
    event.currentTarget.textContent = 'Collapse output';
    showOutput(outputTabs.find(tab => tab.getAttribute('aria-selected') === 'true')?.dataset.output || 'yaml');
  }
});
document.querySelector('#references-panel-open').addEventListener('click', loadReferenceGraph);
document.querySelector('#references-tools-open').addEventListener('click', () => referencesOpen.click());
document.querySelector('#semantic-panel-open').addEventListener('click', () => loadSemanticDiff(false));
commandDispatcher.bindButton(document.querySelector('#graph-add'), 'graph.add', () => ({ sourcePin: null }));
commandDispatcher.bindButton(document.querySelector('#graph-copy'), 'graph.copy', () => [...graphCanvas.selection]);
commandDispatcher.bindButton(document.querySelector('#graph-paste'), 'graph.paste');
commandDispatcher.bindButton(document.querySelector('#graph-auto-layout'), 'graph.auto-layout');
commandDispatcher.bindButton(document.querySelector('#graph-align-left'), 'graph.align-left');
commandDispatcher.bindButton(document.querySelector('#graph-align-top'), 'graph.align-top');
commandDispatcher.bindButton(document.querySelector('#graph-distribute-horizontal'), 'graph.distribute-horizontal');
commandDispatcher.bindButton(document.querySelector('#graph-distribute-vertical'), 'graph.distribute-vertical');
commandDispatcher.bindButton(document.querySelector('#graph-comment'), 'graph.comment');
commandDispatcher.bindButton(document.querySelector('#graph-group'), 'graph.group');
commandDispatcher.bindButton(document.querySelector('#graph-color'), 'graph.color');
commandDispatcher.bindButton(document.querySelector('#graph-focus-upstream'), 'graph.focus-upstream');
commandDispatcher.bindButton(document.querySelector('#graph-focus-downstream'), 'graph.focus-downstream');
let paletteContext = { type: 'commands' };

function graphNodeDefinitions() {
  return nodeDefinitions(graphCanvas.projection?.resourceKind, state.editorSchemas.values());
}

function graphNodeByPin(pin) {
  return (graphCanvas.projection?.nodes || []).find(node => node.id === pin?.nodeId) || null;
}

function graphInsertion(definition, sourcePin) {
  const projection = graphCanvas.projection, selected = projection?.nodes.find(node => graphCanvas.selection.has(node.id));
  const sourceNode = graphNodeByPin(sourcePin), kind = projection?.resourceKind;
  let parentYamlPath = null, index = null;
  if (kind === 'behavior') {
    const parent = sourceNode || (selected && ['sequence', 'selector', 'priority-selector', 'parallel'].includes(selected.kind) ? selected : projection.nodes[0]);
    if (!parent || !['sequence', 'selector', 'priority-selector', 'parallel'].includes(parent.kind)) return null;
    parentYamlPath = `${parent.yamlPath}/children`;
    const targetPath = paletteContext.edge?.targetYamlPath;
    if (targetPath?.startsWith(parentYamlPath + '/')) {
      const segment = targetPath.substring(parentYamlPath.length + 1).split('/')[0];
      if (/^\d+$/.test(segment)) index = Number(segment);
    }
  } else if (definition.destination === 'script' && ['dialogue', 'quest', 'npc', 'script'].includes(kind)) {
    const owner = sourceNode || selected;
    if (kind === 'script' && (!owner || owner.kind === 'reusable-script')) parentYamlPath = projection.rootYamlPath;
    else if (kind === 'quest' && owner?.kind === 'quest-phase') parentYamlPath = `${owner.yamlPath}/on-start`;
    else if (kind === 'quest' && owner?.kind === 'quest') parentYamlPath = `${owner.yamlPath}/on-start`.replace(/^\/\//, '/');
    else if (kind === 'npc' && owner?.kind === 'npc') parentYamlPath = `${owner.yamlPath}/on-interact`.replace(/^\/\//, '/');
    else if (owner?.kind === 'dialogue-entry') parentYamlPath = `${owner.yamlPath}/script`;
    else if ((owner?.kind?.startsWith('script-') || owner?.kind === 'extension-command') && owner.yamlPath?.includes('/')) {
      parentYamlPath = owner.yamlPath.slice(0, owner.yamlPath.lastIndexOf('/'));
      const segment = owner.yamlPath.slice(owner.yamlPath.lastIndexOf('/') + 1);
      if (/^\d+$/.test(segment)) index = Number(segment) + 1;
    }
  } else if (kind === 'dialogue') {
    if (definition.nodeKind === 'dialogue-entry') parentYamlPath = `${projection.rootYamlPath}/nodes`.replace(/^\/\//, '/');
    else {
      let owner = sourceNode || selected;
      if (owner && owner.kind !== 'dialogue-entry') owner = projection.nodes.find(node =>
        node.kind === 'dialogue-entry' && owner.yamlPath.startsWith(node.yamlPath + '/'));
      if (!owner) return null;
      parentYamlPath = `${owner.yamlPath}/script`;
    }
  } else if (kind === 'quest') {
    if (definition.nodeKind === 'quest-phase') parentYamlPath = `${projection.rootYamlPath}/phases`.replace(/^\/\//, '/');
    else {
      const phase = sourceNode?.kind === 'quest-phase' ? sourceNode
        : selected?.kind === 'quest-phase' ? selected : projection.nodes.find(node => node.kind === 'quest-phase');
      if (!phase) return null; parentYamlPath = `${phase.yamlPath}/objectives`;
    }
  } else if (kind === 'npc') parentYamlPath = `${projection.rootYamlPath}/anchors`.replace(/^\/\//, '/');
  return parentYamlPath == null ? null : { parentYamlPath, index };
}

function availableGraphNodes() {
  const sourcePin = paletteContext.sourcePin;
  return compatibleDefinitions(graphNodeDefinitions(), sourcePin,
    definition => Boolean(graphInsertion(definition, sourcePin)));
}

async function insertGraphNode(definition) {
  const sourcePin = paletteContext.sourcePin, insertion = graphInsertion(definition, sourcePin);
  if (!insertion) { yamlStatus.textContent = 'No compatible authoritative YAML container exists at this destination.'; return; }
  let key = null;
  if (definition.requiresKey || graphCanvas.projection.resourceKind === 'behavior') {
    key = prompt('Stable node ID', `node-${crypto.randomUUID().slice(0, 8)}`)?.trim();
    if (!key) return;
    if (!/^[a-z0-9][a-z0-9_.-]{0,127}$/.test(key)) {
      yamlStatus.textContent = 'Node IDs use lowercase letters, digits, dot, underscore, and hyphen.'; return;
    }
  }
  const operations = [{ type: 'INSERT', parentYamlPath: insertion.parentYamlPath,
    nodeKind: definition.nodeKind, key, value: definition.extensionType || null, index: insertion.index }];
  if (sourcePin && ['dialogue-entry', 'quest-phase'].includes(definition.nodeKind)) {
    const targetNodeId = `${graphCanvas.projection.resourceKind}:${graphCanvas.projection.resourceId}#${key}`;
    operations.push({ type: 'CONNECT', sourcePinId: sourcePin.id, targetPinId: `${targetNodeId}:in` });
  }
  await graphMutationClient.mutate(operations, sourcePin ? 'Insert and connect node' : `Insert ${definition.label}`);
}

function renderCommands() {
  const query = paletteSearch.value.trim().toLowerCase();
  const entries = paletteContext.type === 'graph'
    ? availableGraphNodes().map(definition => [definition.label, () => insertGraphNode(definition)])
    : commandDispatcher.entries().map(command => [command.label, () => commandDispatcher.execute(command.id)]);
  const matches = entries.filter(([name]) => name.toLowerCase().includes(query));
  paletteResults.replaceChildren(...matches.map(([name, run], index) => {
    const item = document.createElement('li'), button = document.createElement('button');
    button.type = 'button'; button.textContent = name; button.dataset.index = String(index);
    button.addEventListener('click', () => { palette.close(); run(); }); item.append(button); return item;
  }));
  if (!matches.length) {
    const empty = document.createElement('li'); empty.className = 'empty-state';
    empty.textContent = paletteContext.type === 'graph' ? 'No node type is compatible at this destination.' : 'No matching command.';
    paletteResults.append(empty);
  }
  paletteResults.querySelector('button')?.focus();
}
function openPalette() {
  if (!state.connected) return; paletteContext = { type: 'commands' };
  palette.querySelector('label').textContent = 'Command palette'; paletteSearch.placeholder = 'Search commands…';
  palette.showModal(); paletteSearch.value = ''; renderCommands(); paletteSearch.focus();
}
function openGraphPalette(context = {}) {
  if (!state.connected || !graphCanvas.projection?.editable) return;
  paletteContext = { type: 'graph', sourcePin: context.sourcePin || null, edge: context.edge || null };
  palette.querySelector('label').textContent = context.sourcePin ? `Add from ${context.sourcePin.label} output` : 'Add graph node';
  paletteSearch.placeholder = 'Search compatible node types…';
  palette.showModal(); paletteSearch.value = ''; renderCommands(); paletteSearch.focus();
}
paletteOpen.addEventListener('click', openPalette);
paletteSearch.addEventListener('input', renderCommands);
palette.addEventListener('keydown', event => {
  const buttons = [...paletteResults.querySelectorAll('button')], current = buttons.indexOf(document.activeElement);
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault(); buttons[(current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length]?.focus();
  }
});
window.addEventListener('keydown', event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); openPalette(); return; }
  if (event.altKey && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
    event.preventDefault(); const paths = [...state.files.keys()].sort(), index = paths.indexOf(state.selected);
    if (paths.length) selectFile(paths[(index + (event.key === 'ArrowDown' ? 1 : -1) + paths.length) % paths.length]);
  }
});
window.addEventListener('beforeunload', event => {
  flushSelected(); if ([...state.files].some(([path, content]) => content !== state.original.get(path))) event.preventDefault();
});
