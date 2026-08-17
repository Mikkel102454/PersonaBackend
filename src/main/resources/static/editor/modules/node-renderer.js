/** Small data-only renderer interface. No schema or extension can inject executable frontend code. */
export class NodeRendererRegistry {
  constructor() { this.entries = []; }
  register(match, describe) { this.entries.push({ match, describe }); return this; }
  describeNode(node) {
    const entry = this.entries.find(value => value.match(node));
    return { title: node.title, subtitle: node.subtitle || node.kind, classes: [], badges: [],
      ...(entry ? entry.describe(node) : {}) };
  }
  describePin(pin) {
    return { text: pin.label, className: `graph-pin ${pin.direction}`,
      title: `${pin.semanticType} · ${pin.cardinality}${pin.required ? ' · required' : ' · optional'}`,
      ariaLabel: `${pin.direction} pin ${pin.label}, type ${pin.semanticType}, ${pin.cardinality}`
        + (pin.required ? ', required' : ', optional') };
  }
}

export function defaultNodeRenderers() {
  return new NodeRendererRegistry()
    .register(node => Boolean(node.extensionOwner), node => ({ classes: ['extension-owned'], badges: [node.extensionOwner] }))
    .register(node => node.custom, () => ({ classes: ['custom-yaml'], badges: ['custom data'] }))
    .register(node => node.kind === 'missing-reference', () => ({ classes: ['unresolved'], badges: ['unresolved'] }));
}
