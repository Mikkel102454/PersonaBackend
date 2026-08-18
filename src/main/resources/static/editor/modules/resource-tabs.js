export function reorderTabs(tabs, identity, index) {
  const result = [...tabs], from = result.indexOf(identity); if (from < 0) return result;
  result.splice(from, 1); result.splice(Math.max(0, Math.min(result.length, index)), 0, identity); return result;
}

export function closeTabsToRight(tabs, index) {
  return { kept: tabs.slice(0, index + 1), closed: tabs.slice(index + 1) };
}
