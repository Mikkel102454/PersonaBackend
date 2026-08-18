import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';

const root = process.cwd();
const sessionId = '11111111-1111-4111-8111-111111111111';
const installationKey = generateKeyPairSync('ed25519');
const publicKey = installationKey.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const initialContent = '# Browser fixture\nid: demo:walker\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n    - id: wait-one\n      type: wait\n      duration: 1s\nfuture: !vendor retained\n';
const targetContent = 'id: demo:target\nscope: player\nroot:\n  id: root\n  type: wait\n  duration: 1s\n';
const largeContent = 'id: perf:large\nscope: player\nroot:\n  id: root\n  type: sequence\n  children:\n'
  + Array.from({ length: 1999 }, (_, index) => `    - id: node-${index}\n      type: wait\n      duration: 1s\n`).join('');
const publishes = new Map();

function template(kind, id) {
  if (kind === 'behavior') return `id: ${id}\nscope: player\nroot:\n  id: root\n  type: sequence\n  children: []\n`;
  if (kind === 'dialogue') return `content-version: 2\nid: ${id}\nstart: start\nnodes:\n  start:\n    graph:\n      variables: {}\n      nodes:\n        say: { type: say, text: "New dialogue line" }\n        end: { type: end-dialogue }\n      connections:\n        enter: { from: $event.exec, to: say.exec }\n        finish: { from: say.success, to: end.exec }\n`;
  if (kind === 'quest') return `content-version: 2\nid: ${id}\ntitle: "New quest"\nphases:\n  - id: start\n    objectives:\n      - id: begin\n        type: wait\n        duration: 1s\n`;
  if (kind === 'npc') return `content-version: 2\nid: ${id}\ndisplay-name: "New NPC"\n`;
  return `content-version: 2\nid: ${id}\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n  pause: { type: wait, duration: 1ms }\nconnections:\n  enter: { from: $input.exec, to: pause.exec }\n  leave: { from: pause.success, to: $output.exec }\n`;
}

const sha = content => createHash('sha256').update(content).digest('hex');
const signature = input => sign(null, Buffer.from(input), installationKey.privateKey).toString('base64');
const extensionSchemaJson = JSON.stringify({ type: 'object', properties: {
  action: { type: 'string', const: 'vendor:wave' }, message: { type: 'string', title: 'Message' }
}, required: ['action'] });
const extensionSchemaSha = sha(extensionSchemaJson);
const extensionManifest = `schema\x00behavior-action\x00vendor:wave\x00vendor\x001.0.0\x00${extensionSchemaSha}`;
const extensionRevision = sha(extensionManifest + '\n');
const revision = files => {
  const digest = createHash('sha256');
  [...files].sort((a, b) => a.path.localeCompare(b.path))
    .forEach(file => { digest.update(file.path); digest.update(Buffer.from([0])); digest.update(file.sha256); digest.update(Buffer.from([0])); });
  return digest.digest('hex');
};
const json = (response, status, value) => {
  response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  response.end(JSON.stringify(value));
};
const body = request => new Promise((resolveBody, reject) => {
  const chunks = []; request.on('data', chunk => chunks.push(chunk));
  request.on('end', () => { try { resolveBody(chunks.length ? JSON.parse(Buffer.concat(chunks)) : {}); } catch (error) { reject(error); } });
});

