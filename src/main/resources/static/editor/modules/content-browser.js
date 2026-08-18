export const CONTENT_WINDOW = 200;

export function resourceMatches(item, query, extraTerms = '') {
  return !query || `${item.search} ${extraTerms}`.toLowerCase().includes(query);
}

export function boundedResources(items, limit = CONTENT_WINDOW) {
  return items.slice(0, Math.max(CONTENT_WINDOW, Math.min(20_000, limit)));
}
