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
