import { WorkspaceShell, deriveResources } from './modules/workspace-shell.js';
import { PanelLayout } from './modules/layout-store.js';
import { GraphLayoutStore } from './modules/graph-layout.js';
import { GraphCanvas } from './modules/graph-canvas.js';
import { GraphInspector } from './modules/graph-inspector.js';
import { GraphMutationClient, contractMessage, inlineDefaultOperation } from './modules/graph-mutations.js';
import { CommandDispatcher } from './modules/command-dispatcher.js';
import { nodeDefinitions, compatibleDefinitions, matchingDefinitionInput, automaticNodeId, yamlMappingKeys } from './modules/node-registry.js';
import { createWorkspaceState } from './modules/workspace-state.js';
import { SessionTransport } from './modules/transport.js';
import { findModelNode } from './modules/yaml-documents.js';
import { liveNodeKeys } from './modules/live-overlays.js';
import { nestedProjection } from './modules/graph-projection.js';
import { publicationReady, validationHeading, diagnosticLabel } from './modules/validation.js';
import { revealSource } from './modules/source-selection.js';
import { BottomDock } from './modules/bottom-dock.js';
import { hasGraphCapability } from './modules/capabilities.js';

const sessionId = location.pathname.match(/^\/editor\/session\/([0-9a-f-]+)$/i)?.[1];
if (!sessionId) throw new Error('The Persona editor requires a server-created session URL.');
const connectPanel = document.querySelector('#connect');
const reconnectPanel = document.querySelector('#reconnect');
const reconnectReason = document.querySelector('#reconnect-reason');
const reconnectNow = document.querySelector('#reconnect-now');
const verifyForm = document.querySelector('#verify');
const status = document.querySelector('#status');
const workspace = document.querySelector('#workspace');
const source = document.querySelector('#source');
const fileName = document.querySelector('#file-name');
const download = document.querySelector('#download');
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
const paletteContextSensitive = document.querySelector('#palette-context-sensitive');
const visual = document.querySelector('#visual');
const yamlStatus = document.querySelector('#yaml-status');
const validationPanel = document.querySelector('#validation-panel');
const validationSummary = document.querySelector('#validation-summary');
const validationList = document.querySelector('#validation-list');
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
    if (matchMedia('(max-width: 899px)').matches && !panelLayout.value.browserCollapsed)
      panelLayout.toggle('browser');
  },
  empty: () => { state.selected = null; source.value = ''; source.disabled = true; fileName.textContent = 'Select a resource'; },
  dirty: resource => state.files.get(resource.path) !== state.original.get(resource.path),
  invalid: resource => state.documentValidity.get(resource.path) === false,
  referenced: resource => state.referenceGraph.references.some(value => value.targetType === resource.kind && value.targetId === resource.id),
  missing: resource => state.referenceGraph.references.some(value => !value.resolved && value.sourceType === resource.kind && value.sourceId === resource.id),
  searchTerms: resource => state.referenceGraph.references.filter(reference =>
    reference.sourceType === resource.kind && reference.sourceId === resource.id
      || reference.targetType === resource.kind && reference.targetId === resource.id)
    .map(reference => `${reference.sourceType} ${reference.sourceId} ${reference.targetType} ${reference.targetId}`).join(' '),
  bookmarked: resource => resource.identity === graphCanvas?.projection?.resourceIdentity
    ? graphCanvas.bookmarks.size > 0 : Boolean(state.graphTabContexts.get(resource.identity)?.bookmarks?.length),
  live: resource => resource.kind === 'npc'
    ? [...state.liveData.npcs.values()].some(value => value.definitionId === resource.id)
    : resource.kind === 'behavior' ? [...state.liveData.behaviors.values()].some(value => value.behaviorId === resource.id) : false,
  restoreFocus: () => document.querySelector('#content-search')?.focus(),
  createFolder: parent => createProjectFolder(parent),
  createHere: folder => openCreation(kindForFolder(folder), '', folder),
  moveFolder: (folder, destination) => moveProjectFolder(folder, destination),
  deleteFolder: folder => deleteProjectFolder(folder),
  moveResourceToFolder: (resource, folder) => moveResourceToFolder(resource, folder),
  copyResourceToFolder: (resource, folder) => copyResourceToFolder(resource, folder)
});
const panelLayout = new PanelLayout('persona:panel-layout:' + sessionId, {
  onStorageError: () => { status.textContent = 'Layout preferences could not be saved; editing remains available.'; }
});
const graphLayoutStore = new GraphLayoutStore(sessionId);
const graphInspector = new GraphInspector({
  onSelectSource: (path, range) => {
    if (!path || !range) return;
    if (state.relationshipMode) { status.textContent = 'Double-click a resolved resource node to open its source.'; return; }
    state.selectedNode = path; revealSource(source, range);
    selectVisualNode(path);
  },
  onEditField: (field, value) => applyVisualEdit(field.yamlPath, value),
  onFocusNode: id => graphCanvas.focusNode(id),
  onParameterAction: action => handleScriptParameterAction(action),
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
let pendingViewportJump = null;
const graphCanvas = new GraphCanvas({
  onSelection: nodes => {
    graphInspector.render(nodes, graphCanvas.projection, graphCanvas.liveNodeKeys);
    if (nodes.length && matchMedia('(max-width: 899px)').matches) panelLayout.show('inspector');
    if (graphCanvas.projection?.resourceKind !== 'relationship' && nodes.length === 1 && nodes[0].yamlPath) {
      state.selectedNode = nodes[0].yamlPath;
      revealSource(source, nodes[0].range, { focus: false });
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
    if (['resource-reference', 'resource-value'].includes(node.kind)
        && ['behavior', 'dialogue', 'quest', 'npc', 'script'].includes(node.subtitle)) {
      const resource = deriveResources(state.files).find(item => item.kind === node.subtitle && item.id === node.title);
      if (resource) { workspaceShell.openResource(resource); return; }
      yamlStatus.textContent = `Missing ${node.subtitle} ${node.title}. Use Create missing to add it safely.`;
    }
    if(node.kind==='script-value'){const pin=(node.pins||[]).find(value=>String(value.direction).toUpperCase()==='OUTPUT'&&value.channel==='DATA');const id=(node.fields||[]).find(field=>field.label==='value')?.value;if(pin&&id&&['quest','npc','behavior','dialogue','script'].includes(pin.valueType)){const resource=deriveResources(state.files).find(item=>item.kind===pin.valueType&&item.id===id);if(resource){workspaceShell.openResource(resource);return;}}}
    if (!node.yamlPath) return; revealSource(source, node.range);
  },
  onPin: pin => { status.textContent = `${String(pin.channel).toLowerCase()} ${pin.direction} pin ${pin.label} accepts ${pin.valueType} (${pin.cardinality}).`; },
  resourceOptions: kind => deriveResources(state.files).filter(resource => resource.kind === kind)
    .map(resource => ({ id: resource.id, label: resource.label })),
  onConnectionPending: pin => { status.textContent = `Selected ${pin.label} output. Choose a compatible input; Escape cancels.`; },
  onConnectionError: message => { yamlStatus.textContent = `Connection rejected before changing YAML: ${message}`; status.textContent = message; },
  onConnect: gesture => commandDispatcher.execute('graph.connect', gesture),
  onInlineDefault: (pin, value) => graphMutationClient.mutate([
    inlineDefaultOperation(graphCanvas.projection, pin, value)
  ], `Set ${pin.label} value`),
  onResourceDrop: async (resource,position,targetPin) => { const key=`value-${crypto.randomUUID().replaceAll('-','').slice(0,8)}`;
    const owner=targetPin?graphNodeByPin(targetPin):(graphCanvas.projection?.nodes||[]).find(node=>graphCanvas.selection.has(node.id))
      ||(graphCanvas.projection?.nodes||[]).find(node=>['event','script-input'].includes(node.kind));
    const descriptor=explicitGraphPath(owner,graphCanvas.projection);
    if(descriptor==null){yamlStatus.textContent='Select an event or reusable graph before dropping a resource.';return;}
    const parentYamlPath=`${descriptor}/nodes`.replace(/^\/\//,'/');
    const create={ type:'CREATE_VALUE_NODE',parentYamlPath,key,value:resource.id,valueType:resource.kind }, operations=targetPin
      ? [{type:'COMPOUND',operationId:crypto.randomUUID(),children:[create,{type:'CONNECT',key:`wire-${crypto.randomUUID().replaceAll('-','').slice(0,12)}`,sourcePinId:`${graphCanvas.projection.resourceKind}:${graphCanvas.projection.resourceId}#graph:${hex(await crypto.subtle.digest('SHA-256',encoder.encode(descriptor))).slice(0,10)}:node:${key}:output:value`,targetPinId:targetPin.id}]}]
      : [create];
    const result=await graphMutationClient.mutate(operations,`${targetPin?'Connect':'Add'} ${resource.id}`);
    placeCreatedGraphNode(result,key,position); },
  onDisconnect: edge => commandDispatcher.execute('graph.disconnect', edge),
  onBreakLinks: (edges, pin) => graphMutationClient.mutate([
    { type: 'BREAK_ALL_LINKS', [String(pin?.direction).toUpperCase() === 'OUTPUT' ? 'sourcePinId' : 'targetPinId']: pin?.id }
  ], `Break ${(edges || []).length} link${(edges || []).length === 1 ? '' : 's'}`),
  onMoveLinks: pin => moveAllPinLinks(pin),
  onPromotePin: pin => promotePinToVariable(pin),
  onTraceSelectionChange: () => { if (state.liveSubscription) refreshLiveSubscription(); },
  viewportContext: () => ({ resourceIdentity: graphCanvas.projection?.resourceIdentity,
    nestedGraph: state.nestedGraph ? structuredClone(state.nestedGraph) : null }),
  viewportBookmarkKey: () => `persona:viewport-bookmarks:v1:${state.installationIdentity || sessionId}`,
  onViewportJump: target => restoreViewportJump(target),
  onInsertWire: (edge, sourcePin, pointer) => commandDispatcher.execute('graph.add', { sourcePin, edge, ...pointer }),
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
    if (command.type === 'LAYOUT') { recordHistory(state.selected, command.before); appendGraphHistory('Local layout command', 'local'); }
    if (command.type === 'COPY') commandDispatcher.execute('graph.copy', command.nodeIds);
    if (command.type === 'CUT' && copyGraphNode(command.nodeIds)) deleteGraphNodes(command.nodeIds);
    if (command.type === 'PASTE') commandDispatcher.execute('graph.paste');
    if (command.type === 'DUPLICATE') duplicateGraphNodes(command.nodeIds);
    if (command.type === 'RENAME') renameGraphNode(command.nodeId);
  }
});

function refreshWorkspaceResources(activePath = state.selected) {
  const resources = deriveResources(state.files);
  const active = resources.find(resource => resource.path === activePath
    && (!state.pendingYamlPath || resource.yamlPath === state.pendingYamlPath))
    || resources.find(resource => resource.path === activePath);
  workspaceShell.update(resources, active?.identity, state.folders);
  return { resources, active };
}

connectPanel.hidden = false;
reconnectPanel.hidden = true;

const sessionApi = path => transport.api(path);
const authorizedHeaders = (values = {}) => transport.headers(values);
const requireConnection = () => transport.requireConnection();
const draftEditAllowed = () => state.connected && state.verified?.capabilities?.includes('DRAFT_EDIT');
const draftEditRequiredMessage = 'Editing content requires Draft Edit trust. Approve DRAFT_EDIT in Minecraft, then wait for this session to refresh.';

function requireDraftEdit() {
  requireConnection();
  if (!draftEditAllowed()) throw new Error(draftEditRequiredMessage);
}

graphMutationClient = new GraphMutationClient({
  endpoint: () => sessionApi('/documents/mutate'),
  headers: authorizedHeaders,
  context: async () => {
    requireConnection(); requireDraftEdit(); flushSelected();
    const resource = workspaceShell.activeResource(), projection = graphCanvas.projection;
    if (!resource || !projection || projection.resourceIdentity !== resource.identity)
      throw new Error('Wait for the authoritative graph projection before editing.');
    const selected = state.selected, connectionGeneration = state.connectionGeneration,
      resourceIdentity = resource.identity, content = state.files.get(selected) ?? '';
    const projectFiles = await contentFiles();
    return { resource, projection, content, projectFiles,
      projectRevision: await projectRevision(projectFiles), selected,
      isCurrent: () => state.connected && state.connectionGeneration === connectionGeneration
        && state.selected === selected && workspaceShell.activeResource()?.identity === resourceIdentity };
  },
  forbiddenMessage: () => draftEditRequiredMessage,
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
    graphMutationClient.mutate([{ type: 'DISCONNECT', edgeId: edge.id, yamlPath: edge.sourceYamlPath,
      sourcePinId: edge.sourcePinId, targetPinId: edge.targetPinId }], 'Disconnect nodes') })
  .register('graph.add', { label: 'Add graph node', enabled: () => state.connected && hasGraphCapability(graphCanvas.projection, 'CREATE_NODE'),
    run: context => openGraphPalette(context || { sourcePin: null }) })
  .register('graph.delete', { label: 'Delete selected graph nodes', enabled: nodeIds => Boolean(nodeIds?.length)
      && hasGraphCapability(graphCanvas.projection, 'DELETE_NODE'),
    run: deleteGraphNodes })
  .register('graph.copy', { label: 'Copy selected graph nodes', enabled: nodeIds => Boolean(nodeIds?.length),
    run: copyGraphNode })
  .register('graph.paste', { label: 'Paste compatible graph nodes', enabled: () => Boolean(state.graphClipboard),
    run: pasteGraphNode })
  .register('graph.align-left', { label: 'Align selected nodes left', enabled: () => graphCanvas.selection.size >= 2,
    run: () => graphCanvas.align('left') })
  .register('graph.align-center', { label: 'Align selected node centers', enabled: () => graphCanvas.selection.size >= 2,
    run: () => graphCanvas.align('center') })
  .register('graph.align-right', { label: 'Align selected nodes right', enabled: () => graphCanvas.selection.size >= 2,
    run: () => graphCanvas.align('right') })
  .register('graph.align-top', { label: 'Align selected nodes top', enabled: () => graphCanvas.selection.size >= 2,
    run: () => graphCanvas.align('top') })
  .register('graph.align-middle', { label: 'Align selected node middles', enabled: () => graphCanvas.selection.size >= 2,
    run: () => graphCanvas.align('middle') })
  .register('graph.align-bottom', { label: 'Align selected nodes bottom', enabled: () => graphCanvas.selection.size >= 2,
    run: () => graphCanvas.align('bottom') })
  .register('graph.distribute-horizontal', { label: 'Distribute selected nodes horizontally', enabled: () => graphCanvas.selection.size >= 3,
    run: () => graphCanvas.distribute('horizontal') })
  .register('graph.distribute-vertical', { label: 'Distribute selected nodes vertically', enabled: () => graphCanvas.selection.size >= 3,
    run: () => graphCanvas.distribute('vertical') })
  .register('graph.snap', { label: 'Snap selected nodes to grid', enabled: () => graphCanvas.selection.size > 0,
    run: () => graphCanvas.snapSelection() })
  .register('graph.tidy', { label: 'Tidy selected nodes', enabled: () => graphCanvas.selection.size > 0,
    run: () => graphCanvas.tidySelection() })
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
  .register('graph.focus-connected', { label: 'Focus connected component', enabled: () => graphCanvas.selection.size > 0,
    run: () => graphCanvas.focusRelated('connected') })
  .register('graph.node-action', { label: 'Graph node action', enabled: action => Boolean(action?.type), run: handleGraphNodeAction })
  .register('history.undo', { label: 'Undo', keywords: 'history', enabled: () => Boolean(state.histories.get(state.selected)?.undo.length),
    run: () => restoreHistory('undo') })
  .register('history.redo', { label: 'Redo', keywords: 'history', enabled: () => Boolean(state.histories.get(state.selected)?.redo.length),
    run: () => restoreHistory('redo') })
  .register('yaml.copy', { label: 'Copy YAML selection', run: () => copyButton.click() })
  .register('yaml.paste', { label: 'Paste YAML', enabled: () => state.connected, run: () => pasteButton.click() })
  .register('changes.text', { label: 'Show textual changes', run: () => { if (diff.hidden) diffToggle.click(); } })
  .register('download.file', { label: 'Download current file', run: () => download.click() })
  .register('references.open', { label: 'Show references and rename preview', run: () => referencesOpen.click() })
  .register('changes.semantic', { label: 'Show semantic project diff', run: () => semanticDiffOpen.click() })
  .register('publish.request', { label: 'Publish validated content', enabled: () => !publishButton.disabled,
    run: () => publishButton.click() });
function lockWorkspace(message) {
  if (state.connected) persistRecovery();
  state.connected = false;
  graphCanvas.traceRing = [];
  document.querySelector('#status-connection').textContent = 'Disconnected—editing stopped';
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
  reconnectReason.textContent = message;
  updateLifecycleButtons();
}

const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const fromB64 = value => Uint8Array.from(atob(value), character => character.charCodeAt(0));
const hex = bytes => [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('');

function snapshotFailureCloseReason(error) {
  const prefix = 'Snapshot refresh failed: ';
  let detail = String(error?.message || error || 'unknown error').replace(/[^\x20-\x7e]/g, '?');
  while (encoder.encode(prefix + detail).length > 123) detail = detail.slice(0, -1);
  return prefix + detail;
}

function parseProjectFolders(content) {
  if (!content) return new Set();
  return new Set(content.split(/\r?\n/).map(line => line.match(/^\s{2}-\s+([^#\s].*?)\s*$/)?.[1])
    .filter(Boolean));
}

function flushSelected() {
  if (state.selected) state.files.set(state.selected, source.value);
}

function renderProject(files, message, revision = null, folders = [], manifestDigest = '') {
  requireConnection();
  const layoutScope = `${state.installationIdentity || sessionId}:persona-project`;
  panelLayout.rekey(`persona:panel-layout:v2:${layoutScope}`);
  workspaceShell.setPreferenceScope(layoutScope);
  graphLayoutStore.scope = layoutScope;
  state.files = new Map(files.map(file => [file.path, file.content]));
  state.original = new Map(state.files);
  state.baseRevision = revision;
  state.currentRevision = revision;
  state.folders = new Set(folders);
  state.manifestDigest = manifestDigest;
  document.querySelector('#status-connection').textContent = 'Connected';
  document.querySelector('#status-revision').textContent = revision ? `Base ${revision.slice(0, 10)}` : 'Revision —';
  document.querySelector('#status-save').textContent = 'Saved';
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
    graphLayoutStore.scope = `${state.installationIdentity || sessionId}:persona-project`;
    const tabContext = state.graphTabContexts.get(cachedGraph.resourceIdentity);
    const jump = pendingViewportJump?.resourceIdentity === cachedGraph.resourceIdentity ? pendingViewportJump : null;
    if (jump) restoreNestedBookmark(jump);
    graphCanvas.setProjection(jump ? visibleGraphProjection(cachedGraph) : cachedGraph, tabContext || null);
    if (jump) { graphCanvas.restoreViewport(jump.viewport); pendingViewportJump = null; }
    graphLayoutStore.load(cachedGraph).then(layout => {
      if (layout && workspaceShell.activeResource()?.identity === cachedGraph.resourceIdentity && !tabContext && !jump)
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
  if (!model?.root) { visual.replaceChildren(); return; }
  const ids=[];const gather=node=>{if(node.kind==='mapping'){const id=node.children?.find(child=>child.key==='id')?.value;if(id)ids.push(id);}for(const child of node.children||[])gather(child);};gather(model.root);state.duplicateNodeIds=new Set(ids.filter((id,index)=>ids.indexOf(id)!==index));
  visual.replaceChildren(renderVisualNode(model.root));
  renderInsights(model);
  if (state.selectedNode) selectVisualNode(state.selectedNode);
  overlayLiveBehaviorNodes();
}
function modelValue(node){if(!node)return null;if(node.kind==='mapping')return Object.fromEntries(node.children.map(child=>[child.key,modelValue(child)]));if(node.kind==='sequence')return node.children.map(modelValue);if(node.kind==='boolean')return node.value==='true';if(node.kind==='integer'||node.kind==='number')return Number(node.value);if(node.kind==='null')return null;return node.value;}
function graphCard(label,problem=false){const card=document.createElement('span');card.className=`graph-node${problem?' problem':''}`;card.textContent=label;return card;}
function graphNodes(graph){return Object.values(graph?.nodes||{}).filter(node=>node&&typeof node==='object');}
function renderInsights(model){const value=modelValue(model.root),kind=editorKind();insightsTitle.textContent=`${kind[0].toUpperCase()+kind.slice(1)} structure`;visualGraph.replaceChildren();visualPreview.replaceChildren();if(kind==='behavior')renderBehaviorInsights(value);else if(kind==='dialogue')renderDialogueInsights(value);else if(kind==='quest')renderQuestInsights(value);else if(kind==='npc')renderNpcInsights(value);else renderScriptInsights(value);}
function renderBehaviorInsights(value){const nodes=[];const walk=node=>{if(!node||typeof node!=='object')return;nodes.push(node);for(const child of node.children||[])walk(child);walk(node.child);};walk(value.root);for(const node of nodes){const persistence=['checkpoint','wait','cooldown'].includes(node.type)?' · durable checkpoint/deadline':['action','condition'].includes(node.type)?' · transient':'',semantics=node.type==='sequence'?' · ordered':node.type?.includes('selector')?' · priority':node.type==='parallel'?` · success ${node['success-threshold']||'all'}, failure ${node['failure-threshold']||1}`:'';visualGraph.append(graphCard(`${node.id||'?'} · ${node.type||'?'}${semantics}${persistence}`));}visualPreview.textContent=`${value.scope||'player'} scope · ${nodes.length} nodes · placeholders: <player>, <npc>, <memory:key>, <npc-memory:key>, <event:key>`;}
function dialogueEdges(nodes){const edges=new Map();for(const [id,entry] of Object.entries(nodes||{})){const outgoing=graphNodes(entry.graph).filter(node=>node.type==='goto').map(node=>node.dialogue?`${node.dialogue}/${node.node||'start'}`:node.node).filter(Boolean);edges.set(id,outgoing);}return edges;}
function renderDialogueInsights(value){const nodes=value.nodes||{},edges=dialogueEdges(nodes),targets=new Set([...edges.values()].flat().filter(target=>!target.includes('/')));for(const [id,outgoing] of edges){const commands=graphNodes(nodes[id]?.graph),missing=outgoing.some(next=>!next.includes('/')&&!nodes[next]),unreachable=id!==value.start&&!targets.has(id),dead=!commands.some(step=>['goto','end-dialogue'].includes(step.type));visualGraph.append(graphCard(`${id}${outgoing.length?` → ${outgoing.join(', ')}`:''}${unreachable?' · unreachable':''}${dead?' · implicit end':''}`,missing||unreachable));}const lines=Object.values(nodes).flatMap(node=>graphNodes(node.graph)).filter(step=>step.type==='say').map(step=>{const translations=Object.entries(step.translations||{}).map(([locale,text])=>`${locale}=${text}`).join(', ');return`${step.text||step['text-key']||'(weighted variants)'}${translations?` {${translations}}`:''}${step.delay?` [${step.delay}]`:''}`;});visualPreview.textContent=`Preview lines / localization keys:\n${lines.join('\n')||'(none)'}\nPlaceholders: <player>, <npc>, <dialogue>, <quest>, <phase>, <objective>, <current>, <required>, <memory:key>`;}
function renderQuestInsights(value){const phases=value.phases||[],ids=new Set(phases.map(phase=>phase.id)),reachable=new Set();const visit=id=>{if(!id||id==='end'||reachable.has(id)||!ids.has(id))return;reachable.add(id);const index=phases.findIndex(phase=>phase.id===id),phase=phases[index];for(const branch of phase.branches||[])visit(branch['next-phase']);if(index+1<phases.length)visit(phases[index+1].id);};visit(phases[0]?.id);phases.forEach((phase,index)=>{const destinations=(phase.branches||[]).map(branch=>branch['next-phase']).filter(Boolean),invalid=destinations.some(id=>id!=='end'&&!ids.has(id)),impossible=(phase.branches||[]).some(branch=>branch.when?.type==='chance'&&Number(branch.when.chance)<=0),unreachable=!reachable.has(phase.id);visualGraph.append(graphCard(`${phase.id||'?'} → ${destinations.join(', ')||phases[index+1]?.id||'end'} · ${(phase.objectives||[]).length} objectives${impossible?' · impossible branch':''}${unreachable?' · unreachable':''}`,invalid||impossible||unreachable));});const objectives=phases.flatMap(phase=>(phase.objectives||[]).map(objective=>`${phase.id}/${objective.id}: ${objective.type}, ${objective.optional?'optional':'required'}, ${objective.hidden?'hidden':'visible'}, target ${objective.amount||objective.duration||1}`));visualPreview.textContent=`Requirements: ${JSON.stringify(value.when||value.requirements||'none')}\nTimer: ${value['time-limit']||'none'} · repeatable: ${value.repeatable||false} · cooldown: ${value.cooldown||'none'} · maximum completions: ${value['maximum-completions']||'unlimited'}\n${objectives.join('\n')}\nPlaceholders: <player>, <quest>, <phase>, <objective>, <current>, <required>, <memory:key>`;}
function renderNpcInsights(value){const definition=value.id,live=[...state.liveData.npcs.values()].filter(npc=>npc.definitionId===definition),anchors=Object.entries(value.anchors||{}),table=document.createElement('table');table.className='anchor-table';table.innerHTML='<tr><th>Anchor</th><th>World / coordinates</th><th></th></tr>';for(const [name,anchor] of anchors){const actor=live.find(npc=>!npc.playerId),far=actor?.position&&actor.position.world===anchor.world?Math.hypot(actor.position.x-anchor.x,actor.position.y-anchor.y,actor.position.z-anchor.z)>48:false,row=document.createElement('tr'),label=document.createElement('td'),position=document.createElement('td'),action=document.createElement('td'),button=document.createElement('button');label.textContent=name+(far?' ⚠ far from actor':'');position.textContent=`${anchor.world} ${anchor.x} ${anchor.y} ${anchor.z} ${anchor.yaw||0} ${anchor.pitch||0}`;button.type='button';button.textContent='Paste coordinates';button.addEventListener('click',()=>importAnchor(name));action.append(button);row.append(label,position,action);table.append(row);}visualGraph.append(table);if(anchors.length){const map=document.createElement('div');map.className='anchor-map';const xs=anchors.map(([,a])=>Number(a.x)),zs=anchors.map(([,a])=>Number(a.z)),minX=Math.min(...xs),maxX=Math.max(...xs),minZ=Math.min(...zs),maxZ=Math.max(...zs);for(const [name,anchor] of anchors){const point=document.createElement('span');point.className='anchor-point';point.style.left=`${5+90*(Number(anchor.x)-minX)/(maxX-minX||1)}%`;point.style.top=`${5+90*(Number(anchor.z)-minZ)/(maxZ-minZ||1)}%`;point.textContent=name;point.title=`${anchor.world}: ${anchor.x}, ${anchor.y}, ${anchor.z}`;map.append(point);}visualGraph.append(map);}const presentations=live.map(npc=>`${npc.playerId||'shared'}: ${npc.presentation}/${npc.projectionState}, ${npc.entityName||value['display-name']||''} ${npc.entityType||''}, skin ${npc.skin||'none'}, equipment ${JSON.stringify(npc.equipment||{})}, age ${npc.age??'n/a'}, pose ${npc.pose||'n/a'}`);visualPreview.textContent=`Definition ${definition||'?'} · display ${value['display-name']||''}\nshared behavior ${value['shared-behavior']||'none'} · player behavior ${value['player-behavior']||'none'}\n${presentations.join('\n')||'Open a trusted live subscription to preview shared/private Citizens presentation.'}`;}
function renderScriptInsights(value){for(const [id,node] of Object.entries(value.nodes||{}))visualGraph.append(graphCard(`${id} · ${node.type||'unknown'}`));visualPreview.textContent=`${Object.keys(value.inputs||{}).length} inputs · ${Object.keys(value.outputs||{}).length} outputs · ${Object.keys(value.variables||{}).length} local variables · ${Object.keys(value.connections||{}).length} explicit wires.`;}
async function importAnchor(name){const raw=prompt('Paste “x y z [yaw pitch]” or a Minecraft /tp command');if(!raw)return;const numbers=raw.match(/-?\d+(?:\.\d+)?/g)?.map(Number);if(!numbers||numbers.length<3){yamlStatus.textContent='Could not find at least x, y, and z coordinates.';return;}const values=numbers.slice(-5),xyz=values.length>=5?values:values.slice(0,3);for(const [field,value] of [['x',xyz[0]],['y',xyz[1]],['z',xyz[2]],['yaw',values.length>=5?values[3]:0],['pitch',values.length>=5?values[4]:0]])await applyVisualEdit(`/anchors/${name.replaceAll('~','~0').replaceAll('/','~1')}/${field}`,String(value));}
function runSimulation(input, output) { try { const mocks=JSON.parse(input.value),model=state.documentModels.get(state.selected),value=modelValue(model.root);output.textContent=JSON.stringify(simulate(editorKind(),value,mocks),null,2); } catch(error) { output.textContent=`Simulation input error: ${error.message}`; } }
document.querySelector('#simulate-open').addEventListener('click',()=>showOutput('simulation'));document.querySelector('#simulation-close').addEventListener('click',()=>simulationDialog.close());document.querySelector('#simulation-run').addEventListener('click',()=>runSimulation(simulationInput,simulationOutput));
document.querySelector('#simulation-dock-run').addEventListener('click',()=>runSimulation(document.querySelector('#simulation-dock-input'),document.querySelector('#simulation-dock-output')));
function testCondition(condition,mocks){if(!condition)return true;if(Array.isArray(condition))return condition.every(item=>testCondition(item,mocks));switch(condition.type){case'all':return(condition.conditions||[]).every(item=>testCondition(item,mocks));case'any':return(condition.conditions||[]).some(item=>testCondition(item,mocks));case'not':return!testCondition(condition.when||condition.condition,mocks);case'flag':return Boolean(mocks.flags?.[condition.name])===Boolean(condition.value??true);case'variable':return String(mocks.variables?.[condition.name]??'')===String(condition.value??'');case'quest-state':return String(mocks.quests?.[condition.quest]??'not-started')===String(condition.state);case'memory':return String(mocks.memories?.[condition.key]??'')===String(condition.value??true);case'event':return(mocks.events||[]).includes(condition.event||condition.name);case'chance':return Number(mocks.chance??0.5)<Number(condition.chance??0);default:return Boolean(mocks.conditions?.[condition.type]);}}
function simulate(kind,value,mocks){if(kind==='behavior')return simulateBehavior(value,mocks);if(kind==='dialogue')return simulateDialogue(value,mocks);if(kind==='quest')return simulateQuest(value,mocks);const graphs=kind==='npc'?[value['on-click'],value['on-damage'],value['on-spawn'],value['on-despawn'],value['on-no-dialogue']]:[value];return{kind,nodes:graphs.flatMap(graph=>graphNodes(graph)).map(node=>node.type),note:'Preview is deterministic and performs no server mutations.'};}
function simulateBehavior(value,mocks){const trace=[];const run=node=>{if(!node)return'FAILURE';let status='SUCCESS';switch(node.type){case'condition':status=testCondition({...node,type:node.condition},mocks)?'SUCCESS':'FAILURE';break;case'wait':status='RUNNING';break;case'action':status=String(mocks.actions?.[node.action]||'SUCCESS');break;case'sequence':for(const child of node.children||[]){status=run(child);if(status!=='SUCCESS')break;}break;case'selector':case'priority-selector':status='FAILURE';for(const child of node.children||[]){const next=run(child);if(next!=='FAILURE'){status=next;break;}}break;case'parallel':{const results=(node.children||[]).map(run),success=results.filter(item=>item==='SUCCESS').length,failure=results.filter(item=>item==='FAILURE').length;status=success>=Number(node['success-threshold']||results.length)?'SUCCESS':failure>=Number(node['failure-threshold']||1)?'FAILURE':'RUNNING';break;}case'invert':status=run(node.child);status=status==='SUCCESS'?'FAILURE':status==='FAILURE'?'SUCCESS':status;break;case'repeat':case'retry':case'timeout':case'cooldown':case'checkpoint':status=run(node.child);break;case'subtree':status=String(mocks.subtrees?.[node.subtree]||'RUNNING');break;default:status='FAILURE';}trace.push({id:node.id,type:node.type,status});return status;};return{status:run(value.root),trace,mockMemories:mocks.memories||{},events:mocks.events||[]};}
function interpolate(text,mocks){return String(text??'').replace(/<([^>]+)>/g,(all,key)=>{const [group,name]=key.split(':',2);return name!=null?mocks[group]?.[name]??all:mocks[key]??all;});}
function simulateDialogue(value,mocks){const transcript=[],visited=[];let nodeId=value.start,steps=0;while(nodeId&&value.nodes?.[nodeId]&&steps++<64){if(visited.includes(nodeId))return{status:'TRANSFER_LOOP',visited:[...visited,nodeId],transcript};visited.push(nodeId);const commands=graphNodes(value.nodes[nodeId].graph),lines=commands.filter(node=>node.type==='say');for(const line of lines)transcript.push({line:interpolate(line.text||line['text-key']||'(weighted variant)',mocks),delay:line.delay||'default'});if(commands.some(node=>node.type==='end-dialogue'))return{status:'ENDED',visited,transcript};const transfer=commands.find(node=>node.type==='goto');if(!transfer)return{status:'IMPLICIT_END',visited,transcript};const jump=transfer.dialogue?`${transfer.dialogue}/${transfer.node||'start'}`:transfer.node;if(jump?.includes('/'))return{status:'TRANSFER',target:jump,visited,transcript};nodeId=jump;}return{status:steps>=64?'TRANSITION_LIMIT':'IMPLICIT_END',visited,transcript};}
function simulateQuest(value,mocks){const transitions=[],lifecycleNodes=[];for(const phase of value.phases||[]){const objectives=(phase.objectives||[]).map(objective=>({id:objective.id,type:objective.type,current:Number(mocks.objectives?.[objective.id]||0),required:Number(objective.amount||objective['required-progress']||parseFloat(objective.duration)||1),optional:Boolean(objective.optional),hidden:Boolean(objective.hidden)}));const complete=objectives.filter(item=>!item.optional).every(item=>item.current>=item.required);transitions.push({phase:phase.id,objectives,complete});lifecycleNodes.push(...graphNodes(phase['on-start']),...graphNodes(phase['on-complete']));if(!complete)break;const branch=(phase.branches||[]).find(item=>testCondition(item.when,mocks));if(branch)transitions.at(-1).next=branch['next-phase'];if(branch?.['next-phase']==='end')break;}return{requirements:testCondition(value.when,mocks),transitions,lifecycleNodes:lifecycleNodes.map(node=>node.type),repeatable:Boolean(value.repeatable),timer:value['time-limit']||null};}
function openBehavior(id){for(const [path,model] of state.documentModels){if(path.startsWith('behaviors/')&&model.root?.children?.find(child=>child.key==='id')?.value===id){selectFile(path);return;}}for(const path of state.files.keys())if(path.startsWith('behaviors/')){const text=state.files.get(path);if(new RegExp(`^id:\\s*["']?${id.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')}["']?\\s*$`,'m').test(text)){selectFile(path);return;}}yamlStatus.textContent=`Referenced subtree ${id} is not present in this project.`;}

function editorKind(){return state.selected?.startsWith('behaviors/')?'behavior':state.selected?.startsWith('npcs/')?'npc':state.selected?.startsWith('dialogues/')?'dialogue':state.selected?.startsWith('quests/')?'quest':state.selected?.startsWith('scripts/')?'script':'generic';}
function collectContainers(node,result=[]){if(node.kind==='sequence'||node.kind==='mapping')result.push(node);for(const child of node.children||[])collectContainers(child,result);return result;}

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
  const jump = pendingViewportJump?.resourceIdentity === full.resourceIdentity ? pendingViewportJump : null;
  if (jump) restoreNestedBookmark(jump);
  const visible = visibleGraphProjection(full);
  graphCanvas.setProjection(visible, layout);
  if (jump) { graphCanvas.restoreViewport(jump.viewport); pendingViewportJump = null; }
  graphCanvas.setDiagnosticPaths(visible.diagnostics.map(issue => issue.yamlPath));
}

function restoreNestedBookmark(target) {
  state.nestedGraph = target.nestedGraph ? structuredClone(target.nestedGraph) : null;
  workspaceShell.setNestedBreadcrumbs(state.nestedGraph
    ? [{ label: state.nestedGraph.label || 'Nested graph' }] : []);
}

function restoreViewportJump(target) {
  const resource = deriveResources(state.files).find(item => item.identity === target.resourceIdentity);
  if (!resource) { yamlStatus.textContent = `Viewport bookmark “${target.name}” references a missing resource.`; return; }
  pendingViewportJump = target; restoreNestedBookmark(target);
  if (workspaceShell.activeResource()?.identity !== resource.identity) { workspaceShell.openResource(resource); return; }
  const full = state.graphProjections.get(resource.identity);
  if (full) showGraphProjection(full, graphCanvas.snapshot());
}

async function applyGraphMutationResult(result, { label, context }) {
  if (!context.isCurrent()) return;
  const layout = graphCanvas.snapshot();
  source.value = result.content;
  if (result.rawFiles?.length) state.files = new Map(result.rawFiles.map(file => [file.path, file.content]));
  else state.files.set(context.selected, result.content);
  for (const patch of result.patches || []) if (patch.filePath !== context.selected) {
    state.documentModels.delete(patch.filePath); state.documentValidity.delete(patch.filePath);
    state.histories.delete(patch.filePath);
  }
  state.documentModels.set(context.selected, result.document);
  state.documentValidity.set(context.selected, true);
  state.graphProjections.set(context.resource.identity, result.projection);
  yamlStatus.textContent = `${label} applied as ${result.appliedOperationCount} authoritative operation${result.appliedOperationCount === 1 ? '' : 's'}; unrelated YAML was retained.`;
  appendGraphHistory(label, 'accepted', result.projectRevision || result.contentDigest);
  renderDocument(result.document);
  showGraphProjection(result.projection, layout);
  const focusedId = Object.values(result.identityRemap || {})[0];
  if (focusedId) graphCanvas.select(focusedId, false);
  graphCanvas.setStale(false);
  refreshWorkspaceResources(); refreshProjectReferences(); invalidateValidation();
  refreshDirty(); scheduleAutosave(); scheduleRecovery(); updateHistoryButtons();
}

function appendGraphHistory(label, kind, revision = null) {
  const list = document.querySelector('#inspector-history');
  if (list.children.length === 1 && list.firstElementChild?.textContent === 'No graph commands yet.') list.replaceChildren();
  const item = document.createElement('li'), time = new Date().toLocaleTimeString();
  item.textContent = `${workspaceShell.activeResource()?.label || 'Project'} · ${label} · ${kind === 'local' ? 'local presentation only' : 'server accepted'} · ${time}`
    + (revision ? ` · ${String(revision).slice(0, 12)}` : '');
  list.prepend(item); while (list.children.length > 100) list.lastElementChild.remove();
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
  const selected = (graphCanvas.projection?.nodes || []).filter(value => nodeIds.includes(value.id)
    && value.yamlPath && !value.custom && !(value.badges || []).includes('non-deletable'));
  const node = selected[0];
  const resource = workspaceShell.activeResource();
  if (!resource || !node) { yamlStatus.textContent = 'Select one or more complete editable graph nodes.'; return false; }
  if (resource.kind === 'behavior') {
    if (selected.length !== 1 || !node.yamlPath.startsWith('/root/children/')) {
      yamlStatus.textContent = 'Behaviour copy accepts one complete child branch.'; return false;
    }
    state.graphClipboard = { resourceKind: 'behavior', sourceFilePath: resource.path,
      yamlPath: node.yamlPath, nodeKind: node.kind, title: node.title };
    status.textContent = `Copied behavior node ${node.title}; choose a compatible behavior container and paste.`;
    return true;
  }
  if (!['npc','dialogue','quest','script'].includes(resource.kind)) return false;
  const descriptors = new Set(selected.map(value => explicitGraphPath(value, graphCanvas.projection)));
  if (descriptors.size !== 1 || descriptors.has(null) || selected.some(value => !value.yamlPath.includes('/nodes/'))) {
    yamlStatus.textContent = 'Graph copy requires nodes from one explicit event or reusable graph.'; return false;
  }
  const selectedIds = new Set(selected.map(value => value.id));
  const pins = new Map(selected.flatMap(value => (value.pins || []).map(pin => [pin.id, { nodeId: value.id,
    direction: pin.direction, label: pin.label }])));
  const edges = (graphCanvas.projection.edges || []).filter(edge => pins.has(edge.sourcePinId) && pins.has(edge.targetPinId)
    && selectedIds.has(pins.get(edge.sourcePinId).nodeId) && selectedIds.has(pins.get(edge.targetPinId).nodeId)
    && edge.id.startsWith('graph-edge:')).map(edge => ({ source: pins.get(edge.sourcePinId), target: pins.get(edge.targetPinId) }));
  state.graphClipboard = { resourceKind: 'graph', sourceFilePath: resource.path, sourceKind: resource.kind,
    nodes: selected.map(value => ({ id: value.id, yamlPath: value.yamlPath, title: value.title })), edges };
  status.textContent = `Copied ${selected.length} graph node${selected.length === 1 ? '' : 's'} and ${edges.length} internal wire${edges.length === 1 ? '' : 's'}.`;
  return true;
}

async function pasteGraphNode() {
  const copied = state.graphClipboard;
  if (!copied) { yamlStatus.textContent = 'The graph clipboard is empty.'; return; }
  if (copied.resourceKind === 'behavior') {
    if (graphCanvas.projection?.resourceKind !== 'behavior') {
      yamlStatus.textContent = 'The copied behavior node is not compatible with this resource.'; return;
    }
    const insertion = graphInsertion({ nodeKind: copied.nodeKind, destination: 'children' }, null);
    if (!insertion) { yamlStatus.textContent = 'Select a compatible behavior container before pasting.'; return; }
    const key = automaticNodeId({ label: `${copied.title}-copy` }, graphCanvas.projection?.nodes);
    await graphMutationClient.mutate([{ type: 'COPY', yamlPath: copied.yamlPath,
      sourceFilePath: copied.sourceFilePath, parentYamlPath: insertion.parentYamlPath,
      key, index: insertion.index }], 'Paste copied behavior node'); return;
  }
  if (!['npc','dialogue','quest','script'].includes(graphCanvas.projection?.resourceKind)) {
    yamlStatus.textContent = 'The copied graph node is not compatible with this resource.'; return;
  }
  const insertion = graphInsertion({ destination: 'graph', nodeKind: 'script-value' }, null);
  if (!insertion) { yamlStatus.textContent = 'Select the destination event or reusable graph before pasting.'; return; }
  const existing = new Set((graphCanvas.projection.nodes || []).filter(value => value.yamlPath?.startsWith(insertion.parentYamlPath + '/')).map(value => value.title));
  const keyById = new Map(), operations = [];
  for (const source of copied.nodes) {
    const base=String(source.title||'node').toLowerCase().replace(/[^a-z0-9_.-]+/g,'-').replace(/^-+|-+$/g,'')||'node';
    let key=`${base}-copy`,counter=2;while(existing.has(key))key=`${base}-copy-${counter++}`;existing.add(key);keyById.set(source.id,key);
    operations.push({type:'COPY',operationId:crypto.randomUUID(),yamlPath:source.yamlPath,
      sourceFilePath:copied.sourceFilePath,parentYamlPath:insertion.parentYamlPath,key});
  }
  const descriptor=insertion.parentYamlPath.slice(0,-'/nodes'.length);
  const prefix=`${graphCanvas.projection.resourceKind}:${graphCanvas.projection.resourceId}#graph:${hex(await crypto.subtle.digest('SHA-256',encoder.encode(descriptor))).slice(0,10)}:node:`;
  const token=value=>String(value||'').replace(/[^A-Za-z0-9_.:-]/g,'_');
  for(const edge of copied.edges){const sourceKey=keyById.get(edge.source.nodeId),targetKey=keyById.get(edge.target.nodeId);if(!sourceKey||!targetKey)continue;
    operations.push({type:'CONNECT',key:`wire-${crypto.randomUUID().replaceAll('-','').slice(0,12)}`,
      sourcePinId:`${prefix}${token(sourceKey)}:output:${token(edge.source.label)}`,
      targetPinId:`${prefix}${token(targetKey)}:input:${token(edge.target.label)}`});}
  const request=operations.length>1?[{type:'COMPOUND',operationId:crypto.randomUUID(),children:operations}]:operations;
  await graphMutationClient.mutate(request,`Paste ${copied.nodes.length} copied graph node${copied.nodes.length===1?'':'s'}`);
}

async function duplicateGraphNodes(nodeIds) { if (copyGraphNode(nodeIds)) await pasteGraphNode(); }

function renameGraphNode(nodeId) {
  const node=(graphCanvas.projection?.nodes||[]).find(value=>value.id===nodeId);
  if(!node?.yamlPath?.includes('/nodes/')||(node.badges||[]).includes('non-deletable'))return;
  const newName=prompt('New stable node ID',node.title)?.trim();if(!newName||newName===node.title)return;
  graphMutationClient.mutate([{type:'RENAME_NODE',operationId:crypto.randomUUID(),yamlPath:node.yamlPath,newName}],`Rename ${node.title}`);
}

function moveAllPinLinks(pin) {
  const candidates=(graphCanvas.projection?.nodes||[]).flatMap(node=>(node.pins||[]).map(value=>({node,pin:value})))
    .filter(entry=>entry.pin.id!==pin.id&&entry.pin.direction===pin.direction&&entry.pin.channel===pin.channel
      &&entry.pin.valueType===pin.valueType).slice(0,50);
  if(!candidates.length){yamlStatus.textContent='No compatible destination pin exists in this graph.';return;}
  const labels=candidates.map((entry,index)=>`${index+1}. ${entry.node.title} · ${entry.pin.label}`);
  const choice=prompt(`Move all links to which compatible pin?\n${labels.join('\n')}`,'1')?.trim();if(!choice)return;
  const selected=/^\d+$/.test(choice)?candidates[Number(choice)-1]:candidates.find(entry=>entry.pin.id===choice);
  if(!selected){yamlStatus.textContent='Choose one of the numbered compatible pins.';return;}
  graphMutationClient.mutate([{type:'MOVE_LINKS',sourcePinId:pin.id,targetPinId:selected.pin.id}],`Move links to ${selected.pin.label}`);
}

function handleGraphNodeAction({ type, node }) {
  if (type === 'DUPLICATE_NODE') {
    if(node.yamlPath.includes('/nodes/')){duplicateGraphNodes([node.id]);return;}
    graphMutationClient.mutate([{ type: 'DUPLICATE', operationId: crypto.randomUUID(), yamlPath: node.yamlPath }], 'Duplicate graph node'); return;
  }
  if (type === 'DELETE_NODE') { deleteGraphNodes([node.id]); return; }
  if (type === 'MOVE_NODE_EARLIER' || type === 'MOVE_NODE_LATER') {
    const parent = node.yamlPath.substring(0, node.yamlPath.lastIndexOf('/'));
    const siblings = (graphCanvas.projection?.nodes || []).filter(value => value.yamlPath
      && value.yamlPath.substring(0, value.yamlPath.lastIndexOf('/')) === parent
      && /^\d+$/.test(value.yamlPath.split('/').at(-1)))
      .sort((left, right) => Number(left.yamlPath.split('/').at(-1)) - Number(right.yamlPath.split('/').at(-1)));
    const index = siblings.findIndex(value => value.id === node.id), target = siblings[index + (type === 'MOVE_NODE_EARLIER' ? -1 : 1)];
    if (target) applyStructure(type === 'MOVE_NODE_EARLIER' ? 'MOVE_BEFORE' : 'MOVE_AFTER', node.yamlPath, target.yamlPath);
    return;
  }
  if (type === 'WRAP_NODE') {
    const nodeKind = prompt('Wrapper type: sequence, selector, priority-selector, parallel, invert, repeat, retry, timeout, cooldown, or checkpoint', 'sequence')?.trim();
    if (!nodeKind) return;
    const key = automaticNodeId({ label: `${nodeKind}-wrapper` }, graphCanvas.projection?.nodes);
    graphMutationClient.mutate([{ type: 'WRAP', operationId: crypto.randomUUID(), yamlPath: node.yamlPath, nodeKind, key }], 'Wrap graph node'); return;
  }
  if (type === 'UNWRAP_NODE') {
    graphMutationClient.mutate([{ type: 'UNWRAP', yamlPath: node.yamlPath }], 'Unwrap graph node'); return;
  }
  if (type === 'SET_DIALOGUE_START') {
    graphMutationClient.mutate([{ type: 'EDIT_FIELD', yamlPath: '/start', value: node.title }], 'Set dialogue start');
    return;
  }
  if(type==='ADD_SCRIPT_PARAMETER'){if(graphCanvas.projection?.resourceKind!=='script'||!['script-input','script-output'].includes(node.kind))return;const name=prompt('Parameter name','value')?.trim();if(!name)return;const valueType=prompt('Nominal type (for example integer, text, quest)','string')?.trim();if(!valueType)return;const required=confirm('Require callers to provide this parameter?');const defaultValue=required?null:prompt('Inline default (leave blank for none)','');graphMutationClient.mutate([{type:'ADD_SCRIPT_PARAMETER',parentYamlPath:`${graphCanvas.projection.rootYamlPath}/${node.kind==='script-input'?'inputs':'outputs'}`,key:name,valueType,required,defaultValue:defaultValue||null}],`Add ${name} parameter`);return;}
  if(['RENAME_VARIABLE','CHANGE_VARIABLE_TYPE','DELETE_VARIABLE'].includes(type)){const variable=node.fields?.find(field=>field.label==='variable')?.value;if(!variable){yamlStatus.textContent='This node does not identify an execution-local variable.';return;}const descriptor=explicitGraphPath(node,graphCanvas.projection);if(type==='RENAME_VARIABLE'){const newName=prompt('New variable name',variable)?.trim();if(newName&&newName!==variable)graphMutationClient.mutate([{type,parentYamlPath:descriptor,parameterName:variable,newName}],`Rename ${variable}`);}else if(type==='CHANGE_VARIABLE_TYPE'){const valueType=prompt('New nominal type','string')?.trim();if(valueType)graphMutationClient.mutate([{type,parentYamlPath:descriptor,parameterName:variable,valueType}],`Change ${variable} type`);}else if(confirm(`Delete variable ${variable}? Getter and setter nodes must be removed first.`))graphMutationClient.mutate([{type,parentYamlPath:descriptor,parameterName:variable}],`Delete ${variable}`);return;}
  if(type==='REPLACE_NODE'){const nodeKind=prompt('Replacement node type (pins must match uniquely)',node.subtitle?.replace(/^(flow-|script-)/,'')||'message')?.trim();if(nodeKind)graphMutationClient.mutate([{type:'REPLACE_NODE',yamlPath:node.yamlPath,nodeKind}],`Replace ${node.title}`);return;}
  if(type==='RENAME_NODE'){renameGraphNode(node.id);return;}
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
    const descriptor = explicitGraphPath(node, graphCanvas.projection);
    const selectedPaths = (graphCanvas.projection?.nodes || []).filter(candidate =>
      (candidate.id === node.id || graphCanvas.selection.has(candidate.id))
      && candidate.yamlPath?.startsWith(`${descriptor}/nodes/`)
      && explicitGraphPath(candidate, graphCanvas.projection) === descriptor)
      .map(candidate => candidate.yamlPath);
    executeProjectOperation('extract-script', { sourcePath: resource.path,
      sourceYamlPath: node.yamlPath, sourceYamlPaths: selectedPaths, scriptId }, 'script', scriptId)
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
  if (type === 'TOGGLE_BOOKMARK') { graphCanvas.toggleBookmark(node.id); workspaceShell.renderBrowser(); return; }
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

function handleScriptParameterAction({ type, port, neighbor }) {
  const parentYamlPath = port.yamlPath.substring(0, port.yamlPath.lastIndexOf('/'));
  if (type === 'RENAME_SCRIPT_PARAMETER') {
    const newName = prompt(`Rename parameter ${port.label}`, port.label)?.trim();
    if (!newName || newName === port.label) return;
    graphMutationClient.mutate([{ type, parentYamlPath, parameterName: port.label, newName }],
      `Rename parameter ${port.label}`); return;
  }
  if (type === 'CHANGE_SCRIPT_PARAMETER_TYPE') {
    const valueType = prompt(`Nominal type for ${port.label}`, port.valueType)?.trim();
    if (!valueType || valueType === port.valueType) return;
    graphMutationClient.mutate([{ type, yamlPath: port.yamlPath, parentYamlPath, parameterName: port.label, valueType }],
      `Change ${port.label} type`); return;
  }
  if (type === 'REORDER_SCRIPT_PARAMETER' && neighbor) {
    const before = (neighbor.order ?? 0) < (port.order ?? 0);
    graphMutationClient.mutate([{ type, yamlPath: port.yamlPath, parentYamlPath,
      parameterName: port.label, [before ? 'beforePortId' : 'afterPortId']: neighbor.id }],
      `Reorder parameter ${port.label}`); return;
  }
  if (type === 'DELETE_SCRIPT_PARAMETER') {
    const scriptId = graphCanvas.projection?.resourceId || '';
    const callFiles = [...state.files].filter(([, content]) => content.includes(`script: ${scriptId}`)
      && new RegExp(`(^|\\n)\\s*${port.label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*:`).test(content)).length;
    const wires = (graphCanvas.projection?.edges || []).filter(edge =>
      edge.sourcePinId === port.id || edge.targetPinId === port.id).length;
    if (!confirm(`Delete parameter ${port.label}? Preview: ${wires} local connection${wires === 1 ? '' : 's'} and bindings in ${callFiles} caller file${callFiles === 1 ? '' : 's'} will be removed atomically.`)) return;
    graphMutationClient.mutate([{ type, parentYamlPath, parameterName: port.label }], `Delete parameter ${port.label}`);
  }
}

function promotePinToVariable(pin) {
  if (!pin || pin.channel !== 'DATA') return;
  const name = prompt('Variable name', String(pin.label || 'value').toLowerCase().replace(/[^a-z0-9_.-]+/g, '-'))?.trim();
  if (!name) return;
  if (!/^[a-z0-9][a-z0-9_.-]{0,127}$/.test(name)) {
    yamlStatus.textContent = 'Variable names use lowercase letters, digits, dot, underscore, and hyphen.'; return;
  }
  const incoming = (graphCanvas.projection?.edges || []).filter(edge => edge.targetPinId === pin.id);
  if (String(pin.direction).toUpperCase() === 'INPUT' && incoming.length
      && !confirm(`Replace ${incoming.length} existing input link${incoming.length === 1 ? '' : 's'} with a getter for ${name}?`)) return;
  const operations = incoming.map(edge => ({ type: 'DISCONNECT', edgeId: edge.id }));
  operations.push({ type: 'PROMOTE_TO_VARIABLE', key: name,
    [String(pin.direction).toUpperCase() === 'OUTPUT' ? 'sourcePinId' : 'targetPinId']: pin.id });
  graphMutationClient.mutate(operations, `Promote ${pin.label} to variable ${name}`);
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
    const parentPath = targetPath?.substring(0, targetPath.lastIndexOf('/'));
    if (!parentPath || path.substring(0, path.lastIndexOf('/')) !== parentPath) {
      yamlStatus.textContent = 'Reorder rejected: source and stable neighbor are not in the same projected sequence.'; return;
    }
    const targetNode = graphCanvas.projection?.nodes.find(node => node.yamlPath === targetPath);
    const neighborPort = targetNode?.pins?.find(pin => String(pin.direction).toUpperCase() === 'INPUT') || targetNode?.pins?.[0];
    const parentNode = graphCanvas.projection?.nodes.find(node => node.pins?.some(pin =>
      String(pin.direction).toUpperCase() === 'OUTPUT' && pin.yamlPath === parentPath && pin.label === '+ child'));
    const parentPort = parentNode?.pins?.find(pin => String(pin.direction).toUpperCase() === 'OUTPUT' && pin.yamlPath === parentPath && pin.label === '+ child');
    if (!neighborPort || !parentPort) { yamlStatus.textContent = 'Reorder rejected: stable parent or neighbor ports are no longer projected.'; return; }
    const sourceNode = graphCanvas.projection?.nodes.find(node => node.yamlPath === path);
    const edit = { type: 'REORDER', yamlPath: path, targetYamlPath: targetPath,
      parentYamlPath: parentPath, parentPortId: parentPort.id, nodeId: sourceNode?.id, expectedSourceRange: sourceNode?.range };
    edit[operation === 'MOVE_AFTER' ? 'afterPortId' : 'beforePortId'] = neighborPort.id;
    return graphMutationClient.mutate([edit], 'Reorder graph nodes');
  }
  yamlStatus.textContent = `Unsupported structural graph command: ${operation}`;
}
async function extractSubtree(path){if(!state.connected)return;const behaviorId=prompt('New namespaced subtree behavior ID','namespace:subtree');if(!behaviorId)return;const scope=state.documentModels.get(state.selected)?.root?.children?.find(child=>child.key==='scope')?.value||'player';let filename;try{filename=await requestSafePath('behavior',behaviorId);}catch(error){yamlStatus.textContent=`Cannot extract: ${error.message}`;return;}if(state.files.has(filename)){yamlStatus.textContent=`Cannot extract: ${filename} already exists.`;return;}recordHistory();try{const response=await fetch(sessionApi('/documents/extract-subtree'),{method:'POST',headers:authorizedHeaders({'Content-Type':'application/json'}),body:JSON.stringify({content:source.value,path,behaviorId,scope})});if(!response.ok)throw new Error(await response.text()||`HTTP ${response.status}`);const result=await response.json();source.value=result.source.content;state.files.set(state.selected,result.source.content);state.documentModels.set(state.selected,result.source);state.files.set(filename,result.extractedContent);renderDocument(result.source);refreshDirty();scheduleAutosave();scheduleRecovery();refreshProjectReferences();const resource=deriveResources(state.files).find(item=>item.kind==='behavior'&&item.id===behaviorId);if(resource)workspaceShell.openResource(resource);}catch(error){state.histories.get(state.selected)?.undo.pop();yamlStatus.textContent=`Subtree extraction rejected: ${error.message}`;}}

function refreshDirty() {
  flushSelected();
  refreshWorkspaceResources();
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
  const globalCreate = document.querySelector('#global-create');
  globalCreate.disabled = !canEdit;
  globalCreate.title = createOpen.title;
  document.querySelector('#status-capabilities').textContent = state.verified?.capabilities?.length
    ? state.verified.capabilities.join(', ') : 'View only';
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
  const folders = [...(snapshot.folders || [])].sort();
  const manifest = files.find(file => file.path === '.persona/project.yml');
  const emptyDigest = hex(await crypto.subtle.digest('SHA-256', new Uint8Array()));
  const manifestDigest = manifest?.sha256 || emptyDigest;
  if (manifestDigest !== snapshot.manifestDigest) throw new Error('Invalid project manifest digest');
  const parsedFolders = [...parseProjectFolders(manifest?.content)].sort();
  if (parsedFolders.length !== folders.length || parsedFolders.some((folder, index) => folder !== folders[index]))
    throw new Error('Signed folder metadata does not match .persona/project.yml');
  let input = `${snapshot.protocolVersion}\n${snapshot.sessionId}\n${snapshot.revision}\n${snapshot.contentFormatVersion}\n${snapshot.createdAt}\n${snapshot.installationPublicKey}\n${snapshot.manifestDigest}`;
  for (const folder of folders) input += `\nfolder:${folder}`;
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
  renderProject(files, `Connected securely. Loaded ${files.length} signed project file${files.length === 1 ? '' : 's'}.`, snapshot.revision, folders, manifestDigest);
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
  const manifest = result.files.find(file => file.path === '.persona/project.yml');
  state.manifestDigest = manifest?.sha256 || state.manifestDigest;
  state.folders = parseProjectFolders(manifest?.content);
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
  document.querySelector('#status-validation').textContent = 'Stale';
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
  graphCanvas.addTraceEntries(snapshot.traces||[]);
  if(snapshot.server)state.liveData.server=snapshot.server;state.liveRevision=snapshot.revision;document.querySelector('#status-live').textContent=`Live r${snapshot.revision}`;renderLive();clearTimeout(state.liveStaleTimer);state.liveStaleTimer=setTimeout(()=>{liveStatus.textContent='Live data is stale; waiting for the connected server…';liveDialog.classList.add('stale');},5000);}
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
      [...state.liveData.npcs.values()].map(value=>`NPC ${value.definitionId}/${value.instanceId} · ${value.presentation}/${value.projectionState}`),
      graphCanvas.traceRing.map(value=>`Trace ${new Date(value.at).toLocaleTimeString()} · ${value.node} · ${value.status}`));
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
async function subscribeLive(){if(state.liveSubscription)return;state.liveSubscription=crypto.randomUUID();state.liveRevision=0;const topics=['PLAYERS','NPCS','BEHAVIORS','QUESTS','DIALOGUES','TRACES','SERVER'];if(state.verified.capabilities.includes('MEMORY_VIEW'))topics.push('MEMORIES');
  const sent=await sendSocket('LIVE_SUBSCRIBE',{protocolVersion,subscriptionId:state.liveSubscription,topics,filter:{playerIds:[],npcDefinitions:[],npcInstances:[],worlds:[],tracepoints:[...graphCanvas.tracepoints],watchedPins:[...graphCanvas.watchedPins]},refreshMillis:500});if(!sent){state.liveSubscription=null;throw new Error('Live socket is not connected');}liveStatus.textContent='Waiting for Persona to authorize the selected tracepoints and watches…';}
async function refreshLiveSubscription(){const previous=state.liveSubscription;if(!previous)return;await sendSocket('LIVE_UNSUBSCRIBE',{protocolVersion,subscriptionId:previous});state.liveSubscription=null;state.liveRevision=0;for(const values of Object.values(state.liveData))if(values instanceof Map)values.clear();graphCanvas.traceRing=[];await subscribeLive();}
liveOpen.addEventListener('click',()=>{liveDialog.showModal();subscribeLive().catch(error=>{liveStatus.textContent=`Live subscription failed: ${error.message}`;});});
document.querySelector('#live-close').addEventListener('click',()=>liveDialog.close());
document.querySelector('#live-dock-subscribe').addEventListener('click',()=>subscribeLive().catch(error=>{document.querySelector('#live-dock-status').textContent=`Live subscription failed: ${error.message}`;}));
document.querySelector('#live-dock-expand').addEventListener('click',()=>liveOpen.click());

async function saveDraft() {
  if (!state.connected) return;
  const generation = state.connectionGeneration;
  if (state.saving) { state.saveAgain = true; return; }
  state.saving = true;
  document.querySelector('#status-save').textContent = 'Saving…';
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
    document.querySelector('#status-save').textContent = saved.stale ? 'Recovery required' : 'Saved';
    appendGraphHistory('Draft autosave', 'accepted', saved.revision || saved.draftId);
    requestValidation(saved.draftId);
  } catch (error) {
    if (generation !== state.connectionGeneration) return;
    status.textContent = `Draft autosave failed: ${error.message}`;
    document.querySelector('#status-save').textContent = 'Unsaved—retrying';
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
    document.querySelector('#status-validation').textContent = 'Checking…';
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
  document.querySelector('#status-validation').textContent = validationHeading(result);
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
      state.connectionFailure = null;
      scheduleHeartbeat();
      refreshCapabilities();
    } catch (error) {
      state.connectionFailure = `Connected, but authoritative project refresh failed: ${error.message}`;
      lockWorkspace(state.connectionFailure);
      state.socket.close(4001, snapshotFailureCloseReason(error));
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
  state.socket.onclose = event => {
    clearTimeout(state.heartbeat);
    state.socket = null;
    if (Date.now() >= Date.parse(verified.expiresAt)) {
      lockWorkspace('Editor session expired. Open a new editor session from Persona.');
      return;
    }
    const delay = Math.min(30000, 1000 * (2 ** Math.min(state.reconnectAttempt++, 5)));
    const closeReason = event.reason
      ? `Server connection closed (${event.code}): ${event.reason}. Editing is locked while reconnecting…`
      : 'Server connection interrupted; editing is locked while reconnecting…';
    lockWorkspace(state.connectionFailure || closeReason);
    document.querySelector('#status-connection').textContent = 'Reconnecting';
    if(state.liveSubscription){liveStatus.textContent='Live data is stale; reconnecting…';liveDialog.classList.add('stale');}
    state.reconnectTimer = setTimeout(connectSocket, delay);
  };
  state.socket.onerror = () => lockWorkspace('Server connection interrupted; editing is locked.');
}

reconnectNow.addEventListener('click', () => {
  if (!state.verified) return;
  if (state.socket && state.socket.readyState < WebSocket.CLOSING) state.socket.close();
  state.socket = null;
  state.connectionFailure = null;
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
const createFolderInput = document.querySelector('#create-folder');
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
  const root = createKind.value === 'behavior' ? 'behaviors' : `${createKind.value}s`;
  if (!createFolderInput.value.startsWith(root)) createFolderInput.value = root;
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
    createPath.value = `${createFolderInput.value}/${value.path.split('/').at(-1)}`;
    createPreview.value = value.content; createSubmit.disabled = !draftEditAllowed();
  } catch (error) { if (generation === createPreviewGeneration) createError.textContent = error.message; }
}

function openCreation(kind = 'npc', id = '', folder = null) {
  try { requireDraftEdit(); }
  catch (error) { status.textContent = error.message; return; }
  createForm.reset(); createKind.value = kind; createId.value = id;
  createFolderInput.value = folder || (kind === 'behavior' ? 'behaviors' : `${kind}s`);
  configureCreateIdentity();
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

function kindForFolder(folder) {
  return ({ npcs: 'npc', dialogues: 'dialogue', quests: 'quest', behaviors: 'behavior', scripts: 'script' })[folder.split('/')[0]] || 'npc';
}

async function createProjectFolder(parent) {
  const segment = prompt(`New folder beneath ${parent}:`, 'new-folder');
  if (!segment) return;
  try {
    const folder = `${parent}/${segment}`;
    await executeProjectOperation('folders/create', { folder, expectedManifestDigest: state.manifestDigest });
    workspaceShell.selectFolder(folder, false);
  } catch (error) { status.textContent = `Folder creation failed: ${error.message}`; }
}

async function moveProjectFolder(folder, destination = null) {
  const basename = folder.split('/').at(-1);
  const replacementFolder = destination ? `${destination}/${basename}`
    : prompt(`Move or rename ${folder} to:`, folder);
  if (!replacementFolder || replacementFolder === folder) return;
  try {
    await executeProjectOperation('folders/move', { folder, replacementFolder,
      expectedManifestDigest: state.manifestDigest });
    workspaceShell.selectFolder(replacementFolder, false);
  } catch (error) { status.textContent = `Folder move failed: ${error.message}`; }
}

async function deleteProjectFolder(folder) {
  try {
    requireDraftEdit();
    const files = await contentFiles(), expectedRevision = await projectRevision(files);
    const body = { files, expectedRevision, expectedManifestDigest: state.manifestDigest, folder };
    const previewResponse = await fetch(sessionApi('/projects/folders/delete-preview'), {
      method: 'POST', headers: authorizedHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body)
    });
    const preview = await previewResponse.json();
    if (!previewResponse.ok) throw new Error(preview.message || `HTTP ${previewResponse.status}`);
    if (preview.blockingReferences?.length) throw new Error(`Blocked by ${preview.blockingReferences.length} external inbound reference(s).`);
    const resources = preview.resources.length ? `\n\n${preview.resources.join('\n')}` : '\n\nThe folder is empty.';
    if (!confirm(`Delete ${folder} and ${preview.resources.length} contained resource file(s)?${resources}`)) return;
    await executeProjectOperation('folders/delete', { folder, expectedManifestDigest: preview.manifestDigest });
  } catch (error) { status.textContent = `Folder deletion failed: ${error.message}`; }
}

async function moveResourceToFolder(resource, folder) {
  const replacementPath = `${folder}/${resource.path.split('/').at(-1)}`;
  if (replacementPath === resource.path) return;
  try { await executeProjectOperation('move', { kind: resource.kind, id: resource.id, replacementPath }, resource.kind, resource.id); }
  catch (error) { status.textContent = `Resource move failed: ${error.message}`; }
}

async function copyResourceToFolder(resource, folder) {
  const replacementId = prompt(`Copy ${resource.id} into ${folder} with new ID:`, `${resource.id}-copy`);
  if (!replacementId) return;
  try {
    const safe = await requestSafePath(resource.kind, replacementId);
    const replacementPath = `${folder}/${safe.split('/').at(-1)}`;
    await executeProjectOperation('duplicate', { kind: resource.kind, sourceId: resource.id,
      replacementId, replacementPath }, resource.kind, replacementId);
  } catch (error) { status.textContent = `Resource copy failed: ${error.message}`; }
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
    const renameFile = confirm('Also rename the file while keeping it in this folder?');
    const safePath = renameFile ? await requestSafePath(resource.kind, replacementId) : resource.path;
    const replacementPath = renameFile ? `${resource.folder}/${safePath.split('/').at(-1)}` : resource.path;
    await executeProjectOperation('rename', { kind: resource.kind, currentId: resource.id, replacementId, renameFile, replacementPath }, resource.kind, replacementId);
  } catch (error) { status.textContent = `Rename failed: ${error.message}`; }
});

moveResourceButton.addEventListener('click', async () => {
  const resource = workspaceShell.activeResource(); if (!resource || moveResourceButton.disabled) return;
  try {
    const suggested = `${workspaceShell.selectedFolder}/${resource.path.split('/').at(-1)}`;
    const replacementPath = prompt('Validated destination path beneath the same kind root:', suggested);
    if (!replacementPath) return;
    if (replacementPath === resource.path) {
      status.textContent = `${resource.id} already uses that path.`; return;
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
  publishButton.disabled = true; status.textContent = 'Sending the validated content to Persona…';
  try {
    const response = await fetch(`/api/v1/editor/sessions/${sessionId}/publishes`, {
      method: 'POST', headers: { Authorization: `Bearer ${state.verified.browserLeaseToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ protocolVersion, draftId: state.draftId,
        proposedRevision: state.validationResult.proposedRevision })
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const publish = await response.json();
    validationPanel.hidden = false;
    validationSummary.textContent = `Publishing ${publish.publishId.substring(0, 8)} through the trusted Persona session…`;
    status.textContent = 'Persona is revalidating, backing up, and applying the candidate.';
    pollPublish(publish.publishId);
  } catch (error) { status.textContent = `Publication request failed: ${error.message}`; updatePublishButton(); }
});

function setEditorView(view) {
  if (!['visual', 'split', 'yaml'].includes(view)) view = 'visual';
  editorElement.dataset.view = view;
  for (const name of ['visual', 'split', 'yaml'])
    document.querySelector(`#view-${name}`).setAttribute('aria-pressed', String(name === view));
  localStorage.setItem('persona:editor-view:v1', view);
  panelLayout.setCenterSplit(view);
  if (view === 'split' || view === 'yaml') panelLayout.selectDock('yaml', true);
  graphCanvas.schedule();
}
for (const view of ['visual', 'split', 'yaml']) document.querySelector(`#view-${view}`).addEventListener('click', () => setEditorView(view));
setEditorView(panelLayout.value.centerSplit || localStorage.getItem('persona:editor-view:v1') || 'visual');
const bottomDock = new BottomDock(panelLayout);
function showOutput(name) {
  bottomDock.show(name);
}
document.querySelector('#references-panel-open').addEventListener('click', loadReferenceGraph);
document.querySelector('#references-tools-open').addEventListener('click', () => referencesOpen.click());
document.querySelector('#semantic-panel-open').addEventListener('click', () => loadSemanticDiff(false));
commandDispatcher.bindButton(document.querySelector('#graph-add'), 'graph.add', () => ({ sourcePin: null }));
commandDispatcher.bindButton(document.querySelector('#graph-copy'), 'graph.copy', () => [...graphCanvas.selection]);
commandDispatcher.bindButton(document.querySelector('#graph-paste'), 'graph.paste');
commandDispatcher.bindButton(document.querySelector('#graph-auto-layout'), 'graph.auto-layout');
commandDispatcher.bindButton(document.querySelector('#graph-align-left'), 'graph.align-left');
commandDispatcher.bindButton(document.querySelector('#graph-align-center'), 'graph.align-center');
commandDispatcher.bindButton(document.querySelector('#graph-align-right'), 'graph.align-right');
commandDispatcher.bindButton(document.querySelector('#graph-align-top'), 'graph.align-top');
commandDispatcher.bindButton(document.querySelector('#graph-align-middle'), 'graph.align-middle');
commandDispatcher.bindButton(document.querySelector('#graph-align-bottom'), 'graph.align-bottom');
commandDispatcher.bindButton(document.querySelector('#graph-distribute-horizontal'), 'graph.distribute-horizontal');
commandDispatcher.bindButton(document.querySelector('#graph-distribute-vertical'), 'graph.distribute-vertical');
commandDispatcher.bindButton(document.querySelector('#graph-snap'), 'graph.snap');
commandDispatcher.bindButton(document.querySelector('#graph-tidy'), 'graph.tidy');
commandDispatcher.bindButton(document.querySelector('#graph-comment'), 'graph.comment');
commandDispatcher.bindButton(document.querySelector('#graph-group'), 'graph.group');
commandDispatcher.bindButton(document.querySelector('#graph-color'), 'graph.color');
commandDispatcher.bindButton(document.querySelector('#graph-focus-upstream'), 'graph.focus-upstream');
commandDispatcher.bindButton(document.querySelector('#graph-focus-downstream'), 'graph.focus-downstream');
commandDispatcher.bindButton(document.querySelector('#graph-focus-connected'), 'graph.focus-connected');
document.querySelector('#global-create').addEventListener('click', () => createOpen.click());
document.querySelector('#global-search').addEventListener('click', () => workspaceShell.showQuickOpen());
document.querySelector('#global-preview').addEventListener('click', () => showOutput('simulation'));
document.querySelector('#global-validate').addEventListener('click', () => {
  if (state.draftId) requestValidation(state.draftId);
  else status.textContent = 'Save a draft before requesting authoritative validation.';
});
document.querySelector('#global-session').addEventListener('click', () => {
  const expires = state.verified?.expiresAt ? new Date(state.verified.expiresAt).toLocaleTimeString() : 'not authenticated';
  status.textContent = `Session ${sessionId.slice(0, 8)} · expires ${expires} · ${(state.verified?.capabilities || []).join(', ') || 'no capabilities'}`;
});
new MutationObserver(() => { document.querySelector('#global-save').textContent = document.querySelector('#status-save').textContent; })
  .observe(document.querySelector('#status-save'), { childList: true, characterData: true, subtree: true });
document.querySelector('#rail-relationship-map').addEventListener('click', event => {
  document.querySelectorAll('#navigation-rail button').forEach(button => { button.classList.remove('active'); button.removeAttribute('aria-current'); });
  event.currentTarget.classList.add('active'); event.currentTarget.setAttribute('aria-current', 'page'); relationshipMapOpen.click();
});
for (const view of ['library', 'bookmarks', 'recents']) document.querySelector(`#rail-${view}`)
  .addEventListener('click', () => { panelLayout.show('browser'); workspaceShell.showView(view); });
let paletteContext = { type: 'commands' };
const PALETTE_PREFERENCE_KEY = 'persona:graph-palette:v1';
let palettePreferences = loadPalettePreferences();
paletteContextSensitive.checked = palettePreferences.contextSensitive;

function loadPalettePreferences() {
  try {
    const stored = JSON.parse(localStorage.getItem(PALETTE_PREFERENCE_KEY) || '{}');
    return { contextSensitive: stored.contextSensitive !== false,
      favorites: Array.isArray(stored.favorites) ? stored.favorites.slice(0, 100) : [],
      recent: Array.isArray(stored.recent) ? stored.recent.slice(0, 30) : [] };
  } catch { return { contextSensitive: true, favorites: [], recent: [] }; }
}

function savePalettePreferences() {
  try { localStorage.setItem(PALETTE_PREFERENCE_KEY, JSON.stringify(palettePreferences)); }
  catch { /* The palette remains usable when private storage is unavailable. */ }
}

function paletteNodeId(definition) {
  return `${graphCanvas.projection?.resourceKind || 'graph'}:${definition.nodeKind}:${definition.extensionType || definition.valueType || ''}`;
}

function paletteNodeCategory(definition) {
  if (definition.extensionType || definition.nodeKind.startsWith('extension-')) return 'Extensions';
  if (['sequence', 'branch', 'choice', 'switch', 'random', 'gate', 'do-once', 'do-n', 'for', 'for-each', 'while',
    'selector', 'priority-selector', 'parallel'].includes(definition.nodeKind)) return 'Flow Control';
  if (definition.nodeKind.startsWith('get-') || definition.nodeKind.includes('-to-') || definition.nodeKind === 'to-string') return 'Values';
  if (['dialogue-entry', 'quest-phase', 'quest-objective', 'npc-anchor'].includes(definition.nodeKind)) return 'Structure';
  if (definition.nodeKind.includes('behavior') || definition.nodeKind.includes('dialogue') || definition.nodeKind.includes('quest')) return 'References';
  return 'Actions';
}

function paletteSearchText(definition) {
  return [definition.label, definition.nodeKind, paletteNodeCategory(definition), definition.destination,
    ...(definition.keywords || [])].filter(Boolean).join(' ').toLowerCase();
}

function graphNodeDefinitions() {
  return nodeDefinitions(graphCanvas.projection?.resourceKind, state.editorSchemas.values());
}

function graphNodeByPin(pin) {
  return (graphCanvas.projection?.nodes || []).find(node => node.id === pin?.nodeId) || null;
}

function explicitGraphPath(node, projection) {
  if (!node) return projection?.resourceKind === 'script' ? projection.rootYamlPath : null;
  if (node.kind === 'event') return node.yamlPath;
  if (node.kind === 'script-input' || node.kind === 'script-output') return projection.rootYamlPath;
  const marker = node.yamlPath?.lastIndexOf('/nodes/');
  if (marker >= 0) return node.yamlPath.slice(0, marker);
  if (node.kind === 'dialogue-entry') return `${node.yamlPath}/graph`;
  if (node.kind === 'quest-phase' || node.kind === 'quest-objective') {
    return (projection.nodes || []).find(candidate => candidate.kind === 'event'
      && candidate.yamlPath?.startsWith(`${node.yamlPath}/on-start`))?.yamlPath || `${node.yamlPath}/on-start`;
  }
  if (node.kind === 'quest') return `${projection.rootYamlPath}/on-start`.replace(/^\/\//, '/');
  if (node.kind === 'npc-configuration') return `${projection.rootYamlPath}/on-click`.replace(/^\/\//, '/');
  return null;
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
  } else if (['graph', 'script'].includes(definition.destination) && ['dialogue', 'quest', 'npc', 'script'].includes(kind)) {
    let owner = sourceNode || selected;
    if (!owner && kind !== 'script') owner = projection.nodes.find(node => node.kind === 'event');
    const descriptor = explicitGraphPath(owner, projection);
    if (descriptor != null) parentYamlPath = `${descriptor}/nodes`.replace(/^\/\//, '/');
  } else if (kind === 'dialogue') {
    if (definition.nodeKind === 'dialogue-entry') parentYamlPath = `${projection.rootYamlPath}/nodes`.replace(/^\/\//, '/');
    else {
      let owner = sourceNode || selected;
      if (owner && owner.kind !== 'dialogue-entry') owner = projection.nodes.find(node =>
        node.kind === 'dialogue-entry' && owner.yamlPath.startsWith(node.yamlPath + '/'));
      if (!owner) return null;
      parentYamlPath = `${owner.yamlPath}/graph/nodes`;
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
  let definitions = graphNodeDefinitions();
  if (palettePreferences.contextSensitive && paletteContext.edge && graphCanvas.projection?.resourceKind === 'behavior')
    definitions = definitions.filter(definition => ['sequence', 'selector', 'priority-selector', 'parallel',
      'checkpoint', 'cooldown'].includes(definition.nodeKind));
  return compatibleDefinitions(definitions, sourcePin,
    definition => Boolean(graphInsertion(definition, sourcePin)), palettePreferences.contextSensitive);
}

function occupiedInsertionKeys(parentYamlPath) {
  const parent = findModelNode(state.documentModels.get(state.selected)?.root, parentYamlPath);
  const parsedKeys = parent?.kind === 'mapping' ? (parent.children || []).map(child => child.key).filter(Boolean) : [];
  return [...new Set([...parsedKeys, ...yamlMappingKeys(state.files.get(state.selected)), ...yamlMappingKeys(source.value)])];
}

function placeCreatedGraphNode(result, key, position) {
  if (!result || !Number.isFinite(position?.x) || !Number.isFinite(position?.y)) return;
  const createdIds = Object.values(result.identityRemap || {});
  const node = (graphCanvas.projection?.nodes || []).find(candidate => createdIds.includes(candidate.id))
    || (graphCanvas.projection?.nodes || []).find(candidate => candidate.title === key);
  if (!node) return;
  graphCanvas.positions[node.id] = { x: position.x, y: position.y };
  graphCanvas.select(node.id, false);
  graphCanvas.schedule();
  graphCanvas.changed();
}

async function insertGraphNode(definition) {
  const sourcePin = paletteContext.sourcePin, insertion = graphInsertion(definition, sourcePin);
  const matchingInput = matchingDefinitionInput(definition, sourcePin);
  const connectSource = Boolean(matchingInput);
  const position = paletteContext.position;
  if (!insertion) { yamlStatus.textContent = 'No compatible authoritative YAML container exists at this destination.'; return; }
  let key = null;
  if (definition.requiresKey || definition.destination === 'graph' || ['behavior', 'script'].includes(graphCanvas.projection.resourceKind)) {
    const occupied = occupiedInsertionKeys(insertion.parentYamlPath).map(title => ({ title }));
    key = automaticNodeId(definition, [...(graphCanvas.projection?.nodes || []), ...occupied]);
  }
  if (paletteContext.edge && graphCanvas.projection.resourceKind === 'behavior') {
    const edge = paletteContext.edge;
    const wrapper = { type: 'WRAP', operationId: crypto.randomUUID(), yamlPath: edge.targetYamlPath,
      nodeKind: definition.nodeKind, key };
    const result = await graphMutationClient.mutate([{ type: 'INSERT_ON_WIRE', edgeId: edge.id,
      sourcePinId: edge.sourcePinId, targetPinId: edge.targetPinId, children: [wrapper] }],
    `Insert ${definition.label} on wire`);
    placeCreatedGraphNode(result, key, position);
    return;
  }
  const operations = [{ type: 'INSERT', operationId: crypto.randomUUID(), parentYamlPath: insertion.parentYamlPath,
    nodeKind: definition.nodeKind, key, value: definition.extensionType || definition.valueType || null, index: insertion.index }];
  if (connectSource && ['dialogue-entry', 'quest-phase'].includes(definition.nodeKind)) {
    const targetNodeId = `${graphCanvas.projection.resourceKind}:${graphCanvas.projection.resourceId}#${key}`;
    operations.push({ type: 'CONNECT', sourcePinId: sourcePin.id, targetPinId: `${targetNodeId}:in` });
  }
  if (connectSource && definition.destination === 'graph' && matchingInput?.label) {
    const descriptor = insertion.parentYamlPath.slice(0, -'/nodes'.length);
    const graphHash = hex(await crypto.subtle.digest('SHA-256', encoder.encode(descriptor))).slice(0, 10);
    const pinToken = value => String(value || '').replace(/[^A-Za-z0-9_.:-]/g, '_');
    const targetNodeId = `${graphCanvas.projection.resourceKind}:${graphCanvas.projection.resourceId}#graph:${graphHash}:node:${pinToken(key)}`;
    operations.push({ type: 'CONNECT', key: `wire-${crypto.randomUUID().replaceAll('-', '').slice(0, 12)}`,
      sourcePinId: sourcePin.id, targetPinId: `${targetNodeId}:input:${pinToken(matchingInput.label)}` });
  }
  const requestOperations = operations.length > 1
    ? [{ type: 'COMPOUND', operationId: crypto.randomUUID(), children: operations }] : operations;
  const result = await graphMutationClient.mutate(requestOperations, connectSource ? 'Insert and connect node' : `Insert ${definition.label}`);
  placeCreatedGraphNode(result, key, position);
  const nodeId = paletteNodeId(definition);
  palettePreferences.recent = [nodeId, ...palettePreferences.recent.filter(value => value !== nodeId)].slice(0, 30);
  savePalettePreferences();
}

function renderCommands() {
  const query = paletteSearch.value.trim().toLowerCase();
  const graphEntries = paletteContext.type === 'graph' ? availableGraphNodes().map(definition => {
    const insertable = Boolean(graphInsertion(definition, paletteContext.sourcePin));
    return { id: paletteNodeId(definition), name: definition.label, search: paletteSearchText(definition),
      category: paletteNodeCategory(definition), run: () => insertGraphNode(definition), available: insertable,
      reason: insertable ? '' : 'No compatible YAML destination is selected', definition };
  }) : [];
  const entries = paletteContext.type === 'graph' ? graphEntries : commandDispatcher.entries().map(command => ({
    id: command.id, name: command.label, search: `${command.label} ${command.id}`.toLowerCase(), category: 'Commands',
    run: () => commandDispatcher.execute(command.id), available: command.available, reason: command.reason
  }));
  const matches = entries.filter(entry => entry.search.includes(query));
  const recentIndex = id => { const index = palettePreferences.recent.indexOf(id); return index < 0 ? Number.MAX_SAFE_INTEGER : index; };
  const favorite = entry => palettePreferences.favorites.includes(entry.id);
  matches.sort((left, right) => Number(favorite(right)) - Number(favorite(left))
    || recentIndex(left.id) - recentIndex(right.id) || left.category.localeCompare(right.category) || left.name.localeCompare(right.name));
  const groups = new Map();
  for (const entry of matches) {
    const group = favorite(entry) ? 'Favorites' : recentIndex(entry.id) < Number.MAX_SAFE_INTEGER ? 'Recently Used' : entry.category;
    if (!groups.has(group)) groups.set(group, []);
    groups.get(group).push(entry);
  }
  const children = [];
  for (const [group, values] of groups) {
    const heading = document.createElement('li'); heading.className = 'palette-heading'; heading.textContent = group; children.push(heading);
    for (const entry of values) {
      const item = document.createElement('li'), button = document.createElement('button');
      button.type = 'button'; button.className = 'palette-run';
      button.textContent = entry.available ? entry.name : `${entry.name} — ${entry.reason}`;
      button.disabled = !entry.available; button.title = entry.reason;
      button.addEventListener('click', () => { palette.close(); entry.run(); }); item.append(button);
      if (entry.definition) {
        const star = document.createElement('button'); star.type = 'button'; star.className = 'palette-favorite';
        star.textContent = favorite(entry) ? '★' : '☆'; star.setAttribute('aria-label', `${favorite(entry) ? 'Remove' : 'Add'} ${entry.name} ${favorite(entry) ? 'from' : 'to'} favorites`);
        star.addEventListener('click', () => {
          palettePreferences.favorites = favorite(entry) ? palettePreferences.favorites.filter(value => value !== entry.id)
            : [entry.id, ...palettePreferences.favorites].slice(0, 100);
          savePalettePreferences(); renderCommands();
        }); item.append(star);
      }
      children.push(item);
    }
  }
  paletteResults.replaceChildren(...children);
  if (!matches.length) {
    const empty = document.createElement('li'); empty.className = 'empty-state';
    empty.textContent = paletteContext.type === 'graph'
      ? palettePreferences.contextSensitive ? 'No node type is compatible at this destination.' : 'No matching node type.'
      : 'No matching command.';
    paletteResults.append(empty);
  }
}
function openPalette() {
  if (!state.connected) return; paletteContext = { type: 'commands' };
  palette.querySelector('label').textContent = 'Command palette'; paletteSearch.placeholder = 'Search commands…';
  palette.showModal(); paletteSearch.value = ''; renderCommands(); paletteSearch.focus();
}
function openGraphPalette(context = {}) {
  if (!state.connected || !graphCanvas.projection?.editable) return;
  if (graphMutationClient.inFlight) {
    yamlStatus.textContent = 'Wait for the current graph edit to finish before choosing another node.'; return;
  }
  const bounds = graphCanvas.canvas.getBoundingClientRect();
  const clientX = Number.isFinite(context.clientX) ? context.clientX : bounds.left + bounds.width / 2;
  const clientY = Number.isFinite(context.clientY) ? context.clientY : bounds.top + bounds.height / 2;
  paletteContext = { type: 'graph', sourcePin: context.sourcePin || null, edge: context.edge || null,
    position: Number.isFinite(context.graphPosition?.x) && Number.isFinite(context.graphPosition?.y)
      ? { ...context.graphPosition } : graphCanvas.screenToGraph(clientX, clientY) };
  palette.querySelector('label').textContent = context.sourcePin ? `Add from ${context.sourcePin.label} output` : 'Add graph node';
  paletteSearch.placeholder = palettePreferences.contextSensitive
    ? 'Search compatible node types…' : 'Search all node types…';
  palette.showModal(); paletteSearch.value = ''; renderCommands(); paletteSearch.focus();
}
paletteOpen.addEventListener('click', openPalette);
paletteSearch.addEventListener('input', renderCommands);
paletteContextSensitive.addEventListener('change', () => {
  palettePreferences.contextSensitive = paletteContextSensitive.checked;
  if (paletteContext.type === 'graph') paletteSearch.placeholder = palettePreferences.contextSensitive
    ? 'Search compatible node types…' : 'Search all node types…';
  savePalettePreferences(); renderCommands();
});
palette.addEventListener('keydown', event => {
  const buttons = [...paletteResults.querySelectorAll('button:not(:disabled)')], current = buttons.indexOf(document.activeElement);
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    const next = current < 0 ? (event.key === 'ArrowDown' ? 0 : buttons.length - 1)
      : (current + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
    buttons[next]?.focus();
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
