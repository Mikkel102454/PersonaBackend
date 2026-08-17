const sessionId = document.body.dataset.sessionId;
const connectPanel = document.querySelector('#connect');
const importPanel = document.querySelector('#import');
const verifyForm = document.querySelector('#verify');
const importForm = document.querySelector('#import-form');
const status = document.querySelector('#status');
const workspace = document.querySelector('#workspace');
const projectList = document.querySelector('#project');
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
const semanticDiffDialog = document.querySelector('#semantic-diff-dialog');
const semanticDiffSummary = document.querySelector('#semantic-diff-summary');
const semanticDiffList = document.querySelector('#semantic-diff-list');
const publishButton = document.querySelector('#publish-request');
const liveOpen=document.querySelector('#live-open'),liveDialog=document.querySelector('#live-dialog'),liveStatus=document.querySelector('#live-status');
const liveControls=document.querySelector('#live-controls'),liveMode=document.querySelector('#live-mode'),behaviorMutationTarget=document.querySelector('#behavior-mutation-target'),memoryMutationTarget=document.querySelector('#memory-mutation-target'),mutationConfirm=document.querySelector('#mutation-confirm'),mutationDetails=document.querySelector('#mutation-confirm-details'),mutationResult=document.querySelector('#mutation-result');
const encoder = new TextEncoder();
const protocolVersion = 3;
const state = { files: new Map(), original: new Map(), selected: null, socket: null,
  socketSequence: 0, peerSequence: 0, reconnectAttempt: 0, reconnectTimer: null,
  verified: null, privateKey: null, heartbeat: null, baseRevision: null,
  currentRevision: null, draftId: null, autosaveTimer: null, capabilityTimer: null,
  saving: false, saveAgain: false, documentModels: new Map(), originalModels: new Map(), parseTimer: null,
  parseGeneration: 0, selectedNode: null, histories: new Map(), recoveryTimer: null,
  recordingInput: false, validationRequest: null, validationResult: null, publishTimer: null,
  editorSchemas: new Map(), editorCatalogs: new Map(), metadataRevision: null };
state.catalogRequests = new Map(); state.catalogCache = new Map(); state.installationIdentity = null;
state.liveSubscription=null;state.liveRevision=0;state.liveData={players:new Map(),npcs:new Map(),behaviors:new Map(),quests:new Map(),dialogues:new Map(),memories:new Map(),server:null};state.liveStaleTimer=null;
state.pendingMutation=null;state.mutationRequests=new Map();
state.dragPath=null;

connectPanel.hidden = !sessionId;
if (!sessionId) status.textContent = 'Offline mode — no Minecraft server access.';

