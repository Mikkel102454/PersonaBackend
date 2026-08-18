# PersonaBackend agent guide

Spring Boot hosted editor and relay with an embedded vanilla-JS browser editor. Java 25; production uses PostgreSQL 17+ and Redis 7+.

## Find things

- App entry: `src/main/java/nu/miguel/personabackend/PersonaBackendApplication.java`
- HTTP/domain areas: sibling packages `session`, `snapshot`, `draft`, `publish`, `project`, `document`, `graph`, `reference`, `validation`, `audit`, `retention`.
- WebSocket relay: `relay/`; auth, leases, quotas: `security/`, `administration/`; stores: `storage/`; telemetry: `observability/`.
- Shared Persona/backend DTO contract: `protocol/src/main/java/.../editor/protocol/` (separate Gradle subproject).
- Browser entry/styles/modules: `src/main/resources/static/editor/{app.js,style.css,modules/}`; HTML shell: `templates/editor/index.html`.
- Config: `src/main/resources/application.properties`; Flyway: `src/main/resources/db/migration/`.
- Java tests: `src/test/java/`; JS unit tests: `src/test/js/`; Playwright: `src/test/browser/`.
- System/deployment: `EDITOR_ARCHITECTURE.md`, `DEPLOYMENT.md`, `SELF_HOSTING_SOURCE.md`; visual-editor contracts: `docs/visual-editor/`.

## Work rules

- PostgreSQL is durable authority; Redis contains only expiring coordination/session data.
- Add schema changes as new Flyway migrations; never rewrite an applied migration.
- Keep protocol changes compatible with Persona and update both sides/tests.
- Preserve lossless YAML/source mapping, request bounds, authorization, retention, and telemetry privacy. Never log/tag content, tokens, player data, or secrets.
- Treat `build/`, `.gradle/`, `.idea/`, `node_modules/`, and `test-results/` as generated.

## Verify

```sh
./gradlew test
./gradlew build
npm run test:frontend
npm run test:browser   # Playwright; requires a suitable running app/environment
```

Run one Java test with `./gradlew test --tests 'fully.qualified.TestName'`. Local stack instructions and variables are in `DEPLOYMENT.md` and `.env.example`; do not expose or commit `.env` secrets.
