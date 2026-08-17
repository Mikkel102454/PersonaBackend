# P2 implementation checkpoints

This log records only validated vertical slices. `Persona/TODO.md` remains the
authoritative completion checklist.

## 2026-08-17 — Editor foundation productivity and export

- Added bounded, deterministic ZIP export for either the complete project or only
  changed YAML files. Paths, duplicate entries, file digests, file count, and total
  uncompressed bytes are validated before archive creation.
- Added per-file undo/redo, explicit YAML copy/paste, tab-local recovery, existing
  unsaved-change warnings, a searchable command palette, and keyboard file navigation.
- Added Git-style textual changes and changed-field markers in the visual model.
- Preserved raw YAML as the editable authority; exporting and history never reserialize
  YAML, so comments, ordering, aliases, unknown keys, and extension data remain intact.
- Validation: `bash gradlew test --rerun-tasks` in `PersonaBackend` passed (2026-08-17).

## 2026-08-17 — Signed least-privilege session filters

- Added canonical, immutable world, player, NPC, and content-namespace restrictions to
  the shared protocol. Restrictions are part of the installation-signed session
  request and exposed in browser/plugin status; the relay cannot broaden them.
- Extended `/persona editor <content-scope>` with repeatable `world=`, `player=`,
  `npc=`, and `namespace=` filters and displays them both before trust and in session
  listings.
- Bumped the independently versioned editor protocol to v3 because the signed session
  envelope changed.
- Validation: full `bash gradlew test --rerun-tasks` suites passed in both `Persona`
  and `PersonaBackend` (2026-08-17).

## 2026-08-17 — Spring Security enforcement and quota foundation

- Added stateless Spring Security lease authentication for all session HTTP endpoints
  and both WebSocket handshakes. Plugin and browser roles are endpoint-specific;
  capability authorities protect snapshot and draft operations in addition to the
  existing service-layer checks.
- Routed WebSocket envelope capability decisions through Spring Security's
  authorization manager while retaining strict message allowlists and signatures.
- Added bounded per-installation and per-session quotas for session creation,
  verification, socket connections/messages, snapshots, and drafts. Keys expire and
  the implementation has a hard tracked-key bound; the interface is ready for the
  required Redis-backed horizontal implementation.
- Validation: `bash gradlew test --rerun-tasks` in `PersonaBackend` passed, including
  role-isolation and 429 quota tests (2026-08-17).

## 2026-08-17 — PostgreSQL, Flyway, Redis, and horizontal session infrastructure

- Added explicit domain records and PostgreSQL tables for installations, editor
  sessions, browser identities, capability grants, content revisions/files, drafts,
  publish requests, subscription definitions, and structured audit events.
- Replaced process-local revision and draft authority with a transactional JDBC store.
  Raw YAML remains text and round-trips byte-for-byte, including comments, aliases,
  unknown fields, and extension-owned data. No Minecraft runtime state is persisted.
- Added Flyway migration V1 with foreign keys, constraints, and query indexes.
- Moved verification codes, hashed session leases, nonce replay protection, and quota
  counters behind an expiring-state interface backed by atomic Redis operations in
  production. Verification-code compare-and-delete is one atomic Lua operation.
- Added PostgreSQL-backed session rehydration so a plugin and browser may authenticate
  against different backend instances. Redis pub/sub routes already validated signed
  envelopes between instances; Redis presence has bounded TTLs. A missed replay on a
  new node continues to use the existing full-resynchronization path.
- Added bounded WebSocket send-time/send-buffer enforcement through Spring's concurrent
  session decorator and retained count/byte-bounded replay windows.
- Added durable, sanitized structured audit recording for connection, trust, snapshot,
  draft, and revocation operations already exposed. Memory/value/chat/inventory/IP/
  secret fields are rejected from audit detail payloads.
- Validation: full `bash gradlew test --rerun-tasks` passed with real PostgreSQL 17 and
  Redis 7.4 Testcontainers; the production Spring context test also selected and
  connected all PostgreSQL/Redis coordination beans (2026-08-17).

## 2026-08-17 — Deployment and privacy-safe observability