const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const fromB64 = value => Uint8Array.from(atob(value), character => character.charCodeAt(0));
const hex = bytes => [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('');

function flushSelected() {
  if (state.selected) state.files.set(state.selected, source.value);
}

function renderProject(files, message, revision = null) {
  state.files = new Map(files.map(file => [file.path, file.content]));
  state.original = new Map(state.files);
  state.baseRevision = revision;
  state.currentRevision = revision;
  state.documentModels.clear(); state.originalModels.clear(); state.histories.clear();
  const recoveryKey = `persona:recovery:${sessionId || revision || 'offline'}`;
  try {
    const recovered = JSON.parse(sessionStorage.getItem(recoveryKey));
    if (recovered?.revision === revision && recovered.files && typeof recovered.files === 'object') {
      for (const [path, content] of Object.entries(recovered.files)) {
        if (state.files.has(path) && typeof content === 'string') state.files.set(path, content);
      }
      if ([...state.files].some(([path, content]) => content !== state.original.get(path)))
        message += ' Recovered unsaved changes from this browser tab.';
    }
  } catch { sessionStorage.removeItem(recoveryKey); }
  state.selected = null;
  source.value = '';
  projectList.replaceChildren(...[...state.files.keys()].sort().map(path => {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = path; button.dataset.path = path;
    button.addEventListener('click', () => selectFile(path));
    item.append(button); return item;
  }));
  workspace.hidden = false;
  connectPanel.hidden = true;
  importPanel.hidden = true;
  status.textContent = message;
  const first = [...state.files.keys()].sort()[0];
  if (first) selectFile(first);
}

function dirty() {
  flushSelected();
  return [...state.files].some(([path, content]) => content !== state.original.get(path));
}

function selectFile(path) {
  flushSelected(); state.selected = path;
  source.value = state.files.get(path) ?? '';
  source.disabled = false; download.disabled = false; fileName.textContent = path;
  copyButton.disabled = false; pasteButton.disabled = false; diffToggle.disabled = false;
  if (!state.histories.has(path)) state.histories.set(path, { undo: [], redo: [] });
  renderDocument(state.documentModels.get(path)); yamlStatus.textContent = '';
  document.querySelectorAll('#project button').forEach(button => button.setAttribute('aria-current', String(button.dataset.path === path)));
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

function selectVisualNode(path) {
  state.selectedNode = path;
  document.querySelectorAll('.yaml-field').forEach(row => row.classList.toggle('selected', row.parentElement.dataset.path === path));
  const selected = visual.querySelector(`.yaml-node[data-path="${CSS.escape(path)}"]`);
  selected?.scrollIntoView({ block: 'nearest' });
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
document.querySelector('#simulate-open').addEventListener('click',()=>simulationDialog.showModal());document.querySelector('#simulation-close').addEventListener('click',()=>simulationDialog.close());document.querySelector('#simulation-run').addEventListener('click',()=>{try{const mocks=JSON.parse(simulationInput.value),model=state.documentModels.get(state.selected),value=modelValue(model.root);simulationOutput.textContent=JSON.stringify(simulate(editorKind(),value,mocks),null,2);}catch(error){simulationOutput.textContent=`Simulation input error: ${error.message}`;}});
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
function refreshVisualTools(model){const containers=collectContainers(model.root),templates=visualTemplates();visualTools.hidden=!containers.length||!templates.length;const oldContainer=visualContainer.value,oldTemplate=visualTemplate.value;visualContainer.replaceChildren(...containers.map(node=>option(JSON.stringify({path:node.path,kind:node.kind}),`${node.path||'/'} · ${node.kind}`)));visualContainer.value=oldContainer;const update=()=>{if(!visualContainer.value)return;const selected=JSON.parse(visualContainer.value);visualTemplate.replaceChildren(...templates.map((template,index)=>({template,index})).filter(item=>item.template.kind===selected.kind).map(item=>option(String(item.index),item.template.label)));if([...visualTemplate.options].some(item=>item.value===oldTemplate))visualTemplate.value=oldTemplate;};visualContainer.onchange=update;update();}
visualTools.addEventListener('submit',event=>{event.preventDefault();if(!visualContainer.value||!visualTemplate.value)return;const container=JSON.parse(visualContainer.value),template=visualTemplates()[Number(visualTemplate.value)];if(!template)return;const key=template.key?.();if(template.kind==='mapping'&&!key)return;insertVisualBlock(container.path,template.kind,key,template.yaml());});
async function insertVisualBlock(parentPath,kind,key,yaml){if(!state.selected)return;recordHistory();try{const endpoint=kind==='mapping'?'/api/v1/editor/documents/insert-field':'/api/v1/editor/documents/insert',body=kind==='mapping'?{content:source.value,parentPath,key,yamlValue:yaml}:{content:source.value,parentPath,yaml};const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});if(!response.ok)throw new Error(await response.text()||`HTTP ${response.status}`);const model=await response.json();source.value=model.content;state.files.set(state.selected,model.content);state.documentModels.set(state.selected,model);renderDocument(model);refreshDirty();scheduleAutosave();scheduleRecovery();updateHistoryButtons();}catch(error){state.histories.get(state.selected)?.undo.pop();yamlStatus.textContent=`Visual insertion rejected: ${error.message}`;}}

function overlayLiveBehaviorNodes(){
  visual.querySelectorAll('.yaml-node.live-active').forEach(node=>node.classList.remove('live-active'));
  if(!state.selected?.startsWith('behaviors/'))return;
  const model=state.documentModels.get(state.selected),definition=model?.root?.children?.find(node=>node.key==='id')?.value;
  const runtimes=[...state.liveData.behaviors.values()].filter(runtime=>!definition||runtime.behaviorId===definition),active=new Set(runtimes.flatMap(runtime=>runtime.runningPath||[]).map(path=>path.split('/').at(-1)));
  if(!active.size)return;
  const visit=node=>{const id=node.children?.find(child=>child.key==='id')?.value,element=visual.querySelector(`.yaml-node[data-path="${CSS.escape(node.path)}"]`);if(id&&active.has(id))element?.classList.add('live-active');if(id&&element){const details=runtimes.flatMap(runtime=>[...(runtime.recentOutcomes||[]),...(runtime.recentConditions||[])]).filter(item=>item.node?.split('/').at(-1)===id).slice(-3).map(item=>item.detail||item.explanation||item.status).filter(Boolean);if(details.length)element.title=details.join('\n');}for(const child of node.children||[])visit(child);};visit(model.root);
}

async function parseSelected() {
  if (!state.selected) return;
  const path = state.selected, content = source.value, generation = ++state.parseGeneration;
  try {
    const response = await fetch('/api/v1/editor/documents/parse', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content })
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const model = await response.json();
    if (generation !== state.parseGeneration || path !== state.selected) return;
    if (model.valid) {
      state.documentModels.set(path, model); yamlStatus.textContent = ''; renderDocument(model);
      if (!state.originalModels.has(path)) parseOriginal(path);
    } else {
      const issue = model.diagnostics[0];
      yamlStatus.textContent = `YAML ${issue.line}:${issue.column} — ${issue.message}. Showing the last valid visual model.`;
    }
  } catch (error) {
    if (generation === state.parseGeneration) yamlStatus.textContent = `YAML analysis unavailable: ${error.message}`;
  }
}

