# Visual editor UX specification

Status: accepted implementation contract for `VISUAL_EDITOR_TODO.md` Phase 0.

## Product boundary

The visual editor is an authenticated, server-connected authoring workspace. The
browser cannot open a project until a Persona session is verified and an authoritative
signed snapshot has been loaded. Losing the server connection replaces the workspace
with a reconnect screen and makes every editing command unavailable. Reconnection
must refresh the session, capabilities, metadata, project revision, and draft state
before editing resumes.

Raw YAML remains the only editable content document. Graphs, indexes, diagnostics,
live overlays, and layouts are projections. Commands send expected-revision-guarded,
bounded operations to the backend and replace their projection with the returned
authoritative document and graph.

## Workspace

```text
┌ Persona Editor ─ server ● ─ autosaved ─ valid ─ revision 7ad2 ─ publish ready ┐
├───────────────┬──────────────────────────────────────────┬────────────────────┤
│ CONTENT       │ [npc:vander •] [dialogue:welcome ×]      │ DETAILS            │
│ + Create      ├──────────────────────────────────────────┤ id: greet          │
│ Search…       │ project › Dialogues › welcome › main     │ type: say          │
│ Kind [All]    ├──────────────────────────────────────────┤ text: Hello…       │
│ State [All]   │   ┌────────┐ choice     ┌────────────┐    │ delay: 2s          │
│ Sort [Name]   │   │ Start  ├───────────►│ Greeting   │    │                    │
│ ▾ NPCs (3)    │   └────────┘            └─────┬──────┘    │ Diagnostics (0)    │
│   Vander      │                        next   │           │ References (2)     │
│ ▾ Dialogues   │                               ▼           │ Live: current line │
│   Welcome     │                    ┌──────────────────┐   │                    │
│ ▸ Quests (8)  │                    │ End dialogue     │   │                    │
│ ▸ Behaviours  │                    └──────────────────┘   │                    │
│ ▸ Scripts     │                                          │                    │
│ ▸ Other YAML  │ [−] 100% [+] [Fit] [Grid]      minimap   │                    │
├───────────────┴──────────────────────────────────────────┴────────────────────┤
│ Problems  References  YAML  Changes  Simulation  Live                    [⌃] │
└───────────────────────────────────────────────────────────────────────────────┘
```

- Left, center, right, and bottom regions are keyboard reachable landmarks. Splitters
  use separator semantics and arrow-key resizing.
- The header reports connection, save, validation, base revision, and publication
  state without obscuring the canvas.
- Visual/YAML modes may be side-by-side or exclusive. Selecting either representation
  reveals the exact counterpart using the stable YAML path and source range.
- Panel visibility and sizes are browser preferences, never Persona content.

## Content browser and resource navigation

- Groups are NPCs, Dialogues, Quests, Behaviours, Scripts, and Other YAML, with a
  visible result count and independently remembered collapsed state.
- A result's primary label is its ID or display title; its path is secondary text.
- Search indexes ID, title/display name, kind, path, tags, and inbound/outbound IDs.
- Filters cover kind, dirty state, validation state, reference state, and live state.
- Sort modes are name, kind, path, recently opened, and validation state.
- Open resources appear as reorderable tabs. Tabs include kind, dirty and error state,
  close, middle-click close, reopen-closed, and a per-tab canvas/selection snapshot.
- `Ctrl/Cmd+P` opens resources, `Ctrl/Cmd+K` opens commands, `Ctrl/Cmd+Tab` cycles the
  recent-resource stack, and browser-style back/forward traverses editor history.

## Relationship Map

```text
┌ Relationship Map ─ Direction [Both] ─ Type [All] ─ Problems only [ ] ┐
│                                                                      │
│ [NPC vander] ─dialogue────────► [Dialogue welcome]                   │
│      │                                                               │
│      ├─player behaviour───────► [Behaviour greet] ─subtree──┐        │
│      │                                                       │        │
│      └─shared behaviour───────► [Missing village:patrol] !  │        │
│                                                               ▼        │
│ [Quest bread] ─script────────► [Script celebrate] ◄──────────┘        │
│       ▲                                      cycle ↺                  │
│       └──────────── command/start-quest ─────────────────────         │
└──────────────────────────────────────────────────────────────────────┘
```

Resolved, unresolved, inbound, and cyclic edges have distinct color-independent
styles and text labels. Node and edge context menus expose Open source, Open target,
Show inbound, Show outbound, and Create missing target. This view navigates between
resources; it does not mutate content by dragging arbitrary project-level wires.

## Content graphs

### Behaviours

```text
[Root: player]
      │ execute
      ▼
[Sequence] 1 ─► [Event condition] ─success─► [Checkpoint] ─► [Wait]
           2 ─► [Action: command]
```

Composite outputs are ordered. Decorators expose one child pin. Leaf nodes expose
success/failure status outputs for inspection, not arbitrary YAML branches. Rewiring
uses only compatible structural pins and preserves sequence order. Runtime active
paths, outcomes, wake deadlines, and checkpoints are overlay badges.

### Dialogues

```text
[Start] ─► [Say: “Welcome”] ─next─► [Choice]
                                      ├─“Shop”────► [Script/action]
                                      └─“Leave”───► [End dialogue]
```

Entry nodes own their ordered scripts. Control-flow steps may be expanded into nested
subgraphs. Choice pins use player-facing text or localization keys. Missing targets,
unreachable nodes, implicit ends, and loops appear on the canvas and in Problems.

### Quests

```text
[Entry] ─► [Phase: delivery] ─condition────────► [Phase: reward] ─► [Complete]
                    │
                    ├─ objectives (3) ─► [nested objective graph]
                    └─ lifecycle scripts ─► [nested script graph]
```

