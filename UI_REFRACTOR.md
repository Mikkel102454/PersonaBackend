# Persona visual editor UI refactor specification

Status: implementation-ready visual and interaction contract.

This document is the authoritative specification for the visual editor's layout,
graph interaction, and UI refactor. `VISUAL_EDITOR_TODO.md` remains the broad backlog;
where that backlog or an older UX sketch conflicts with this document, this document
governs visual and interaction behavior. The security, session, lossless-document,
validation, publication, recovery, and live-state boundaries in
`EDITOR_ARCHITECTURE.md` remain authoritative.

The key product rule is:

> Raw YAML is the sole authoritative editable document. The graph is a versioned,
> source-ranged projection of that YAML, and every graph connection goes from an
> explicit output port to a compatible explicit input port. A node body is never a
> connection endpoint.

The words **must**, **should**, and **may** describe required, recommended, and
optional behavior respectively.

## Visual reference

The primary visual reference is
`images/ChatGPT Image Aug 18, 2026, 10_06_50 AM.png`. This path is resolved relative
to `PersonaBackend/UI_REFRACTOR.md`.

![Dense narrative graph editor visual reference](<images/ChatGPT Image Aug 18, 2026, 10_06_50 AM.png>)

The image is qualitative direction, not a pixel-identical acceptance target.

Adopt these principles from the reference:

- a clear IDE hierarchy with a compact global toolbar, navigation and content on the
  left, the graph as the dominant center surface, an inspector on the right, and a
  shallow tool dock below;
- high information density without hiding resource identity, current context, or
  editor state;
- compact node cards with semantic header colors, obvious named ports, restrained
  wire routing, strong selection, and validation state visible at a glance;
- resource tabs, breadcrumbs, graph-local controls, minimap, Inspector/History tabs,
  and bottom panels that feel like parts of one workspace;
- layered charcoal surfaces, a subtle canvas grid, compact typography, fine borders,
  and color used deliberately rather than decoratively.

Intentional Persona differences are:

- Persona content kinds and signed extension catalogs determine the browser,
  palettes, node types, fields, ports, and compatibility rules;
- YAML stays directly available in a bottom panel or optional split view and remains
  authoritative; the visual graph is never a second serialized model;
- security capabilities, signed snapshot state, server connection, validation,
  publication readiness, recovery, and live-runtime overlays remain first-class;
- unsupported, tagged, anchored, aliased, unknown, or unsafe YAML remains visibly
  lossless through `Custom YAML` nodes instead of being normalized or omitted;
- the Relationship Map is a separate cross-resource navigation view. It must not
  imply that arbitrary cross-resource wires can mutate content;
- the UI uses Persona's tokens and accessible states, not the reference image's exact
  brand, copy, geometry, icons, colors, or sample content.

## Goals and non-goals

The refactor must make common authoring workflows visual: following and editing
behavior children, dialogue choices and branches, quest phase transitions, NPC
references, and script flow. It must also make the exact YAML source, diagnostics,
references, and pending changes easy to reach without permanently taking half of the
screen away from the graph.

The primary target is a dense desktop editor. Smaller viewports use drawers and
exclusive modes; they are not a compressed four-column desktop layout.

This work does not create an alternate content format, allow the browser to infer
extension behavior, weaken capability checks, add a frontend build service, or turn
live overlays into editable server state. Canvas positions, open tabs, panel sizes,
and presentation groups are browser preferences and are not written into Persona
YAML.

## Workspace shell

### Desktop composition

```text
┌ Global toolbar: project | create | save | validate | preview | search | account ┐
├────┬──────────────────┬────────────────────────────────────┬────────────────────┤
│Nav │ Content browser  │ Resource tabs                      │ Inspector | History│
│rail│ Search / filters ├────────────────────────────────────┤                    │
│    │ tree / results   │ breadcrumbs                        │ selected object    │
│    │                  ├────────────────────────────────────┤ fields, ports, refs│
│    │                  │ graph toolbar                      │ diagnostics, live  │
│    │                  ├────────────────────────────────────┤                    │
│    │                  │                                    │                    │
│    │                  │          DOMINANT CANVAS           │                    │
│    │                  │                            minimap  │                    │
├────┴──────────────────┴────────────────────────────────────┴────────────────────┤
│ Problems | References | Preview | YAML | Changes                         [⌃]   │
├────────────────────────────────────────────────────────────────────────────────┤
│ save • validation • connection • capabilities • base/draft revision • live    │
└────────────────────────────────────────────────────────────────────────────────┘
```

From top to bottom:

1. The **global toolbar** is one compact row. It contains project identity and
   switching, creation, save state, validate, preview/play controls when available,
   global search, command access, connection alerts, and account/session actions.
   Resource-specific graph commands do not belong here.
2. The **navigation rail** switches Library, Bookmarks, Recents, Relationship Map,
   and other project-level views. It is icon-first but exposes text in tooltips and
   accessible names.
3. The **content browser** contains search, filters, and a virtualized tree/list for
   NPCs, Dialogues, Quests, Behaviors, Scripts, and Other YAML. It is searchable by
   ID, title, path, tags, kind, and inbound/outbound reference IDs.
