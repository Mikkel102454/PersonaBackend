export function connectionOwners(projection) {
  return new Map((projection?.ports || projection?.nodes?.flatMap(node => node.pins || []) || [])
    .map(port => [port.id, port.nodeId]));
}

export function connectionsForNode(projection, nodeId) {
  const owners = connectionOwners(projection);
  return (projection?.edges || []).filter(edge => owners.get(edge.sourcePinId) === nodeId
    || owners.get(edge.targetPinId) === nodeId);
}
