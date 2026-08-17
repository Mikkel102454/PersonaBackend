# Visual node editor TODO

## Goal

Replace the current visual YAML form with an Unreal-style node workspace that makes
Persona content easy to discover, create, connect, inspect, and validate. A user
should be able to create and move between NPCs, dialogues, quests, behaviours, and
reusable scripts without leaving the editor or manually creating files.

The node editor is a visual projection of the project, not a second content format.
Raw YAML remains the authoritative editable document and must continue to round-trip
without losing comments, ordering, anchors, aliases, custom tags, unknown fields, or
extension-owned data.

## Non-negotiable constraints

- [ ] Keep `YamlDocumentService` and raw YAML as the source of truth. Never serialize
  a browser-owned graph over an entire YAML file.
- [ ] Give every visual node, pin, field, and diagnostic a stable file path and YAML
  path/source range.
- [ ] Translate node operations into narrow, lossless scalar or structural patches,
  then parse the returned YAML and rebuild the affected graph.
- [ ] Do not allow visual editing while the selected file has invalid YAML. Keep the
  last valid graph visible, mark it stale, and make the raw error easy to navigate to.
- [ ] Preserve offline import/export and the existing session, capability, draft,
  validation, semantic diff, publication, recovery, and undo/redo flows.
- [ ] Keep extension support data-driven through signed editor schemas and catalogs;
  extensions must not inject frontend code.
- [ ] Treat live server data as a read-only overlay unless the existing explicit,
  capability-gated mutation confirmation flow is used.
- [ ] Maintain keyboard and screen-reader access and respect reduced-motion settings.

## Target user experience

### Workspace shell and navigation

- [ ] Replace the flat file list with a **Content Browser** grouped by:
  `NPCs`, `Dialogues`, `Quests`, `Behaviours`, `Scripts`, and `Other YAML`.
- [ ] Display content names/IDs as the primary label and file paths as secondary
  information. Show counts on each group.
- [ ] Add search across ID, display name/title, type, tag, referenced content, and
  file path.
- [ ] Add filters for content kind, dirty/clean, valid/invalid, referenced/unreferenced,
  missing references, and live/active status.
- [ ] Add sort modes for name, kind, file path, recently opened, and validation state.
- [ ] Add a compact/list toggle and collapsible groups; remember the choice locally.
- [ ] Add editor tabs for open resources with kind icon, dirty marker, error badge,
  close button, middle-click close, tab reordering, and reopen-closed support.
- [ ] Support `Ctrl/Cmd+P` quick-open, `Ctrl/Cmd+Tab` recent-resource switching,
  `Ctrl/Cmd+K` commands, and back/forward navigation history.
- [ ] Add breadcrumbs above the canvas: project → content kind → resource → nested
  graph/subgraph. Each segment must be clickable.
- [ ] Keep selection and viewport per open tab so switching resources does not lose
  context.
- [ ] Add a project **Relationship Map** showing typed links between resources, such
  as NPC → behaviour/dialogue, dialogue → dialogue/script, and quest → script. This
  view is for project navigation and dependency inspection; opening a resource enters
  its editable internal graph.
- [ ] In the Relationship Map, distinguish resolved, unresolved, inbound, and cyclic
  links and provide “open source” / “open target” actions.

### Main editor layout

- [ ] Use a resizable four-part layout:
  Content Browser on the left, node canvas in the center, Details/Inspector on the
  right, and collapsible Problems/References/YAML/Changes output below.
- [ ] Allow panels to collapse and reset to a known default layout. Persist layout
  preferences only in the browser, not in project YAML.
- [ ] Provide a clear Visual/YAML split-view toggle. Selecting either a graph element
  or YAML location must reveal and select its counterpart.
- [ ] Show autosave state, validation state, base revision, live connection state,
  and publication readiness without taking canvas space.
- [ ] Move references, semantic diff, simulation, and live inspection into dockable
  workspace panels instead of separate disconnected dialogs where practical.

### Node canvas interaction