4. The **center workspace** stacks reorderable resource tabs, breadcrumbs, a compact
   graph toolbar, and the canvas. The canvas receives all remaining center height.
5. The **right panel** has Inspector and History tabs. Inspector is the default and
   follows selection. History lists local compound commands and server-accepted
   draft revisions; it does not imply that published history can be mutated.
6. The **bottom dock** contains Problems, References, Preview, YAML, and Changes. It
   starts collapsed or at the last local height. Opening YAML uses this dock by
   default; a user may pin YAML into a center split view.
7. The **status bar** continuously reports saved/saving/dirty/recovery state,
   validation summary, connected/reconnecting/read-only state, granted capabilities,
   content digest/base and draft revision, and live/publication state.

The canvas must be visually dominant at the default desktop layout. At 1440 by 900
CSS pixels, the default content browser is 280 px, Inspector is 320 px, bottom dock
is collapsed to a 34 px tab strip, global toolbar is at most 48 px, resource tabs are
36 px, breadcrumbs are 30 px, graph toolbar is 38 px, navigation rail is 48 px, and
status bar is 24 px. User resizing may vary these values within the bounds below.

### Resizing, collapse, and persistence

- Content browser width: 220–480 px; Inspector width: 280–520 px; expanded bottom
  dock height: 180 px to 55% of available workspace height.
- Each of those panels must collapse independently. `[` toggles the left region,
  `]` toggles the right region, and `Ctrl/Cmd+J` toggles the bottom dock.
- Splitters have `role="separator"`, expose current/min/max values, support pointer
  drag, and resize in 8 px increments with arrow keys or 32 px with Shift+arrow.
- Store visibility, active panel tabs, sizes, center split, browser filters, and per-
  resource viewport/selection locally in the browser, namespaced by installation and
  project identity. Clamp stale dimensions on restore.
- Persist through a bounded, versioned `layout-store` record. Never include YAML,
  secrets, capability grants, signed data, diagnostics payloads, or live player data.
- A **Reset layout** command restores defaults. Layout storage failure must not block
  editing and must be announced once non-modally.

### Resource navigation

Resource tabs show kind, title/ID, dirty state, validation severity, live indicator,
and close action. They support reorder, middle-click close, close others/right,
reopen closed, and `Ctrl/Cmd+Tab` recent order. Each tab remembers graph/YAML mode,
nested breadcrumb, viewport, selection, and open bottom tool.

Breadcrumbs represent project, resource, and nested graph context such as a dialogue
entry script, quest objective, or lifecycle hook. Selecting a segment returns to that
level without opening a new resource tab. Browser-style Back/Forward traverses this
navigation history.

`Ctrl/Cmd+P` opens a resource quick-open; `Ctrl/Cmd+K` opens the command palette.
Quick-open searches resources and YAML paths. The command palette searches enabled
commands and explains why capability- or state-gated commands are disabled.

## Visual system

The UI is dark charcoal and uses shallow elevation, borders, and spacing to separate
regions. The canvas is darker than tool panels and carries a subtle square or dotted
grid that remains quiet at every zoom level.

Initial design tokens, expressed as CSS custom properties, are:

| Token | Initial value | Use |
| --- | --- | --- |
| `--surface-app` | `#111417` | page background |
| `--surface-panel` | `#171b20` | browser, inspector, dock |
| `--surface-raised` | `#1d232a` | cards, menus, active controls |
| `--surface-canvas` | `#0f1317` | graph canvas |
| `--border-subtle` | `#2b333d` | panel and control borders |
| `--grid-minor` | `rgba(151,166,181,.08)` | canvas grid |
| `--text-primary` | `#edf2f7` | primary copy |
| `--text-secondary` | `#a7b1bd` | metadata and labels |
| `--text-muted` | `#778390` | disabled and tertiary copy |
| `--accent` | `#55a7ff` | focus, active tab, primary action |
| `--selection` | `#77b9ff` | selected node outline |
| `--valid` | `#45c486` | success/valid state |
| `--warning` | `#e9b44c` | warnings and pending state |
| `--danger` | `#ef646f` | invalid/error state |
| `--live` | `#55ddb5` | live runtime overlay |

Use a compact system sans-serif stack at 12–13 px for controls and metadata, 14 px
for primary labels, and a system monospace stack for IDs, YAML paths, values where
syntax matters, and source positions. The base spacing unit is 4 px; most controls
are 28–32 px high. Touch targets and keyboard focus remain at least 24 by 24 CSS
pixels even when their visible port glyph is smaller.

Node header color conveys semantic family but is never the only cue. Every family
also has a label and icon: entry/terminal, dialogue, control/condition, behavior
composite/decorator/action, quest, reference, script/command, and custom YAML. Use a
muted header fill and a stronger 3 px accent, not a fully saturated card.

Selected nodes have a 2 px selection outline, raised shadow, and selected state in
their accessible name. Keyboard focus has a distinct high-contrast inner ring.
Errors and warnings use a badge with icon and count. A live node uses a labeled
`Live` badge and animated outline only when reduced motion is not requested.

## Canvas and node cards

