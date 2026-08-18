/** Adapts the versioned wire contract to the renderer's compact presentation vocabulary. */
export function normalizeProjection(projection) {
  if (!projection || !Array.isArray(projection.nodes)) return projection;
  if (projection.graphVersion !== 3) throw new Error(`Unsupported graph projection version ${projection.graphVersion ?? 'missing'}; reload or update the editor.`);
  const pin = value => ({ ...value,
    direction: String(value.direction || '').toLowerCase(),
    cardinality: ['EXACTLY_ONE', 'ZERO_OR_ONE'].includes(value.cardinality) ? 'single'
      : ['ONE_OR_MANY', 'ZERO_OR_MANY'].includes(value.cardinality) ? 'many' : value.cardinality
  });
  const ports = (projection.ports || projection.nodes.flatMap(node => node.pins || [])).map(pin);
  const byId = new Map(ports.map(value => [value.id, value]));
  return { ...projection, ports, nodes: projection.nodes.map(node => ({ ...node,
    pins: (node.pins || []).map(value => byId.get(value.id) || pin(value)) })) };
}

/** Returns a nested view while retaining the full authoritative projection and stable identities. */
export function nestedProjection(full, nested) {
  if (!nested || nested.resourceIdentity !== full.resourceIdentity) return { projection: full, valid: true };
  const nodes = full.nodes.filter(node => node.id === nested.ownerNodeId
    || node.yamlPath && node.yamlPath.startsWith(nested.rootYamlPath + '/'));
  if (!nodes.some(node => node.id === nested.ownerNodeId)) return { projection: full, valid: false };
  const ids = new Set(nodes.map(node => node.id));
  const owners = new Map(full.nodes.flatMap(node => (node.pins || []).map(pin => [pin.id, node.id])));
  return { valid: true, projection: { ...full, nodes, edges: full.edges.filter(edge =>
    ids.has(owners.get(edge.sourcePinId)) && ids.has(owners.get(edge.targetPinId))),
  diagnostics: full.diagnostics.filter(issue => !issue.yamlPath || issue.yamlPath === nested.rootYamlPath
    || issue.yamlPath.startsWith(nested.rootYamlPath + '/')) } };
}