- [ ] Implement an infinite canvas with pan, cursor-centered zoom, zoom-to-fit,
  frame-selection, minimap, optional grid, and reset-view controls.
- [ ] Render nodes as accessible HTML controls and connections as an SVG layer so
  labels, fields, focus, and screen readers remain usable.
- [ ] Support click, marquee, additive selection, multi-select move, alignment,
  distribution, duplicate, delete, copy/paste, and keyboard nudging.
- [ ] Open a searchable node palette from an Add button, right-click, or dragging from
  a compatible output pin. Filter the palette to nodes valid at that destination.
- [ ] Give pins a direction, semantic type, cardinality, required/optional state, and
  compatible connection rules. Reject an invalid connection before changing YAML and
  explain why.
- [ ] Support drag-to-connect, reconnect, disconnect, insert-node-on-wire, and a small
  reroute/comment node that stores layout metadata only.
- [ ] Use visible connection labels for semantics that would otherwise be ambiguous,
  such as `success`, `failure`, `choice`, `next phase`, and `reference`.
- [ ] Put common properties directly on compact node cards; edit all remaining schema
  fields in the Details panel.
- [ ] Show node badges for validation errors, warnings, dirty fields, custom data,
  extension ownership, unresolved references, and active live runtime state.
- [ ] Add comments/groups, color labels, collapse/expand, bookmarks, and “focus
  upstream/downstream”. Keep purely visual layout data out of content YAML unless a
  dedicated, versioned editor-metadata format is approved.
- [ ] Add an auto-layout command that is deterministic, undoable, and never changes
  content semantics.
- [ ] Make every graph command participate in the existing per-file undo/redo and tab
  recovery system. A compound gesture, such as inserting and connecting a node, is
  one undo step.

## Graph models by content kind

### Behaviours

- [ ] Render the behaviour root and its child tree as execution nodes with explicit
  parent/child order.
- [ ] Provide first-class cards for sequence, selector/priority selector, parallel,
  action, condition, checkpoint, wait/cooldown, and supported control/decorator types.
- [ ] Represent ordered children with numbered pins or a clearly reorderable output
  list; rewiring must preserve YAML sequence ordering.
- [ ] Expose scope, thresholds, timeout/deadline, checkpoint persistence, and failure
  semantics in the Details panel.
- [ ] Add extension action/condition nodes from signed schemas, including catalog-backed
  fields and dependent inputs.
- [ ] Preserve the existing extract-subtree operation as “Convert selection to
  behaviour”, create the new file, replace the source subtree with a reference, and
  open the new behaviour in a tab.
- [ ] Overlay active paths, recent outcomes, conditions, wake deadlines, and checkpoints
  from trusted live data without changing graph structure.

### Dialogues

- [ ] Render dialogue entries as nodes and transfers/choices as directed connections.
- [ ] Provide nodes for say/line, choice, conditional branch, wait, script/action,
  goto/transfer, and end-dialogue operations.
- [ ] Make the start node visually distinct and provide an explicit “Set as start”
  command.
- [ ] Label choice pins with player-facing choice text or localization key.
- [ ] Show missing destinations, unreachable nodes, implicit ends, and transfer loops
  directly on the canvas using existing diagnostics plus local advisory analysis.
- [ ] Include line text, translations/localization keys, delay, placeholders, and
  conditions in the Details panel and preview.
- [ ] Allow a transfer to another dialogue to open that dialogue in a new tab while
  retaining back navigation.
- [ ] Overlay current line, eligible choices, wait deadline, and cancellation state
  when trusted live dialogue data is available.

### Quests

- [ ] Render phases as the primary flow graph, with entry, branches, next-phase links,
  and terminal completion nodes.
- [ ] Open objectives and lifecycle scripts as nested graphs from their phase card.
- [ ] Provide nodes for built-in objectives, requirements/conditions, phase branches,
  lifecycle scripts, completion, and extension-defined objectives.
- [ ] Show required/optional and visible/hidden objectives, amounts/durations, timers,
  repeatability, cooldown, and maximum completions in the Details panel.
