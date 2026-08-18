const SVG = 'http://www.w3.org/2000/svg';

/** Renders the complete projection once per caller-scheduled animation frame. */
export function renderGraphMinimap({ element, positions, projection, viewport, canvasWidth, canvasHeight,
  selection, liveNodeKeys, diagnosticPaths, nodeWidth = 220, nodeHeight = 130 }) {
  const points = Object.values(positions);
  if (!points.length) { element.replaceChildren(); return; }
  const minX = Math.min(...points.map(point => point.x)) - 40, minY = Math.min(...points.map(point => point.y)) - 40;
  const maxX = Math.max(...points.map(point => point.x + nodeWidth)) + 40;
  const maxY = Math.max(...points.map(point => point.y + nodeHeight)) + 40;
  element.setAttribute('viewBox', `${minX} ${minY} ${Math.max(1, maxX - minX)} ${Math.max(1, maxY - minY)}`);
  const fragment = document.createDocumentFragment(), nodes = new Map(projection.nodes.map(node => [node.id, node]));
  for (const [id, point] of Object.entries(positions)) {
    const rect = document.createElementNS(SVG, 'rect'); rect.setAttribute('x', point.x); rect.setAttribute('y', point.y);
    rect.setAttribute('width', String(nodeWidth)); rect.setAttribute('height', String(nodeHeight));
    const node = nodes.get(id), classes = ['minimap-node'];
    if (selection.has(id)) classes.push('selected');
    if (node && (liveNodeKeys.has(node.title) || liveNodeKeys.has(node.yamlPath))) classes.push('live');
    if (node && [...diagnosticPaths].some(path => path && (path === node.yamlPath || path.startsWith(node.yamlPath + '/')))) classes.push('error');
    rect.setAttribute('class', classes.join(' ')); fragment.append(rect);
  }
  const visible = document.createElementNS(SVG, 'rect');
  visible.setAttribute('x', String(-viewport.x / viewport.zoom)); visible.setAttribute('y', String(-viewport.y / viewport.zoom));
  visible.setAttribute('width', String(canvasWidth / viewport.zoom)); visible.setAttribute('height', String(canvasHeight / viewport.zoom));
  visible.setAttribute('class', 'minimap-viewport'); fragment.append(visible); element.replaceChildren(fragment);
}