async function parseOriginal(path) {
  try {
    const response = await fetch('/api/v1/editor/documents/parse', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: state.original.get(path) ?? '' })
    });
    if (!response.ok) return;
    const model = await response.json();
    if (model.valid) { state.originalModels.set(path, model); if (path === state.selected) renderDocument(state.documentModels.get(path)); }
  } catch { /* Current parsing status already reports endpoint availability. */ }
}

function findModelNode(node, path) {
  if (!node || path == null) return null;
  if (node.path === path) return node;
  for (const child of node.children) { const found = findModelNode(child, path); if (found) return found; }
  return null;
}

function scheduleParse() {
  clearTimeout(state.parseTimer);
  state.parseTimer = setTimeout(parseSelected, 250);
}

async function applyVisualEdit(path, value) {
  if (!state.selected) return;
  recordHistory();
  try {
    const response = await fetch('/api/v1/editor/documents/edit', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: source.value, path, value })
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const model = await response.json();
    source.value = model.content; state.files.set(state.selected, model.content);
    state.documentModels.set(state.selected, model); yamlStatus.textContent = '';
    renderDocument(model); selectVisualNode(path); refreshDirty(); scheduleAutosave(); scheduleRecovery(); updateHistoryButtons();
  } catch (error) {
    state.histories.get(state.selected)?.undo.pop();
    yamlStatus.textContent = `Visual edit rejected: ${error.message}`;
    renderDocument(state.documentModels.get(state.selected));
  }
}

