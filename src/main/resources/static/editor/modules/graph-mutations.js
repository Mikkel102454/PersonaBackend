/**
 * Sends graph commands to the server-owned, digest-checked mutation compiler.
 * The caller supplies current normalized workspace state; this module never creates a
 * replacement graph or YAML document in the browser.
 */
export class GraphMutationClient {
  constructor(options) { this.options = options; this.inFlight = false; this.tail = Promise.resolve(); }

  mutate(operations, label = 'Graph edit') {
    if (!Array.isArray(operations) || !operations.length) return Promise.resolve(null);
    const queued = this.tail.then(() => this.perform(operations, label));
    this.tail = queued.catch(() => null);
    return queued;
  }

  async perform(operations, label) {
    let context;
    try { context = await this.options.context(); }
    catch (error) { this.options.onError?.(label, error); return null; }
    if (!context?.projection?.editable) {
      this.options.onError?.(label, new Error('The authoritative graph is not editable.')); return null;
    }
    this.inFlight = true;
    this.options.recordHistory?.(context);
    try {
      const response = await fetch(this.options.endpoint(), {
        method: 'POST',
        headers: this.options.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({
          graphVersion: context.projection.graphVersion,
          requestId: crypto.randomUUID(),
          resourceIdentity: context.projection.resourceIdentity,
          filePath: context.resource.path,
          resourceKind: context.resource.kind,
          resourceId: context.resource.id,
          rootYamlPath: context.resource.yamlPath || '',
          content: context.content,
          expectedContentDigest: context.projection.contentDigest,
          expectedProjectRevision: context.projectRevision || null,
          projectFiles: context.projectFiles,
          operations
        })
      });
      let result = await readJson(response);
      if (!response.ok) {
        if (response.status === 403 && (!result || typeof result !== 'object' || !result.code)) result = {
          ...(result && typeof result === 'object' ? result : {}),
          code: 'DRAFT_EDIT_REQUIRED',
          message: this.options.forbiddenMessage?.() || 'This graph edit requires additional session trust.'
        };
        this.options.rollbackHistory?.(context);
        if (response.status === 409 || ['STALE_CONTENT', 'STALE_PROJECTION', 'STALE_PROJECT_REVISION', 'STALE_SOURCE_RANGE'].includes(result.code)) this.options.onConflict?.(result);
        else this.options.onContractError?.(label, result);
        return null;
      }
      if (context.isCurrent()) await this.options.onApplied(result, { label, operations, context });
      return result;
    } catch (error) {
      if (context.isCurrent()) {
        this.options.rollbackHistory?.(context);
        this.options.onError?.(label, error);
      }
      return null;
    } finally {
      this.inFlight = false;
      this.options.onSettled?.();
    }
  }
}

async function readJson(response) {
  const text = await response.text();
  if (!text) return { code: 'EMPTY_RESPONSE', message: `HTTP ${response.status}` };
  try {
    const result = JSON.parse(text);
    if (result && typeof result === 'object' && !result.message)
      result.message = result.detail || result.error || `HTTP ${response.status}`;
    return result;
  }
  catch { return { code: 'INVALID_RESPONSE', message: text }; }
}

export function contractMessage(error) {
  const location = [error.filePath, error.yamlPath].filter(Boolean).join(' ');
  return `${error.code ? error.code + ': ' : ''}${error.message || 'Graph mutation rejected'}${location ? ` (${location})` : ''}`;
}

export function inlineDefaultOperation(projection, pin, value) {
  const owner = (projection?.nodes || []).find(node => node.id === pin?.nodeId);
  const belongsToExplicitGraph = projection?.resourceKind === 'script' || owner?.yamlPath?.includes('/nodes/');
  return belongsToExplicitGraph
    ? { type: 'SET_PIN_DEFAULT', targetPinId: pin.id, value }
    : { type: 'EDIT_FIELD', yamlPath: pin.yamlPath, value };
}
