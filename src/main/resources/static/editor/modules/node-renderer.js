import { describePort } from './port-renderer.js';

const TITLES = new Map([
  ['say', 'Say line'], ['run-script', 'Run reusable script'], ['branch', 'Boolean branch'],
  ['do-n', 'Do N'], ['for', 'For loop'], ['for-each', 'For each']
]);

function operationTitle(node) {
  const type = String(node.subtitle || node.kind || '').replace(/^(?:script|flow)-/, '');
  if (TITLES.has(type)) return TITLES.get(type);
  return type.split('-').filter(Boolean).map(word => word.toUpperCase() === 'npc' ? 'NPC'
    : word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

/** Small data-only renderer interface. No schema or extension can inject executable frontend code. */
export class NodeRendererRegistry {
  constructor() { this.entries = []; }
  register(match, describe) { this.entries.push({ match, describe }); return this; }
  describeNode(node) {
    const entry = this.entries.find(value => value.match(node));
    return { title: node.title, subtitle: node.subtitle || node.kind, classes: [], badges: [],
      ...(entry ? entry.describe(node) : {}) };
  }
  describePin(pin) { return describePort(pin); }
}

export function defaultNodeRenderers() {
  return new NodeRendererRegistry()
    .register(node => node.kind?.startsWith('flow-')
      || node.kind?.startsWith('script-') && !['script-input', 'script-output'].includes(node.kind)
      || node.kind === 'extension-command', node => ({ title: operationTitle(node), subtitle: '',
        classes: node.extensionOwner ? ['extension-owned'] : [], badges: node.extensionOwner ? [node.extensionOwner] : [] }))
    .register(node => Boolean(node.extensionOwner), node => ({ classes: ['extension-owned'], badges: [node.extensionOwner] }))
    .register(node => node.custom, () => ({ classes: ['custom-yaml'], badges: ['custom data'] }))
    .register(node => node.kind === 'missing-reference', () => ({ classes: ['unresolved'], badges: ['unresolved'] }));
}