async function applyStructure(operation,path,targetPath){if(!state.selected)return;recordHistory();try{const response=await fetch('/api/v1/editor/documents/structure',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content:source.value,operation,path,targetPath})});if(!response.ok)throw new Error(await response.text()||`HTTP ${response.status}`);const model=await response.json();source.value=model.content;state.files.set(state.selected,model.content);state.documentModels.set(state.selected,model);yamlStatus.textContent='';renderDocument(model);refreshDirty();scheduleAutosave();scheduleRecovery();updateHistoryButtons();}catch(error){state.histories.get(state.selected)?.undo.pop();yamlStatus.textContent=`Structural edit rejected: ${error.message}`;renderDocument(state.documentModels.get(state.selected));}}
async function extractSubtree(path){const behaviorId=prompt('New namespaced subtree behavior ID','namespace:subtree');if(!behaviorId)return;const scope=state.documentModels.get(state.selected)?.root?.children?.find(child=>child.key==='scope')?.value||'player',filename=`behaviors/${behaviorId.replace(':','-').replace(/[^a-z0-9_.-]/g,'-')}.yml`;if(state.files.has(filename)){yamlStatus.textContent=`Cannot extract: ${filename} already exists.`;return;}recordHistory();try{const response=await fetch('/api/v1/editor/documents/extract-subtree',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content:source.value,path,behaviorId,scope})});if(!response.ok)throw new Error(await response.text()||`HTTP ${response.status}`);const result=await response.json();source.value=result.source.content;state.files.set(state.selected,result.source.content);state.documentModels.set(state.selected,result.source);state.files.set(filename,result.extractedContent);const item=document.createElement('li'),button=document.createElement('button');button.type='button';button.textContent=filename;button.dataset.path=filename;button.classList.add('dirty');button.addEventListener('click',()=>selectFile(filename));item.append(button);projectList.append(item);renderDocument(result.source);refreshDirty();scheduleAutosave();scheduleRecovery();}catch(error){state.histories.get(state.selected)?.undo.pop();yamlStatus.textContent=`Subtree extraction rejected: ${error.message}`;}}

function refreshDirty() {
  flushSelected();
  document.querySelectorAll('#project button').forEach(button => button.classList.toggle('dirty', state.files.get(button.dataset.path) !== state.original.get(button.dataset.path)));
  exportChanged.disabled = !dirtyFiles().length;
  renderDiff();
}

function dirtyFiles() {
  flushSelected();
  return [...state.files].filter(([path, content]) => content !== state.original.get(path));
}

function recordHistory() {
  if (!state.selected) return;
  const history = state.histories.get(state.selected) ?? { undo: [], redo: [] };
  const previous = state.files.get(state.selected) ?? '';
  if (history.undo.at(-1) !== previous) history.undo.push(previous);
  if (history.undo.length > 200) history.undo.shift();
  history.redo.length = 0; state.histories.set(state.selected, history); updateHistoryButtons();
}

function restoreHistory(direction) {
  if (!state.selected) return;
  const history = state.histories.get(state.selected), from = history?.[direction];
  if (!from?.length) return;
  const other = direction === 'undo' ? history.redo : history.undo;
  other.push(source.value); source.value = from.pop(); state.files.set(state.selected, source.value);
  refreshDirty(); scheduleParse(); scheduleAutosave(); scheduleRecovery(); updateHistoryButtons();
}

function updateHistoryButtons() {
  const history = state.histories.get(state.selected);
  undoButton.disabled = !history?.undo.length; redoButton.disabled = !history?.redo.length;
}

