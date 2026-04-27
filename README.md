# Radiance — Spring Boot 4 Slack/Mattermost-style chat app

A small workspace chat built with Spring Boot 4.0.5, Java 25, PostgreSQL, Keycloak OIDC, STOMP-over-WebSocket, Thymeleaf and vanilla JS.

## Why this exists

Workplace chat is critical infrastructure. Decisions get made there, knowledge accrues there, postmortems happen there. Most companies have handed the keys to a vendor whose interests do not include making sure you can still read your own conversations next year.

**Slack** is good. It's also proprietary, cloud-only, and your archive is governed by the vendor's pricing tiers and retention rules. The cost-per-seat and the visibility horizon are theirs to set. That's a workable trade for plenty of teams. It isn't workable for regulated industries, security-conscious orgs, or anyone who'd rather not have their internal knowledge graph held off-premises.

**Mattermost** sold itself as the open-source escape valve, and for a while it was. Then the free edition started taking things back. SAML and OAuth2 logins are paywalled now. Team message history is capped at 10,000 on the free plan. Governance tooling has moved behind enterprise editions. You can still self-host the binary. The open-core playbook is at work here: the things that separate a real chat app from a demo keep migrating into the licence you have to pay for. "Open source" stops meaning much when the table stakes aren't.

This is a small bet that those aren't the only two options. A chat application you can deploy on a box you control. No message cap. No SSO paywall. No telemetry. No vendor able to change the terms a year from now because the funding round demanded it. It won't have Slack's polish or Mattermost's feature breadth. It will still be readable in five years, on a server you own, running code you can audit, under a licence that can't be retroactively narrowed.

## Prerequisites

Before the Quick start you need a Java 25 JDK and (for the container path) Podman. Gradle itself is not required — `./gradlew` downloads it on first use.

### AlmaLinux / Rocky / RHEL 10+

```bash
sudo dnf install -y java-25-openjdk-devel podman podman-compose
java -version    # should report "openjdk 25"
```

### Ubuntu 24.10+ / Debian trixie

```bash
sudo apt install -y openjdk-25-jdk podman podman-compose
java -version
```

On Ubuntu 24.04 LTS or older Debian releases OpenJDK 25 is not in the default archives yet. Either upgrade to a newer release, or install Temurin from Adoptium:

```bash
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/trusted.gpg.d/adoptium.asc
echo "deb https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-25-jdk
```

### macOS

```bash
brew install openjdk@25 podman
brew services start podman    # or: podman machine init && podman machine start
```

If `java` doesn't end up on `PATH`, follow the post-install instructions Homebrew prints (`echo 'export PATH="/opt/homebrew/opt/openjdk@25/bin:$PATH"' >> ~/.zshrc` on Apple silicon).

## Quick start — development

For exploring the app, hacking on it, or quick local testing. Two commands once the prerequisites above are installed:

```bash
podman compose up -d   # Postgres 17 + Keycloak 26, with the 'chat' realm pre-imported
./gradlew bootRun      # the Spring Boot app on :8080
```

Open http://localhost:8080 and sign in as `alice` / `alice` or `bob` / `bob`. Keycloak admin console is at http://localhost:8081 (`admin` / `admin`).

If `podman compose` can't find a socket, run once: `systemctl --user enable --now podman.socket && export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock`.