The canvas supports infinite-feeling pan with origin rebasing, cursor-centered wheel
or pinch zoom, zoom-to-fit graph/selection, reset to 100%, optional grid snapping,
and a minimap. Zoom is bounded to 20–240%. Middle drag or Space+primary drag pans;
trackpads may pan without requiring Space. Browser page zoom remains independent.

```text
             output port and label
                       │
┌● Behavior: Sequence ─┴────────────┐
│ sequence_1              ! 1  LIVE │  header: family, title, badges
├───────────────────────────────────┤
│ Mode       first success          │  compact summary fields
│ Children   3                      │
├───────────────────────────────────┤
│ 1 first     ○─────────────────────┤  ordered, named output port
│ 2 fallback  ○─────────────────────┤
│ + child     ○─────────────────────┤
└───────────────────────────────────┘
  ▲
  └─ input port; body is not connectable
```

A node card contains a semantic header, short identity/subtitle, only the scalar
fields useful while tracing the graph, validation/live badges, and clearly separated
port rows. Long-form, advanced, and low-frequency properties belong in Inspector.
Cards use a consistent minimum width of 180 px and default width of 220 px; schemas
may request 180–320 px. Text truncates with a tooltip and accessible full value.

Clicking a node body selects or moves it; it must never start, accept, or preview a
wire. Only visible, focusable port controls participate in connections. Port glyph
shape distinguishes input from output, and optional/required or single/many state is
present in text or iconography as well as color.

### Canvas tools

- Empty-canvas drag marquee-selects; Shift/Ctrl/Cmd modifies selection.
- Selected nodes move together by drag or keyboard nudge (1 px, 10 px with Shift).
  Movement changes local layout only and creates a local layout undo entry.
- Align left/center/right/top/middle/bottom, distribute horizontally/vertically,
  snap to grid, tidy selection, and deterministic auto-layout are commands.
- Copy/paste serializes a bounded, typed graph clipboard plus a plain-text YAML
  fallback. Paste remaps identities, previews collisions, and uses one mutation.
- Visual groups, labels, and comment frames are local layout metadata unless a
  future content schema explicitly defines them.
- `F` fits selection or graph; `0` resets zoom; `+`/`-` zoom; arrow keys traverse;
  Delete requests deletion; Escape cancels the active gesture.
- **Focus upstream**, **Focus downstream**, and **Focus connected component** dim
  unrelated nodes without changing YAML or selection.
- Auto-layout is stable for unchanged graphs, preserves explicitly pinned nodes, and
  can lay out all nodes or the selection. It is undoable as a layout-only action.
- Node and edge context menus expose only valid commands for that target. Empty-
  canvas context includes Add node, Paste, Select all, Auto-layout, and Fit.

The minimap shows the full graph, viewport, selection, errors, and live nodes. It can
collapse. Clicking recenters; dragging the viewport pans. It updates at most once per
animation frame and must not become an independent selection model.

## Explicit port connection contract

### Port model

Every projected port must contain:

| Field | Contract |
| --- | --- |
| `id` | opaque stable ID generated by the backend, unique within the projection |
| `nodeId` | owning projected node ID |
| `direction` | exactly `INPUT` or `OUTPUT` |
| `semanticType` | versioned type such as `execution`, `behavior-child`, `dialogue-flow`, or `quest-phase-ref` |
| `label` | concise user-facing role or branch/choice name |
| `cardinality` | `ZERO_OR_ONE`, `EXACTLY_ONE`, `ZERO_OR_MANY`, or `ONE_OR_MANY` |
| `required` | whether an absent compatible edge is a diagnostic |
| `order` | zero-based semantic position among sibling ports; absent only when unordered |
| `yamlPath` and `sourceRange` | exact owning YAML location and one-based display position data |
| `compatibility` | allowed target/source semantic types, resource scopes, cycle policy, and capability requirements |

Port IDs are server-owned and must be stable across no-op reprojection, scalar edits
outside the owning construct, and reorder operations. A successful mutation must
return the authoritative projection plus any identity remap needed when a construct
is replaced. The browser must not derive IDs from labels, array indexes, canvas
coordinates, or DOM IDs. Labels may change without invalidating an ID.

An edge contains a stable edge ID, source output port ID, target input port ID,
semantic type, optional label/order, resolved/cyclic state, and source ranges for the
YAML fragments that encode both ends. Projection validation rejects edges whose
source is not `OUTPUT`, whose target is not `INPUT`, or whose endpoints are absent.

### Connection gesture

1. A wire starts only from an enabled output port. Starting at an input is allowed
   only as a reconnect gesture for its existing single edge.
2. During drag, compatible inputs enlarge and receive a `Compatible: <reason>`
   affordance. Incompatible ports remain visible but use a blocked cursor and expose
   the first actionable rejection reason.
3. Compatibility is evaluated locally from the signed/versioned projection for fast
   feedback, then authoritatively by the backend. Local acceptance never guarantees
   mutation success.
4. Dropping on an incompatible port or node body performs no mutation. It announces
   type, direction, cardinality, scope, cycle, capability, unsupported-YAML, or stale-
   projection failure and keeps keyboard focus on the source port.
