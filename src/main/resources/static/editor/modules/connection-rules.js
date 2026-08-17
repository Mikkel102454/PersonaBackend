/** Pure client-side advisory checks. The server repeats every rule authoritatively. */
export function connectionCompatibility(source, target, { incoming = [], wouldCycle = false } = {}) {
  if (!source || !target) return { valid: false, reason: 'Choose two existing pins.' };
  if (source.direction !== 'output' || target.direction !== 'input')
    return { valid: false, reason: 'Connections run from an output pin to an input pin.' };
  const semanticMatch = source.semanticType === target.semanticType
    || source.semanticType === 'reference' && target.semanticType.startsWith('reference:')
    || target.semanticType === 'reference' && source.semanticType.startsWith('reference:');
  if (!semanticMatch) return { valid: false,
    reason: source.semanticType + ' outputs cannot connect to ' + target.semanticType + ' inputs.' };
  if (source.nodeId === target.nodeId) return { valid: false, reason: 'A node cannot connect to itself.' };
  if (wouldCycle) return { valid: false, reason: 'That connection would create a cycle that is not allowed here.' };
  return { valid: true, replace: target.cardinality === 'single' ? incoming : [] };
}
