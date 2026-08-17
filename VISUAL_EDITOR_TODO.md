# Visual node editor TODO

## Goal

Replace the current visual YAML form with an Unreal-style node workspace that makes
Persona content easy to discover, create, connect, inspect, and validate. A user
should be able to create and move between NPCs, dialogues, quests, behaviours, and
reusable scripts without leaving the editor or manually creating files.

The editor is a server-connected experience. It must only be available while an
active, authenticated Persona server session is connected; there is no offline
editor or locally editable fallback.

The node editor is a visual projection of the project, not a second content format.
Raw YAML remains the authoritative editable document and must continue to round-trip
without losing comments, ordering, anchors, aliases, custom tags, unknown fields, or
extension-owned data.

## Non-negotiable constraints

- [x] Keep `YamlDocumentService` and raw YAML as the source of truth. Never serialize
  a browser-owned graph over an entire YAML file.
- [x] Give every visual node, pin, field, and diagnostic a stable file path and YAML
  path/source range.
- [x] Translate node operations into narrow, lossless scalar or structural patches,
  then parse the returned YAML and rebuild the affected graph.
- [x] Do not allow visual editing while the selected file has invalid YAML. Keep the
  last valid graph visible, mark it stale, and make the raw error easy to navigate to.
- [x] Require an active, authenticated server connection before opening the editor or
  loading project content. Gate every read and mutation by the existing session and
  capability checks.
- [x] Do not provide an offline editor, offline project loading, or a locally editable
  fallback. If the connection is lost, stop editing immediately and show a reconnect
  state until the server session is restored.
- [x] Preserve server-backed import/export and the existing session, capability,
  draft, validation, semantic diff, publication, recovery, and undo/redo flows.
- [x] Keep extension support data-driven through signed editor schemas and catalogs;
  extensions must not inject frontend code.
- [x] Treat live server data as a read-only overlay unless the existing explicit,
  capability-gated mutation confirmation flow is used.
- [x] Maintain keyboard and screen-reader access and respect reduced-motion settings.

## Target user experience

### Workspace shell and navigation

- [x] Replace the flat file list with a **Content Browser** grouped by:
  `NPCs`, `Dialogues`, `Quests`, `Behaviours`, `Scripts`, and `Other YAML`.
- [x] Display content names/IDs as the primary label and file paths as secondary
  information. Show counts on each group.
- [x] Add search across ID, display name/title, type, tag, referenced content, and
  file path.
- [x] Add filters for content kind, dirty/clean, valid/invalid, referenced/unreferenced,
  missing references, and live/active status.
- [x] Add sort modes for name, kind, file path, recently opened, and validation state.
- [x] Add a compact/list toggle and collapsible groups; remember the choice locally.
- [x] Add editor tabs for open resources with kind icon, dirty marker, error badge,
  close button, middle-click close, tab reordering, and reopen-closed support.
- [x] Support `Ctrl/Cmd+P` quick-open, `Ctrl/Cmd+Tab` recent-resource switching,
  `Ctrl/Cmd+K` commands, and back/forward navigation history.
- [x] Add breadcrumbs above the canvas: project → content kind → resource → nested
  graph/subgraph. Each segment must be clickable.
- [x] Keep selection and viewport per open tab so switching resources does not lose
  context.
- [x] Add a project **Relationship Map** showing typed links between resources, such
  as NPC → behaviour/dialogue, dialogue → dialogue/script, and quest → script. This
  view is for project navigation and dependency inspection; opening a resource enters
  its editable internal graph.
- [x] In the Relationship Map, distinguish resolved, unresolved, inbound, and cyclic
  links and provide “open source” / “open target” actions.

### Main editor layout

- [x] Use a resizable four-part layout:
  Content Browser on the left, node canvas in the center, Details/Inspector on the
  right, and collapsible Problems/References/YAML/Changes output below.
- [x] Allow panels to collapse and reset to a known default layout. Persist layout
  preferences only in the browser, not in project YAML.
- [x] Provide a clear Visual/YAML split-view toggle. Selecting either a graph element
  or YAML location must reveal and select its counterpart.
- [x] Show autosave state, validation state, base revision, server connection state,
  and publication readiness without taking canvas space. Replace the editable
  workspace with a reconnect state whenever the server connection is unavailable.
- [x] Move references, semantic diff, simulation, and live inspection into dockable
  workspace panels instead of separate disconnected dialogs where practical.

### Node canvas interaction