For container-free setup, see [Without containers (native install)](#without-containers-native-install) below.

## Quick start — production

For a real internet-facing deployment. **Do not skip the hardening steps**: the bundled defaults are tuned for local dev and would be embarrassing on the public internet.

```bash
# 1. Build a runnable jar
./gradlew assemble                # produces build/libs/chat-*.jar

# 2. Stand up Postgres 17 + Keycloak 26 on the host (see "Without containers" below)
#    or your managed equivalents. Point the app at them via env vars.

# 3. Configure the production env. Each line below is required.
export CHAT_DB_URL=jdbc:postgresql://db.internal:5432/chat
export CHAT_DB_USERNAME=chat
export CHAT_DB_PASSWORD=$(openssl rand -base64 32)
export KEYCLOAK_ISSUER_URI=https://auth.example.com/realms/chat
export KEYCLOAK_CLIENT_SECRET=$(openssl rand -base64 32)   # rotate from the dev default
export SERVER_ADDRESS=127.0.0.1                            # bind localhost only; nginx fronts it
export CHAT_SECURITY_COOKIE_SECURE=true                    # mark JSESSIONID + CSRF cookies Secure

# 4. Run behind a TLS-terminating reverse proxy (see nginx_example.conf in this repo):
java -jar build/libs/chat-*.jar
```

Then complete the [production hardening checklist](#production-hardening-checklist) below before flipping DNS.

## Production: systemd + JVM tuning

The bare `java -jar …` line above gets you running once. For an actual deployment, run under systemd so the OS supervises the process, restarts it on crash, captures logs to `journald`, and applies basic sandboxing.

### systemd unit

Drop this at `/etc/systemd/system/radiance.service`:

```ini
[Unit]
Description=Radiance chat server
Wants=network-online.target
After=network-online.target postgresql.service

[Service]
Type=simple
User=radiance
Group=radiance
WorkingDirectory=/opt/radiance
EnvironmentFile=/etc/radiance/env
UMask=0027

ExecStart=/usr/lib/jvm/java-25-openjdk/bin/java $JAVA_OPTS -jar /opt/radiance/chat.jar

Restart=on-failure
RestartSec=5s
TimeoutStopSec=30s
KillSignal=SIGTERM

# Sandboxing — strip Linux capabilities the JVM doesn't need.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/radiance/data
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
RestrictNamespaces=true
LockPersonality=true
SystemCallArchitectures=native
# MemoryDenyWriteExecute is intentionally NOT set — the JIT needs writable + executable
# pages and the JVM won't start with it on.

[Install]
WantedBy=multi-user.target
```

The companion env file at `/etc/radiance/env` (chmod 600, owned by `radiance`):

```bash
# JVM tuning — see the table below for what each flag does.
JAVA_OPTS=-Xms1g -Xmx1g -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/radiance/data/heapdumps -XX:+UseStringDeduplication -XX:+AlwaysPreTouch -Duser.timezone=UTC

# App config (see "Quick start — production" for the full list)
CHAT_DB_URL=jdbc:postgresql://db.internal:5432/chat
CHAT_DB_USERNAME=radiance
CHAT_DB_PASSWORD=...
KEYCLOAK_ISSUER_URI=https://auth.example.com/realms/chat
KEYCLOAK_CLIENT_SECRET=...
SERVER_ADDRESS=127.0.0.1
CHAT_SECURITY_COOKIE_SECURE=true
```

Bring it up:

```bash
sudo useradd --system --home /opt/radiance --shell /usr/sbin/nologin radiance
sudo install -d -o radiance -g radiance /opt/radiance /opt/radiance/data /opt/radiance/data/heapdumps
sudo install -d -o root -g radiance -m 750 /etc/radiance
sudo install -m 640 -o root -g radiance /path/to/env /etc/radiance/env
sudo install -m 644 build/libs/chat-*.jar /opt/radiance/chat.jar
sudo chown radiance:radiance /opt/radiance/chat.jar
sudo systemctl daemon-reload
sudo systemctl enable --now radiance
sudo systemctl status radiance
sudo journalctl -u radiance -f
```

### JVM options

Defaults that fail fast and dump enough to debug:

| Flag | Why |
|---|---|
| `-Xms1g -Xmx1g` | Fix the heap. Resizing during a load spike causes a full GC right when you can least afford it. 1 GiB comfortably handles low-thousands of concurrent WebSocket sessions; bump to 2 GiB if you cross ~5k concurrent users or grow a large Lucene index. |
| `-XX:+ExitOnOutOfMemoryError` | Don't limp along with a half-broken VM — let systemd restart instead. |
| `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=…` | A 1 GiB heap dump is small. You'll want it the next time OOM hits. Make sure the path is writable and rotated occasionally. |
| `-XX:+UseStringDeduplication` | G1-only. Collapses duplicate `String` byte arrays — free win on a chat app where the same usernames / channel names appear in every message DTO. |
| `-XX:+AlwaysPreTouch` | Pre-faults every heap page at startup. Adds ~1 s to boot, removes first-allocation jitter at runtime. Worth it for a long-lived server. |
| `-Duser.timezone=UTC` | Container hosts often default to local TZ; pin to UTC so log timestamps line up with your dashboards. |

Things you do **not** need to set:

- `-XX:MaxRAMPercentage` — only useful when running in a container with a cgroup limit and no fixed `-Xmx`.
- `-XX:+UseG1GC` — already the default since Java 9.
- `-XX:+UseCompressedOops` — already on for any heap < 32 GiB.
- `--enable-preview` — Spring Boot 4 doesn't use preview language features here.

The app already enables **virtual threads** via `spring.threads.virtual.enabled=true` in `application.yml`, so your servlet + WebSocket handlers run on Project Loom green threads. That keeps the OS thread count flat regardless of WebSocket fan-out, and shifts the bottleneck from "thread pool exhausted" to "GC throughput" — which leads to the next section.

### What about ZGC / generational ZGC?

Java 25 ships **generational ZGC** (sub-millisecond pauses regardless of heap size) and **generational Shenandoah** as alternatives to the default G1. Both are *exciting*, and both are the wrong choice at a 1 GiB heap.

The honest tradeoff:

- **G1GC** (default): 10–50 ms pauses on a 1 GiB heap, ~5% throughput overhead, mature and well-understood.
- **ZGC** (`-XX:+UseZGC`): <1 ms pauses regardless of heap size, but reserves ~15–20% extra RAM for colored-pointer metadata and costs ~10–15% throughput. The pause-time win only helps when your p99 GC pause is hurting users — it isn't, at this heap size.
- **Shenandoah** (`-XX:+UseShenandoahGC`): similar profile to ZGC, slightly less RAM overhead, depends on your JDK build shipping it (most OpenJDK distributions do; some vendor builds don't).

Reach for ZGC when your heap crosses ~4 GiB **and** GC pauses become user-visible (long-poll latency spikes, dropped WebSocket frames during collection). At 1 GiB G1 wins on every axis you actually care about: throughput, RAM footprint, and operational familiarity.

If you scale up later, the migration is one line in `/etc/radiance/env`:

```bash
JAVA_OPTS=-XX:+UseZGC -Xms4g -Xmx4g -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/radiance/data/heapdumps -Duser.timezone=UTC
```

(ZGC self-configures and ignores most legacy GC flags. Drop `+UseStringDeduplication` and `+AlwaysPreTouch` — neither applies under ZGC.)

## Features

- Sign in with **Keycloak** (OAuth2 / OIDC).
- **Channels** (public + private). Anyone can join public channels; private channels require an admin invite. Channel admins can invite members and promote others.
- **Direct messages** (1:1 and group). DM list lives alongside channels in the sidebar; "Send DM" entry point on every avatar hovercard.
- **Real-time messaging** over native STOMP-over-WebSocket — messages, edits, deletes, and avatar updates fan out live.
- **Threaded replies**, **emoji reactions**, **mentions** (`@username`) with per-channel unread + mention badges, **per-user read state**, **typing indicators**, and **message permalinks**.
- **File attachments** uploaded via streamed multipart (no buffering); image attachments open in a lightbox.
- **Profile pictures** with server-side resize (PNG/JPEG ≤256px), live broadcast on change.
- **Avatar hovercard** with profile info + "Send direct message" action.
- **@mention notifications**: in-tab toast plus opportunistic OS notification (Notification API) when permitted.
- **Markdown** message bodies — server-side render with CommonMark + GFM tables + autolinks, sanitized with jsoup, fenced-code syntax highlighting via highlight.js, and link previews / embedded YouTube.
- **Full-text search** powered by an embedded **Apache Lucene** index. Three scopes: per-channel, across all channels you've joined, and (admin-only) everywhere.
- **Themes** (8 built-in palettes) chosen on the profile page.
- **Admin console** at `/admin` for users with the Keycloak `admin` realm role.

## Stack

### Runtime / build
- **Java 25** toolchain
- **Spring Boot 4.0.5**, Gradle Kotlin DSL
- **PostgreSQL 17** (uses `gen_random_uuid()`)
- **Keycloak 26** as the OIDC issuer (runs out-of-process via `podman compose`)

### Spring Boot starters
| Starter | Brings in |
|---|---|
| `spring-boot-starter-web` | Spring MVC + embedded Tomcat 11, Jackson |
| `spring-boot-starter-websocket` | STOMP-over-WebSocket message broker |
| `spring-boot-starter-thymeleaf` | Thymeleaf 3.1 server-side templates |
| `spring-boot-starter-data-jpa` | Spring Data JPA + Hibernate ORM 7 |
| `spring-boot-starter-security` | Core Spring Security (filter chains, CSRF, headers) |
| `spring-boot-starter-oauth2-client` | OIDC login flow against Keycloak |
| `spring-boot-starter-oauth2-resource-server` | Bearer-JWT validation on `/api/**` and `/ws/**` |
| `spring-boot-starter-validation` | Hibernate Validator 9 for `@Valid` request bodies |
| `spring-boot-starter-actuator` | Health + metrics endpoints |
| `spring-boot-flyway` + `flyway-core` + `flyway-database-postgresql` | Schema migrations (`db/migration/V*.sql`) |

### Auxiliary Spring pieces
- `spring-security-messaging` — STOMP-frame authorization (`StompAuthorizationConfig`)
- `thymeleaf-extras-springsecurity6` — `sec:authorize` attributes in templates

### Storage
- **Hibernate ORM 7.2** (transitive via Spring Data JPA), `ddl-auto=validate`
- **PostgreSQL JDBC 42.7** (runtime only)
- **HikariCP** connection pool (Spring Boot default)
- **Flyway 11** migrations

### Domain libraries
- **CommonMark 0.22** + GFM-tables and autolink extensions — server-side Markdown rendering (`MarkdownRenderer`)
- **jsoup 1.18** — HTML sanitization with a tightened `Safelist.basic` after Markdown render
- **Apache Lucene 10.4** (`core`, `analysis-common`, `queryparser`) — embedded full-text index at `./data/lucene` (`MessageIndexService`); writes are flushed after the surrounding JPA transaction commits. No Postgres `tsvector`, no ILIKE.
- **Apache Commons FileUpload 2.0** (`jakarta-servlet6` variant) — streaming multipart parser used for avatar / attachment uploads, bypasses Spring's buffering `MultipartResolver`

### Frontend
- Thymeleaf templates + hand-written vanilla JS (no React/Vue/Svelte, no npm bundler)
- Vendored under `static/js/vendor/`: **StompJS** (WebSocket STOMP client) and **highlight.js** (code-block syntax highlighting)
- STOMP rides on **native WebSocket only** — no SockJS fallback (its inline-script transports break the strict CSP)

### Test stack
- **JUnit 5** (Jupiter) via `spring-boot-starter-test`
- **AssertJ**, **Mockito** (transitive)
- `spring-security-test` for security helpers
- **Testcontainers BOM 1.20.4** (`postgresql` + `junit-jupiter`) — real Postgres 17 per IT class (no H2)
- `spring-boot-testcontainers` for `@ServiceConnection` wiring

### Code generation
- **Lombok 1.18.38** — `@Getter` / `@Setter` / `@NoArgsConstructor(access = PROTECTED)` on JPA entities only. DTOs stay as records (records cover the immutable-data-class case the language already gives us). Side-effecting setters (`setBodyMarkdown` bumps `editedAt`, `setAvatar` bumps `avatarUpdatedAt`, `pin/unpin`, `markRead`) are written by hand — Lombok skips generation when a same-signature method already exists.

### Notably absent (intentional)
- No MapStruct (manual `from(...)` factory methods on DTOs instead — keeps the mapping visible)
- No reactive stack (no WebFlux, R2DBC)
- No Spring Cloud, no external message broker beyond the in-memory STOMP broker
- No H2 / HSQLDB — production schema uses features H2 won't accept

## Architecture

### Deployment overview

One JVM, three external dependencies (Keycloak, Postgres, a local data directory). Lucene is embedded; STOMP runs against Spring's in-memory `SimpleBrokerMessageHandler`, no Redis or RabbitMQ.

```mermaid
flowchart LR
  Browser["Browser<br/>Thymeleaf pages +<br/>vanilla JS + STOMP"]
  App["Spring Boot 4 app<br/>Java 25 · Tomcat 11<br/>virtual threads"]
  KC["Keycloak 26<br/>OIDC issuer"]
  PG[("PostgreSQL 17")]
  Lx[("Lucene index<br/>./data/lucene")]
  Fs[("Local files<br/>./data/{avatars,attachments,branding}")]

  Browser <-- "HTTPS · WebSocket" --> App
  Browser <-- "OIDC login redirect" --> KC
  App <-- "JWT validation" --> KC
  App <-- "JDBC + HikariCP" --> PG
  App <-- "embedded R/W" --> Lx
  App <-- "filesystem" --> Fs
```

### Backend request lifecycle

What happens when the browser sends a chat message over STOMP:

```mermaid
sequenceDiagram
  autonumber
  participant B as Browser
  participant SP as Spring Security<br/>(apiFilterChain)
  participant SA as StompAuthorization<br/>Config
  participant C as ChatWebSocket<br/>Controller
  participant M as MessageService
  participant DB as Postgres
  participant L as Lucene
  participant BR as SimpMessaging<br/>Broker

  B->>SP: STOMP SEND /app/channels/{id}/send
  SP->>SA: bearer JWT validated
  SA->>C: frame allowed (membership check)
  C->>M: post(channel, user, body)
  M->>DB: INSERT message + mention rows
  M->>L: index after Tx commit
  C->>BR: convertAndSend /topic/channels/{id}
  BR-->>B: MessageEvent fan-out to subscribers
```

Two `SecurityFilterChain` beans split the auth posture: `apiFilterChain` (order 1) handles `/api/**` and `/ws/**` with stateless bearer JWT (no CSRF). `webFilterChain` (order 2) handles browser page loads with stateful OIDC session and CSRF cookies. `StompAuthorizationConfig` is a separate `ChannelInterceptor` that enforces channel/conversation membership on every `SUBSCRIBE` frame.

### Frontend module composition

Each page is a Thymeleaf template that pulls in a single page-specific entry-point JS file (wrapped in an IIFE — nothing leaks to `window`). Shared modules are loaded only by the pages that need them:

```mermaid
flowchart TB
  subgraph SharedModules["Shared modules"]
    HC[hovercard.js]
    NT[notifications.js]
    TL[theme-loader.js]
    HL[highlight.min.js]
    ST[stomp.umd.min.js]
  end

  subgraph Pages["Page templates"]
    Ch[channels.html]
    Cv[conversation.html]
    P[profile.html]
  end

  subgraph PageScripts["Page entry points"]
    CJ[chat.js]
    CvJ[conversation.js]
    PJ[profile.js]
  end

  Ch --> ST & HL & TL & HC & NT & CJ
  Cv --> ST & HC & CvJ
  P --> PJ
```

Cross-module calls go through tiny `window.*` surfaces only where needed (e.g. `window.MentionNotifications.show(...)` so `chat.js` can fire a toast without importing `notifications.js` directly).

## Run locally

### With Podman compose (recommended)

The container runtime here is **Podman** (Docker is not installed in our dev env). The Quick start above covers the happy path; here are the details if you need them.

```bash
systemctl --user enable --now podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock

podman compose up -d            # or `podman-compose up -d`
./gradlew bootRun
```

`podman compose up -d` reads `docker-compose.yml`, starts a `postgres:17-alpine` container with the `chat`/`chat`/`chat` (db/user/password) defaults, and a `keycloak:26.0` container that imports `keycloak/realm.json` on first start.

If `gradlew` is missing (fresh checkout into a tree where the wrapper isn't committed), run once: `gradle wrapper --gradle-version 9.0.0`.

### Without containers (native install)

If you'd rather run Postgres and Keycloak directly on the host (production deploy, air-gapped server, no container runtime), here's the path.

#### PostgreSQL 17

**RHEL / Fedora / Rocky:**
```bash
sudo dnf install -y postgresql17-server postgresql17
sudo /usr/pgsql-17/bin/postgresql-17-setup initdb
sudo systemctl enable --now postgresql-17
```

**Debian / Ubuntu:**
```bash
sudo apt install -y postgresql-17
sudo systemctl enable --now postgresql
```

**macOS (Homebrew):**
```bash
brew install postgresql@17
brew services start postgresql@17
```

Create the database and role:

```bash
sudo -u postgres psql <<'SQL'
CREATE USER chat WITH PASSWORD 'chat';
CREATE DATABASE chat OWNER chat;
GRANT ALL PRIVILEGES ON DATABASE chat TO chat;
SQL
```

That's all the SQL setup you need. Flyway runs the schema migrations on first app start (V1 → V13).

#### Keycloak 26

Keycloak needs Java 21+ — your Java 25 install is fine. Download a release and run it directly:

```bash
KC_VERSION=26.0.5
curl -L https://github.com/keycloak/keycloak/releases/download/${KC_VERSION}/keycloak-${KC_VERSION}.tar.gz | tar -xz
cd keycloak-${KC_VERSION}

# Drop the realm definition where Keycloak picks it up on import:
mkdir -p data/import
cp /path/to/this-repo/keycloak/realm.json data/import/

# Initial admin (set once before first start):
export KC_BOOTSTRAP_ADMIN_USERNAME=admin
export KC_BOOTSTRAP_ADMIN_PASSWORD=admin

bin/kc.sh start-dev --import-realm --http-port=8081
```

`start-dev` runs against an embedded H2 — fine for a quick local run. For production, switch to `bin/kc.sh start` and configure a Postgres backend (a separate database from the chat one) per Keycloak's docs.

Once Keycloak is up at http://localhost:8081 the `chat` realm exists with users `alice` / `alice` and `bob` / `bob`. Point the chat app at it:

```bash
export KEYCLOAK_ISSUER_URI=http://localhost:8081/realms/chat
export KEYCLOAK_CLIENT_SECRET=<value from realm.json or a fresh one you rotated to>
./gradlew bootRun
```

## Keycloak realm

The bundled `keycloak/realm.json` defines everything `podman compose` and `kc.sh start-dev --import-realm` pick up. If you build a realm from scratch in the admin UI, match the settings below.

### Realm + client

| Item | Value |
|---|---|
| Realm name | `chat` |
| Login with email | enabled |
| Self-registration | enabled |
| Client id | `chat` (confidential, Authorization Code + PKCE) |
| Client secret | `chat-secret` (override via `KEYCLOAK_CLIENT_SECRET` in production) |
| Valid redirect URIs | `http://localhost:8080/*`, `http://192.168.100.98:8080/*` |
| Web origins | `http://localhost:8080`, `http://192.168.100.98:8080` |

If you move the app to a different host or port, update both Valid redirect URIs and Web Origins to match. A mismatch shows up as `400 invalid_redirect_uri` from Keycloak after sign-in.

### Roles

Three realm roles ship in the bundled config:

| Role | Purpose | Granted to |
|---|---|---|
| `user` | Marker assigned to every regular account. Not consumed by the chat app itself; handy for filtering in Keycloak. | alice, bob; assign as default to self-registered accounts |
| `admin` | Keycloak's built-in realm admin. **Intentionally ignored** by the chat app. | (Keycloak internal) |
| `chat-admin` | Application admin. Required for `/admin` and cross-channel search. Maps to Spring's `ROLE_ADMIN` in `KeycloakRolesConverter`. | alice (in the bundled realm) |

The split is deliberate: the person who admins your Keycloak instance is not automatically a chat administrator. Promote individual users to `chat-admin` via **Users → pick user → Role mappings → Assign role**.

### Enabling user registration

Self-registration is already on in the bundled realm. To toggle (or enable on a hand-built realm):

1. Open the Keycloak admin console at http://localhost:8081 (default `admin` / `admin`).
2. Switch to the **chat** realm in the top-left dropdown.
3. **Realm settings → Login** tab.
4. Toggle **User registration** on.
5. (Optional) **Forgot password** to expose a self-service reset link on the login page.
6. (Optional) **Verify email** to require confirmation before first login. Needs SMTP configured under **Realm settings → Email**.

Then make sure new self-registered accounts get the `user` realm role automatically:

1. **Realm settings → User registration** sub-tab (or **Realm roles → default-roles-chat**).
2. Assign realm role `user` (and any others you want every account to have).

`chat-admin` is deliberately **not** in the default role set and should never be — promote people one at a time, after you've vetted them.

## Configuration

Every override is plain Spring Boot env-var substitution against `application.yml` — no profiles, no Vault, no surprises.

| Variable | Default | Purpose |
|---|---|---|
| `CHAT_DB_URL` | `jdbc:postgresql://localhost:5432/chat` | JDBC URL for the Postgres instance |
| `CHAT_DB_USERNAME` | `chat` | Postgres user |
| `CHAT_DB_PASSWORD` | `chat` | Postgres password — **set this in production** |
| `KEYCLOAK_ISSUER_URI` | `http://192.168.100.98:8081/realms/chat` | Keycloak realm issuer (used by both OIDC client and resource server) |
| `KEYCLOAK_CLIENT_ID` | `chat` | OIDC client id |
| `KEYCLOAK_CLIENT_SECRET` | `chat-secret` | OIDC client secret — **set this in production** |
| `SERVER_PORT` | `8080` | HTTP port the Boot app binds to |
| `SERVER_ADDRESS` | `192.168.100.98` | Network interface to bind (`0.0.0.0` to listen on all) |
| `CHAT_ATTACHMENTS_DIR` | `./data/attachments` | Where uploaded message attachments are stored |
| `CHAT_AVATARS_DIR` | `./data/avatars` | Where uploaded avatars are stored |
| `CHAT_BRANDING_DIR` | `./data/branding` | Where the admin-uploaded logo is stored |
| `CHAT_SECURITY_COOKIE_SECURE` | `false` | Mark JSESSIONID + CSRF cookies `Secure`. Set to `true` when serving over HTTPS. |

The Lucene index lives at `./data/lucene` (override with `chat.search.lucene-dir`). Back up the whole `./data/` directory plus the Postgres database and you have everything: messages, attachments, avatars, branding, and the search index.

### Upload size cap

Default cap is **50 MiB per upload**. The cap applies per user; admins (anyone with the `chat-admin` realm role) get unlimited.

To grant a non-admin a higher (or lower) cap:

1. Open the Keycloak admin console → **chat** realm → **Users** → pick the user.
2. **Attributes** tab → add an attribute named `chat_max_upload_bytes` with a positive byte count (e.g., `524288000` for 500 MiB), or `-1` for unlimited.
3. Save. The user's next login picks up the new value via the JWT claim mapper that ships in `keycloak/realm.json`.

Avatars have a separate, hard 5 MiB cap (they're decoded into memory for resize, so the cap is structural rather than configurable). Both endpoints stream chunk-by-chunk via Apache Commons FileUpload so the bytes are never fully buffered.

Server-side errors are returned as `413 Payload Too Large` with `{ code: "upload_too_large", maxBytes: <bytes> }`; the JS upload UX in `chat.js` / `conversation.js` / `profile.js` renders that as "File too large — your account is capped at N MiB per upload."

### Session timeout

Inactive users are signed out after **8 hours** of no input by default. The browser watches for `mousemove` / `keydown` / `scroll` / `touchstart` / `focus` events and fires `POST /logout` once the threshold is hit; Spring's HTTP session and Keycloak's SSO session both expire on the same 8h schedule, so a tab left open quietly drops on every layer.

The authoritative knob is **Keycloak**, not the chat app. To change the timeout:

1. Open the Keycloak admin console → **chat** realm → **Realm settings → Sessions** tab.
2. Set **SSO Session Idle** (and optionally **SSO Session Max** for a hard ceiling) to your preferred duration.
3. Bump `server.servlet.session.timeout` in `application.yml` (or set `SERVER_SERVLET_SESSION_TIMEOUT` as an env var) to match — otherwise Spring's web session may expire before Keycloak's.
4. Update the `IDLE_TIMEOUT_MS` constant at the top of `static/js/idle-logout.js` if you want the proactive client-side logout to align too.

For production, also: terminate TLS in front of the JVM (Caddy / Nginx / a managed LB), front Keycloak with TLS too, and review `security_plan.md` for the full hardening checklist.

### Admin email visibility

The admin console at `/admin` lists every user in the workspace, and by default that list **shows full email addresses**. This matters because:

- Most small teams use the column to find a colleague's email — it's useful and the same admins can look it up directly in Keycloak anyway.
- A screenshot of the admin page, or a stolen browser session, exposes every user's email at once. For a workspace under privacy-sensitive policies (HIPAA, GDPR-strict, customer-facing forums), that's a leak.

There's a per-deployment toggle for this. **Default: on (raw emails visible)** to preserve the existing behaviour for installs upgrading from before the toggle existed. To flip it off:

1. Sign in as an admin (Keycloak `chat-admin` realm role) and open `/admin`.
2. Find the **Privacy** section (right above the Users table).
3. Uncheck **Show full emails on this page** and click **Save privacy setting**.

When off, each row is rendered server-side as `al…@example.com` (first two letters of the local part, then `…`, then the full domain). The DB still stores the raw value — only the rendering is masked, so flipping the toggle back on doesn't lose anything.

The setting persists in `app_settings.expose_user_emails` (V20 migration). It applies only to the admin page; mention rendering, hovercards, and the user profile API don't expose email at all (and never have).

### Production hardening checklist

The bundled defaults are tuned for local development. **Before exposing this to the internet**, work through this list. Each item is something an attacker (or curious user) will probe within minutes of finding the host.

| | What | Why |
|---|---|---|
| ☐ | Rotate `KEYCLOAK_CLIENT_SECRET` to a freshly-generated value (Keycloak admin console → **Clients → chat → Credentials → Regenerate**) | The bundled `chat-secret` is in this public repo; anyone can read it. |
| ☐ | Restrict the `chat` client's **Valid redirect URIs** + **Web origins** to your real hostname; drop `localhost` and the dev IP | OIDC redirect-URI matching is your defence against open-redirect token theft. |
| ☐ | Set `CHAT_SECURITY_COOKIE_SECURE=true` | Marks JSESSIONID + CSRF cookies `Secure`; without it MITM can lift them over plain HTTP. |
| ☐ | Bind the JVM to localhost (`SERVER_ADDRESS=127.0.0.1`) and front it with nginx (see `nginx_example.conf`) | Centralises TLS termination, real client IP forwarding, and HSTS. |
| ☐ | Set `KC_BOOTSTRAP_ADMIN_PASSWORD` on Keycloak to something other than `admin` | The Keycloak admin console is the master key to every account in your realm. |
| ☐ | Enable **Verify email** in Keycloak (Realm settings → Login) before opening self-registration | With registration on and no email check, bots will mass-register. |
| ☐ | Configure SMTP under Realm settings → Email so password reset and verification actually work | Otherwise you're locking yourself out of your own deploy. |
| ☐ | Tighten Keycloak's `ssoSessionIdleTimeout` to whatever your security posture demands (default 8h) | Long idle sessions amplify any one stolen cookie. |
| ☐ | Replace the in-memory `RateLimiter` with a distributed limiter (Bucket4j+Hazelcast or Redis) before scaling out | Per-process limits don't compose across N replicas. |
| ☐ | Set `client_max_body_size` in nginx if you want a hard ceiling at the edge (the app's per-user 50 MiB cap is enforced internally) | Stops a misconfigured user-attribute setting an unintentionally huge cap. |
| ☐ | Take regular Postgres + `./data/` backups; verify restores work | The whole product fits in `pg_dump` + that directory. |
| ☐ | Enable a CVE scanner in CI (OWASP `dependency-check` Gradle plugin, GitHub Dependabot, etc.) | Hibernate / Tomcat / Jackson all ship CVEs over the lifetime of any deploy. |

The companion `security_plan.md` has the full per-finding rationale plus historical context. Items that have moved from "open" to "addressed" are noted in `SecurityBoundaryIT` and `InternetExposureSecurityIT`.

## Tests

The suite is **190+ tests across 21 classes** — about 30 unit tests that run anywhere, and ~160 integration tests that need a Postgres container. Both layers run from a single `./gradlew test`.

### Run everything

```bash
# One-time: expose the Podman user socket so Testcontainers can find a Docker-compatible API
systemctl --user enable --now podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock

./gradlew test                    # full suite (unit + integration)
```

The first run pulls `postgres:17-alpine` (~80 MB); subsequent runs reuse the cached image and finish in 1–2 minutes on a laptop. Reports land at `build/reports/tests/test/index.html` (HTML) and `build/test-results/test/TEST-*.xml` (JUnit XML for CI).

### Run a subset

```bash
# Unit tests only — no Docker needed, finishes in seconds
./gradlew test --tests 'com.example.chat.service.*' --tests 'com.example.chat.security.*'

# A single integration test class
./gradlew test --tests 'com.example.chat.integration.HovercardAndDmFlowIT'

# A single test method
./gradlew test --tests 'com.example.chat.integration.SearchFlowIT.fuzzyMatch_*'

# Force a rerun even if Gradle thinks nothing changed
./gradlew test --rerun-tasks

# Stop background daemons if the cached env is stale (e.g. after changing DOCKER_HOST)
./gradlew --stop
```

### Test layers

- **Unit tests** under `src/test/java/.../service/` and `.../security/` (`MarkdownRendererTest`, `ChannelServiceUnitTest`, `SearchServiceUnitTest`, `KeycloakRolesConverterTest`) cover pure-logic branches: Markdown rendering + sanitization, channel slug rules, search input validation, role conversion. They run anywhere — no Docker.
- **Integration tests** under `src/test/java/.../integration/` boot a slimmed Spring context (`IntegrationTestApplication` excludes security / OAuth2 / web autoconfigs and only scans `service` + `repository` + `search`) against a **Testcontainers Postgres**, run Flyway, and exercise the service layer end-to-end. Each IT class registers a unique `chat.search.lucene-dir` via `TestLuceneDirs.register(...)` so concurrent / cached Spring contexts don't fight over the Lucene index lock.
- **Controller-shaped ITs** (`AvatarBroadcastIT`, `HovercardAndDmFlowIT`, `MentionBroadcastIT`, `InternetExposureSecurityIT`) construct a controller manually with mocked `CurrentUser` / `SimpMessagingTemplate` so the broadcast wiring + per-endpoint guards can be asserted without spinning up a full web layer.
- **Security boundary ITs** (`SecurityBoundaryIT`, `InternetExposureSecurityIT`) pin the auth/authz invariants so they can't regress silently — see `security_plan.md` for the full rationale.

### Constraints worth knowing

- **No H2 fallback.** Hibernate runs in `validate` mode against the production schema; H2 won't accept some of the column types the production schema uses.
- **Per-class Postgres container.** Each `@Container` annotation spins up a fresh database. Running the whole suite uses ~21 short-lived containers (a few hundred MiB transient).
- **Lucene per-context dir.** If a test prints "Failed to open Lucene index at …", clear `build/test-lucene/` and re-run — it's a stale lock from a previous run, not a real failure.
- **Stale daemon env.** Gradle's daemon caches `DOCKER_HOST` from when it started; if you change the export and re-run, follow with `./gradlew --stop` so the next invocation picks up the new value.

## Layout

```
src/main/java/com/example/chat/
├── ChatApplication.java
├── config/        # SecurityConfig (two filter chains), WebSocketConfig, StompAuthorizationConfig, MultipartConfig
├── domain/        # JPA entities (User, Channel, Message, Conversation, Attachment, Reaction, Mention, ...)
├── repository/    # Spring Data JPA repos
├── search/        # Embedded Lucene index service + bootstrap rebuild
├── service/       # ChannelService, MessageService, ConversationService, AvatarService, AttachmentService,
│                  # ReactionService, ReadStateService, MentionService, MarkdownRenderer, SidebarService, ...
├── security/      # CurrentUser, KeycloakRolesConverter, RateLimiter, RateLimitExceededException
└── web/           # REST + MVC controllers, STOMP controllers, ApiExceptionHandler, dto/

src/main/resources/
├── application.yml
├── db/migration/V1__…V13__…sql        # Flyway migrations (channels → DMs → attachments → reactions → ...)
├── templates/                          # landing, channels, conversation, profile, admin
└── static/
    ├── css/app.css
    └── js/                             # chat, conversation, hovercard, notifications, profile,
                                        # theme-loader, emoji-data + vendor/{stomp,highlight}
```

## Roadmap (still open)

- Highlighted snippets in search results.
- Permission UI for promoting/demoting channel admins (the service supports it; no UI wired yet).
- E2E test with a real Keycloak (Testcontainers Keycloak module).
- Distributed rate limiting (`RateLimiter` is per-process; replace with Bucket4j-with-Hazelcast or Redis before going multi-instance).
- OWASP `dependency-check` Gradle plugin for CVE scanning.
- Broader MIME sniffing on upload — currently `URLConnection.guessContentTypeFromStream`; swap in Apache Tika for wider coverage.

## License

Apache License 2.0. See [`LICENSE`](LICENSE) for the full text. Every source file carries the standard Apache header — fork it, run it, change it, ship it. The licence cannot be retroactively narrowed; that's the point.
