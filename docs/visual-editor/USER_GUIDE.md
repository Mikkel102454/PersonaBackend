# Persona visual editor user guide

The visual editor is available only from a server-created, authenticated editor session. Open the
session URL, enter the in-game verification code, and keep Persona connected. A disconnect locks
every editable control immediately; reconnecting reloads and verifies the authoritative server
snapshot before editing resumes. There is no offline editing mode.

## Find and open content

The Content Browser groups NPCs, dialogues, quests, behaviours, reusable scripts, and other YAML.
Search matches IDs, names, paths, types, tags, and typed references. Filters expose dirty, invalid,
unreferenced, missing-reference, and live resources. Resources open in reorderable tabs and retain
their own selection and viewport.

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
are rejected before YAML changes. Wire controls disconnect, insert a node on a behaviour wire, or
add a layout-only reroute. Node cards expose duplicate, delete, wrap/unwrap, bookmark, collapse,
resource navigation, and kind-specific actions.

Marquee with Shift, add to selection with Ctrl/Cmd, and drag any selected card to move the group.
The toolbar aligns and distributes selections. `Ctrl/Cmd` plus an arrow nudges selected cards;
Shift increases the step. Copy/paste transfers an exact behaviour node between compatible tabs and
asks for a new stable ID. Layout commands are undoable but never modify content YAML.

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
- Dialogue commands can be extracted exactly to `scripts.yml`; the source command becomes a typed
  `run-script` reference and the new script opens in a tab.
- Behaviour branches retain Convert selection to behaviour. Script cards expose typed callers.

Quest phase cards open objectives, conditions, branches, and lifecycle commands as a nested view of
the same authoritative projection. Dialogue start cards, transfers, NPC references/anchors, and
script calls provide explicit navigation and editing actions.

## Validation, live state, and publication

Problems, References, YAML, Changes, Simulation, and Live are integrated output panels. Autosave writes hosted draft
patches, Persona validation remains authoritative, Semantic diff describes typed changes, and
export always uses the current server-connected project. Publication remains capability-gated and
requires the existing in-game confirmation.

Live data is a signed, read-only overlay: active behaviour paths, dialogue lines/choices, quest
progress, NPC presentation/anchors, timers, outcomes, and checkpoints can highlight cards without
changing graph structure or YAML. Elevated live mutation controls are shown only with their
existing capability and always require explicit review.

## Layout and accessibility

Viewport, card positions, selection, comments, groups, colors, collapse state, bookmarks, and
reroutes use a bounded, versioned IndexedDB store keyed by installation, project revision, kind, and
resource ID. Rename lookup may reuse the newest matching resource layout; stale entries expire and
are pruned. Layout metadata never enters Persona YAML.

Cards, pins, wires, dialogs, panels, and diagnostics are keyboard and screen-reader accessible.
Arrow keys traverse connected cards. The editor supports browser zoom to 200%, narrow layouts,
forced colors, visible focus, and reduced motion. Pointer interactions use pointer events and work
with mouse, pen, and touch.

If a visual gesture is unavailable, use the synchronized YAML panel. That is the intentional safe
path for advanced or unrecognized content—not a lossy fallback.
