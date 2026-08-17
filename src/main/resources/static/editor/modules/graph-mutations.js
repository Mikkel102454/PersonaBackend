/**
 * Sends graph commands to the server-owned, digest-checked mutation compiler.
 * The caller supplies current normalized workspace state; this module never creates a
 * replacement graph or YAML document in the browser.
 */
export class GraphMutationClient {
  constructor(options) { this.options = options; this.inFlight = false; }

  async mutate(operations, label = 'Graph edit') {
    if (this.inFlight || !Array.isArray(operations) || !operations.length) return null;
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
          path: context.resource.path,
          resourceKind: context.resource.kind,
          resourceId: context.resource.id,
          yamlPath: context.resource.yamlPath || '',
          content: context.content,
          expectedDigest: context.projection.contentDigest,
          projectFiles: context.projectFiles,
          operations
        })
      });
      const result = await readJson(response);
      if (!response.ok) {
        this.options.rollbackHistory?.(context);
        if (response.status === 409 || result.code === 'STALE_CONTENT') this.options.onConflict?.(result);
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
  try { return JSON.parse(text); }
  catch { return { code: 'INVALID_RESPONSE', message: text }; }
}

export function contractMessage(error) {
  const location = [error.filePath, error.yamlPath].filter(Boolean).join(' ');
  return `${error.code ? error.code + ': ' : ''}${error.message || 'Graph mutation rejected'}${location ? ` (${location})` : ''}`;
}
