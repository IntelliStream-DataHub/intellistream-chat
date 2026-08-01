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
| Postgres | `postgres:18-alpine`         | `127.0.0.1:5432`        | `ichat_role` / `ichat_role`, db `intellistream_chat` |
| Keycloak | `keycloak:26.0`              | port `8081` (see note)  | admin console: `admin` / `admin` |
| coturn   | `coturn:4.6`                 | `127.0.0.1:3478/udp`    | shared secret `dev-turn-secret` |

coturn is the TURN relay that carries voice and video for 1:1 calls. It binds to loopback, which is
both what the quick start needs — two browsers on this machine — and the only safe default for a
container that starts on its own with a shared secret published in this repo. Reaching it from
another device means setting `ICHAT_TURN_RELAY_IP` *and* a real `ICHAT_TURN_SECRET`; the comments
on the `coturn` service in `docker-compose.yml` spell out what else has to change, and
`QUICKSTART-MANUAL.md` covers running it properly on a host.

Keycloak imports the `ichat-realm` realm from `keycloak/realm.json` on first boot (takes
15–30 s — watch `podman compose logs -f keycloak` for `Imported realm ichat-realm`). The realm
ships an OIDC client (`ichat-client`) and two test users: **`alice`/`alice`** and **`bob`/`bob`**.

> **Note — bind address:** the compose file binds Keycloak to a LAN IP so phones on the same
> network can log in during mobile testing. If you don't need that, change the `keycloak`
> service's `ports`/`KC_HOSTNAME` entries to `127.0.0.1` and adjust
> `KEYCLOAK_ISSUER_URI` accordingly.

An optional OpenBao (Vault) dev server is profile-gated — only started with
`podman compose --profile openbao up -d`. The app runs fine without it.

## 2. Run the app

```bash
# The OIDC client secret is baked into the dev realm file; export it for the app:
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[] | select(.clientId=="ichat-client") | .secret' keycloak/realm.json)

# Point the app at the coturn started in step 1, so the call buttons appear:
export ICHAT_TURN_URLS=turn:127.0.0.1:3478?transport=udp
export ICHAT_TURN_SECRET=dev-turn-secret

./gradlew bootRun
```

`bootRun` auto-activates the `dev` Spring profile, builds the JS/CSS bundles (see
`ASSETS.md`), runs Flyway migrations against Postgres, and serves the app on
**http://localhost:8080**.

The two `ICHAT_TURN_*` values must match coturn's, which they do above — the app signs a short-lived
credential with that secret and coturn recomputes the same HMAC to check it. Get them out of step
and every call fails to connect while looking exactly like a network problem. Both are unset by
default and the call buttons simply don't render until they are, so the feature fails closed rather
than offering a control that cannot work.

## 3. Sign in and smoke-test

1. Open http://localhost:8080 → **Sign in with Keycloak** → `alice` / `alice`.
2. Create a channel, post a message.
3. Open a second browser (or private window) as `bob` / `bob`, join the channel — bob sees
   alice's messages appear live over WebSocket without a reload.
4. Call: from alice, open a direct message with bob and press the phone or camera button in the
   header. Bob's window rings. Answer it, and both sides show a running timer; hang up and the
   conversation gets a `Call · 12 sec` line.

The call buttons are in direct messages only — a channel has no single person to ring, and one
peer connection has nowhere to put a third participant.

> **It has to be `localhost`.** `getUserMedia` requires a secure context, and `localhost` is the
> only origin exempt from the HTTPS requirement. Reach the same app over a LAN IP and the camera
> and microphone are blocked outright — no permission prompt, no useful console error, just a call
> that never gets media. This catches people who test from a phone on the same network; that needs
> real TLS, not a different bind address.

## Stopping / resetting

Stop the app first with `Ctrl-C` in the terminal running `bootRun` — Gradle reports the cancelled
run as a failed build, which is expected. Then the containers:

```bash
podman compose down        # stop containers, keep the Postgres volume (chat history survives)
podman compose down -v     # also wipe the volume — chat history gone, fresh schema on next up
```

That volume is the only difference between the two. Keycloak keeps no volume of its own, so the
realm re-imports on either form and `alice` / `bob` come back regardless.

Neither touches `data/` on the host (attachments, avatars, Lucene index), so delete that too if you
want `down -v` to leave nothing behind.

## Troubleshooting

- **`relation "..." does not exist` at startup** — Postgres wasn't healthy yet or the volume
  holds stale state: `podman compose down -v && podman compose up -d`, then retry.
- **`invalid_client` on login** — `KEYCLOAK_CLIENT_SECRET` isn't exported in the shell running
  `bootRun` (step 2).
- **Login redirect loops** — Keycloak was still importing the realm; wait for
  `Imported realm ichat-realm` in its logs.
- **No call buttons in a direct message** — `ICHAT_TURN_URLS` and `ICHAT_TURN_SECRET` aren't both
  exported in the shell running `bootRun` (step 2). The app hides the buttons rather than render a
  control with no media path behind it.
- **The call rings, is answered, then never connects** — the app and coturn disagree about the
  secret, or coturn isn't up: `podman compose ps coturn` and `podman compose logs coturn`. Every
  candidate pair failing to authenticate is indistinguishable from a network fault from the
  browser's side.
- **Camera or microphone never prompts** — you're not on `localhost`. See the note in step 3.