function sourceRange(content, needle) {
  const startOffset = Math.max(0, content.indexOf(needle));
  const before = content.slice(0, startOffset).split('\n');
  return { startOffset, endOffset: startOffset + needle.length, startLine: before.length,
    startColumn: before.at(-1).length + 1, endLine: before.length, endColumn: before.at(-1).length + needle.length + 1 };
}
function documentModel(content) {
  const scalar = (key, value, path, occurrence = key + ':') => {
    const range = sourceRange(content, occurrence), valueOffset = content.indexOf(value, range.startOffset);
    return { path, key, kind: 'string', value, tag: 'tag:yaml.org,2002:str', editable: true,
      keyOffset: range.startOffset, keyLine: range.startLine, keyColumn: range.startColumn,
      startOffset: valueOffset, endOffset: valueOffset + value.length,
      startLine: range.startLine, startColumn: valueOffset - content.lastIndexOf('\n', valueOffset) ,
      endLine: range.startLine, endColumn: valueOffset - content.lastIndexOf('\n', valueOffset) + value.length, children: [] };
  };
  const rootRange = { startOffset: 0, endOffset: content.length, startLine: 1, startColumn: 1,
    endLine: content.split('\n').length, endColumn: 1 };
  const id = content.match(/^id:\s*([^\s#]+)/m)?.[1] || 'demo:walker';
  const behaviorRoot = { path: '/root', key: 'root', kind: 'mapping', value: null, tag: 'tag:yaml.org,2002:map',
    editable: false, keyOffset: content.indexOf('root:'), keyLine: 4, keyColumn: 1,
    ...sourceRange(content, 'id: root\n  type: sequence'), children: [
      scalar('id', 'root', '/root/id', 'id: root'), scalar('type', 'sequence', '/root/type', 'type: sequence'),
      { path: '/root/children', key: 'children', kind: 'sequence', value: null, tag: 'tag:yaml.org,2002:seq',
        editable: false, keyOffset: content.indexOf('children:'), keyLine: 7, keyColumn: 3,
        ...sourceRange(content, '- id: wait-one\n      type: wait\n      duration: 1s'), children: [] }
    ] };
  return { valid: true, content, diagnostics: [], root: { path: '', key: null, kind: 'mapping', value: null,
    tag: 'tag:yaml.org,2002:map', editable: false, keyOffset: -1, keyLine: -1, keyColumn: -1,
    ...rootRange, children: [scalar('id', id, '/id'), scalar('scope', 'player', '/scope'), behaviorRoot,
      { path: '/future', key: 'future', kind: 'custom', value: 'retained', tag: '!vendor', editable: false,
        keyOffset: content.indexOf('future:'), keyLine: 11, keyColumn: 1, ...sourceRange(content, 'retained'), children: [] }] } };
}

function projection(request) {
  const content = request.content, digest = sha(content);
  const id = request.resourceKind + ':' + request.resourceId;
  const rootRange = sourceRange(content, request.resourceKind === 'behavior' ? 'id: root' : request.resourceId);
  const makePin = (nodeId, direction, label, type = 'execution') => ({
    id: direction === 'input' ? nodeId + ':in' : nodeId + ':out:' + label,
    nodeId, direction, channel: type.startsWith('reference:') ? 'DATA' : 'EXECUTION', valueType: type.startsWith('reference:') ? type.slice(10) : 'execution', semanticType: type, cardinality: 'many', required: false, label, yamlPath: request.yamlPath || ''
  });
  if (request.resourceKind === 'quest') {
    const center = id + '#quest', phase = id + '#start', objective = id + '#begin';
    const pin = (nodeId, direction, label, type) => ({ id: nodeId + (direction === 'input' ? ':in' : ':out:' + label),
      nodeId, direction, channel: 'EXECUTION', valueType: 'execution', semanticType: type, cardinality: direction === 'input' ? 'single' : 'many',
      required: direction === 'input', label, yamlPath: '' });
    const nodes = [
      { id: center, yamlPath: '', range: sourceRange(content, request.resourceId), kind: 'quest', title: request.resourceId,
        subtitle: 'New quest', fields: [], pins: [pin(center, 'output', 'entry', 'phase-flow')], badges: [], custom: false, extensionOwner: null },
      { id: phase, yamlPath: '/phases/0', range: sourceRange(content, 'id: start'), kind: 'quest-phase', title: 'start',
        subtitle: 'Quest phase', fields: [], pins: [pin(phase, 'input', 'entry', 'phase-flow'), pin(phase, 'output', 'objectives', 'objective')],
        badges: [], custom: false, extensionOwner: null },
      { id: objective, yamlPath: '/phases/0/objectives/0', range: sourceRange(content, 'id: begin'), kind: 'quest-objective', title: 'begin',
        subtitle: 'wait', fields: [], pins: [pin(objective, 'input', 'phase', 'objective')], badges: [], custom: false, extensionOwner: null }
    ];
    return { graphVersion: 3, resourceIdentity: id, resourceKind: 'quest', resourceId: request.resourceId,
      filePath: request.path, rootYamlPath: '', contentDigest: digest, editable: true, nodes, edges: [
        { id: 'quest-entry', sourcePinId: center + ':out:entry', targetPinId: phase + ':in', semanticType: 'phase-flow', label: 'entry', sourceYamlPath: '', targetYamlPath: '/phases/0', resolved: true, cyclic: false },
        { id: 'quest-objective', sourcePinId: phase + ':out:objectives', targetPinId: objective + ':in', semanticType: 'objective', label: 'objective', sourceYamlPath: '/phases/0', targetYamlPath: '/phases/0/objectives/0', resolved: true, cyclic: false }],
      diagnostics: [], capabilities: ['SELECT', 'PAN_ZOOM', 'AUTO_LAYOUT', 'INSPECT', 'EDIT_FIELDS', 'CREATE_NODE', 'DELETE_NODE', 'CONNECT', 'DISCONNECT', 'REORDER'] };
  }
  if (request.resourceKind === 'dialogue') {
    const entry = id + '#start', event = id + '#event', say = id + '#say', end = id + '#end';
    const nodes = [
      { id: entry, yamlPath: '/nodes/start', range: sourceRange(content, 'start:'), kind: 'dialogue-entry', title: 'start', subtitle: 'Start node', fields: [],
        pins: [makePin(entry, 'input', 'in', 'dialogue-flow'), makePin(entry, 'output', 'next')], badges: ['start'], custom: false, extensionOwner: null },
      { id: event, yamlPath: '/nodes/start/graph', range: sourceRange(content, 'graph:'), kind: 'event', title: 'Dialogue · start', subtitle: 'Host event',
        fields: [], pins: [makePin(event, 'output', 'exec')], badges: ['permanent'], custom: false, extensionOwner: null },
      { id: say, yamlPath: '/nodes/start/graph/nodes/say', range: sourceRange(content, 'say: { type: say'), kind: 'script-say', title: 'say', subtitle: 'say',
        fields: [], pins: [makePin(say, 'input', 'in'), makePin(say, 'output', 'then')], badges: [], custom: false, extensionOwner: null },
      { id: end, yamlPath: '/nodes/start/graph/nodes/end', range: sourceRange(content, 'end: { type: end-dialogue'), kind: 'script-end-dialogue', title: 'end', subtitle: 'end-dialogue',
        fields: [], pins: [makePin(end, 'input', 'in')], badges: [], custom: false, extensionOwner: null }
    ];
    return { graphVersion: 3, resourceIdentity: id, resourceKind: 'dialogue', resourceId: request.resourceId,
      filePath: request.path, rootYamlPath: '', contentDigest: digest, editable: true, nodes, edges: [
        { id: 'dialogue-say', sourcePinId: event + ':out:exec', targetPinId: say + ':in', semanticType: 'execution', label: 'enter', sourceYamlPath: '/nodes/start/graph/connections/enter/from', targetYamlPath: '/nodes/start/graph/connections/enter/to', resolved: true, cyclic: false },
        { id: 'dialogue-end', sourcePinId: say + ':out:then', targetPinId: end + ':in', semanticType: 'execution', label: 'finish', sourceYamlPath: '/nodes/start/graph/connections/finish/from', targetYamlPath: '/nodes/start/graph/connections/finish/to', resolved: true, cyclic: false }],
      diagnostics: [], capabilities: ['SELECT', 'PAN_ZOOM', 'AUTO_LAYOUT', 'INSPECT', 'EDIT_FIELDS', 'CREATE_NODE', 'DELETE_NODE', 'CONNECT', 'DISCONNECT', 'REORDER'] };
  }
  if (request.resourceKind !== 'behavior') {
    const nodeId = id + '#root';
    return { graphVersion: 3, resourceIdentity: id, resourceKind: request.resourceKind, resourceId: request.resourceId,
      filePath: request.path, rootYamlPath: request.yamlPath || '', contentDigest: digest, editable: true,
      nodes: [{ id: nodeId, yamlPath: request.yamlPath || '', range: rootRange, kind: request.resourceKind === 'npc' ? 'npc-configuration' : request.resourceKind,
        title: request.resourceId, subtitle: request.resourceKind, fields: [], pins: [makePin(nodeId, 'input', 'in'), makePin(nodeId, 'output', 'next')],
        badges: [], custom: false, extensionOwner: null }], edges: [], diagnostics: [], capabilities: ['SELECT', 'PAN_ZOOM', 'AUTO_LAYOUT'] };
  }
  const rootId = id + '#root', customId = id + '#custom';
  const children = [...content.matchAll(/^    - id: ([a-z0-9_.-]+)\n      type: ([a-z0-9_.-]+)/gm)]
    .map((match, index) => ({ key: match[1], type: match[2], index }));
  const nodes = [
    { id: rootId, yamlPath: '/root', range: sourceRange(content, 'id: root'), kind: 'sequence', title: 'root', subtitle: 'sequence',
      fields: [{ id: rootId + ':field', label: 'type', yamlPath: '/root/type', range: sourceRange(content, 'sequence'),
        valueType: 'string', value: 'sequence', editable: true, required: false, custom: false }],
      pins: [makePin(rootId, 'input', 'in'), ...children.map((child, index) => makePin(rootId, 'output', String(index + 1))),
        { ...makePin(rootId, 'output', '+ child'), yamlPath: '/root/children' }],
      badges: [], custom: false, extensionOwner: null },
    ...children.map(child => {
      const childId = id + '#' + child.key, path = `/root/children/${child.index}`;
      const duration = content.slice(content.indexOf('id: ' + child.key)).match(/duration:\s*([^\s]+)/)?.[1] || '1s';
      return { id: childId, yamlPath: path, range: sourceRange(content, 'id: ' + child.key), kind: child.type,
        title: child.key, subtitle: child.type,
        fields: child.type === 'wait' ? [{ id: childId + ':duration', label: 'duration', yamlPath: path + '/duration',
          range: sourceRange(content, duration), valueType: 'string', value: duration, editable: true, required: false, custom: false }] : [],
        pins: [makePin(childId, 'input', 'in'), ...(child.type === 'wait' ? [{
          id: childId + ':input:duration', nodeId: childId, direction: 'input', channel: 'DATA', valueType: 'duration',
          semanticType: 'data:duration', cardinality: 'exactly-one', required: true, label: 'duration',
          yamlPath: path + '/duration', literal: { value: duration, defaultValue: null, hasDefault: false, connected: false, editable: true }
        }] : []), makePin(childId, 'output', 'result')],
        badges: child.type === 'wait' ? ['durable'] : [], custom: false, extensionOwner: null };
    }),
    { id: customId, yamlPath: '/future', range: sourceRange(content, 'retained'), kind: 'custom-yaml', title: 'Custom YAML · future', subtitle: '!vendor',
      fields: [], pins: [makePin(customId, 'input', 'in', 'custom')], badges: ['custom data'], custom: true, extensionOwner: null }
  ];
  return { graphVersion: 3, resourceIdentity: id, resourceKind: 'behavior', resourceId: request.resourceId,
    filePath: request.path, rootYamlPath: '', contentDigest: digest, editable: true, nodes,
    edges: children.map((child, index) => ({ id: 'edge-' + (index + 1), sourcePinId: rootId + ':out:' + (index + 1),
      targetPinId: id + '#' + child.key + ':in', semanticType: 'execution', label: String(index + 1),
      sourceYamlPath: '/root/children/' + index, targetYamlPath: '/root/children/' + index, resolved: true, cyclic: false })),
    diagnostics: [], capabilities: ['SELECT', 'PAN_ZOOM', 'AUTO_LAYOUT', 'INSPECT', 'EDIT_FIELDS',
      'CREATE_NODE', 'DELETE_NODE', 'CONNECT', 'DISCONNECT', 'REORDER'] };
}

async function staticResource(pathname, response) {
  let file;
  if (pathname === '/editor/app.js') file = 'src/main/resources/static/editor/app.js';
  else if (pathname === '/editor/style.css') file = 'src/main/resources/static/editor/style.css';
  else if (pathname.startsWith('/editor/modules/') && !pathname.includes('..'))
    file = 'src/main/resources/static/editor/modules/' + pathname.substring('/editor/modules/'.length);
  if (!file) return false;
  const data = await readFile(resolve(root, file));
  response.writeHead(200, { 'content-type': file.endsWith('.css') ? 'text/css' : 'text/javascript' });
  response.end(data); return true;
}

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1:4173');
    if (url.pathname === '/health') { response.end('ok'); return; }
    if (url.pathname === '/fixture/sign') {
      const message = await body(request), payloadDigest = createHash('sha256')
        .update(JSON.stringify(message.payload)).digest('base64');
      const signingInput = `${message.protocolVersion}\n${message.sessionId}\n${message.sequence}\n${message.type}\n${payloadDigest}`;
      json(response, 200, { ...message, signature: signature(signingInput) }); return;
    }
    if (await staticResource(url.pathname, response)) return;
    if (url.pathname === '/editor/session/' + sessionId) {
      const page = await readFile(resolve(root, 'src/main/resources/templates/editor/index.html'));
      response.writeHead(200, { 'content-type': 'text/html', 'cache-control': 'no-store' }); response.end(page); return;
    }
    if (url.pathname.endsWith('/verify')) {
      json(response, 200, { protocolVersion: 3, sessionId, browserLeaseToken: 'browser-test-lease',
        browserSocketUrl: 'ws://127.0.0.1:4173/browser-fixture', expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        capabilities: ['CONTENT_VIEW', 'DRAFT_EDIT', 'CONTENT_PUBLISH', 'PLAYER_VIEW'] }); return;
    }
    if (url.pathname.endsWith('/snapshot')) {
      const file = { path: 'behaviors/walker.yml', sha256: sha(initialContent), content: initialContent };
      const target = { path: 'behaviors/target.yml', sha256: sha(targetContent), content: targetContent };
      const large = { path: 'behaviors/large.yml', sha256: sha(largeContent), content: largeContent };
      const files = [file, target, large], createdAt = new Date().toISOString(), projectRevision = revision(files),
        manifestDigest = sha('');
      let signingInput = `3\n${sessionId}\n${projectRevision}\n1\n${createdAt}\n${publicKey}\n${manifestDigest}`;
      for (const item of [...files].sort((a, b) => a.path.localeCompare(b.path)))
        signingInput += `\n${item.path}\n${item.sha256}`;
      json(response, 200, { protocolVersion: 3, sessionId, revision: projectRevision, contentFormatVersion: 1,
        createdAt, installationPublicKey: publicKey, manifestDigest, folders: [], files,
        signature: signature(signingInput) }); return;
    }
    if (url.pathname.endsWith('/metadata')) {
      const createdAt = new Date().toISOString();
      const signingInput = `3\n${sessionId}\n${createdAt}\n${publicKey}\n${extensionRevision}\n${extensionManifest}`;
      json(response, 200, { protocolVersion: 3, sessionId, createdAt,
        installationPublicKey: publicKey, revision: extensionRevision, schemas: [{ contentType: 'behavior-action',
          typeId: 'vendor:wave', extensionId: 'vendor', extensionVersion: '1.0.0', schemaJson: extensionSchemaJson,
          schemaSha256: extensionSchemaSha }], catalogs: [], signature: signature(signingInput) }); return;
    }
    if (url.pathname.endsWith('/status')) {
      json(response, 200, { grantedCapabilities: ['CONTENT_VIEW', 'DRAFT_EDIT', 'CONTENT_PUBLISH', 'PLAYER_VIEW'] }); return;
    }
    if (url.pathname.endsWith('/documents/parse')) {
      const input = await body(request); json(response, 200, documentModel(input.content)); return;
    }
    if (url.pathname.endsWith('/documents/projection')) {
      json(response, 200, projection(await body(request))); return;
    }
    if (url.pathname.endsWith('/documents/mutate')) {
      const input = await body(request);
      const expectedDigest = input.expectedContentDigest || input.expectedDigest;
      const filePath = input.filePath || input.path, rootYamlPath = input.rootYamlPath ?? input.yamlPath;
      const flatten = values => values.flatMap(operation => ['COMPOUND', 'INSERT_ON_WIRE'].includes(operation.type)
        ? flatten(operation.children || []) : [operation]);
      const operations = flatten(input.operations || []);
      if (expectedDigest !== sha(input.content)) {
        json(response, 409, { code: 'STALE_PROJECTION', message: 'Fixture digest conflict', filePath, yamlPath: rootYamlPath }); return;
      }
      if (operations.some(operation => operation.key === 'stale-node')) {
        json(response, 409, { code: 'STALE_PROJECTION', message: 'A newer authoritative digest exists',
          filePath, yamlPath: rootYamlPath }); return;
      }
      if (operations.some(operation => operation.key === 'slow-node'))
        await new Promise(resolveDelay => setTimeout(resolveDelay, 300));
      let content = input.content;
      for (const operation of operations) {
        if (operation.type === 'COPY' && operation.parentYamlPath === '/root/children') {
          const sourceFile = input.projectFiles.find(file => file.path === operation.sourceFilePath);
          const sourceId = operation.yamlPath.split('/').at(-1), matches = [...sourceFile.content.matchAll(
            /^    - id: ([a-z0-9_.-]+)\n      type: ([a-z0-9_.-]+)(?:\n      duration: ([^\n]+))?/gm)];
          const copied = matches[Number(sourceId)] || matches[0], duration = copied?.[3] ? `\n      duration: ${copied[3]}` : '';
          const block = `    - id: ${operation.key}\n      type: ${copied?.[2] || 'wait'}${duration}\n`;
          content = content.includes('children: []') ? content.replace('children: []', `children:\n${block}`)
            : content.replace('\nfuture:', `\n${block}future:`);
        } else if (operation.type === 'WRAP' && operation.yamlPath === '/root/children/0') {
          const original = content.match(/    - id: wait-one\n      type: wait\n      duration: (?:'1s'|1s)(?:[^\n]*)\n/)?.[0];
          if (original) {
            const nested = original.split('\n').filter(Boolean).map(line => '    ' + line).join('\n') + '\n';
            content = content.replace(original, `    - id: ${operation.key}\n      type: ${operation.nodeKind}\n      child:\n${nested}`);
          }
        } else if (operation.type === 'INSERT' && operation.parentYamlPath === '/root/children') {
          const key = operation.key || 'server-node', type = operation.nodeKind === 'extension-action' ? 'action'
            : operation.nodeKind === 'extension-condition' ? 'condition' : operation.nodeKind;
          const details = ['wait', 'cooldown'].includes(type) ? '\n      duration: 1s'
            : operation.nodeKind === 'extension-action' ? `\n      action: ${operation.value}`
              : operation.nodeKind === 'extension-condition' ? `\n      condition: ${operation.value}`
                : type === 'action' ? '\n      action: set-visible\n      visible: true'
                  : type === 'condition' ? '\n      condition: chance\n      chance: 1.0'
                : ['sequence', 'selector', 'priority-selector', 'parallel'].includes(type) ? '\n      children: []' : '';
          const block = `    - id: ${key}\n      type: ${type}${details}\n`;
          content = operation.index === 0
            ? content.replace('    - id: wait-one\n', block + '    - id: wait-one\n')
            : content.replace('\nfuture:', `\n${block}future:`);
        } else if (operation.type === 'EDIT_FIELD' && operation.yamlPath.endsWith('/duration')) {
          content = content.replace(/duration:\s*[^\s#]+/, 'duration: ' + operation.value);
        } else if (operation.type === 'REORDER' && operation.parentYamlPath === '/root/children') {
          const section = content.match(/  children:\n([\s\S]*?)(?=future:)/)?.[1] || '';
          const blocks = [...section.matchAll(/    - id: [\s\S]*?(?=    - id: |$)/g)].map(match => match[0]);
          const sourceIndex = Number(operation.yamlPath.split('/').at(-1));
          const neighborIndex = Number(operation.targetYamlPath.split('/').at(-1));
          const [moved] = blocks.splice(sourceIndex, 1);
          let destination = neighborIndex + (operation.afterPortId ? 1 : 0);
          if (sourceIndex < destination) destination--;
          blocks.splice(Math.max(0, destination), 0, moved);
          content = content.replace(section, blocks.join(''));
        } else if (operation.type === 'DISCONNECT') {
          json(response, 422, { code: 'ORPHAN_NOT_ALLOWED', message: 'Behaviour nodes cannot be left disconnected',
            filePath, yamlPath: operation.yamlPath }); return;
        }
      }
      const graph = projection({ ...input, content }), document = documentModel(content);
      json(response, 200, { previousDigest: expectedDigest, contentDigest: sha(content), content, document,
        projection: graph, affectedPaths: operations.map(operation => operation.yamlPath || operation.parentYamlPath).filter(Boolean),
        appliedOperationCount: operations.length }); return;
    }
    if (url.pathname.endsWith('/projects/references')) {
      json(response, 200, { declarations: [
        { type: 'behavior', id: 'demo:walker', path: 'behaviors/walker.yml', line: 2, column: 5 },
        { type: 'behavior', id: 'demo:target', path: 'behaviors/target.yml', line: 1, column: 5 }], references: [] }); return;
    }
    if (url.pathname.endsWith('/projects/relationship-projection')) {
      const input = await body(request), sourceId = 'relationship:behavior:demo:walker', targetId = 'relationship:behavior:demo:target';
      const missingId = 'relationship:missing-dialogue:demo:missing';
      const node = (id, title, path) => ({ id, yamlPath: '/id', range: sourceRange(path === 'behaviors/walker.yml' ? initialContent : targetContent, title),
        kind: 'relationship-behavior', title, subtitle: path, fields: [], pins: [
          { id: id + ':in', nodeId: id, direction: 'input', semanticType: 'reference', cardinality: 'many', required: false, label: 'inbound', yamlPath: '/id' },
          { id: id + ':out:reference', nodeId: id, direction: 'output', semanticType: 'reference', cardinality: 'many', required: false, label: 'references', yamlPath: '/id' }],
        badges: [], custom: false, extensionOwner: null });
      json(response, 200, { graphVersion: 3, resourceIdentity: 'project:relationship-map', resourceKind: 'relationship',
        resourceId: 'project', filePath: '', rootYamlPath: '', contentDigest: input.expectedRevision, editable: false,
        nodes: [node(sourceId, 'demo:walker', 'behaviors/walker.yml'), node(targetId, 'demo:target', 'behaviors/target.yml'),
          { id: missingId, yamlPath: '', range: sourceRange(initialContent, 'demo:walker'), kind: 'missing-reference',
            title: 'demo:missing', subtitle: 'Missing dialogue', fields: [], pins: [
              { id: missingId + ':in', nodeId: missingId, direction: 'input', semanticType: 'reference:dialogue', cardinality: 'many', required: true, label: 'unresolved', yamlPath: '' }],
            badges: ['unresolved'], custom: false, extensionOwner: null }],
        edges: [{ id: 'relationship:0', sourcePinId: sourceId + ':out:reference', targetPinId: targetId + ':in',
          semanticType: 'reference:behavior', label: 'behavior', sourceYamlPath: '/root', targetYamlPath: '/id', resolved: true, cyclic: true },
          { id: 'relationship:1', sourcePinId: sourceId + ':out:reference', targetPinId: missingId + ':in',
            semanticType: 'reference:dialogue', label: 'dialogue', sourceYamlPath: '/dialogue', targetYamlPath: '', resolved: false, cyclic: false }],
        diagnostics: [{ code: 'UNRESOLVED_REFERENCE', severity: 'ERROR', message: 'Missing dialogue demo:missing', filePath: 'behaviors/walker.yml',
          yamlPath: '/dialogue', range: sourceRange(initialContent, 'demo:walker'), nodeId: missingId,
          relatedResourceKind: 'dialogue', relatedResourceId: 'demo:missing' }], capabilities: ['SELECT', 'PAN_ZOOM', 'AUTO_LAYOUT', 'INSPECT', 'OPEN_SOURCE', 'OPEN_TARGET'] }); return;
    }
    if (url.pathname.endsWith('/projects/template')) {
      const kind = url.searchParams.get('kind'), id = url.searchParams.get('id');
      const path = (kind === 'behavior' ? 'behaviors' : kind + 's') + '/' + id.split(':').at(-1) + '.yml';
      const content = template(kind, id);
      json(response, 200, { kind, id, path, content }); return;
    }
    if (url.pathname.endsWith('/projects/safe-path')) {
      const kind = url.searchParams.get('kind'), id = url.searchParams.get('id');
      json(response, 200, { kind, id, path: (kind === 'behavior' ? 'behaviors' : kind + 's') + '/' + id.split(':').at(-1) + '.yml' }); return;
    }
    if (url.pathname.endsWith('/projects/create')) {
      const input = await body(request), files = [...input.files];
      const content = template(input.kind, input.id);
      files.push({ path: input.path, content, sha256: sha(content) });
      json(response, 200, { revision: revision(files), files, affectedPaths: [input.path], warnings: [] }); return;
    }
    if (url.pathname.endsWith('/projects/semantic-diff')) {
      const input = await body(request), before = new Map(input.before.map(file => [file.path, file.content]));
      const after = new Map(input.after.map(file => [file.path, file.content]));
      const changes = [...new Set([...before.keys(), ...after.keys()])].filter(path => before.get(path) !== after.get(path))
        .map(path => ({ category: 'RESOURCE', path, yamlPath: '', change: !before.has(path) ? 'ADDED' : !after.has(path) ? 'REMOVED' : 'CHANGED',
          beforeValue: before.get(path) ?? null, afterValue: after.get(path) ?? null }));
      json(response, 200, { changes }); return;
    }
    if (url.pathname.endsWith('/export')) {
      const input = await body(request);
      response.writeHead(200, { 'content-type': 'application/zip', 'content-disposition': 'attachment; filename="persona-project.zip"' });
      response.end(Buffer.from(`fixture archive with ${input.files.length} files`)); return;
    }
    if (url.pathname.endsWith('/projects/rename-preview')) {
      const input = await body(request), source = input.files.find(file => file.content.includes(`id: ${input.currentId}`));
      json(response, 200, { safe: Boolean(source), conflicts: source ? [] : ['Declaration not found'], occurrences: source
        ? [{ role: 'declaration', path: source.path, line: 1, column: 5, yamlPath: '/id' }] : [] }); return;
    }
    if (url.pathname.endsWith('/projects/duplicate')) {
      const input = await body(request), files = input.files.map(file => ({ ...file }));
      const source = files.find(file => file.content.includes(`id: ${input.sourceId}`));
      const content = source.content.replace(`id: ${input.sourceId}`, `id: ${input.replacementId}`);
      files.push({ path: input.replacementPath, content, sha256: sha(content) });
      json(response, 200, { revision: revision(files), files, affectedPaths: [input.replacementPath], warnings: ['References remain unchanged.'] }); return;
    }
    if (url.pathname.endsWith('/projects/rename')) {
      const input = await body(request), files = input.files.map(file => ({ ...file }));
      const source = files.find(file => file.content.includes(`id: ${input.currentId}`));
      source.content = source.content.replaceAll(input.currentId, input.replacementId); source.sha256 = sha(source.content);
      if (input.renameFile) source.path = input.replacementPath;
      json(response, 200, { revision: revision(files), files, affectedPaths: [source.path], warnings: [] }); return;
    }
    if (url.pathname.endsWith('/projects/move')) {
      const input = await body(request), files = input.files.map(file => ({ ...file }));
      const source = files.find(file => file.content.includes(`id: ${input.id}`)); source.path = input.replacementPath;
      json(response, 200, { revision: revision(files), files, affectedPaths: [source.path, input.replacementPath], warnings: [] }); return;
    }
    if (url.pathname.endsWith('/projects/delete')) {
      const input = await body(request), files = input.files.filter(file => !file.content.includes(`id: ${input.id}`));
      json(response, 200, { revision: revision(files), files, affectedPaths: input.files.filter(file => !files.includes(file)).map(file => file.path), warnings: [] }); return;
    }
    if (url.pathname.endsWith('/projects/extract-script')) {
      const input = await body(request), files = input.files.map(file => ({ ...file }));
      const source = files.find(file => file.path === input.sourcePath);
      const exact = '        say: { type: say, text: "New dialogue line" }\n';
      source.content = source.content.replace(exact, `        say:\n          type: run-script\n          script: ${input.scriptId}\n          inputs: {}\n`);
      source.sha256 = sha(source.content);
      const scriptPath=`scripts/${input.scriptId.split(':').at(-1)}.yml`;
      const content=`content-version: 2\nid: ${input.scriptId}\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n  entry:\n    type: say\n    text: "New dialogue line"\nconnections:\n  enter: { from: $input.exec, to: entry.exec }\n  leave: { from: entry.success, to: $output.exec }\n`;
      files.push({ path: scriptPath, content, sha256: sha(content) });
      json(response, 200, { revision: revision(files), files, affectedPaths: [source.path, scriptPath], warnings: [] }); return;
    }
    if (url.pathname.endsWith('/projects/create-and-assign')) {
      const input = await body(request), files = input.files.map(file => ({ ...file }));
      const source = files.find(file => file.path === input.sourcePath);
      const kind = input.assignment === 'typed-reference' ? input.targetKind
        : input.assignment === 'npc-dialogue' ? 'dialogue' : 'behavior';
      const assignment = kind === 'dialogue' ? `dialogues:\n  - id: ${input.targetId}\n`
        : `player-behavior: ${input.targetId}\n`;
      if (input.assignment === 'typed-reference') source.content = source.content.replace(input.targetId, input.targetId);
      else source.content += assignment;
      source.sha256 = sha(source.content);
      const path = `${kind === 'behavior' ? 'behaviors' : kind + 's'}/${input.targetId.split(':').at(-1)}.yml`;
      const existing = files.find(file => file.path === path);
      if (!existing) {
        const content = template(kind, input.targetId); files.push({ path, content, sha256: sha(content) });
      }
      json(response, 200, { revision: revision(files), files, affectedPaths: [source.path, path], warnings: [] }); return;
    }
    if (url.pathname.includes('/drafts/')) {
      json(response, 200, { draftId: url.pathname.split('/').at(-1), stale: false, updatedAt: new Date().toISOString() }); return;
    }
    if (request.method === 'POST' && url.pathname.endsWith('/publishes')) {
      const input = await body(request), publishId = '22222222-2222-4222-8222-222222222222';
      publishes.set(publishId, { publishId, status: 'PUBLISHED', activeRevision: input.proposedRevision,
        backupId: 'fixture-backup', error: null });
      json(response, 200, { publishId, status: 'REQUESTED', requestedAt: new Date().toISOString() }); return;
    }
    if (request.method === 'GET' && url.pathname.includes('/publishes/')) {
      const value = publishes.get(url.pathname.split('/').at(-1));
      json(response, value ? 200 : 404, value || { message: 'not found' }); return;
    }
    json(response, 404, { message: 'Fixture route not found: ' + url.pathname });
  } catch (error) { json(response, 500, { message: error.stack || error.message }); }
});
server.listen(4173, '127.0.0.1');
