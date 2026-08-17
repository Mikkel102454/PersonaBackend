# Visual editor architecture

The browser owns workspace state and optional layout only. Persona YAML, project revisions, signed
schemas/catalogs, validation, references, live state, and publication decisions remain server-owned.

`app.js` is the composition root. Versioned ES modules divide responsibilities:

- `workspace-state.js`: normalized document, graph, layout, validation, live, history, and dirty maps
- `workspace-shell.js`: Content Browser, tabs, breadcrumbs, quick open, and navigation history
- `transport.js`: authenticated session URLs, headers, and connection gating
- `yaml-documents.js`: source-model lookup shared by synchronization and inspector code
- `graph-canvas.js`, `graph-layout.js`: batched HTML/SVG rendering, viewport, selection, and layout
- `node-registry.js`, `node-renderer.js`, `connection-rules.js`, `graph-inspector.js`: common
  built-in/extension definitions, card/pin rendering, advisory compatibility, and fields
- `graph-mutations.js`, `command-dispatcher.js`: typed server mutations and one command route
- `graph-projection.js`, `live-overlays.js`, `validation.js`: nested projection views and pure
  conversion of trusted runtime/validation records to presentation state
- `layout-store.js`: bounded browser-only panel persistence

Graph endpoints enforce document, project-context, node, edge, operation, nesting, and request-rate
bounds. Mutations carry the expected content digest. Project refactors carry the expected complete
project revision and return a fully validated all-or-nothing candidate.

Rendering is scheduled with `requestAnimationFrame`; minimap geometry is coalesced to one update per
frame, and large graphs virtualize offscreen cards/wires while retaining the complete projection and
minimap. Keyboard navigation pans, renders, and focuses virtualized nodes. Selection and viewport
changes do not reparse the project. Only a changed document is reparsed/reprojected. The accepted
budgets and representative fixtures are documented in `QUALITY_BUDGETS.md` and enforced by backend,
frontend-unit, and Playwright performance tests.