- Added a non-root Java 25 multi-stage Docker image, a bounded local Compose topology
  for the editor/PostgreSQL/Redis, an environment template, and complete production
  secret/configuration documentation. `docker compose config --quiet` and a complete
  `docker build` both succeeded.
- Added an Nginx TLS-termination/load-balancing reference with WebSocket upgrade,
  forwarded-header, body-size, and timeout configuration. Redis routing means sticky
  sessions are optional; full signed resync covers replay misses after node changes.
- Exposed liveness, readiness, metrics, and Prometheus through Actuator. Every actuator
  route requires a distinct constant-time bearer token of at least 32 characters;
  missing configuration fails closed.
- Added Spring Boot 4 OpenTelemetry/OTLP tracing and optional OTLP metrics alongside
  Prometheus. Both exporters default off unless explicitly configured.
- Added global trace and metric filters that remove memory/value, content/YAML,
  player/UUID, browser, IP/address, inventory/chat, and credential attributes, plus a
  global metric-count bound.
- Validation: backend unit and container suites passed; a random-port production test
  proved unauthenticated readiness is rejected while authorized readiness and
  Prometheus return live data (2026-08-17).

## 2026-08-17 — Authoritative continuous draft validation

- Added typed `VALIDATION_REQUEST`, `ValidationProject`, diagnostic, and signed
  `VALIDATION_RESULT` protocol messages. Browser relay requests contain UUID pointers
  only; candidate YAML is retrieved separately with the plugin's lease.
- Correlated requests through expiring Redis-compatible state and persisted drafts, so
  request and result routing remains valid when HTTP, browser socket, and plugin socket
  land on different backend instances. Results are accepted once and audit-recorded.
- Persona stages the complete candidate scope beside, never over, live content and runs
  its normal `ContentValidator` and extension API on the Minecraft server thread.
  Temporary files are bounded, digest checked, scope checked, and deleted afterward;
  no active registry or runtime object is changed.
- The editor requests validation after each debounced autosave, verifies Persona's
  existing installation signature, and renders navigable file/line/column/node errors
  and suggested typo fixes while retaining its last valid visual YAML model.
- Added isolation, correlation, signed-relay, mismatch, one-shot, and structured
  diagnostics tests. Full `bash gradlew test` in `PersonaBackend` and full
  `bash gradlew test --rerun-tasks` in `Persona` passed (2026-08-17).
- Added parameterized visual/source round-trip fixtures for behaviors, NPCs, quests,
  dialogues, scripts, and extension-defined nodes. Each fixture proves comments,
  ordering, anchors/aliases, and unknown future fields survive typed visual edits.
- Missing content references are returned as separate reference type/ID fields in
  addition to file, line, column, nearest stable node ID, message, and suggested fix;
  the editor displays and navigates these structured diagnostics.
- Replaced full-project autosave uploads with digest-guarded file patches tied to a
  specific signed base revision. The backend rejects unavailable/cross-installation
  bases and mismatched per-file base digests, supports explicit additions/deletions,
  then reconstructs and stores a complete bounded draft for validation. Export remains
  independent of publish capability.
- Added a bounded project-wide reference analyzer for behavior, NPC, dialogue, quest,
  and reusable-script declarations and typed references. The editor shows inbound and
  unresolved edges and provides a non-mutating rename preview with declaration/reference
  roles and exact YAML paths, file lines, and columns; conflicts fail the safety check.
- Added a bounded deterministic semantic diff for behavior, NPC, dialogue, quest, and
  script YAML. It distinguishes typed scalar additions/removals/changes and whole-file
  changes while intentionally excluding comments and formatting; the editor exposes a
  navigable project-level view and the command palette links to it.
- The relay continues to reject command/publish/mutation message names outside its
  explicit typed allowlist; validation and draft patch messages contain bounded data or
  identifiers only and cannot carry executable server commands.
- Validation after the patch/reference/diff slice: full `bash gradlew test` in
  `PersonaBackend`, full `bash gradlew test --rerun-tasks` in `Persona`, and
  `git diff --check` in `Persona` all passed (2026-08-17).

## 2026-08-17 — Confirmed authoritative publication

- Bound validation proof to the exact deterministic candidate revision, so editing a
  reused draft ID invalidates the result and publication proof.