5. Dropping on empty canvas opens the node palette filtered to node types that can
   provide a compatible target input. Creating a node and connecting it is one
   compound request and one undo entry. Escape closes the palette without mutation.
6. Successful connect, reconnect, disconnect, insert-on-wire, or delete replaces the
   raw document and projection with the authoritative response, then restores focus
   using returned stable IDs/remaps.

```text
Dragging from: Choice / "Ask about work" [dialogue-flow OUTPUT]

  ◎ compatible input     accepts dialogue-flow; click/drop to connect
  ⊘ behavior child       requires behavior-child, received dialogue-flow
  ⊘ already connected    cardinality EXACTLY_ONE is full
  ▧ node body            select/move only; never a connection target

No YAML mutation has occurred.                         [Esc cancels]
```

Reconnect drags the existing endpoint while retaining the old connection until the
server accepts the replacement. Disconnect is explicit and warns before leaving a
required port empty. Dropping a node on a highlighted wire offers insert-on-wire only
when one input/output pair can preserve that wire's semantic type. Multi-step edits,
including create-and-connect and insert-on-wire, are atomic compound commands:
validation failure applies none of their operations.

Ordinary scalar values remain compact node fields or Inspector fields. They do not
become ports merely because another resource might contain the same string. Only
control flow, structural child relationships, branches, choices, and schema-declared
typed references become ports.

## Graph-specific port definitions

### Behaviors

- Every behavior graph has an `execution` input at its entry/root boundary.
- Sequence, selector, priority-selector, and parallel nodes expose one ordered
  `behavior-child` output per child plus an `add child` port/action. Reordering ports
  is a semantic YAML list reorder, not a visual-only change.
- Decorators expose exactly one `behavior-child` output, required according to their
  schema. Their execution input and child output are visually distinct.
- Condition/action outcome (`success`, `failure`, `running`, or declared extension
  outcomes) is shown as semantic status. It becomes a connectable output only where
  the Persona content contract explicitly supports an outcome branch; it must not
  invent branch YAML for leaf nodes.
- Subtree/call nodes expose a typed `behavior-reference` output targeting a compatible
  behavior resource input. Shared-only/player-only scope restrictions are enforced.

### Dialogues

- Start and dialogue entries expose `dialogue-flow` input/output ports.
- Sequential steps expose a named `next` output where continuation is supported.
- Each choice has its own stable, ordered output labeled with player-facing text or
  localization key. Choice ports are never collapsed into one generic node output.
- Condition steps expose distinct `true` and `false` outputs. Random branches expose
  one ordered, labeled/weighted output per branch.
- Local `goto` and external dialogue transfers use typed transfer-reference outputs,
  rendered differently from containment flow while remaining port-to-port.
- `end-dialogue`, `stop`, and implicit/explicit terminals have input ports and no
  connectable continuation output.

### Quests

- Quest entry and every phase expose a `quest-phase-flow` input.
- A phase has a distinct `default next` output and one stable, ordered output for
  every named/conditional branch. Completion uses a distinct `quest-completion`
  output/terminal, not a magic node-body edge.
- Objective graphs and lifecycle scripts (`on-start`, `on-complete`, `on-fail`,
  `on-reset`, phase hooks, and objective hooks) use named typed ports that open or
  connect to their nested graph boundary.
- Reordering phases/objectives remains an explicit semantic reorder even if a
  default-next edge makes the visual result look unchanged.

### NPCs

The central NPC card exposes separately named reference outputs for shared behavior,
player behavior, each ordered dialogue, each anchor/map entry, presentation data, and
lifecycle scripts. Behavior, dialogue, anchor, presentation, and lifecycle ports use
different semantic types and compatible targets. Clearing one reference cannot affect
another. Presentation is a port only when a signed schema declares a typed reference;
ordinary appearance fields stay in the card or Inspector.

### Scripts

- Every script or nested script boundary has an execution input. Sequential steps
  expose `next` outputs.
- Conditions expose `true` and `false`; random/choice constructs expose one stable,
  ordered output per branch; commands expose success/failure only when their YAML
  contract supports those handlers.
- Reusable script calls expose a `script-reference` output distinct from execution
  continuation, so opening the referenced script cannot be confused with rewiring
  local flow.
- `stop`, `end-dialogue`, and other schema-declared terminal nodes have no outgoing
  execution port.

### Unsupported and custom YAML

```text
┌ Custom YAML  ! unsupported structural shape ┐
│ !vendor-tag &shared                          │
│ lines 18:3–27:11                             │
│ [Reveal YAML] [Copy path]                    │
│ No safe ports; graph rewiring disabled       │
└──────────────────────────────────────────────┘
```

Unsupported constructs remain visible as `Custom YAML` cards at their graph position
and source range. They expose no structural port unless the backend can prove a typed,
lossless boundary. Wires may visually terminate at a noninteractive unresolved marker
when needed to explain existing YAML, but users cannot rewire it. Safe operations
elsewhere remain available. Invalid YAML freezes the last valid graph, marks it stale
and read-only, and focuses the exact syntax diagnostic in the YAML panel.

## Node palette

