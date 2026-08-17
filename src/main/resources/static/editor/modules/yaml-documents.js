export function findModelNode(node, path) {
  if (!node || path == null) return null;
  if (node.path === path) return node;
  for (const child of node.children || []) {
    const found = findModelNode(child, path); if (found) return found;
  }
  return null;
}
