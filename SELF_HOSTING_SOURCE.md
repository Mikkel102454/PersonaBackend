# Persona hosted editor source distribution

The hosted browser application, HTTP API, WebSocket relay, shared protocol, database
migrations, container build, and reverse-proxy example are all maintained in this
directory. No closed relay component or separately hosted frontend is required.

Run `./gradlew sourceDistribution` to produce the reproducible archive
`build/distributions/persona-hosted-editor-<version>-sources.zip`. The archive includes:

- the Spring Boot API and relay under `src/main/java`;
- the browser application under `src/main/resources/static/editor`;
- the versioned Java protocol module under `protocol`;
- Flyway migrations, configuration, Docker/Compose, and nginx deployment files; and
- the Gradle wrapper/build inputs needed to build and test the same sources.

An operator can inspect the archive, run `./gradlew test bootJar`, build the supplied
Dockerfile, and configure the resulting service using `DEPLOYMENT.md`. Preserve the
protocol compatibility contract if deploying a modified relay: Persona installations
reject unsupported versions, invalid message types, and invalid signatures.

The source archive is intentionally an application source distribution, not a dump of
credentials or build output. Local `.env` files, Gradle caches, IDE files, and generated
artifacts are excluded.
