/** Evicts derived graph views whose signatures or referenced resources changed. */
export function invalidateAffectedProjections(projections, affectedResourceIds, retainedIdentity) {
  for (const identity of affectedResourceIds || []) {
    if (identity !== retainedIdentity) projections.delete(identity);
  }
}
