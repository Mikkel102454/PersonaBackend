# Persona visual editor user guide

The visual editor is available only from a server-created, authenticated editor session. Open the
session URL, enter the in-game verification code, and keep Persona connected. A disconnect locks
every editable control immediately; reconnecting reloads and verifies the authoritative server
snapshot before editing resumes. There is no offline editing mode.

## Find and open content

The Content Browser coordinates a Sources tree with a list/tile asset view. NPCs, Dialogues,
Quests, Behaviours, and Scripts are fixed roots; real nested folders are expandable beneath them.
Breadcrumbs, back/forward history, recursive-search scope, filters, sorting, and result counts all
follow the selected folder. Search matches IDs, names, paths, types, tags, typed references, and
validation state. Resources open in reorderable tabs and retain their own selection and viewport.

- `Ctrl/Cmd+P`: quick open
- `Ctrl/Cmd+Tab`: switch recent resources
- `Ctrl/Cmd+K`: command palette
- `Ctrl/Cmd+N`: create content
- `Alt+Left` / `Alt+Right`: navigation history
- `Ctrl/Cmd+Z` / `Ctrl/Cmd+Shift+Z`: undo / redo

The Relationship Map shows server-analyzed typed links. Solid links resolve, dashed links are
missing, and cyclic links are highlighted. Each link has keyboard-accessible open-source and
open-target controls. Missing cards atomically create the already-referenced target and open it;
the server validates the complete candidate before either file changes.

## Edit a graph safely

Raw YAML is always authoritative. A visual operation sends a typed, digest-checked request to the
server. The server applies the smallest scalar or structural source-range patch, reparses it, and
returns a fresh graph. Comments, blank lines, ordering, anchors, aliases, tags, quoting, unknown
fields, and extension-owned data outside the selected range are not serialized or replaced.

Use Add node, right-click empty canvas space, or drag from an output pin to open the compatible
palette. Click pins or drag between them to connect. Invalid type, cardinality, and cycle gestures
are rejected before YAML changes. Double-click a wire to add a draggable layout-only reroute point;
Straighten in its context menu removes those points. Alt-click breaks a pin/wire; Ctrl/Cmd-drag moves existing links. Dropping
on an occupied single input atomically replaces its old wire. Node cards expose duplicate, delete,
replace, extract, variables, bookmarks, tracepoints, collapse, and kind-specific actions.

Marquee with Shift, add to selection with Ctrl/Cmd, and drag any selected card to move the group.
The toolbar aligns and distributes selections. `Ctrl/Cmd` plus an arrow nudges selected cards;
Shift increases the step. Find in Graph searches the current graph, while node bookmarks and named
viewport bookmarks provide local navigation. Layout commands are undoable but never modify content YAML.

Visual, Split, and YAML modes synchronize source selections in both directions. Invalid YAML keeps
the last valid graph visible but stale and disables visual mutations until the source parses again.
Custom YAML cards identify ranges that must be edited in YAML.

## Content workflows

- Create previews a server-owned minimal template and safe path before one atomic project update.
- Duplicate makes a lossless server-side copy and reports references that still target the original.
- Rename previews every declaration/reference patch, then applies all patches and the optional file
  move atomically.
- Delete is blocked when typed inbound references exist.
- NPC cards can create and assign a player/shared behaviour or dialogue atomically.
- Missing reference cards create the already-assigned target and open it while preserving history.
- A selected graph node can be extracted to a new `scripts/<folders>/<id>.yml`; the source becomes
  a typed `run-script` call and the new script opens in a tab.
- Behaviour branches retain Convert selection to behaviour. Script cards expose typed callers.
- Reusable scripts are individual content-version 2 files below `scripts/`. Their Input/Output cards edit the typed
  signature; the Inspector can rename, reorder, change type, or delete a selected parameter. Rename
  and delete update callers atomically, while incompatible type changes are blocked.
- Dragging a resource from the Content Browser onto any explicit event/reusable graph creates a typed value
  node. Connect only matching circular data pins; triangular execution pins define control flow.
  Unconnected data inputs expose inline checkbox, numeric, duration, text, or resource controls.

Quest phase cards open objectives, conditions, branches, and lifecycle commands as a nested view of
the same authoritative projection. Dialogue start cards, transfers, NPC references/anchors, and
script calls provide explicit navigation and editing actions.

## Validation, live state, and publication

Problems, References, YAML, Changes, Simulation, and Live are integrated output panels. Autosave writes hosted draft
patches, Persona validation remains authoritative, Semantic diff describes typed changes, and
export always uses the current server-connected project. Publication remains capability-gated and
requires the existing in-game confirmation.

Live data is a signed, read-only overlay. Local tracepoints and watched pins subscribe only to the
chosen nodes/pins, do not pause Minecraft, and keep at most 1,000 entries in memory. Active nodes
and execution wires highlight with order, values, branch results, failures, and limit diagnostics.
Disconnect clears captured values; tracepoint definitions remain local layout metadata.

## Layout and accessibility

Viewport, named viewport bookmarks, card positions, selection, comments, groups, colors, collapse state, tracepoints, watched pins, bookmarks, and
reroutes use a bounded, versioned IndexedDB store keyed by installation, project revision, kind, and
resource ID. Rename lookup may reuse the newest matching resource layout; stale entries expire and
are pruned. Layout metadata never enters Persona YAML.

Cards, pins, wires, dialogs, panels, and diagnostics are keyboard and screen-reader accessible.
Arrow keys traverse connected cards. The editor supports browser zoom to 200%, narrow layouts,
forced colors, visible focus, and reduced motion. Pointer interactions use pointer events and work
with mouse, pen, and touch.

If a visual gesture is unavailable, use the synchronized YAML panel. That is the intentional safe
path for advanced or unrecognized content—not a lossy fallback.
