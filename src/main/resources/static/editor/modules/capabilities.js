export function hasGraphCapability(projection, capability) {
  return Boolean(projection?.editable) && Array.isArray(projection.capabilities)
    && projection.capabilities.includes(capability);
}

export function graphCapabilityReason(projection, capability) {
  if (!projection) return 'No graph is open.';
  if (!projection.editable) return projection.readOnlyReason || 'This graph is read only.';
  return projection.capabilities?.includes(capability) ? '' : `The projection does not grant ${capability}.`;
}