function scheduleRecovery() {
  clearTimeout(state.recoveryTimer);
  state.recoveryTimer = setTimeout(() => {
    flushSelected();
    const key = `persona:recovery:${sessionId || state.baseRevision || 'offline'}`;
    const changed = Object.fromEntries(dirtyFiles());
    if (Object.keys(changed).length) sessionStorage.setItem(key, JSON.stringify({ revision: state.baseRevision, files: changed }));
    else sessionStorage.removeItem(key);
  }, 300);
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
  const response = await fetch(`/api/v1/editor/sessions/${sessionId}/snapshot`, { headers: { Authorization: `Bearer ${verified.browserLeaseToken}` } });
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
  state.currentRevision = snapshot.revision;
  if (dirty()) {
    status.textContent = 'Server content changed while this draft was open. Your edits were retained and marked stale.';
    scheduleAutosave();
  } else {
    renderProject(files, `Connected securely. Loaded ${files.length} signed content file${files.length === 1 ? '' : 's'}.`, snapshot.revision);
  }
}

async function loadEditorMetadata(verified, installationPublicKey, installationKey) {
  const response = await fetch(`/api/v1/editor/sessions/${sessionId}/metadata`, { headers: { Authorization: `Bearer ${verified.browserLeaseToken}` } });
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
  if (!sessionId || !state.verified?.capabilities?.includes('DRAFT_EDIT') || !state.baseRevision) return;
  invalidateValidation();
  clearTimeout(state.autosaveTimer);
  state.autosaveTimer = setTimeout(saveDraft, 750);
}

function updatePublishButton() {
  publishButton.disabled = !(state.verified?.capabilities?.includes('CONTENT_PUBLISH')
    && state.validationResult?.valid && state.validationResult.proposedRevision && state.draftId
    && !document.querySelector('.invalid-catalog'));
}

function invalidateValidation() {
  state.validationResult = null; state.validationRequest = null; updatePublishButton();
  if (!validationPanel.hidden) validationSummary.textContent = 'Candidate changed; waiting for fresh Persona validation…';
}

async function refreshCapabilities() {
  clearTimeout(state.capabilityTimer);
  if (!state.verified || Date.now() >= Date.parse(state.verified.expiresAt)) return;
  try {
    const response = await fetch(`/api/v1/editor/sessions/${sessionId}/status`, {
      headers: { Authorization: `Bearer ${state.verified.browserLeaseToken}` }
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const session = await response.json();
    const before = state.verified.capabilities;
    state.verified.capabilities = session.grantedCapabilities;
    liveOpen.disabled=!session.grantedCapabilities.includes('PLAYER_VIEW');
    const mutable=session.grantedCapabilities.includes('LIVE_MUTATE');liveControls.hidden=!mutable;liveMode.textContent=mutable?'— elevated controls trusted':'— read only';
    updatePublishButton();
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
  document.querySelector('#live-server').textContent=state.liveData.server?JSON.stringify(state.liveData.server,null,2):'Not subscribed to server metrics.';renderMutationTargets();overlayLiveBehaviorNodes();}
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

async function saveDraft() {
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
    status.textContent = saved.stale
      ? 'Draft autosaved, but server content has changed; review before any future publish.'
      : `Draft autosaved at ${new Date(saved.updatedAt).toLocaleTimeString()}.`;
    requestValidation(saved.draftId);
  } catch (error) {
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
  validationPanel.hidden = false;
  validationSummary.textContent = result.valid
    ? `Validated by Persona content format ${result.contentFormatVersion}. No errors.`
    : `Persona found ${result.diagnostics.length} error${result.diagnostics.length === 1 ? '' : 's'}.`;
  validationList.replaceChildren(...result.diagnostics.map(issue => {
    const item = document.createElement('li'), button = document.createElement('button');
    button.type = 'button';
    const where = `${issue.path}:${issue.line}:${issue.column}${issue.nodeId ? ` · node ${issue.nodeId}` : ''}`
      + `${issue.referenceId ? ` · ${issue.referenceType} ${issue.referenceId}` : ''}`;
    button.textContent = `${where} — ${issue.message}${issue.suggestion ? ` ${issue.suggestion}` : ''}`;
    button.addEventListener('click', () => {
      if (!state.files.has(issue.path)) return;
      selectFile(issue.path); const offset = diagnosticOffset(source.value, issue.line, issue.column);
      source.focus(); source.setSelectionRange(offset, offset);
    });
    item.append(button); return item;
  }));
}

async function sendSocket(type, payload) {
  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) return false;
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
  const verified = state.verified;
  const separator = verified.browserSocketUrl.includes('?') ? '&' : '?';
  state.socket = new WebSocket(`${verified.browserSocketUrl}${separator}lease=${encodeURIComponent(verified.browserLeaseToken)}&after=${state.peerSequence}`);
  state.socket.onopen = () => {
    state.reconnectAttempt = 0; scheduleHeartbeat();
    if (!state.files.size) loadSnapshot(verified).catch(error => { status.textContent = `Connected, but snapshot loading failed: ${error.message}`; });
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
    if (Date.now() >= Date.parse(verified.expiresAt)) { status.textContent = 'Editor session expired.'; return; }
    const delay = Math.min(30000, 1000 * (2 ** Math.min(state.reconnectAttempt++, 5)));
    status.textContent = 'Live connection interrupted; reconnecting…';
    if(state.liveSubscription){liveStatus.textContent='Live data is stale; reconnecting…';liveDialog.classList.add('stale');}
    state.reconnectTimer = setTimeout(connectSocket, delay);
  };
  state.socket.onerror = () => { status.textContent = 'Live connection interrupted.'; };
}

verifyForm.addEventListener('submit', async event => {
  event.preventDefault(); const button = verifyForm.querySelector('button'); button.disabled = true;
  status.textContent = 'Creating browser identity…';
  try {
    const keys = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
    const publicKey = b64(await crypto.subtle.exportKey('spki', keys.publicKey));
    const response = await fetch(`/api/v1/editor/sessions/${sessionId}/verify`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ verificationCode: document.querySelector('#code').value.replaceAll('-', '').trim().toUpperCase(), browserPublicKey: publicKey, browserDescription: navigator.userAgent }) });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const verified = await response.json();
    sessionStorage.setItem(`persona:${sessionId}:privateKey`, b64(await crypto.subtle.exportKey('pkcs8', keys.privateKey)));
    state.verified = verified; state.privateKey = keys.privateKey; connectSocket(); refreshCapabilities();
  } catch (error) { status.textContent = `Verification failed: ${error.message}`; button.disabled = false; }
});