```text
┌ Add node ─ compatible with "true" output ─────────────────┐
│ Search nodes…                                              │
│ Suggested  Say · Run script · Command · End dialogue      │
│ Control    If · Choice · Random · Wait                     │
│ Extension  vendor:reputation-check  ✓ signed              │
│                                                           │
│ Enter create/connect   Alt+Enter create only   Esc cancel │
└───────────────────────────────────────────────────────────┘
```

The palette opens from the graph toolbar, empty-canvas context menu, keyboard, or
empty-canvas wire drop. Results are driven by graph kind, source port compatibility,
current nesting/scope, signed schema catalog, capabilities, and search. Each result
shows kind, description, ports it will use, extension owner/signature state, and why
it is disabled. Recent and suggested types must never bypass compatibility filtering.

## Inspector and selection synchronization

```text
┌ Inspector | History ──────────────────────┐
│ Choice: ask_work               ID: …       │
│ Source  dialogues/welcome.yml:34:5 [open] │
├ General ──────────────────────────────────┤
│ Text       I'm here for work.              │
│ Condition  [Edit…]                         │
├ Ports ────────────────────────────────────┤
│ IN  flow       connected from greeting    │
│ OUT choice     connected to quest_offer   │
├ Validation ───────────────────────────────┤
│ ! target is unreachable                    │
├ References / Live ────────────────────────┤
│ Used by 2 · not currently active           │
└────────────────────────────────────────────┘
```

Inspector sections are General, type-specific fields, Ports/Connections,
Validation, References, Live, and Metadata. Sections remember local collapsed state.
All controls show validation, units, defaults, source location, read-only reason, and
schema owner where relevant. Field commits use the same digest-guarded command route
as graph operations; free text may debounce, while enums/toggles commit immediately.

There is one workspace selection model. Selecting a canvas node, port, Inspector
field, diagnostic, reference, or YAML range updates every other representation:

- node selection opens its Inspector and reveals its complete YAML range;
- port or wire selection opens Ports/Connections and reveals the precise YAML tokens
  that encode the relationship;
- Inspector field focus highlights the card field and YAML token;
- a Problem selects the closest node/port/field and scrolls both Inspector and YAML;
- YAML cursor movement selects the innermost source-ranged graph object without
  stealing editor focus;
- live-runtime selection highlights the projected node but never changes source.

When a syntax error makes the graph stale, graph selection may reveal the last known
range but must be labeled `stale`; only YAML selection is authoritative until parse
success.

## Bottom dock

```text
┌ Problems 3 | References 12 | Preview | YAML • | Changes 4              [⌄] ┐
├────────────────────────────────────────────────────────────────────────────┤
│ YAML  dialogues/welcome.yml       Ln 34, Col 5       [Split] [Format? off] │
│ 33 │ - choice:                                                            │
│ 34 │     text: "I'm here for work."     ← selected port/field             │
│ 35 │     script: …                                                        │
└────────────────────────────────────────────────────────────────────────────┘
```

- **Problems** groups diagnostics by current resource or project, severity, source,
  and authoritative/advisory origin. It supports keyboard navigation and exact source
  reveal.
- **References** shows typed inbound/outbound references, resolved/cyclic state, and
  safe navigation/refactor commands.
- **Preview** contains dialogue/quest simulation or schema-provided preview when the
  session grants it. It cannot impersonate authoritative live state.
- **YAML** is a source editor with file tabs, diagnostics, source selection, undo
  integration, and optional center split. Formatting is never automatic.
- **Changes** shows source and semantic diff against the base revision, grouped by
  file and graph command, with recovery/publication state.

The active tab displays a count/status badge. Opening a diagnostic or source command
expands the needed tab just enough to reveal its target. Users can pin the dock open,
maximize it, or move YAML into a horizontal/vertical center split. Closing the split
returns YAML to the dock without changing content or undo history.

## Backend graph projection

The browser consumes a versioned, bounded `EditorGraphProjection` (next contract
version) with this logical shape:

```text
GraphProjection {
  graphVersion, schemaCatalogVersion, resourceIdentity, resourceKind, resourceId,
  filePath, rootYamlPath, contentDigest, editable, readOnlyReason,
  resources[], nodes[], ports[], edges[], sourceRanges[], editableFields[],
  diagnostics[], capabilities[], identityRemap?
}
```

- `resources` describes the open resource and referenced/nested graph boundaries.
- `nodes` contains stable ID, kind, title/subtitle, source-range ID, field IDs, port
  IDs, badges, extension owner, and custom/read-only state.
- `ports` is normalized rather than discoverable only by walking node DOM. It uses
  the complete port contract above.
- `edges` names existing source and target port IDs and their semantic/source state.
- `sourceRanges` contains file path, YAML path, UTF-8-safe offsets, and start/end line
  and column. Display lines/columns are one-based; protocol offsets use one documented
  convention consistently.
- `editableFields` includes type, serialized value, constraints, required/custom
  state, source range, edit capability, and signed schema/catalog ownership.
- `diagnostics` includes stable code, severity, message, authority, source range,
  related node/port/field IDs, and optional quick-fix command descriptor.
