# Persona YAML to graph mapping

Status: accepted mapping for the graph contract v3 and Persona script content format 2. Authoritative runtime
sources are `Persona/content/ContentLoader.java`, `Persona/behavior/BehaviorLoader.java`,
and extension editor schemas. Paths below are JSON-pointer-shaped YAML paths.

Every projected object carries `filePath`, `yamlPath`, `startOffset`, `endOffset`,
`startLine`, `startColumn`, `endLine`, and `endColumn`. A semantic ID is added where
available. No canvas coordinate is a content identity.

## Documents

| YAML construct | Graph projection | Pins/edges | Inspector | Allowed lossless operations |
| --- | --- | --- | --- | --- |
| `behaviors/*.{yml,yaml}` | Behaviour resource + root node | root `execute` | `content-version`, `id`, `scope` | scalar edit; root wrap/replace; atomic rename/move/delete |
| `dialogues/*.{yml,yaml}` | Dialogue resource + entry nodes | start and transfer edges | `content-version`, `id`, `start` | scalar edit; add/remove/reorder script; entry create/delete; rename |
| `quests/*.{yml,yaml}` | Quest resource + ordered phase flow | entry, branch, implicit-next, complete | root fields and hooks | scalar edit; phase/objective/script structural patches; rename |
| `npcs/*.{yml,yaml}` | Central NPC overview | typed reference/containment edges | root presentation fields | scalar edit; list/map patches; reference assignment; rename |
| `scripts.yml#/scripts/{id}` | Explicit typed graph with synthetic Input/Output boundaries | execution and nominal data wires | signature, defaults, callers | keyed node/wire patches; atomic parameter create/rename/reorder/type/delete |
| unrecognized YAML file | Custom YAML resource | reference edges only when typed by server | raw range | raw YAML edit only |

## Behaviour nodes

All nodes require `id` and `type`. Composite `children` and decorator `child` are
containment pins whose order is semantic.

| `type` | Card/pins | Fields | Structural mutations |
| --- | --- | --- | --- |
| `sequence` | ordered execution; N child outputs | `id` | insert, disconnect, reorder, wrap, unwrap, delete |
| `selector` | ordered first-success; N child outputs | `id` | same as sequence |
| `priority-selector` | reactive ordered selector; N outputs | `id` | same; moving changes priority |
| `parallel` | N child outputs | `success-threshold`, `failure-threshold`, `cancel-remaining` | same; thresholds validated against child count |
| `invert` | one child input/output | `id` | connect/disconnect/wrap/unwrap/delete |
| `repeat`, `retry` | one child | exactly one of `times` or `forever` | decorator operations; scalar edit |
| `timeout`, `cooldown` | one child | `duration` | decorator operations; scalar edit |
| `checkpoint` | one child | checkpoint compatibility state is overlay only | decorator operations |
| `wait` | leaf | `duration` | insert/move/duplicate/delete |
| `condition` | leaf, success/failure semantic outputs | discriminator plus condition fields | insert/move/duplicate/delete; scalar edit |
| `action` | leaf, success/failure semantic outputs | discriminator plus action fields | same |
| `subtree` | behaviour reference | referenced behaviour ID (`behavior`; legacy spelling is not synthesized) | assign/create/open target; insert/move/delete |
| namespaced extension node | schema-generated card | signed schema properties only | only operations allowed by schema/capabilities |

Behaviour condition discriminators and fields:

| Condition | Fields |
| --- | --- |
| `event` | `event` (or preserved existing `name`), `consume` |
| `memory` | `key`, `scope`, `operator`, `value` |
| `quest-state` | `quest`, `state` |
| `item-count` | `material`, `amount` |
| `flag` | `name`, `value` |
| `variable` | `name`, `operator`, `value` |
| `permission` | `permission` |
| `world` | `world` |
| `chance` | `chance` |
| namespaced extension condition | signed schema fields plus compatibility metadata |

Behaviour action discriminators and fields:

| Action | Fields |
| --- | --- |
| `navigate`, `private-navigate` | `destination`/preserved `anchor`, `arrival-distance`, `speed`, `pathfinding-range`, `stuck-seconds`, `stuck-action`, `stuck-retries` |
| `begin-private-presentation` | none |
| `logical-travel` | `source`, `destination`/preserved `anchor`, `duration` |
| `wander` | `radius` |
| `look`, `set-anchor` | `anchor` |
| `set-visible` | `visible` |
| `remember` | `key`, `value`, `value-type`, `ttl`, `scope` |
| `adjust-memory` | `key`, `amount`, `ttl`, `scope` |
| `forget` | `key`, `scope` |
| `signal` | `name` |
| `script` | `script` reusable-script reference |
| `command` | `command` plus the selected command's fields |
| namespaced extension action | signed schema fields plus scope/durable metadata |

