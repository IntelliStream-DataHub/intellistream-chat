# Contributing to IntelliStream Chat

Thanks for your interest in IntelliStream Chat — a small, self-hostable Slack/Mattermost-style chat
built with Spring Boot 4, Java 25, Postgres, Keycloak, and vanilla JS. Contributions of all
sizes are welcome.

## Ground rules

- **Read `AGENT.md` first.** It captures the conventions that aren't obvious from the code —
  the two security filter chains, the read-vs-write channel access split, the STOMP
  authorization model, the server-side Markdown sanitization, the embedded-Lucene search, and
  the "don'ts" (no SPA framework, no ILIKE search, no H2 in tests, no `ddl-auto=update`).
- **No new front-end framework.** UI is Thymeleaf + hand-written vanilla JS in `static/js/`.
  Render new UI server-side; reach for plain `fetch` + DOM updates.
- **Schema changes go through Flyway.** `ddl-auto=validate` is intentional — add a `V2+`
  migration under `src/main/resources/db/migration/`.
- **JS/CSS are bundled at build time** (Closure Compiler; see `ASSETS.md`). Include assets via
  the `fragments/assets` Thymeleaf fragment, never a raw `<script>`/`<link>`.
- **Every source file carries the Apache-2.0 header** (see any existing file for the template).

## Development setup

The fastest path is the compose stack — see **`QUICKSTART-COMPOSE.md`**:

```bash
podman compose up -d          # Postgres 18 + Keycloak 26 (realm pre-imported)
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[]|select(.clientId=="ichat-client").secret' keycloak/realm.json)
./gradlew bootRun             # http://localhost:8080 — sign in as alice/alice
```

For a native (no-container) setup, see `QUICKSTART-MANUAL.md`. Container runtime here is
**Podman** (Docker is not required); `podman compose` and `docker compose` are interchangeable.

Copy `application-dev.properties.example` to `application-dev.properties` (gitignored) if you
need to override local dev settings — `bootRun` auto-activates the `dev` profile.

## Running tests

```bash
./gradlew test                                                   # everything (needs a container runtime for ITs)
./gradlew test --tests 'ai.intellistream.chat.service.*'  # pure unit tests, no containers
```

- **Integration tests** (`src/test/java/.../integration/*IT.java`) run against a real Postgres
  via Testcontainers — **there is no H2 fallback** (the production schema needs real Postgres).
- Expose the Podman socket first: `systemctl --user enable --now podman.socket` then
  `export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock`. If Ryuk misbehaves,
  `export TESTCONTAINERS_RYUK_DISABLED=true`.
- **Add a test with your change:** a unit test for pure-logic branches, and an IT (new or an
  addition to a sibling) for anything DB- or endpoint-shaped.

## Submitting changes

1. Branch off `main`.
2. Keep commits focused; write a clear message explaining the *why*.
3. Make sure `./gradlew build` passes (compile + tests + asset bundling).
4. Open a pull request describing the change and how you verified it.

## Reporting bugs and security issues

- **Bugs / features:** open a GitHub issue with reproduction steps.
- **Security vulnerabilities:** do **not** open a public issue — follow `SECURITY.md`.

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