- [ ] Detect and highlight unreachable phases, missing phase destinations, impossible
  branches, duplicate IDs, and accidental cycles.
- [ ] Overlay current phase, objective progress, timer deadline, recent transitions,
  and completion count from trusted live data.

### NPCs

- [ ] Use an NPC overview graph with a central NPC node connected to presentation,
  anchors, shared behaviour, player behaviour, dialogue set, and lifecycle scripts.
- [ ] Make referenced behaviour/dialogue cards open their resource in a new tab and
  show missing-reference actions when unresolved.
- [ ] Provide an anchor list/map subview with coordinate paste, world catalog selection,
  duplicate-anchor checks, and distance warnings from the existing live preview.
- [ ] Keep presentation fields, skin/equipment/age/pose, scope, and extension-defined
  properties editable through schema-driven Details controls.
- [ ] Overlay shared/private presentation and projection state from trusted live data.

### Reusable scripts and unknown content

- [ ] Render each reusable script as its own selectable graph while retaining
  `scripts.yml` as the authoritative source file.
- [ ] Provide built-in command, condition, if, random, call/reference, and terminal
  nodes, plus extension-defined command/condition nodes.
- [ ] Allow “Extract selection to reusable script” and safe navigation to all callers.
- [ ] Keep unrecognized or unsupported YAML visible as a **Custom YAML** node with its
  exact source range. It may be edited in YAML but must never be silently dropped.
- [ ] Fall back to the current structured form/YAML view for content that cannot yet
  be represented safely as a graph.

## Create, duplicate, rename, and delete content in the UI

- [ ] Add a global **Create** menu and `Ctrl/Cmd+N` flow for NPC, dialogue, quest,
  behaviour, and reusable script.
- [ ] Build a creation wizard with:
  content kind, namespaced ID, suggested safe filename, optional template, and a
  review of the initial YAML before creation.
- [ ] Validate IDs and paths on both client and server. Reject duplicates, traversal,
  invalid extensions, reserved paths, case-folding collisions, and project size/file
  count overflow.
- [ ] Offer useful minimal templates:
  empty behaviour root, single-line dialogue, one-phase quest, NPC with optional
  anchor/behaviour/dialogue links, and empty reusable script.
- [ ] Let users create a missing referenced resource from a pin or diagnostic. Prefill
  its kind and ID, create it, connect it, and open it without losing current context.
- [ ] Add “Create and assign” actions on NPC behaviour/dialogue fields and quest/script
  references.
- [ ] Implement duplicate as a server-produced lossless file/subtree copy with a new
  ID and path; preview references that remain pointed at the original.
- [ ] Turn the current rename preview into an applyable atomic project operation that
  updates the declaration, all typed references, and filename when requested. Show
  every patch before applying it and abort the whole operation on a conflict.
- [ ] Add delete with inbound-reference analysis. Default to blocking deletion when
  references exist; offer navigation to each caller and only permit an explicit
  reviewed force-delete policy if one is defined.
- [ ] Ensure additions, renames, moves, and deletions appear in dirty state, recovery,
  draft patches, semantic diff, export, validation, and publication.

## Backend and data contracts

- [ ] Introduce a typed `EditorGraphProjection` built from `YamlDocumentResponse` and
  content/schema metadata. Include stable graph node IDs, YAML paths/ranges, node kind,
  fields, typed pins, edges, diagnostics, and graph capabilities.
- [ ] Define stable visual identities from content ID plus YAML path/stable node ID;
  never use canvas coordinates or array indexes as the only identity when a semantic
  ID exists.
- [ ] Add bounded projection endpoints per document/resource. Version the graph
  contract independently so cached browser layouts can be invalidated safely.
- [ ] Add typed, bounded mutation requests for connect, disconnect, insert, delete,
  reorder, wrap, unwrap, and compound operations. Require expected content digest (or
  revision) and return conflict details instead of applying to stale YAML.
- [ ] Compile every mutation to minimal source-range edits inside
  `YamlDocumentService`; return the updated raw content, document model, graph
  projection, and affected paths.