## Script steps and reusable graphs

Inline scripts occur in dialogue entries, NPC hooks, quest/phase/objective hooks, command
handlers, and choice/random branches. Their ordered list position is semantic. Reusable scripts are
different: `scripts.yml` must declare `content-version: 2`, and every script is an explicit
`inputs`/`outputs`/`nodes`/`connections` descriptor. Older reusable-script lists are rejected with a
migration diagnostic; the editor never guesses a conversion.

Graph contract v3 exposes `channel` (`EXECUTION` or `DATA`), exact nominal `valueType`, direction,
cardinality, required state, stable order, source range, inline literal/default metadata, connected
state, editability, resource kind, and signed compatibility metadata on every port. Execution pins
are triangular and data pins are circular. Data wires require exact nominal types; explicit
converter nodes are the only coercion. Input and Output boundary cards are synthetic and cannot be
deleted. A `run-script` card derives typed inputs and outputs from its target signature.

Signature rename and deletion update the declaration, boundary wires, every `run-script.inputs`
binding, and caller data endpoints in one revision. Reorder preserves mapping entry bytes. Type
changes fail closed while incompatible wires remain. Unknown fields, comments, quoting, tags, and
neighboring scripts remain byte-for-byte unchanged by bounded mutations.

| Step | Card/pins | Fields and nested graphs | Mutations |
| --- | --- | --- | --- |
| `say` | line node, `next` | one of `text`, `text-key`, `variants`; optional `translations`, `delay`; variant `text`, `weight` | scalar/list patch |
| `if` | condition input; `then`, `else`, `next` | `when`; nested `then`/`else` scripts | connect branch; insert/move/wrap/delete |
| `choice` | one labeled output per option; `next` | option `text`, optional `when`, nested `script` | option insert/reorder/delete; branch connect |
| `goto` | transfer/reference output | `node`; or `dialogue` and optional `node` | reconnect target; scalar patch |
| `end-dialogue` | terminal | none | insert/move/delete |
| `stop` | terminal | none | insert/move/delete |
| `wait` | `next` | `duration` | scalar/list patch |
| `random` | weighted output per option; `next` | option `weight`, nested `script` | option insert/reorder/delete; branch connect |
| `run-script` | script-reference output; `next` | `script` | assign/create/open target; scalar/list patch |
| built-in command | `success`, `failure`, `next` | command fields; nested `on-success`, `on-failure` | scalar and nested list patches |
| namespaced command | same as command | signed schema fields and handlers | schema/capability-approved patches |

Built-in command fields:

| Commands | Fields |
| --- | --- |
| `start-quest`, `finish-quest` | `quest` |
| `deliver-items` | `quest`, `objective` |
| `set-flag` | `flag`, `value` |
| `set-variable` | `variable` or preserved `name`, `value`, `operation` |
| `message`, `action-bar`, `broadcast`, `npc-speak` | `text`, `audience`, `radius`, `location` |
| `title` | `title`, `subtitle`, `fade-in`, `stay`, `fade-out`, `audience`, `radius`, `location` |
| `play-sound` | `sound`, `volume`, `pitch`, `audience`, `radius`, `location` |
| `particle` | `particle`, `count`, `offset-x`, `offset-y`, `offset-z`, `extra`, `audience`, `radius`, `location` |
| `give-item`, `take-item` | `material`, `amount` |
| `give-experience` | `amount` |
| `run-command` | `command`, `as` |
| `teleport`, `lightning-effect`, `npc-move` | `location` |
| `potion-effect` | `effect`, `duration`, `amplifier`, `ambient`, `particles` |
| `spawn-entity` | `entity`, `location` |
| `set-block` | `material`, `location` |
| `npc-animation` | `animation` |

Script condition cards are `all`, `any`, `not`, `quest-state`, `item-count`, `flag`,
`variable`, `permission`, `world`, `chance`, and signed namespaced conditions. `all`
and `any` own ordered `conditions`; `not` owns `when`; other fields match the behaviour
condition table except that script conditions do not provide native event/memory.

## Dialogues

| YAML path | Projection | Fields/pins | Mutations |
| --- | --- | --- | --- |
| `/start` | distinguished Start edge | entry ID | Set as start scalar patch |
| `/nodes/{nodeId}` | dialogue-entry card | semantic stable key; script containment | create/rename/delete entry; open nested script |
| `/nodes/{nodeId}/script` | ordered script graph | entry and next flow | script operations above |
| `goto` within any nested script | transfer wire | local node or external dialogue/node | reconnect with existence/cycle diagnostics |

Advisory analysis adds missing target, unreachable, implicit end, and transfer-loop
diagnostics. Persona validation remains authoritative.