- [x] Implement an infinite canvas with pan, cursor-centered zoom, zoom-to-fit,
  frame-selection, minimap, optional grid, and reset-view controls.
- [x] Render nodes as accessible HTML controls and connections as an SVG layer so
  labels, fields, focus, and screen readers remain usable.
- [x] Support click, marquee, additive selection, multi-select move, alignment,
  distribution, duplicate, delete, copy/paste, and keyboard nudging.
- [x] Open a searchable node palette from an Add button, right-click, or dragging from
  a compatible output pin. Filter the palette to nodes valid at that destination.
- [x] Give pins a direction, semantic type, cardinality, required/optional state, and
  compatible connection rules. Reject an invalid connection before changing YAML and
  explain why.
- [x] Support drag-to-connect, reconnect, disconnect, insert-node-on-wire, and a small
  reroute/comment node that stores layout metadata only.
- [x] Use visible connection labels for semantics that would otherwise be ambiguous,
  such as `success`, `failure`, `choice`, `next phase`, and `reference`.
- [x] Put common properties directly on compact node cards; edit all remaining schema
  fields in the Details panel.
- [x] Show node badges for validation errors, warnings, dirty fields, custom data,
  extension ownership, unresolved references, and active live runtime state.
- [x] Add comments/groups, color labels, collapse/expand, bookmarks, and “focus
  upstream/downstream”. Keep purely visual layout data out of content YAML unless a
  dedicated, versioned editor-metadata format is approved.
- [x] Add an auto-layout command that is deterministic, undoable, and never changes
  content semantics.
- [x] Make every graph command participate in the existing per-file undo/redo and tab
  recovery system. A compound gesture, such as inserting and connecting a node, is
  one undo step.

## Graph models by content kind

### Behaviours

- [x] Render the behaviour root and its child tree as execution nodes with explicit
  parent/child order.
- [x] Provide first-class cards for sequence, selector/priority selector, parallel,
  action, condition, checkpoint, wait/cooldown, and supported control/decorator types.
- [x] Represent ordered children with numbered pins or a clearly reorderable output
  list; rewiring must preserve YAML sequence ordering.
- [x] Expose scope, thresholds, timeout/deadline, checkpoint persistence, and failure
  semantics in the Details panel.
- [x] Add extension action/condition nodes from signed schemas, including catalog-backed
  fields and dependent inputs.
- [x] Preserve the existing extract-subtree operation as “Convert selection to
  behaviour”, create the new file, replace the source subtree with a reference, and
  open the new behaviour in a tab.
- [x] Overlay active paths, recent outcomes, conditions, wake deadlines, and checkpoints
  from trusted live data without changing graph structure.

### Dialogues

- [x] Render dialogue entries as nodes and transfers/choices as directed connections.
- [x] Provide nodes for say/line, choice, conditional branch, wait, script/action,
  goto/transfer, and end-dialogue operations.
- [x] Make the start node visually distinct and provide an explicit “Set as start”
  command.
- [x] Label choice pins with player-facing choice text or localization key.
- [x] Show missing destinations, unreachable nodes, implicit ends, and transfer loops
  directly on the canvas using existing diagnostics plus local advisory analysis.
- [x] Include line text, translations/localization keys, delay, placeholders, and
  conditions in the Details panel and preview.
- [x] Allow a transfer to another dialogue to open that dialogue in a new tab while
  retaining back navigation.
- [x] Overlay current line, eligible choices, wait deadline, and cancellation state
  when trusted live dialogue data is available.

### Quests

- [x] Render phases as the primary flow graph, with entry, branches, next-phase links,
  and terminal completion nodes.
- [x] Open objectives and lifecycle scripts as nested graphs from their phase card.
- [x] Provide nodes for built-in objectives, requirements/conditions, phase branches,
  lifecycle scripts, completion, and extension-defined objectives.
- [x] Show required/optional and visible/hidden objectives, amounts/durations, timers,
  repeatability, cooldown, and maximum completions in the Details panel.
- [x] Detect and highlight unreachable phases, missing phase destinations, impossible
  branches, duplicate IDs, and accidental cycles.
- [x] Overlay current phase, objective progress, timer deadline, recent transitions,
  and completion count from trusted live data.

### NPCs

- [x] Use an NPC overview graph with a central NPC node connected to presentation,
  anchors, shared behaviour, player behaviour, dialogue set, and lifecycle scripts.
