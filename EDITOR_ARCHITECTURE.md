# Persona editor frontend architecture

The hosted editor uses browser-native HTML, CSS, and versioned ES modules served by
Spring Boot. There is no Node.js process, server-side template engine, or frontend
code running on the Minecraft server. This keeps the deployable editor as one Java
application while allowing the browser UI to grow through small custom elements and
modules.

## Boundary

- Spring Boot owns authentication, session leases, bounded session-scoped ZIP/YAML ingestion,
  durable draft and revision APIs, validation orchestration, publishing, and relay
  policy.
- Browser modules own project navigation, raw and visual editing state, local undo and
  recovery, and rendering. Browser validation is advisory.
- Persona remains authoritative for live content, extension schemas, validation, and
  publication. It sends signed immutable snapshots and validates every requested
  mutation.
- The hosted relay and browser are not trusted to assert Minecraft state. A browser
  verifies server snapshot signatures before loading files into the workspace.

There is deliberately no offline editor or sessionless project endpoint. The page
route is available only while the matching Persona plugin relay is present. Browser
verification, every browser HTTP request, and the WebSocket handshake recheck that
presence. Losing the plugin socket closes the browser relay locally or across backend
instances; the workspace becomes inert immediately and is not revealed again until a
new signed authoritative snapshot and editor metadata have been verified.

Project import, export, parsing, reference analysis, semantic diff, templates, and
project lifecycle operations are all scoped beneath an authenticated session URL.
Create, duplicate, atomic rename, and guarded delete accept a digest-verified bounded
raw-YAML candidate and return byte-preserving patches/candidate files for the existing
draft, validation, semantic-diff, export, publication, recovery, and undo flows.

## Lossless document workspace

The raw YAML string is the sole editable document. A bounded Spring endpoint parses it
into a typed, source-ranged view model for the browser; the browser never maintains a
second serialized copy. Visual scalar edits are applied to the exact source range and
then reparsed. They do not dump the surrounding object graph, so comments, mapping
order, aliases, custom tags, unknown keys, and extension-owned sections remain intact.
Custom tagged and anchored values that cannot be rewritten without changing YAML
semantics remain visible and source-editable but are read-only in the generic form.

Raw edits are parsed after a short debounce. A syntax error includes a one-based line
and column and leaves the last valid visual tree on screen. Selecting a visual field
selects its YAML range; moving the YAML cursor highlights the innermost visual field.
These hosted checks cover YAML structure only. Persona remains authoritative for
content-format, reference, extension-schema, and live-server validation.

Entering the one-time code binds the browser's ephemeral key but grants only
`CONTENT_VIEW`. Player visibility, memory visibility, draft editing, publishing, and
live mutation are separate requested capabilities. Persona displays the sanitized
browser description, scope, requested capabilities, verification code, and expiry;
an authorized operator must then run the two-step `editor trust` command before the
backend grants any elevated capability. Trust can be revoked independently while the
read-only session remains open.

## Live transport

Production public endpoints must use HTTPS and WSS; plain HTTP/WS is accepted only
for loopback development. `PERSONA_PUBLIC_URL` and `PERSONA_PUBLIC_WS_URL` describe
the externally reachable endpoints, while the plugin separately controls its hosted
URL, TLS policy, and whether the editor feature is enabled.

Plugin and browser sockets use separate handlers and leases. Every forwarded envelope
is signed by its originating installation or ephemeral browser key and is rejected
before dispatch unless its protocol version, session, next sequence number, message
type, bounded payload, capability, and signature are valid. The current allowlist is
read-only: heartbeat, snapshot-change notification, and resynchronization request.
There is deliberately no relay message that can write or activate Persona content.

Each direction retains a bounded replay window (both count- and byte-limited). A
reconnecting client supplies the last peer sequence it accepted. The relay replays
the missing suffix or emits transport-only `RESYNC_REQUIRED`; the browser then reloads
and verifies the current signed Persona snapshot, and the plugin uploads a fresh
authoritative snapshot if its side requires resynchronization. Relay control messages
never contain authoritative Minecraft data.

Both clients send heartbeats. The backend closes idle sockets, enforces an absolute
session expiry, supports authenticated explicit revocation, replaces stale sockets on
reconnect, and closes all sockets during application shutdown. Plugin disable/reload
also revokes its active sessions and closes their outbound sockets.
