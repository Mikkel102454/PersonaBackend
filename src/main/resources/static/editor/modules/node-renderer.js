import { describePort } from './port-renderer.js';

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
    // Inline `say` steps are shared by dialogues, quests, and NPC lifecycle hooks. Show the
    // operation as the title so placeholder copy such as "New line" is not mistaken for a type.
    .register(node => node.kind === 'script-say', node => ({ title: 'Say line', subtitle: node.title }))
    .register(node => Boolean(node.extensionOwner), node => ({ classes: ['extension-owned'], badges: [node.extensionOwner] }))
    .register(node => node.custom, () => ({ classes: ['custom-yaml'], badges: ['custom data'] }))
    .register(node => node.kind === 'missing-reference', () => ({ classes: ['unresolved'], badges: ['unresolved'] }));
}
