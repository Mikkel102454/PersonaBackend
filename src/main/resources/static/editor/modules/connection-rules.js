/** Pure client-side advisory checks. The server repeats every rule authoritatively. */
export function connectionCompatibility(source, target, { incoming = [], outgoing = [], wouldCycle = false, capabilities = [], resourceScope = 'CURRENT_RESOURCE' } = {}) {
  if (!source || !target) return { valid: false, reason: 'Choose two existing pins.' };
  if (String(source.direction).toUpperCase() !== 'OUTPUT' || String(target.direction).toUpperCase() !== 'INPUT')
    return { valid: false, reason: 'Connections run from an output pin to an input pin.' };
  if (source.channel !== target.channel) return { valid: false,
    reason: `${source.channel?.toLowerCase()} outputs cannot connect to ${target.channel?.toLowerCase()} inputs.` };
  if (source.channel === 'DATA' && source.valueType !== target.valueType) return { valid: false,
    reason: `${source.valueType} outputs require an exact ${source.valueType} input; this input is ${target.valueType}.` };
  const semanticMatch = source.channel === 'DATA' || source.semanticType === target.semanticType;
  if (!semanticMatch) return { valid: false,
    reason: source.semanticType + ' outputs cannot connect to ' + target.semanticType + ' inputs.' };
  const allows = (port, type) => !port.compatibility?.semanticTypes?.length
    || port.compatibility.semanticTypes.some(value => value === type
      || value === 'reference' && type.startsWith('reference:')
      || type === 'reference' && value.startsWith('reference:'));
  if (!allows(source, target.semanticType) || !allows(target, source.semanticType))
    return { valid: false, reason: 'The signed port compatibility contract rejects this semantic type.' };
  if (![source, target].every(port => !port.compatibility?.resourceScopes?.length
      || port.compatibility.resourceScopes.includes(resourceScope)))
    return { valid: false, reason: 'These ports are not compatible in the current resource scope.' };
  const required = new Set([...(source.compatibility?.capabilityRequirements || []),
    ...(target.compatibility?.capabilityRequirements || [])]);
  for (const capability of required) if (!capabilities.includes(capability))
    return { valid: false, reason: `This connection requires the ${capability} capability.` };
  if (source.nodeId === target.nodeId) return { valid: false, reason: 'A node cannot connect to itself.' };
  const cyclesAllowed = source.compatibility?.cyclePolicy === 'ALLOW' && target.compatibility?.cyclePolicy === 'ALLOW';
  if (wouldCycle && !cyclesAllowed) return { valid: false, reason: 'That connection would create a cycle that is not allowed here.' };
  const replace = ['single', 'EXACTLY_ONE', 'ZERO_OR_ONE'].includes(target.cardinality) ? [...incoming] : [];
  if (source.channel === 'EXECUTION' && ['single', 'EXACTLY_ONE', 'ZERO_OR_ONE'].includes(source.cardinality))
    replace.push(...outgoing);
  return { valid: true, replace: [...new Map(replace.map(edge => [edge.id, edge])).values()] };
}