- `capabilities` states supported commands at projection/resource/node/port scope.
- `contentDigest` is computed over the exact authoritative raw content used to build
  the projection. A graph mutation with a different digest is stale.

Unknown projection fields are ignored only within the same compatible graph version.
Unsupported versions fail closed with a reload/update screen; the browser must not
guess ports or rewrite YAML.

Extension nodes, fields, ports, icons, compatibility, and palette entries are data-
driven by Persona-provided, versioned, signed schemas and catalogs. The backend
verifies signatures and bounds before projection. The browser never executes schema-
supplied JavaScript, HTML, CSS, URLs, or templates; it maps declarative records onto
an allowlisted component and token library. Missing, invalid, expired, or incompatible
schemas produce a visible Custom YAML card.

## Mutation protocol and losslessness

Use one authenticated graph mutation endpoint and one command dispatcher. Requests
are bounded and contain:

```text
GraphMutationRequest {
  graphVersion, requestId, resourceIdentity, filePath, rootYamlPath,
  expectedContentDigest, expectedProjectRevision?, operations[]
}

Operation {
  operationId,
  type: CONNECT | DISCONNECT | RECONNECT | INSERT | DELETE | REORDER | COMPOUND,
  sourceNodeId?, sourcePortId?, targetNodeId?, targetPortId?, edgeId?,
  parentPortId?, nodeKind?, nodeId?, beforePortId?, afterPortId?, fieldValue?,
  expectedSourceRange?, children?
}
```

`COMPOUND` contains a bounded list of the same primitive operations and is atomic.
Insert-on-wire is a compound disconnect, insert, and two connects. Reconnect is a
single atomic replacement, not client-side disconnect followed by connect. Delete
must name the projected node/edge and expected owning range. Reorder names the stable
parent port and neighbor port IDs; it never trusts a client-supplied array index alone.

Server bounds cover request bytes, project bytes/file count, operation count,
compound depth, inserted-node count, string sizes, nesting, affected files/ranges,
and request rate. Every operation rechecks session, capability, graph version,
digest/revision, IDs, directions, type compatibility, cardinality, order, cycles,
signed schema permissions, unsupported range intersection, and Persona validation.

A stale digest returns `409 STALE_PROJECTION` with the current digest/revision and no
mutation. The browser preserves the user's gesture as a non-applied history item,
reloads the authoritative document/projection, and offers retry only after showing
changed endpoints. Contract errors include code, safe message, file/YAML path, source
range, related node/port IDs, and retryability.

A successful response contains the exact updated raw file(s), new content digest and
project revision, minimal source patches with before/after ranges, rebuilt projection,
diagnostics, affected resource IDs, and identity remap. The browser must not patch its
graph optimistically beyond a temporary visual preview.

Every mutation compiles to the smallest provably safe YAML patch:

- scalar/reference edits replace only their scalar token and preserve scalar style
  when safe;
- insertion adds one generated fragment at a verified collection boundary;
- deletion removes exactly the owned entry/key and indentation/trailing line;
- reorder cuts and inserts the original byte slice;
- compound commands stage all patches and commit all or none.

Patches must preserve comments, mapping and sequence order, blank lines, scalar
styles, anchors, aliases, merge keys, custom tags, unknown fields, extension data,
line endings, and unaffected bytes. If a safe boundary cannot be proven, reject with
`UNSUPPORTED_YAML` and reveal the range. Neither projection nor mutation may parse
and reserialize the surrounding document.

Undo/redo stores accepted inverse commands and expected digests, not whole projected
graphs. A compound action is one history entry. If intervening YAML makes a safe
inverse stale, Undo is disabled with an explanation and the user may use Changes or
raw YAML. Publication/recovery history is not silently rewritten by local undo.

## Feedback, error, and read-only states

Autosave uses four explicit states: `Saving…`, `Saved`, `Unsaved—retrying`, and
`Recovery required`. Do not use a transient toast as the only feedback. Validation
uses `Valid`, `Warnings n`, `Errors n`, `Checking…`, or `Stale`. Connection uses
`Connected`, `Reconnecting`, or `Disconnected—editing stopped`.

On connection loss, capability revocation, expired session, stale signed snapshot, or
authoritative resync, all mutating inputs and graph gestures become inert immediately.
Existing project content is covered by a reconnect/read-only state according to the
security architecture; it must not remain a misleading editable cached workspace.
After reconnect, refresh session, grants, metadata, signed snapshot, revision, draft,
projection, validation, and live state before enabling commands.

Read-only controls remain inspectable and state their reason: missing capability,
unsupported YAML, invalid schema, stale projection, validation lock, publication in
progress, or disconnected session. Context menus and shortcuts must enforce the same
command availability as visible buttons.

## Narrow-screen fallback

```text
┌ Toolbar: ☰  welcome/dialogue   valid ●  ⋮ ┐
├───────────────────────────────────────────┤
│ breadcrumb › nested script                │
│ [Select] [Add] [Fit] [100%] [minimap]     │
│                                           │
│              CANVAS                       │
│                                           │
├───────────────────────────────────────────┤
│ Problems | Preview | YAML                 │
└───────────────────────────────────────────┘
  ☰ opens Content drawer; selection opens Inspector drawer
```