## Quests

| YAML construct | Projection | Fields/pins | Mutations |
| --- | --- | --- | --- |
| quest root | resource/entry | `id`, `title`, `description`, `when`, `repeatable`, `cooldown`, `maximum-completions`, `time-limit` | scalar edit/rename |
| `on-start`, `on-complete`, `on-fail`, `on-reset` | nested script graph | lifecycle edge | script operations |
| `phases[]` | ordered phase card | `id`, `title`, `description`; implicit-next pin | insert/reorder/duplicate/delete/scalar edit |
| phase `branches[]` | conditional edge | `when`, `next-phase` | insert/reorder/delete/reconnect |
| phase `on-start`, `on-complete` | nested script graph | lifecycle edge | script operations |
| `objectives[]` | objective card | common and type fields; required/optional state | insert/reorder/duplicate/delete/scalar edit |
| objective hooks | nested script graph | start/progress/complete | script operations |
| `on-progress` | progress hook | `every`, `script` | scalar/script operations |

Objective fields common to built-ins are `id`, `title`, `description`, `type`,
`amount`, `optional`, `hidden`, `on-start`, `on-progress`, and `on-complete`.

| Objective type | Additional fields |
| --- | --- |
| `collect-item`, `deliver-item` | `material`, `amount` |
| `talk-to-npc` | `npc`, optional `instance`, `amount` |
| `kill-entity` | `entity`, `amount` |
| `go-to-location`, `interact-block` | `location.{world,x,y,z}`, `radius`, `amount` |
| `wait`, `survive` | `duration` |
| namespaced objective | signed schema data and parsed required progress |

## NPCs

| YAML construct | Projection | Fields/pins | Mutations |
| --- | --- | --- | --- |
| NPC root | central NPC card | `content-version`, `id`, `display-name` | scalar edit/rename |
| `shared-behavior` | behaviour reference card | shared-only reference pin | assign/create/open/clear |
| `player-behavior` | behaviour reference card | player-only reference pin | assign/create/open/clear |
| `dialogues[]` | ordered dialogue reference cards | `id`, `priority`, optional `when` | insert/reorder/delete/assign/create/open |
| `anchors/{name}` | anchor card/map point | `world`, `x`, `y`, `z`, `yaw`, `pitch` | field edit/create/rename/delete |
| `on-interact`, `on-no-dialogue` | nested script graphs | lifecycle edge | script operations |

Presentation properties supplied by signed extension schemas use schema-generated
inspector controls and remain custom YAML when the schema is absent or invalid.

## References and relationship edges

Typed references are produced by `ProjectReferenceService`, not guessed in the
browser. Types include behaviour/subtree, NPC behaviour/dialogue, dialogue transfer,
quest/command, objective NPC, and reusable-script calls. Each edge includes source
file/path/range, target kind/ID, resolved state, inbound/outbound direction, and cycle
membership. Create, rename, move, and delete use atomic project operations.

## Custom YAML fallback inventory

The following are visible but never structurally rewired:

- invalid YAML (last valid graph remains stale and entirely non-editable);
- custom tags, anchor definitions, aliases, merge keys, or nodes crossing their ranges;
- unknown keys or future content-version constructs;
- extension-owned data without a verified signed schema, with an invalid schema, or
  outside that schema's declared properties;
- mixed/flow-style structures for which a minimal safe insertion boundary cannot be
  proven;
- scalar keys used as identity when renaming would collide or require untyped edits;
- unsupported YAML files and intentionally unsupported graph shapes.

The fallback card links to the exact source range. Scalar edits outside it and graph
operations whose source ranges do not cross it remain available. Any operation that
would move, replace, normalize, or implicitly drop fallback content is rejected with
`UNSUPPORTED_YAML` before mutation.

## Mutation-to-source contract

| Graph command | Required source edit |
| --- | --- |
| field edit / set start / reconnect scalar reference | replace only the scalar token range, preserving scalar style when possible |
| insert node/step/phase/objective | insert a generated YAML fragment at the parent collection boundary |
| delete | delete exactly the item/key range and its owned indentation/trailing line |
| move/reorder | cut the exact source slice and insert it at a sibling boundary |
| duplicate | copy the exact slice, patch only required semantic IDs, insert at sibling boundary |
| wrap/unwrap | insert/remove wrapper delimiters and indentation through one compound patch |
| connect/disconnect containment | compile to insert/move/delete operations appropriate to the target cardinality |
| atomic create/rename/delete/move | stage all affected file patches, validate the candidate project, then return all or none |

Every request includes graph contract version, expected document digest/base revision,
bounded operations, and target stable/YAML IDs. Every successful response includes
raw content, parsed document, rebuilt projection, affected paths, and new digest.