- Added capability-gated, rate-limited publish requests with a 60-bit single-use code,
  no-store browser response, five-minute expiry, persisted semantic diff/validation/
  revision metadata, and request/confirmation/completion audit events.
- Added `/persona editor apply <session> <code>`. Persona rechecks the initiating
  operator, current permission/capability, feature switch, signed base revision,
  candidate digest, built-in schemas, and current extension parsers on the server
  thread before any file changes. Publication is disabled by default.
- Persona writes a verbatim recoverable backup and manifest, replaces each YAML file
  through same-directory atomic moves, swaps the validated runtime registry, and
  restores both files and the previous registry if activation fails. The hosted
  service cannot perform the apply itself.
- The browser exposes publication only after fresh successful Persona validation,
  shows the exact in-game command and expiry, then polls the authenticated durable
  result. Backend and plugin tests cover validation binding, one-shot codes, stale/
  unvalidated rejection, metadata, backup fidelity, successful activation, and
  rollback on runtime failure.
- Full test suites passed in both projects; PostgreSQL/Redis Testcontainers remained
  green and Compose configuration validated with required secrets (2026-08-17).
- Added initiator-only `/persona editor rollback <session> <publish-id> confirm`.
  Persona accepts only the exact immutable backup/manifest for a completed publication,
  checks that live content is still the published revision, revalidates the rollback
  target, and uses the same recoverable transaction while creating a second safety
  backup. Rollback request/result state and audit events are durable and typed.
- Full suites passed again after explicit rollback, including successful restoration,
  safety-backup fidelity, status persistence, audit recording, and protocol/security
  routing tests (2026-08-17).
- Added configurable transactional retention for terminal publishes, unreferenced
  drafts/revisions, expired subscriptions, and structured audit events. The latest
  installation revision and every active workflow reference are protected, while a
  per-installation revision cap bounds high-frequency churn. Durable live tracing is
  explicitly disabled (zero retention). Both in-memory and PostgreSQL 17 tests cover
  deletion order, reference protection, and latest-revision preservation; the full
  backend suite passed (2026-08-17).
## Extension-defined metadata and live catalogs

- Persona API 2.2 adds generic `EditorSchemaProvider` and `EditorCatalogProvider` contracts for all existing and future namespaced content kinds; extensions publish data-only JSON Schema and bounded read-only providers, never frontend code.
- Persona annotations, schema constraints, discriminators, widget hints, dependent inputs, stable catalog values, pagination, missing/deprecated policies, and server-thread/bounds enforcement are covered by API and validation tests.
- Each session receives a separately signed immutable metadata snapshot through the Redis-backed expiring store. The relay accepts only typed, signed `CATALOG_REQUEST`/`CATALOG_RESULT` messages, and Persona invokes catalog code on the server thread after permission and revision checks.
- The browser verifies document and aggregate digests plus the installation signature, renders schema-driven controls, searches/caches live values by installation/extension/catalog/dependencies, displays live/cached/stale/unavailable states, preserves vanished IDs, and blocks publish UI for rejected missing values.
- Normal Persona loading, dry-run validation, and publish staging validate extension schemas and catalog references authoritatively. The signed metadata revision is bound into validation proofs and publish audit data, so extension/catalog changes invalidate an outstanding proof.
- Evidence: `ExpansionRegistryTest`, `ValidationSchemaTest`, `BehaviorLoaderTest`, `ContentLoaderTest`, `EditorMetadataServiceTest`, `RelaySocketHandlerTest`, `EditorLeaseAuthenticationFilterTest`, `EditorFoundationHttpTest`, `ValidationServiceTest`, and `PublishServiceTest`.
## Subscription-scoped live observability

