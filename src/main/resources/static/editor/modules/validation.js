export function publicationReady(state, invalidCatalog = false) {
  return Boolean(state.verified?.capabilities?.includes('CONTENT_PUBLISH')
    && state.validationResult?.valid && state.validationResult.proposedRevision && state.draftId && !invalidCatalog);
}

export function validationHeading(result) {
  return result.valid ? `Validated by Persona content format ${result.contentFormatVersion}. No errors.`
    : `Persona found ${result.diagnostics.length} error${result.diagnostics.length === 1 ? '' : 's'}.`;
}

export function diagnosticLabel(issue) {
  const where = `${issue.path}:${issue.line}:${issue.column}${issue.nodeId ? ` · node ${issue.nodeId}` : ''}`
    + `${issue.referenceId ? ` · ${issue.referenceType} ${issue.referenceId}` : ''}`;
  return `${where} — ${issue.message}${issue.suggestion ? ` ${issue.suggestion}` : ''}`;
}
