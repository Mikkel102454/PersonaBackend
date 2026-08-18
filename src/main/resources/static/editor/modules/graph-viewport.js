export function normalizeViewport(value) {
  return {
    x: Number.isFinite(value?.x) ? Math.max(-100000, Math.min(100000, value.x)) : 40,
    y: Number.isFinite(value?.y) ? Math.max(-100000, Math.min(100000, value.y)) : 40,
    zoom: Number.isFinite(value?.zoom) ? Math.max(.2, Math.min(2.5, value.zoom)) : 1
  };
}

export function fitViewport(points, width, height, nodeWidth = 220, nodeHeight = 130) {
  if (!points.length) return null;
  const minX = Math.min(...points.map(point => point.x)), minY = Math.min(...points.map(point => point.y));
  const maxX = Math.max(...points.map(point => point.x + nodeWidth)), maxY = Math.max(...points.map(point => point.y + nodeHeight));
  const zoom = Math.max(.2, Math.min(1.5, Math.min((width - 80) / Math.max(1, maxX - minX),
    (height - 80) / Math.max(1, maxY - minY))));
  return { zoom, x: 40 - minX * zoom, y: 40 - minY * zoom };
}