- [x] Make referenced behaviour/dialogue cards open their resource in a new tab and
  show missing-reference actions when unresolved.
- [x] Provide an anchor list/map subview with coordinate paste, world catalog selection,
  duplicate-anchor checks, and distance warnings from the existing live preview.
- [x] Keep presentation fields, skin/equipment/age/pose, scope, and extension-defined
  properties editable through schema-driven Details controls.
- [x] Overlay shared/private presentation and projection state from trusted live data.

### Reusable scripts and unknown content

- [x] Render each reusable script as its own selectable graph while retaining
  `scripts.yml` as the authoritative source file.
- [x] Provide built-in command, condition, if, random, call/reference, and terminal
  nodes, plus extension-defined command/condition nodes.
- [x] Allow “Extract selection to reusable script” and safe navigation to all callers.
- [x] Keep unrecognized or unsupported YAML visible as a **Custom YAML** node with its
  exact source range. It may be edited in YAML but must never be silently dropped.
- [x] Fall back to the current structured form/YAML view for content that cannot yet
  be represented safely as a graph.

## Create, duplicate, rename, and delete content in the UI

- [x] Add a global **Create** menu and `Ctrl/Cmd+N` flow for NPC, dialogue, quest,
  behaviour, and reusable script.
- [x] Build a creation wizard with:
  content kind, namespaced ID, suggested safe filename, optional template, and a
  review of the initial YAML before creation.
- [x] Validate IDs and paths on both client and server. Reject duplicates, traversal,
  invalid extensions, reserved paths, case-folding collisions, and project size/file
  count overflow.
- [x] Offer useful minimal templates:
  empty behaviour root, single-line dialogue, one-phase quest, NPC with optional
  anchor/behaviour/dialogue links, and empty reusable script.
- [x] Let users create a missing referenced resource from a pin or diagnostic. Prefill
  its kind and ID, create it, connect it, and open it without losing current context.
- [x] Add “Create and assign” actions on NPC behaviour/dialogue fields and quest/script
  references.
- [x] Implement duplicate as a server-produced lossless file/subtree copy with a new
  ID and path; preview references that remain pointed at the original.
- [x] Turn the current rename preview into an applyable atomic project operation that
  updates the declaration, all typed references, and filename when requested. Show
  every patch before applying it and abort the whole operation on a conflict.
- [x] Add delete with inbound-reference analysis. Default to blocking deletion when
  references exist; offer navigation to each caller and only permit an explicit
  reviewed force-delete policy if one is defined.
- [x] Ensure additions, renames, moves, and deletions appear in dirty state, recovery,
  draft patches, semantic diff, export, validation, and publication.

## Backend and data contracts

- [x] Introduce a typed `EditorGraphProjection` built from `YamlDocumentResponse` and
  content/schema metadata. Include stable graph node IDs, YAML paths/ranges, node kind,
  fields, typed pins, edges, diagnostics, and graph capabilities.
- [x] Define stable visual identities from content ID plus YAML path/stable node ID;
  never use canvas coordinates or array indexes as the only identity when a semantic
  ID exists.
- [x] Add bounded projection endpoints per document/resource. Version the graph
  contract independently so cached browser layouts can be invalidated safely.
- [x] Add typed, bounded mutation requests for connect, disconnect, insert, delete,
  reorder, wrap, unwrap, and compound operations. Require expected content digest (or
  revision) and return conflict details instead of applying to stale YAML.
- [x] Compile every mutation to minimal source-range edits inside
  `YamlDocumentService`; return the updated raw content, document model, graph
  projection, and affected paths.
- [x] Add atomic project operations for create, rename/apply, delete, and move. These
  must validate the complete candidate project and either return all patched files or
  make no change.
- [x] Reuse `ProjectReferenceService` for Relationship Map edges and extend its typed
  reference rules rather than guessing references in the browser.
- [x] Centralize kind/path/ID rules currently implicit in directories and reference
  analysis, and expose safe filename generation to the browser.
- [x] Add a versioned browser-layout store keyed by installation/project, revision,
  content kind, and resource ID. Start with IndexedDB/local storage; do not add layout
  data to Persona YAML. Document how layouts survive rename and how stale entries are
  pruned.
- [x] Keep all endpoints bounded by file count, byte size, graph node/edge count,
  operation count, nesting depth, and request rate.
- [x] Return structured error codes and precise file/YAML paths for invalid pins,
  cycles, cardinality errors, stale revisions, unsupported YAML, and schema failures.

