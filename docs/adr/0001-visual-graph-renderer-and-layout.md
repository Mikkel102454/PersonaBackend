# ADR 0001: Browser-native HTML/SVG graph and local layout store

- Status: accepted
- Date: 2026-08-17
- Scope: Persona visual node editor

## Context

PersonaBackend ships one Spring Boot application and currently has no Node build
pipeline. The graph must preserve raw YAML, support accessible controls, work on touch
and keyboard, stay auditable in the source distribution, and avoid extension-supplied
frontend code. Layout is not Persona content and must survive ordinary editing and
resource navigation without contaminating YAML.

## Renderer spike

| Criterion | In-house HTML cards + SVG wires | Vendored graph library |
| --- | --- | --- |
| Bundle/build | ES modules and current Java packaging; no build step | library bundle, update process, source map and license packaging required |
| Accessibility | native buttons/forms/groups and explicit connections list | canvas/WebGL libraries need a parallel semantic tree; DOM libraries vary |
| Lossless command model | direct stable IDs/YAML paths on DOM elements | adapter still required; library models encourage a second graph authority |
| Touch/pointer | Pointer Events implemented once for cards, pins, canvas | mature gestures possible, but behavior/library-specific |
| Performance target | sufficient for bounded 2,000-node/4,000-edge documents with virtualization | likely stronger at extreme sizes that Persona intentionally rejects |
| Licensing/audit | project-owned code only | dependency license/SBOM/update surface |
| Current runtime | native match | introduces a foreign bundling/runtime constraint |

The spike selects browser-native HTML node cards in a transformed scene with one SVG
wire layer. Pointer Events provide mouse, pen, and touch handling. Rendering uses a
small registry shared by built-in and schema-generated nodes. Edges are batched in one
animation frame; off-viewport card detail may be reduced, but semantic nodes remain
available to assistive technology through a synchronized list.

A graph library may be reconsidered only if measured fixtures miss the accepted
budgets after profiling and the ADR is superseded with the exact bundle, license,
accessibility, and migration evidence.

## Layout persistence decision

Layout records are stored in IndexedDB, with a bounded localStorage fallback for
preferences only. The key is:

```text
persona-layout/v1/{installationId}/{projectRevision}/{contentKind}/{resourceId}
```

The value contains schema version, resource ID, last-used timestamp, viewport, panel
state, and stable node ID to `{x,y,width?,collapsed?,color?,group?,bookmark?}`. It never
contains YAML content, field values, runtime data, credentials, or capabilities.

- A resource rename copies the current layout to its new identity only after the
  server's atomic rename succeeds, then deletes the old key.
- A content revision first looks for an exact key; when absent it migrates the latest
  prior layout by stable node ID and drops unmatched entries.
- Deterministic auto-layout seeds only nodes without a usable position and is an
  undoable layout command, not a YAML mutation.
- On startup and every 100 writes, entries older than 90 days are pruned, then oldest
  entries are removed until no more than 2,000 resources or 20 MiB remain per origin.
- Signing out or losing the connection does not expose cached content; layout alone
  may remain. A user command clears all local Persona layout/preferences.

## Consequences

The frontend must be split into ES modules for transport, state, projection, registry,
viewport, selection, rendering, inspector, commands, validation/live overlays, and
persistence. Spring must serve maintainable static HTML instead of controller string
replacement. The graph contract remains independent of renderer and layout versions.