Phase cards summarize requirements, objectives, timers, and lifecycle hooks. Opening
an objective or hook pushes a breadcrumb segment rather than replacing the resource
tab. Runtime progress and deadlines are read-only overlays.

### NPCs

```text
                         [Presentation]
                               ▲
[Shared behaviour] ◄──── [NPC vander] ────► [Dialogue set]
                               │
              [Anchors/map] ◄──┴──► [Player behaviour]
                               │
                        [Lifecycle scripts]
```

Reference cards open their resources. Anchor cards open the coordinate table/map and
offer catalog selection or coordinate paste. Missing references offer Create and
assign without discarding current navigation state.

### Reusable scripts

```text
[Input] ─exec─► [Command: play-sound] ─success─► [Wait] ─exec─► [Output]
   └─ sound:data ────────────────┘       └─failure─► [Command: message]
```

Every key below `scripts:` is a separately navigable graph but remains in
`scripts.yml`. Reusable graphs use stable keyed nodes and connections, synthetic non-deletable
Input/Output boundaries, white triangular execution pins, and type-colored circular data pins.
Unwired data inputs show type-appropriate inline controls; resource values can be dragged from the
Content Browser to create value nodes. Call nodes mirror the selected script signature and open the
callee on double-click.

The Input and Output cards provide an accessible add-parameter control. Selecting a parameter in
the Inspector exposes rename, type, reorder, and guarded delete actions. These signature mutations
are project-wide and atomic, including caller bindings and explicit data wires.

### Unsupported and custom YAML

```text
┌ Custom YAML ! ─ read-only visual projection ┐
│ !vendor-tag &anchor                         │
│ exact range: lines 18–27                    │
│ [Reveal YAML]                               │
└─────────────────────────────────────────────┘
```

Unknown fields, tags, aliases, invalid extension schemas, and unsafe structural
shapes remain visible. They can be changed only in YAML. No graph operation may
delete, normalize, or serialize through them.

## Node and canvas interaction

- Pan with middle/space drag; zoom around the pointer with wheel/pinch; Fit frames the
  graph or selection. Reset returns to 100%. Grid and minimap are optional.
- Add opens from the toolbar, empty-canvas context menu, or dragging from an output.
  Search results are filtered by graph kind, destination pin, scope, and schema.
- Dragging a node moves layout only. Dragging a pin previews compatible targets;
  dropping commits one graph command. Invalid targets explain the rejected type,
  cardinality, scope, or cycle rule before any YAML request.
- Reconnect drags an existing wire endpoint. Delete/disconnect, insert-on-wire, and
  reroute/comment nodes are available from wire context menus.
- Click selects, Shift/Ctrl/Cmd adds selection, and empty-canvas drag marquee-selects.
  Keyboard equivalents cover graph traversal, selection, movement, connection,
  deletion, duplication, alignment, distribution, and palette use.
- Compound actions such as create-and-connect produce one history entry.

## Creation wizard

```text
┌ Create content ─ 1 Kind › 2 Identity › 3 Template › 4 Review ┐
│ Kind: Dialogue                                               │
│ ID: village:welcome        ✓ available                       │
│ Path: dialogues/welcome.yml                                  │
│ Template: Single line                                        │
│                                                              │
│ YAML preview (server-generated)                              │
│ id: village:welcome                                          │
│ start: start                                                  │
│ nodes: …                                                      │
│                                   [Back] [Create and open]    │
└───────────────────────────────────────────────────────────────┘
```

The server validates kind, namespaced ID, path, extension, reserved names, duplicate
and case-fold collisions, traversal, file count, and byte limits. Review displays the
exact initial YAML. Creation is atomic and enters project dirty/recovery/draft/diff/
validation/publication flows.

## Missing-reference flow

```text
Missing behaviour village:patrol
[Open referring NPC] [Choose existing…] [Create and assign…]
                                      │
                                      └─ prefilled creation wizard
                                         → atomic create + narrow assignment patch
                                         → open target; Back returns to source pin
```

Deletion is blocked when inbound references exist. Rename and force-delete, if ever
enabled, show every affected file/YAML path and require an explicit reviewed apply.

## Disconnected and narrow-screen states

```text
┌ Connection lost ─ editing stopped ─ unsent changes were not applied ┐
│ The workspace is unavailable until Persona reconnects.              │
│ Attempt 3… [Retry now] [Copy diagnostic ID]                          │
└───────────────────────────────────────────────────────────────────────┘
```

Disconnect immediately disables commands, source input, autosave, graph pointer
actions, and mutation requests. The browser may retain visual layout preferences but
must not expose an editable cached project. After reconnect, a full authenticated
refresh resolves revision conflicts before the workspace is shown.

Below 900 CSS pixels the content browser and inspector become modal drawers, the
bottom panel becomes a tab sheet, and Visual/YAML becomes an exclusive toggle. The
canvas retains at least 320 by 320 CSS pixels. At 200% zoom, all functions remain
reachable without two-dimensional page scrolling; canvas panning is not page scroll.

## Accessibility contract

- Nodes are focusable HTML groups with a concise name, kind, error count, and state.
  Pins are buttons named with direction, semantic type, cardinality, and connection.
- Wires are SVG presentation backed by an accessible textual connections list.
- Focus order follows logical graph order, not canvas coordinates. Arrow keys traverse
  adjacent nodes and pins; Enter opens or starts a connection; Escape cancels.
- Color never carries the only meaning. Focus, selection, validation, live state, and
  wire semantics use text/icon/shape in addition to color.
- Motion respects `prefers-reduced-motion`; high-contrast/forced-color mode retains
  boundaries and focus; status changes use polite live regions except connection loss,
  which is assertive.