## Frontend structure

- [x] Split the current monolithic `static/editor/app.js` into versioned ES modules for
  workspace state, content browser, tabs/history, YAML documents, graph projection,
  canvas/viewport, selection, node registry, inspector, commands, validation, live
  overlays, persistence, and transport.
- [x] Move the page markup out of string-replacement assembly in
  `EditorPageController` into a maintainable static/template structure before the
  workspace shell grows further.
- [x] Define a small renderer interface for node cards and pins. Built-in types and
  schema-generated extension types must use the same registry and connection rules.
- [x] Use one command dispatcher for buttons, keyboard shortcuts, palette actions,
  context menus, and undoable graph mutations.
- [x] Keep project state normalized by resource identity, with separate document,
  graph, layout, validation, live-overlay, history, and dirty-state records.
- [x] Batch rendering and connection geometry updates for large graphs; avoid full
  project reparses on viewport or selection changes.
- [x] Run a short architecture spike comparing a small in-house HTML/SVG canvas with
  a vendored graph library. Record an ADR covering bundle/build impact, accessibility,
  licensing, touch support, performance, and compatibility with the current no-Node
  runtime. Do not adopt a library solely for visual polish.

## Delivery plan

### Phase 0 — UX specification and contracts

- [x] Create low-fidelity wireframes for the workspace, Relationship Map, each content
  graph, creation wizard, missing-reference flow, and narrow-screen fallback.
- [x] Write a mapping table from every supported Persona YAML construct to graph node,
  pins, inspector fields, and lossless mutation operations.
- [x] Inventory constructs that cannot safely be rewired and define their Custom YAML
  fallback.
- [x] Decide layout persistence and graph renderer approach in ADRs.
- [x] Define usability and performance budgets, including representative large project
  and large graph fixtures.

**Exit criteria:** the mappings cover all current built-in behaviour, dialogue, quest,
NPC, and script fixtures; unknown and extension-owned YAML has a documented safe path.

### Phase 1 — Organized workspace and in-UI creation

- [x] Implement the grouped Content Browser, search/filter/sort, tabs, breadcrumbs,
  quick-open, history, and saved panel layout.
- [x] Implement server-validated create/duplicate/delete primitives and the creation
  wizard for all five content kinds.
- [x] Integrate new files with recovery, dirty markers, autosave/draft patches, export,
  validation, semantic diff, and publication.
- [x] Add browser, keyboard, responsive, and accessibility tests for navigation and
  resource creation.
- [x] Add connection-gating tests proving the workspace cannot open or remain editable
  without an active authenticated server session.

**Exit criteria:** a user can create an NPC, dialogue, quest, behaviour, and script,
switch among them quickly, find them by ID/name, recover changes, and export or publish
the same lossless YAML.

### Phase 2 — Read-only graph projections

- [x] Add the versioned graph projection contract and render read-only graphs for all
  supported content kinds.
- [x] Add pan/zoom/minimap, selection synchronization, inspector, diagnostics, Custom
  YAML nodes, and Relationship Map navigation.
- [x] Add saved per-resource viewport/layout and deterministic auto-layout.
- [x] Overlay current validation and live runtime state.

**Exit criteria:** every existing fixture renders without content changes; selecting a
node selects the exact YAML range, diagnostics navigate correctly, and unsupported
content remains visible and source-editable.

#### Checkpoint evidence — 2026-08-17

- Phase 0 documents are in `docs/visual-editor/` and the renderer/layout decision is
  recorded in `docs/adr/0001-visual-graph-renderer-and-layout.md`.
- The complete Gradle suite passes, including authenticated connection gating, relay
  disconnect, all five creation templates, atomic/lossless project operations, every
  graph projection, typed mutations, stale digests, source-range preservation, bounds,
  security, publication/rollback, and the 2,000-file/2,000-node performance fixtures.
- Golden palette tests cover every built-in/extension family and first-container
  insertions. A fixed-seed property test executes 10,000 bounded scalar patches while
  preserving comments, blank lines, anchors/aliases, tags, ordering, and unknown data.
- The frontend unit suite covers normalized state, layout migration and determinism,
  renderer/registry/compatibility rules, signed extension entries, and large index/layout
  budgets.
- The Playwright suite covers 24 browser scenarios: all five resource lifecycles,
  relationship navigation and atomic create-missing, palette/pin/wire/keyboard gestures,
  cross-tab copy, extraction, recovery/conflicts, connection loss/reconnect, live overlays,
  responsive/reduced-motion/forced-colors/200% zoom, touch, axe WCAG checks, and a
  virtualized 2,000-node render/tab/pan/zoom/heap budget.