- [ ] Add atomic project operations for create, rename/apply, delete, and move. These
  must validate the complete candidate project and either return all patched files or
  make no change.
- [ ] Reuse `ProjectReferenceService` for Relationship Map edges and extend its typed
  reference rules rather than guessing references in the browser.
- [ ] Centralize kind/path/ID rules currently implicit in directories and reference
  analysis, and expose safe filename generation to the browser.
- [ ] Add a versioned browser-layout store keyed by installation/project, revision,
  content kind, and resource ID. Start with IndexedDB/local storage; do not add layout
  data to Persona YAML. Document how layouts survive rename and how stale entries are
  pruned.
- [ ] Keep all endpoints bounded by file count, byte size, graph node/edge count,
  operation count, nesting depth, and request rate.
- [ ] Return structured error codes and precise file/YAML paths for invalid pins,
  cycles, cardinality errors, stale revisions, unsupported YAML, and schema failures.

## Frontend structure

- [ ] Split the current monolithic `static/editor/app.js` into versioned ES modules for
  workspace state, content browser, tabs/history, YAML documents, graph projection,
  canvas/viewport, selection, node registry, inspector, commands, validation, live
  overlays, persistence, and transport.
- [ ] Move the page markup out of string-replacement assembly in
  `EditorPageController` into a maintainable static/template structure before the
  workspace shell grows further.
- [ ] Define a small renderer interface for node cards and pins. Built-in types and
  schema-generated extension types must use the same registry and connection rules.
- [ ] Use one command dispatcher for buttons, keyboard shortcuts, palette actions,
  context menus, and undoable graph mutations.
- [ ] Keep project state normalized by resource identity, with separate document,
  graph, layout, validation, live-overlay, history, and dirty-state records.
- [ ] Batch rendering and connection geometry updates for large graphs; avoid full
  project reparses on viewport or selection changes.
- [ ] Run a short architecture spike comparing a small in-house HTML/SVG canvas with
  a vendored graph library. Record an ADR covering bundle/build impact, accessibility,
  licensing, touch support, performance, and compatibility with the current no-Node
  runtime. Do not adopt a library solely for visual polish.

## Delivery plan

### Phase 0 — UX specification and contracts

- [ ] Create low-fidelity wireframes for the workspace, Relationship Map, each content
  graph, creation wizard, missing-reference flow, and narrow-screen fallback.
- [ ] Write a mapping table from every supported Persona YAML construct to graph node,
  pins, inspector fields, and lossless mutation operations.
- [ ] Inventory constructs that cannot safely be rewired and define their Custom YAML
  fallback.
- [ ] Decide layout persistence and graph renderer approach in ADRs.
- [ ] Define usability and performance budgets, including representative large project
  and large graph fixtures.

**Exit criteria:** the mappings cover all current built-in behaviour, dialogue, quest,
NPC, and script fixtures; unknown and extension-owned YAML has a documented safe path.

### Phase 1 — Organized workspace and in-UI creation

- [ ] Implement the grouped Content Browser, search/filter/sort, tabs, breadcrumbs,
  quick-open, history, and saved panel layout.
- [ ] Implement server-validated create/duplicate/delete primitives and the creation
  wizard for all five content kinds.
- [ ] Integrate new files with recovery, dirty markers, autosave/draft patches, export,
  validation, semantic diff, and publication.
- [ ] Add browser, keyboard, responsive, and accessibility tests for navigation and
  resource creation.

**Exit criteria:** a user can create an NPC, dialogue, quest, behaviour, and script,
switch among them quickly, find them by ID/name, recover changes, and export or publish
the same lossless YAML.

### Phase 2 — Read-only graph projections

- [ ] Add the versioned graph projection contract and render read-only graphs for all
  supported content kinds.
- [ ] Add pan/zoom/minimap, selection synchronization, inspector, diagnostics, Custom
  YAML nodes, and Relationship Map navigation.
- [ ] Add saved per-resource viewport/layout and deterministic auto-layout.
- [ ] Overlay current validation and live runtime state.

