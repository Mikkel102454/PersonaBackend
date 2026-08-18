# Persona hosted editor deployment

PersonaBackend requires PostgreSQL 17+ and Redis 7+ in production. PostgreSQL is the
durable authority for authoring metadata, revisions, drafts, and audit events. Redis
holds only expiring verification/session state, quotas, presence, and relay routing.
Minecraft runtime state is never made authoritative in either hosted store.

The complete hosted frontend and relay source can be audited or self-hosted from this
project. `./gradlew sourceDistribution` creates a reproducible source archive; see
`SELF_HOSTING_SOURCE.md` for its contents and compatibility constraints.

## Local development

Copy `.env.example` to `.env`, replace both secrets, then run:

```sh
docker compose up --build
```

Loopback HTTP/WS is accepted for local development only. The Compose ports bind to
`127.0.0.1`; PostgreSQL and Redis are not published to the host network.

## Production requirements

- Put one or more editor containers behind an HTTPS reverse proxy. Use the supplied
  `deploy/nginx/persona-editor.conf` as a baseline and install a valid certificate.
- Set `PERSONA_PUBLIC_URL=https://editor.example.com` and
  `PERSONA_PUBLIC_WS_URL=wss://editor.example.com`. These are links returned to Persona
  and must describe the externally reachable proxy, not an internal container name.
- Keep PostgreSQL, Redis, and actuator endpoints on private networks. Rotate database,
  Redis, and actuator credentials through the deployment secret manager.
- Back up PostgreSQL and test restoration. Redis data may be lost without losing
  durable content; active editor sessions will expire and must be reopened.
- Preserve WebSocket upgrade headers and set proxy idle timeouts above the configured
  heartbeat/idle thresholds. Sticky sessions are optional: Redis pub/sub routes peers
  across instances and a reconnect to a node without replay state requests a signed
  full resynchronization.

## Environment variables

| Variable | Required | Purpose |
| --- | --- | --- |
| `PERSONA_DATABASE_URL` | yes | PostgreSQL JDBC URL |
| `PERSONA_DATABASE_USER` | yes | Least-privilege database role |
| `PERSONA_DATABASE_PASSWORD` | yes | Database secret |
| `PERSONA_DATABASE_POOL_SIZE` | no | JDBC pool bound, default 10 |
| `PERSONA_REDIS_HOST`, `PERSONA_REDIS_PORT` | yes | Private Redis endpoint |
| `PERSONA_REDIS_PASSWORD` | production | Redis authentication secret |
| `PERSONA_PUBLIC_URL`, `PERSONA_PUBLIC_WS_URL` | yes | Public HTTPS/WSS origins |
| `PERSONA_SESSION_LIFETIME` | no | Absolute editor-session lifetime (default `8h`); changing it affects newly created sessions |
| `PERSONA_ACTUATOR_TOKEN` | yes | Random 32+ character bearer token |
| `PERSONA_QUOTA_*` | no | Per-installation/session request bounds |
| `PERSONA_PUBLISH_CONFIRMATION_LIFETIME` | no | Lifetime of a pending trusted-session publish request (default `5m`) |
| `PERSONA_QUOTA_PUBLISH_REQUESTS` | no | Publish requests allowed per session per request window (default `20`) |
| `PERSONA_RETENTION_REVISIONS` | no | Maximum age for unreferenced signed revisions (default `30d`) |
| `PERSONA_RETENTION_DRAFTS` | no | Maximum age for drafts not retained by a publish record (default `30d`) |
| `PERSONA_RETENTION_PUBLISHES` | no | Maximum age for terminal publish/rollback records (default `180d`) |
| `PERSONA_RETENTION_AUDIT` | no | Structured audit retention (default `365d`) |
| `PERSONA_RETENTION_MAX_REVISIONS` | no | Per-installation revision cap; the latest and referenced revisions are protected (default `100`) |
| `PERSONA_RELAY_SEND_TIMEOUT_MS` | no | Slow-consumer send deadline |
| `PERSONA_RELAY_SEND_BUFFER_BYTES` | no | Per-WebSocket outbound buffer bound |
| `PERSONA_TELEMETRY_ENABLED` | no | Enables trace export; default false |
| `PERSONA_OTLP_METRICS_ENABLED` | no | Enables OTLP metrics in addition to Prometheus; default false |
| `PERSONA_TRACE_SAMPLE_RATE` | no | Trace sample probability, default 0.05 |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | when tracing | Private OTLP/HTTP trace endpoint |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | when OTLP metrics | Private OTLP/HTTP metrics endpoint |

Flyway runs before the application becomes ready. `/actuator/health/liveness`,
`/actuator/health/readiness`, `/actuator/metrics`, and `/actuator/prometheus` require
`Authorization: Bearer $PERSONA_ACTUATOR_TOKEN`; an absent token disables actuator
access rather than exposing it anonymously.

Telemetry exports operational route templates, timings, status, JDBC pool, Redis, and
relay measurements. A global observation/meter filter removes keys related to memory
values, content/YAML, players/UUIDs, browser identity, IP/address, inventory/chat, and
all codes/tokens/leases. Do not add raw content or player data as metric tags or span
attributes in downstream extensions.

## Retention and deletion

An hourly transaction removes expired subscriptions, terminal publish records, their
now-unreferenced drafts, old audit events, and unreferenced content revisions. The
latest revision per installation and every revision referenced by an active draft or
publish/rollback record are always protected. The per-installation revision cap also
prevents unbounded growth when revisions arrive faster than their age window.

Live trace recording is disabled: runtime relay data is ephemeral and has no durable
trace table, so its effective retention is zero. Enabling future trace recording must
introduce an explicit bounded policy and migration before any runtime payload is stored.
PostgreSQL backups remain an operator responsibility and must use a retention policy at
least as strict as the application policy when deletion guarantees are required.