Below 900 CSS pixels, the navigation/content browser and Inspector become modal
drawers, the bottom dock becomes a tab sheet, and Visual/YAML is an exclusive toggle.
Only one drawer is open at a time; Escape closes it and restores focus. The canvas
retains at least 320 by 320 CSS pixels. Tabs may horizontally scroll, while the page
must not require two-dimensional scrolling. At 200% browser zoom every action remains
reachable. Canvas pan is not page scroll.

## Accessibility

- Each node is a focusable group named by kind, title, error count, selection, and
  live/read-only state. Each port is a real button named by direction, label, semantic
  type, cardinality, required state, and current connection.
- SVG/canvas wires are presentation backed by an accessible textual Connections list
  in Inspector. Keyboard users start a connection with Enter/Space, traverse filtered
  compatible targets, confirm with Enter, and cancel with Escape.
- Logical focus order follows graph flow and port order, not current pixel position.
  Virtualized nodes must become rendered and focused when reached.
- Focus never disappears after mutation, tab close, drawer close, projection reload,
  or error. Returned stable IDs/remaps determine the closest valid focus target.
- Status changes use polite live regions; connection loss and destructive conflict
  are assertive. Color is never the only signal.
- All text and interactive states meet WCAG 2.2 AA contrast. Forced colors preserve
  borders, ports, selection, and focus. `prefers-reduced-motion` disables pulsing,
  animated wire travel, and nonessential transitions.
- Keyboard graph authoring, menus, splitters, palette, minimap alternatives, Inspector,
  dock, and YAML synchronization must work at 200% zoom.

## Frontend module refactor

Keep the frontend browser-native. Spring Boot serves HTML, CSS, assets, and versioned
ES modules; the refactor must not require Node.js, npm, a bundler, transpilation, or a
frontend development server in production or development. Node-based browser test
tooling may remain optional test infrastructure, not an application build step.

`app.js` remains a small composition root. Split the monolith along these boundaries:

- `workspace-state.js`: normalized documents, projections, selections, history,
  validation, live overlays, dirty state, and immutable state transitions;
- `workspace-shell.js`, `content-browser.js`, `resource-tabs.js`, and
  `bottom-dock.js`: layout landmarks and navigation;
- `layout-store.js`: versioned, bounded local presentation preferences;
- `graph-canvas.js`, `graph-viewport.js`, `graph-selection.js`, `graph-layout.js`, and
  `graph-minimap.js`: rendering and presentation-only canvas state;
- `node-registry.js`, `node-renderer.js`, `port-renderer.js`, and
  `connection-rules.js`: declarative built-in/extension cards and compatibility UI;
- `graph-connections.js`, `graph-mutations.js`, and `command-dispatcher.js`: gestures,
  typed requests, atomic history, and one enabled-command path;
- `graph-inspector.js`, `source-selection.js`, `yaml-documents.js`, and
  `validation.js`: fields, ranges, diagnostics, and YAML synchronization;
- `transport.js`, `live-overlays.js`, and `capabilities.js`: authenticated transport,
  resync gating, trusted live display, and command availability.

Modules communicate through explicit state/actions and DOM events with documented
payloads; they do not reach into one another's private DOM. Custom elements may be
used for stable UI boundaries. Avoid a framework-shaped homegrown runtime: state and
rendering utilities stay small, testable, and browser-standard.

Rendering is scheduled with `requestAnimationFrame`. Batch node and SVG wire updates,
coalesce minimap updates to once per frame, virtualize offscreen nodes/wires for large
graphs, and retain the complete projection and accessible connection list. Parsing or
projection happens only after content changes, never on pan, zoom, selection, or live
overlay updates.

## Delivery stages

### Stage 1 — shell and visual foundation

Implement design tokens, global toolbar, navigation rail, searchable content browser,
resource tabs, breadcrumbs, Inspector/History shell, bottom dock, status bar,
splitters, local layout persistence, responsive drawers, and the canvas-first default.
Retain current editing behavior behind the new shell where necessary.

Exit: the default desktop canvas is dominant; panels are accessible, collapsible,
resizable, restorable, and no longer reserve a permanent half-screen YAML editor.

### Stage 2 — read-only semantic projection

Render versioned resources, semantic nodes, explicit ports, port-to-port wires,
fields, diagnostics, Inspector, minimap, live overlays, Custom YAML nodes, and two-way
YAML/source selection. No visual structural mutation is enabled.

Exit: every supported relationship maps to named ports; existing YAML is represented
without loss; invalid/unsupported content fails visibly and safely.

### Stage 3 — behaviors and dialogues editing

Enable connect, disconnect, reconnect, insert-on-wire, create-on-drop, delete,
reorder, Inspector field editing, and compound undo/redo for behavior and dialogue
graphs. Add compatibility and golden round-trip coverage before enabling each command.

Exit: common behavior trees and dialogue flows, including choices and true/false
branches, are fully authorable visually with no node-body connections.

### Stage 4 — remaining graphs and relationships

