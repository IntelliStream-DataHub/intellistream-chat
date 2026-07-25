# IntelliStream Chat — quick start (Docker/Podman Compose)

The fastest way to a running IntelliStream Chat: Postgres 18 and Keycloak 26 come up in containers
with everything pre-configured (realm, OIDC client, two test users); the app itself runs from
Gradle on the host.

Works with Podman (`podman compose`) or Docker (`docker compose`) — the commands below use
`podman compose`; substitute `docker compose` 1:1 if that's what you have.

## Prerequisites

- **Java 25** (the Gradle toolchain downloads one if your JDK differs)
- **Podman** (with `podman compose`) or **Docker** with the compose plugin
- **jq** (used once, to read the dev client secret out of the realm file)

## 1. Start the infrastructure

```bash
podman compose up -d
```

This starts:

| Service  | Image                        | Address                 | Credentials |
|----------|------------------------------|-------------------------|-------------|
| Postgres | `postgres:18-alpine`         | `127.0.0.1:5432`        | `intellistream` / `intellistream`, db `intellistream_chat` |
| Keycloak | `keycloak:26.0`              | port `8081` (see note)  | admin console: `admin` / `admin` |

Keycloak imports the `intellistream` realm from `keycloak/realm.json` on first boot (takes
15–30 s — watch `podman compose logs -f keycloak` for `Imported realm intellistream`). The realm
ships an OIDC client (`intellistream-chat`) and two test users: **`alice`/`alice`** and **`bob`/`bob`**.

> **Note — bind address:** the compose file binds Keycloak to a LAN IP so phones on the same
> network can log in during mobile testing. If you don't need that, change the `keycloak`
> service's `ports`/`KC_HOSTNAME` entries to `127.0.0.1` and adjust
> `KEYCLOAK_ISSUER_URI` accordingly.

An optional OpenBao (Vault) dev server is profile-gated — only started with
`podman compose --profile openbao up -d`. The app runs fine without it.

## 2. Run the app

```bash
# The OIDC client secret is baked into the dev realm file; export it for the app:
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[] | select(.clientId=="intellistream-chat") | .secret' keycloak/realm.json)

./gradlew bootRun
```

`bootRun` auto-activates the `dev` Spring profile, builds the JS/CSS bundles (see
`ASSETS.md`), runs Flyway migrations against Postgres, and serves the app on
**http://localhost:8080**.

## 3. Sign in and smoke-test

1. Open http://localhost:8080 → **Sign in with Keycloak** → `alice` / `alice`.
2. Create a channel, post a message.
3. Open a second browser (or private window) as `bob` / `bob`, join the channel — bob sees
   alice's messages appear live over WebSocket without a reload.

## Stopping / resetting

```bash
podman compose down        # stop containers, keep the Postgres volume (chat history survives)
podman compose down -v     # also wipe the volume — full reset, realm re-imports on next up
```

## Troubleshooting

- **`relation "..." does not exist` at startup** — Postgres wasn't healthy yet or the volume
  holds stale state: `podman compose down -v && podman compose up -d`, then retry.
- **`invalid_client` on login** — `KEYCLOAK_CLIENT_SECRET` isn't exported in the shell running
  `bootRun` (step 2).
- **Login redirect loops** — Keycloak was still importing the realm; wait for
  `Imported realm intellistream` in its logs.