**Exit criteria:** every existing fixture renders without content changes; selecting a
node selects the exact YAML range, diagnostics navigate correctly, and unsupported
content remains visible and source-editable.

### Phase 3 — Safe graph editing

- [ ] Add node creation/deletion/duplication, field editing, ordered rewiring, and
  compound undo/redo for behaviours first.
- [ ] Add dialogue transfers/choices, quest phases/objectives, NPC references/anchors,
  and reusable script editing in that order.
- [ ] Add palette filtering, pin compatibility, connection explanations, extract/wrap
  commands, and schema-driven extension nodes.
- [ ] Add optimistic digest checks and conflict recovery for every graph mutation.

**Exit criteria:** supported visual gestures produce minimal YAML changes, preserve all
unrelated source bytes and custom constructs, survive undo/redo/reload, and pass
authoritative validation.

### Phase 4 — Project-wide refactors and polish

- [ ] Apply atomic safe rename, create-from-reference, extract-to-resource, guarded
  delete, and project Relationship Map refactors.
- [ ] Add multi-select editing, alignment, comments/groups, bookmarks, copy/paste
  across compatible graphs, and polished context menus/shortcuts.
- [ ] Run keyboard-only, screen-reader, touch, reduced-motion, and high-contrast audits.
- [ ] Profile and optimize large projects and dense graphs without weakening bounds.
- [ ] Replace remaining disconnected dialogs with integrated panels and complete user
  documentation/onboarding.

**Exit criteria:** common content-authoring workflows can be completed visually without
opening raw YAML, while advanced/custom YAML remains safe and fully accessible.

## Testing checklist

- [ ] Golden round-trip tests for every built-in node type: comments, blank lines,
  ordering, anchors/aliases, tags, quoting style, unknown keys, and extension data are
  unchanged outside the exact edited ranges.
- [ ] Property/fuzz tests for graph mutations, YAML path escaping, deeply nested
  graphs, unusual valid IDs, and failed operations.
- [ ] Contract tests proving projection → mutation → reparse produces the expected
  graph and rejects stale digests.
- [ ] Unit tests for pin compatibility, cardinality, ordering, cycle policy, palette
  filtering, ID/path validation, and layout migration.
- [ ] Integration tests for create, duplicate, rename, delete, undo/redo, recovery,
  autosave, validation, semantic diff, export, publish, and rollback.
- [ ] Browser tests for tabs, quick-open, history, selection/YAML synchronization,
  pan/zoom, drag connections, keyboard equivalents, and focus restoration.
- [ ] Accessibility tests for semantic node labels, pin descriptions, errors, keyboard
  graph traversal, dialogs/panels, contrast, reduced motion, and zoom up to 200%.
- [ ] Performance tests using bounded fixtures near maximum project size and graphs
  with many nodes/edges. Set budgets for initial projection, tab switch, pan/zoom,
  mutation response, and memory usage.
- [ ] Security tests for malicious IDs/paths/YAML, oversized graph requests, forged
  layout metadata, stale revisions, unauthorized session operations, and extension
  schema/catalog abuse.

## Definition of done

- [ ] NPCs, dialogues, quests, behaviours, and scripts can be created, discovered,
  opened, edited, renamed, and safely deleted from the UI.
- [ ] Resource switching is fast and organized through grouped browsing, search,
  filters, tabs, history, breadcrumbs, and the Relationship Map.
- [ ] All supported content kinds have useful editable node graphs with Unreal-style
  palette, pins, wires, inspector, minimap, navigation, and keyboard workflows.
- [ ] Raw YAML remains byte-preserved outside targeted edits, and unsupported/custom
  content is never hidden or discarded.
- [ ] Browser diagnostics are advisory and Persona remains authoritative for schema,
  reference, extension, live-state, validation, and publication decisions.
- [ ] Existing security/capability boundaries, bounded requests, offline mode, live
  overlays, validation proof, publication confirmation, and rollback still work.
- [ ] Automated round-trip, integration, browser, accessibility, security, and
  performance suites pass, and the architecture/user documentation is updated.