Add quests, NPCs, reusable/nested scripts, signed extension nodes/catalogs, and the
Relationship Map. Implement cross-resource navigation and atomic create/assign or
rename flows without arbitrary Relationship Map rewiring.

Exit: all supported Persona resource kinds use the same port and mutation contracts;
unsupported extension/YAML content remains visible and lossless.

### Stage 5 — advanced tools and hardening

Add auto-layout, alignment/distribution, groups, copy/paste, upstream/downstream focus,
complete keyboard authoring, reduced-motion/forced-color polish, narrow-screen polish,
virtualization, and performance tuning.

Exit: accessibility, visual regression, large-project performance, security, and
recovery suites pass the budgets and acceptance criteria below.

## Test strategy

### Contract tests

Backend and browser fixture tests must cover:

- stable node, port, and edge identities across no-op projection, scalar edit,
  reorder, mutation response, and reload;
- direction and semantic compatibility, scope/cycle/capability rules, required and
  optional ports, all cardinalities, and ordered ports;
- rejected node-body endpoints, missing/forged IDs, duplicate edges, full inputs,
  stale digests/revisions, unsupported versions, invalid signed schemas, and bounded
  request limits;
- error codes and exact file, YAML path, line/column, source range, and related
  node/port/field IDs;
- atomic connect, disconnect, reconnect, insert, delete, reorder, and compound
  requests, including all-or-nothing failure.

### Golden lossless round trips

For every graph kind and mutation, compare before/after raw bytes and prove that only
the intended token/range changed. Fixtures include comments before/after entries,
mapping and sequence order, blank lines, quoted/folded/literal scalars, CRLF, anchors,
aliases, merge keys, custom tags, unknown fields, signed extension data, duplicate-
looking list entries, nested scripts, and unsupported neighbors. Reparse the response
and assert projection IDs, ports, edges, source ranges, diagnostics, and digest.

### Browser interaction tests

Use real pointer and keyboard paths for output-to-input drag, compatible highlighting,
invalid target explanation, node-body rejection, reconnection, disconnect, palette on
empty drop, create-and-connect, insert-on-wire, compound undo/redo, stale response,
pan, cursor-centered zoom, fit, minimap, marquee, multi-move, nudge, layout, copy/paste,
groups, alignment, upstream/downstream focus, context menus, quick-open, command
palette, Inspector editing, Problems navigation, and two-way YAML synchronization.

Also test autosave states, capability loss, read-only/unsupported content, syntax
errors, reconnect/resync, panel persistence/clamping, tab state, drawer focus return,
and recovery/publication state retention.

### Visual regression tests

Use the reference image only as qualitative design guidance. Regression baselines
test Persona's own defined tokens and layouts at representative desktop, narrow, 200%
zoom, reduced-motion, forced-color, error, read-only, live, dense-graph, minimap, and
expanded-dock states. Baselines must assert canvas prominence, panel hierarchy, node
density, named port visibility, wire legibility, selection, and validation states;
they must not encode a pixel-perfect copy of the reference.

### Accessibility and performance tests

Run automated checks plus manual keyboard coverage for landmarks, splitters, focus
order/retention, complete graph authoring, port descriptions, live regions, menus,
drawers, contrast, forced colors, reduced motion, and 200% zoom.

Performance fixtures cover many resources, large individual graphs, dense wires,
many diagnostics/live overlays, rapid pan/zoom, Inspector edits, YAML reparsing, and
projection replacement. Record load-to-interactive, frame time, long tasks, DOM/SVG
counts, memory, mutation round trip, and minimap cost against the budgets in
`docs/visual-editor/QUALITY_BUDGETS.md`. Performance optimization must not remove
accessible representations, Custom YAML nodes, diagnostics, or source fidelity.

## Completion criteria

The refactor is complete only when all of the following are true:

- the canvas is visually dominant in the default desktop workspace;
- YAML is authoritative and available in the dock or optional split, without
  permanently consuming half of the editor;
- no wire begins or ends on a node body;
- every wire runs from an explicit output port to a compatible explicit input port;
- choices, branches, ordered children, outcomes where supported, and typed references
  expose distinct stable named ports;
- compatible targets highlight and invalid targets reject before mutation with an
  actionable reason;
- connect, disconnect, reconnect, insert-on-wire, palette-on-drop, delete, reorder,
  and compound undo/redo use bounded digest-guarded requests and minimal YAML patches;
- common workflows for behaviors, dialogues, quests, NPCs, and scripts can be
  completed visually, including with keyboard-only authoring;
- canvas, Inspector, Problems, References, live overlays, and YAML source selection
  remain synchronized;
- unsupported YAML is visible, source-revealable, read-only where unsafe, and
  byte-preserved through unrelated edits;
- panels are accessible, collapsible, resizable, and locally persisted; narrow and
  200%-zoom layouts keep all functionality reachable;
- signed extension schemas remain declarative, capability-scoped, bounded, and
  incapable of injecting executable browser content;
- existing authentication, connection gating, security capabilities, validation,
  live-state trust, autosave/draft, publication, semantic diff, recovery, revision,
  and lossless round-trip behavior remains intact;
- contract, golden, browser, visual, accessibility, and large-project performance
  tests pass.