- The shared protocol now has typed subscription topics/filters, acknowledgements, and immutable player/NPC/behavior/quest/dialogue/memory/server snapshots and deltas; no arbitrary runtime maps or executable payloads cross the relay.
- The backend persists only expiring subscription definitions, applies signed session restrictions again, requires player/memory capabilities, rate-limits creation, enforces topic/item/sequence bounds, and never stores snapshot bodies as authoritative hosted state.
- Persona captures UUID/world/quest counts, logical NPC presentations, behavior paths/checkpoints/wakes/deadlines/outcomes/condition inputs/inboxes, quest progress, dialogue position, and typed memories on the server thread. Player names, IPs, chat, inventories, and unrelated state are absent; memory keys/values default to redacted unless a namespace is configured.
- Per-session feeds run at a bounded 250–5,000 ms cadence, compute changed-record deltas and removals, coalesce pending refreshes, and serialize/send off-thread through the bounded relay queue.
- The browser exposes a read-only live server view and visibly dims/labels stale data after interruption.
- Evidence: `LiveSubscriptionServiceTest`, `RelaySocketHandlerTest`, `EditorFoundationHttpTest`, `LiveSnapshotBuilderTest`, and `EditorClientJsonTest`.
## Safe live controls and complete observability

- Added coalesced active-node animation plus typed navigation lifecycle, quest transition/completion history, dialogue line/choice/wait/cancellation state, and server scheduler/projection metrics.
- Added capability-gated behavior and memory mutation DTOs, Persona-side scope/type/optimistic-revision validation on the server thread, a secure-default global kill switch, browser confirmation forms, structured results, mutation-specific rate limiting, and sanitized audit events.
- Targeted Persona live snapshot/client tests and backend relay/editor tests pass.

## Installation authentication and relay resilience

- Added a backend-issued, expiring, one-time challenge; Persona proves possession of its pinned Ed25519 installation key and receives a single-use installation lease before HTTP session creation.
- Added a fail-fast cross-instance relay circuit breaker alongside the existing bounded replay windows, bounded concurrent send buffers, send timeout, backpressure, and slow-consumer termination.
- `SessionServiceTest`, `RelayCircuitBreakerTest`, and `RelayHubTest` pass.

## Visual structure editing and authoring experience

- The lossless YAML document service now supports tested move, duplicate, delete, typed list insertion, and mapping insertion operations; the browser exposes drag/drop branches, stable-ID templates, content-specific and extension palettes, semantic behavior badges, scope/duplicate warnings, subtree navigation, and live node overlays.
- `AUTHORING.md` now contains the complete behavior node table, propagation/cancellation diagram, focused examples, recipes, and format migration policy. Packaged examples explain non-obvious semantics.
- Added `/persona example list|copy` with a fixed packaged manifest, traversal protection, and no-overwrite behavior. Gradle now builds a versioned `schemas` publication artifact.
- `YamlDocumentServiceTest`, `EditorFoundationHttpTest`, `ExampleInstallerTest`, `PackagedSamplesTest`, JavaScript syntax validation, and `schemaArchive` pass.

## Complete visual insights, self-host source, and production integration

- Added lossless branch extraction into standalone behavior files and completed visual
  structure operations for compatible drag/drop moves, nesting, duplication, deletion,
  stable IDs, and subtree navigation.
- Added deterministic no-mutation behavior, dialogue, and quest simulators with mock
  memories/events/conditions; static dialogue reachability, dead-end, missing-target and
  loop diagnostics; quest reachability/impossible-branch previews; contextual placeholder
  inventories; and durable/transient behavior semantics.
- Added NPC anchor tables plus X/Z plotting, command/debug coordinate import, activation-
  distance warnings, shared/private live presentation, and entity type/name/skin/equipment/
  age/pose previews.
- Added localized dialogue `say` steps with `text-key`, per-locale translations, and exact
  locale, language, default, then key fallback. The editor displays the same keys and
  translations used by Persona at runtime.
- Added the reproducible `sourceDistribution` archive and self-hosting documentation for
  the browser frontend, Spring API, relay, protocol, migrations, and deployment inputs.
- Expanded the PostgreSQL/Redis Testcontainers context to run Flyway, real authenticated
  HTTP and WebSocket routing/reconnect, Redis expiry and rate limiting, and Redis pub/sub
  cross-instance routing. The test exposed and fixed the missing Spring Boot 4.1
  `spring-boot-flyway` auto-configuration module.
- Evidence: JavaScript syntax validation; focused document/editor/localization/live/sample
  tests; `ProductionInfrastructureContextTest`; complete `PersonaBackend` test suite plus
  `sourceDistribution`; and complete `Persona` test suite with rerun tasks all pass on
  2026-08-17. Every implementation checkbox under both P2 sections is now checked.