importForm.addEventListener('submit', async event => {
  event.preventDefault(); const button = importForm.querySelector('button'); button.disabled = true;
  status.textContent = 'Importing offline project…';
  try {
    const body = new FormData();
    for (const file of document.querySelector('#files').files) body.append('files', file, file.name);
    const response = await fetch('/api/v1/editor/import', { method: 'POST', body });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const project = await response.json();
    const warning = project.warnings.length ? ` ${project.warnings.length} file${project.warnings.length === 1 ? '' : 's'} ignored.` : '';
    renderProject(project.files, `Loaded ${project.files.length} offline YAML file${project.files.length === 1 ? '' : 's'}.${warning}`, project.revision);
  } catch (error) { status.textContent = `Import failed: ${error.message}`; button.disabled = false; }
});

source.addEventListener('beforeinput', () => {
  if (!state.recordingInput) { recordHistory(); state.recordingInput = true; }
});
source.addEventListener('input', () => {
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
  const node = visit(model.root); if (node) selectVisualNode(node.path);
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
    event.preventDefault(); restoreHistory(event.shiftKey ? 'redo' : 'undo');
  }
});
download.addEventListener('click', () => {
  flushSelected(); if (!state.selected) return;
  const link = document.createElement('a');
  link.href = URL.createObjectURL(new Blob([state.files.get(state.selected)], { type: 'application/yaml;charset=utf-8' }));
  link.download = state.selected.split('/').at(-1); link.click(); URL.revokeObjectURL(link.href);
});
undoButton.addEventListener('click', () => restoreHistory('undo'));
redoButton.addEventListener('click', () => restoreHistory('redo'));
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
  if (!entries.length) return;
  status.textContent = 'Preparing deterministic project archive…';
  try {
    const response = await fetch('/api/v1/editor/export', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
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
  referencesSummary.textContent = 'Analyzing typed project references…'; referencesList.replaceChildren();
  try {
    const response = await fetch('/api/v1/editor/projects/references', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
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
          const row = document.createElement('li');
          row.textContent = `${edge.sourceType}:${edge.sourceId} · ${edge.path}:${edge.line}`; return row;
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
  } catch (error) { referencesSummary.textContent = `Reference analysis failed: ${error.message}`; }
}

referencesOpen.addEventListener('click', () => { referencesDialog.showModal(); loadReferenceGraph(); });
document.querySelector('#references-close').addEventListener('click', () => referencesDialog.close());
renameForm.addEventListener('submit', async event => {
  event.preventDefault(); renameResult.textContent = 'Calculating rename impact…';
  try {
    const request = { files: await contentFiles(), type: document.querySelector('#rename-type').value,
      currentId: document.querySelector('#rename-current').value.trim(),
      replacementId: document.querySelector('#rename-replacement').value.trim() };
    const response = await fetch('/api/v1/editor/projects/rename-preview', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request)
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

semanticDiffOpen.addEventListener('click', async () => {
  semanticDiffDialog.showModal(); semanticDiffSummary.textContent = 'Comparing typed YAML values…';
  semanticDiffList.replaceChildren();
  try {
    const response = await fetch('/api/v1/editor/projects/semantic-diff', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ before: await contentFiles([...state.original]), after: await contentFiles() })
    });
    if (!response.ok) throw new Error(await response.text() || `HTTP ${response.status}`);
    const result = await response.json();
    semanticDiffSummary.textContent = `${result.changes.length} semantic change${result.changes.length === 1 ? '' : 's'}; comments and formatting are excluded.`;
    semanticDiffList.replaceChildren(...result.changes.map(change => {
      const item = document.createElement('li');
      const values = change.change === 'CHANGED' ? `: ${change.beforeValue} → ${change.afterValue}` : '';
      item.textContent = `${change.category} · ${change.path}${change.yamlPath || ''} · ${change.change}${values}`;
      item.addEventListener('click', () => { if (state.files.has(change.path)) { semanticDiffDialog.close(); selectFile(change.path); } });
      return item;
    }));
  } catch (error) { semanticDiffSummary.textContent = `Semantic diff failed: ${error.message}`; }
});
document.querySelector('#semantic-diff-close').addEventListener('click', () => semanticDiffDialog.close());

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

const commands = [
  ['Undo', () => restoreHistory('undo')], ['Redo', () => restoreHistory('redo')],
  ['Copy YAML selection', () => copyButton.click()], ['Paste YAML', () => pasteButton.click()],
  ['Show textual changes', () => { if (diff.hidden) diffToggle.click(); }],
  ['Download current file', () => download.click()], ['Download complete project', () => exportAll.click()],
  ['Download changed files', () => exportChanged.click()],
  ['Show references and rename preview', () => referencesOpen.click()],
  ['Show semantic project diff', () => semanticDiffOpen.click()],
  ['Request validated publication', () => publishButton.click()]
];
function renderCommands() {
  const query = paletteSearch.value.trim().toLowerCase();
  const matches = commands.filter(([name]) => name.toLowerCase().includes(query));
  paletteResults.replaceChildren(...matches.map(([name, run], index) => {
    const item = document.createElement('li'), button = document.createElement('button');
    button.type = 'button'; button.textContent = name; button.dataset.index = String(index);
    button.addEventListener('click', () => { palette.close(); run(); }); item.append(button); return item;
  }));
  paletteResults.querySelector('button')?.focus();
}
function openPalette() { palette.showModal(); paletteSearch.value = ''; renderCommands(); paletteSearch.focus(); }
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