- Phase 1 through Phase 4 exit criteria and the Definition of Done are closed by the
  implementation and passing automated evidence above. Architecture and user guidance
  are in `docs/visual-editor/`.

### Phase 3 — Safe graph editing

- [x] Add node creation/deletion/duplication, field editing, ordered rewiring, and
  compound undo/redo for behaviours first.
- [x] Add dialogue transfers/choices, quest phases/objectives, NPC references/anchors,
  and reusable script editing in that order.
- [x] Add palette filtering, pin compatibility, connection explanations, extract/wrap
  commands, and schema-driven extension nodes.
- [x] Add optimistic digest checks and conflict recovery for every graph mutation.

**Exit criteria:** supported visual gestures produce minimal YAML changes, preserve all
unrelated source bytes and custom constructs, survive undo/redo/reload, and pass
authoritative validation.

### Phase 4 — Project-wide refactors and polish

- [x] Apply atomic safe rename, create-from-reference, extract-to-resource, guarded
  delete, and project Relationship Map refactors.
- [x] Add multi-select editing, alignment, comments/groups, bookmarks, copy/paste
  across compatible graphs, and polished context menus/shortcuts.
- [x] Run keyboard-only, screen-reader, touch, reduced-motion, and high-contrast audits.
- [x] Profile and optimize large projects and dense graphs without weakening bounds.
- [x] Replace remaining disconnected dialogs with integrated panels and complete user
  documentation/onboarding.

**Exit criteria:** common content-authoring workflows can be completed visually without
opening raw YAML, while advanced/custom YAML remains safe and fully accessible.

## Testing checklist

- [x] Golden round-trip tests for every built-in node type: comments, blank lines,
  ordering, anchors/aliases, tags, quoting style, unknown keys, and extension data are
  unchanged outside the exact edited ranges.
- [x] Property/fuzz tests for graph mutations, YAML path escaping, deeply nested
  graphs, unusual valid IDs, and failed operations.
- [x] Contract tests proving projection → mutation → reparse produces the expected
  graph and rejects stale digests.
- [x] Unit tests for pin compatibility, cardinality, ordering, cycle policy, palette
  filtering, ID/path validation, and layout migration.
- [x] Integration tests for create, duplicate, rename, delete, undo/redo, recovery,
  autosave, validation, semantic diff, export, publish, and rollback.
- [x] Browser tests for tabs, quick-open, history, selection/YAML synchronization,
  pan/zoom, drag connections, keyboard equivalents, and focus restoration.
- [x] Accessibility tests for semantic node labels, pin descriptions, errors, keyboard
  graph traversal, dialogs/panels, contrast, reduced motion, and zoom up to 200%.
- [x] Performance tests using bounded fixtures near maximum project size and graphs
  with many nodes/edges. Set budgets for initial projection, tab switch, pan/zoom,
  mutation response, and memory usage.
- [x] Security tests for malicious IDs/paths/YAML, oversized graph requests, forged
  layout metadata, stale revisions, unauthorized session operations, and extension
  schema/catalog abuse.
- [x] Connection-loss tests proving editing stops immediately, no offline fallback is
  exposed, and the workspace only resumes after the server session is restored and
  project state is refreshed.

## Definition of done

- [x] NPCs, dialogues, quests, behaviours, and scripts can be created, discovered,
  opened, edited, renamed, and safely deleted from the UI.
- [x] Resource switching is fast and organized through grouped browsing, search,
  filters, tabs, history, breadcrumbs, and the Relationship Map.
- [x] All supported content kinds have useful editable node graphs with Unreal-style
  palette, pins, wires, inspector, minimap, navigation, and keyboard workflows.
- [x] Raw YAML remains byte-preserved outside targeted edits, and unsupported/custom
  content is never hidden or discarded.
- [x] Browser diagnostics are advisory and Persona remains authoritative for schema,
  reference, extension, live-state, validation, and publication decisions.
- [x] Existing security/capability boundaries, bounded requests, live overlays,
  validation proof, publication confirmation, and rollback still work, and the editor
  is unavailable whenever there is no active authenticated server connection.
- [x] Automated round-trip, integration, browser, accessibility, security, and
  performance suites pass, and the architecture/user documentation is updated.
