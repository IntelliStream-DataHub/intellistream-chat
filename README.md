# IntelliStream Chat — self-hosted team chat, built to last

Slack/Mattermost-style workspace chat: channels, threads, direct and group messages, reactions,
mentions, presence, polls, slash commands, full-text search, streamed file uploads and OIDC single
sign-on. One JVM process, one Postgres database, one systemd unit.

**[intellistream-datahub.github.io/intellistream-chat](https://intellistream-datahub.github.io/intellistream-chat/)** — screenshots, feature tour and the full manual.

<a href="https://intellistream-datahub.github.io/intellistream-chat/">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/shots/hero-dark.webp">
    <!-- width only, no height: GitHub's markdown CSS caps images at max-width:100% without
         setting height:auto, so a height attribute stays pinned while the width shrinks to the
         ~880px README column — which renders this 1200x733 shot 195px too tall. -->
    <img src="docs/shots/hero-light.webp" alt="A channel in IntelliStream Chat: the sidebar listing channels and direct messages, a conversation with Markdown, a code block and a poll, and the message composer below." width="1200">
  </picture>
</a>

Built on Java 25, Spring Boot 4, PostgreSQL 18, Keycloak and embedded Apache Lucene. A stack chosen
for how well it ages, not for how new it is.

## Quick start

Four commands to a running workspace on your own machine.

```bash
# 1. Clone
git clone https://github.com/IntelliStream-DataHub/intellistream-chat.git
cd intellistream-chat

# 2. Install Java 25 and Podman
#    Fedora / RHEL / AlmaLinux
sudo dnf install -y java-25-openjdk-devel podman podman-compose
#    Ubuntu / Debian
sudo apt install -y openjdk-25-jdk podman podman-compose

# 3. Start Postgres 18 and Keycloak 26
podman compose up -d

# 4. Run it
./gradlew bootRun
```

Open <http://localhost:8080> and sign in as `alice` / `alice`. The Keycloak admin console is on
<http://localhost:8081> with `admin` / `admin`. First `podman compose up` takes 15 to 30 seconds
while Keycloak imports the `ichat-realm` realm and its two test users, `alice` and `bob`.

Docker works too if you already have it; the compose file is plain OCI.

**Deploying to a server rather than trying it out?** That is a different job, and it has its own
guides: [`QUICKSTART-MANUAL.md`](QUICKSTART-MANUAL.md) for PostgreSQL and Keycloak on the host plus
the installer script and the hardened systemd unit, [`QUICKSTART-COMPOSE.md`](QUICKSTART-COMPOSE.md)
for containers all the way down, and [`frontend.md`](frontend.md) for the reverse proxy and TLS.

### It fits on a very small machine

Measured, not estimated: the whole application boots and serves inside a hard
`MemoryMax=900M` / `CPUQuota=100%` cgroup, peaking at **490 MB** with `-Xmx320m` while posting,
threading and searching. One core, well under a gigabyte, and no second service to run because the
search index is embedded and the message broker is in-process.

That is enough for a workspace of around a thousand people. The arithmetic is the measured
per-connection cost from [`scalability.md`](scalability.md): 82 KB per WebSocket connection, so a
thousand people connected at once is roughly 82 MB on top of the base footprint. The message rate
is not the constraint either, a thousand-person workspace produces a handful of messages a second
and one core handles far more than that. Memory is what you size for, and a 1 GB VM has room.

## Why this exists

Workplace chat is important infrastructure. We should stop handing the keys to a vendor whose
interests do not include making sure you can still read your own conversations next year. The
ability to self-host isn't a feature; it's a right.

**Slack** is mostly good. The UI is slow, and the product is proprietary, cloud-only, and your
archive is governed by the vendor's pricing tiers and retention rules. The cost-per-seat and the
visibility horizon are theirs to set. That's a workable trade for plenty of teams. It isn't workable
for regulated industries, security-conscious organisations, or anyone who'd rather not have their
internal knowledge graph held off-premises.

**Mattermost** sold itself as the open-source Slack alternative, and for a while it was. Then the
free edition started taking things back. SAML and OAuth2 logins are paywalled. Team message history
is capped at 10,000 messages. You can still self-host the binary, but the open-core playbook is at
work: the things that separate a real chat app from a demo keep migrating into the licence you have
to pay for. "Open source" stops meaning much when the table stakes aren't. The UI is genuinely slick
and responsive, which makes the direction more of a shame.

**Microsoft Teams** is the most frustrating of the bunch, I am regularly late to meetings because 
the client wants to update and restart on launch, and a UI that can freeze for seconds at a time.

What this is instead: a chat server you deploy on a box you control. Fast UI. No message cap. No SSO
paywall. No telemetry. No vendor able to change the terms a year from now because the funding round
demanded it. It won't have Slack's polish or Mattermost's feature breadth. It will still be readable
in five years, on a server you own, running code you can audit, under a licence that cannot be
retroactively narrowed.

## The architecture, and why each piece was chosen

The whole system is one JVM process and one database. There is no message broker to operate, no
search cluster, no Redis, no sidecar, no npm build. That is the central design decision and
everything else follows from it.

| Layer | Choice | Why                                                                                                                                                                                               |
|---|---|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Language | **Java 25** | Virtual threads make a connection-per-user server cheap without async plumbing. A language with a 30-year compatibility record and tooling that will still work in a decade.                      |
| Framework | **Spring Boot 4** | The most thoroughly documented server framework in existence. Any problem you hit has been hit before, in public, with an answer.                                                                 |
| Storage | **PostgreSQL 18** | One database for everything. Schema changes go through Flyway migrations with `ddl-auto=validate`, so the schema is always exactly what the code expects.                                         |
| Search | **Embedded Apache Lucene** | Real full-text search — the same engine under Elasticsearch — with no second service to run, monitor or keep in sync. The index lives on local disk and rebuilds itself from Postgres on startup. |
| Identity | **Keycloak (OIDC)** | Authentication is a solved problem owned by people who specialise in it. This codebase contains no password hashing, no session store, no reset flow — deliberately.                              |
| Realtime | **STOMP over native WebSocket** | An in-process broker that does over 100,000 deliveries/second. No Apache Pulsar until you actually need multiple nodes.                                                                           |
| Frontend | **Thymeleaf + vanilla JS** | Server-rendered HTML and hand-written ES modules, bundled at build time by Closure Compiler. No npm dependency tree, no framework migration every three years, no supply-chain surface.           |

Every one of those is replaceable behind an existing seam — see
[What's intentionally under-engineered](#whats-intentionally-under-engineered-so-a-fork-can-swap-it).

## Performance

Measured on one Broadwell 12-core / 31 GB virtual machine with the load generator running 
**on the same box**, so these are floors rather than ceilings. Full method, raw results and 
analysis in [`scalability.md`](scalability.md); the harness is in [`benchmark/`](benchmark/).

| | |
|---|---|
| Messages persisted + delivered | **17,066 / second**, p50 21.6 ms end-to-end, 0 dropped |
| Fan-out into 50-member rooms | **136,043 deliveries / second**, 0 dropped |
| Concurrent connections served | **100,000**, 47,484 deliveries/s, 0 dropped, p50 792 ms |
| Memory holding 100,000 connections | **11.2 GiB** RSS, whole JVM |
| Attachment upload | **~380 MB/s** (~3 Gbps) single stream |

Two design decisions behind those numbers are worth knowing before you fork:

- **The write path is batched, and broadcast waits for the commit.** `MessageWriteBehind`
  pre-allocates message ids and inserts rows in batches; a message is broadcast and indexed only
  *after* its batch commits, so nobody is ever shown a message that then failed to persist. Queues
  are sharded by channel, so per-channel ordering holds. The sender doesn't wait for any of it — the
  composer renders optimistically and reconciles on the broadcast.
- **Uploads are raw request bodies, not multipart.** Multipart's boundary scan caps throughput well
  below line rate; the file is streamed straight to disk and the metadata rides in headers.

## Built to be maintained

Performance is easy to demonstrate and hard to keep. What makes that possible here is that the
codebase is small, conventional and covered:

- **975 tests across 101 classes** (47 integration, 54 unit), running in about six minutes.
  Integration tests run against a real PostgreSQL via Testcontainers — never H2, which silently
  accepts SQL that Postgres rejects.
- **Conventions are written down.** [`AGENT.md`](AGENT.md) documents the decisions you cannot infer
  from the code: the two security filter chains, `requireMember` vs `requireWriteAccess`, why
  broadcast happens after commit, why there is no SockJS. Read it before your first change.
- **Security posture is explicit.** A strict CSP with no inline script, two separate filter chains,
  STOMP `SUBSCRIBE` authorisation, server-side Markdown rendering sanitised with jsoup, and a
  hardened systemd unit that scores 4.6 OK on `systemd-analyze security`. The open items are listed
  honestly in [`security_plan.md`](security_plan.md) and [`SECURITY.md`](SECURITY.md).
- **One artifact, one unit file.** `./gradlew assemble` produces a single runnable jar. Deployment is
  copying it and `systemctl restart`.

**Maturity:** 1.0, under active development. Tested and audited: 975 tests across 101 classes, the
integration suite runs against a real PostgreSQL, and the installer is verified end to end on
AlmaLinux 10.2 with SELinux enforcing. What it has not had is years of production exposure across
many deployments, so read the code before trusting it with anything sensitive, follow the hardening
checklist in [`SECURITY.md`](SECURITY.md) before exposing an instance, and keep backups.

## Use as a starting point

If you want a team-chat tool that doesn't quite match Slack or Mattermost — internal-only,
compliance-locked, embedded inside another product, an unusual channel taxonomy, a domain-specific
slash-command surface — this codebase is small enough to fork and shape rather than build from
scratch. A feature typically touches one service, one controller, one migration and one test class.

1. **Fork the repo and rename.** The name lives in several distinct slugs, on purpose — rename each
   deliberately rather than with one global search-and-replace:

   | Slug | Used for |
   |---|---|
   | `ai.intellistream.chat` | Java package |
   | `ichat` | config property prefix (`ichat.search.lucene-dir`, …) |
   | `ICHAT_` | environment variables (`ICHAT_DB_URL`, …) |
   | `ichat-realm` / `ichat-client` / `ichat-*` roles | Keycloak realm, OIDC client, realm roles |
   | `ichat_role` / `intellistream_chat` | Postgres role and database |
   | `intellistream-chat` | Gradle artifact, systemd unit, `/opt` path |

   Then regenerate `V1__init.sql`.
2. **Read `AGENT.md` and keep it current.** It is the conventions document for the project, and it
   is worth more to a new contributor than any amount of generated API documentation. Update it as
   your fork diverges.
3. **Write the change down before writing it.** Acceptance criteria beat prose: *"polls auto-close
   after 7 days; closed polls show the winner above the option list; admins can re-open a closed poll
   within 24 hours."*
4. **Follow the existing shape.** New slash command? Model it on `PollCommand`. New entity? Flyway
   migration plus a JPA entity plus a repository. New endpoint? Decide `requireMember` or
   `requireWriteAccess` first.
5. **Keep the suite green.** Add a unit test for pure logic and an integration test under
   `integration/` for anything database-shaped. The existing 975 tests are the floor, not the ceiling.

### What's intentionally under-engineered (so a fork can swap it)

These pieces are deliberately simple, behind a seam, so a fork can replace them without a rewrite:

| Today | Swap to, when |
|---|---|
| In-memory `RateLimiter` | Bucket4j-with-Hazelcast or Redis-backed (multi-replica deploy) |
| Embedded Lucene at `./data/lucene` | Elasticsearch / OpenSearch behind `MessageIndexService` (>10M messages or distributed search) |
| In-memory STOMP broker (`SimpleBrokerMessageHandler`) | RabbitMQ / ActiveMQ STOMP plugin (multi-replica WebSocket) |
| Local-disk attachments under `./data/attachments` | S3 SDK behind `AttachmentService` (cloud deploy / object storage) |
| Per-process slash-command registry | Plug-in loader (custom internal commands without a fork-of-the-fork) |

### Conventions to keep when extending

- **Two filter chains** in `SecurityConfig`. Browser pages and `/api/**` / `/ws/**` have very
  different auth postures (CSRF on/off, stateful/stateless). Merging them re-introduces classic
  CSRF-via-XHR bugs.
- **`CurrentUser` indirection.** Don't read JWT / `OidcUser` claims in controllers — go through
  `currentUser.resolve(principal)`. Provisioning the domain `User` row from the OIDC subject is the
  *only* place that should happen.
- **`requireMember` for read, `requireWriteAccess` for write.** PUBLIC channels are world-readable
  but never world-writeable; mix the two checks up and you've quietly broken that.
- **Strict CSP — no inline `<script>`, no SockJS.** Inline blocks and SockJS's `iframe` /
  `htmlfile` / `jsonp-polling` transports both require relaxing the CSP. Extract to `static/js/`.
- **`ddl-auto=validate` + Flyway.** Schema changes are migrations under `db/migration/V*.sql`, not
  `ddl-auto=update`. Don't flip the switch.
- **Testcontainers + real Postgres, no H2.** The schema uses Postgres-only features (`generated by
  default as identity`, partial indexes, named-constraint syntax). H2 silently accepts invalid SQL
  and lies to you.

If your fork ends up generally useful, send a PR back — generic improvements (distributed rate
limiter, S3 attachment backend, pluggable slash-command loader) are welcome upstream.

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

Ubuntu 24.04 LTS and later package `openjdk-25-jdk`, as does Debian 13. Only Debian 12 and Ubuntu releases older than 24.04 need [SDKMAN](https://sdkman.io) or Adoptium.

### macOS

```bash
brew install openjdk@25 podman
brew services start podman    # or: podman machine init && podman machine start
```

If `java` doesn't end up on `PATH`, follow the post-install instructions Homebrew prints (`echo 'export PATH="/opt/homebrew/opt/openjdk@25/bin:$PATH"' >> ~/.zshrc` on Apple silicon).

## Quick start — development (detail)

The short version is at the top of this file. What follows is the same flow with the optional paths: the prod profile, an external database, and Vault.


For exploring the app, hacking on it, or quick local testing. Two commands once the prerequisites above are installed:

```bash
podman compose up -d   # Postgres 18 + Keycloak 26, with the 'ichat-realm' realm pre-imported
./gradlew bootRun      # the Spring Boot app on :8080
```

Open http://localhost:8080 and sign in as `alice` / `alice` or `bob` / `bob`. Keycloak admin console is at http://localhost:8081 (`admin` / `admin`).

If `podman compose` can't find a socket, run once: `systemctl --user enable --now podman.socket && export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock`.

To boot with the production profile locally (for verification — the build wires `bootRun` to `--spring.profiles.active=dev` only when `SPRING_PROFILES_ACTIVE` is unset), keep the containers from above running, then:

```bash
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[] | select(.clientId=="ichat-client") | .secret' keycloak/realm.json)
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

`KEYCLOAK_CLIENT_SECRET` has no default (`application.yml` deliberately leaves it empty rather than falling back to the secret baked into `keycloak/realm.json`), so it must be set explicitly. The app **refuses to start** without it — an empty string is a valid property value, so previously the context came up, `/actuator/health` returned 200, and then every login died at the token exchange and bounced to `/login?error` with nothing in the log. See `OidcClientSecretCheck`.

### Quick start — without Podman (external Postgres + Keycloak)

Already running Postgres 18 and Keycloak 26 elsewhere (managed cloud, a host install, a shared dev environment)? Skip `podman compose` and point the app at them via env vars:

```bash
export ICHAT_DB_URL=jdbc:postgresql://db.example.com:5432/intellistream_chat
export ICHAT_DB_USERNAME=ichat_role
export ICHAT_DB_PASSWORD=...
export KEYCLOAK_ISSUER_URI=https://auth.example.com/realms/ichat-realm
export KEYCLOAK_CLIENT_SECRET=...
./gradlew bootRun
```

The Keycloak realm definition you'll need is in `keycloak/realm.json` — import it via the admin console (**Realms → Import**) or `bin/kcadm.sh create realms -f keycloak/realm.json`. Once imported, regenerate the client secret (the bundled one is in this public repo) and use the new value for `KEYCLOAK_CLIENT_SECRET`. Flyway runs the schema on first boot — no manual SQL setup beyond `CREATE DATABASE intellistream_chat OWNER ichat_role`. See [Without containers (native install)](#without-containers-native-install) for a step-by-step host install of both, and [Keycloak realm](#keycloak-realm) for the realm/client knobs.

To pull `ICHAT_DB_PASSWORD` and `KEYCLOAK_CLIENT_SECRET` from a Vault / OpenBao KV-v2 record instead of plain env vars:

```bash
export ICHAT_VAULT_ENABLED=true
export ICHAT_VAULT_URI=https://vault.example.com:8200
export ICHAT_VAULT_TOKEN=...
export ICHAT_VAULT_PATH=intellistream-chat     # default; maps to secret/data/intellistream-chat
./gradlew bootRun
```

The five expected keys (`db.username`, `db.password`, `keycloak.client-id`, `keycloak.client-secret`, `keycloak.issuer-uri`) and a try-it-locally OpenBao recipe are in [Optional: Vault / OpenBao secret backend](#optional-vault--openbao-secret-backend).

For container-free setup, see [Without containers (native install)](#without-containers-native-install) below.

## Quick start — production

For a real internet-facing deployment. **Do not skip the hardening steps**: the bundled defaults are tuned for local dev and would be embarrassing on the public internet.

```bash
# 1. Build a runnable jar
./gradlew assemble                # produces build/libs/intellistream-chat-<version>.jar

# 2. Stand up Postgres 18 + Keycloak 26 on the host (see "Without containers" below)
#    or your managed equivalents. Point the app at them via env vars.

# 3. Configure the production env. Each line below is required.
export ICHAT_DB_URL=jdbc:postgresql://db.internal:5432/intellistream_chat
export ICHAT_DB_USERNAME=ichat_role
export ICHAT_DB_PASSWORD=$(openssl rand -base64 32)
export KEYCLOAK_ISSUER_URI=https://auth.example.com/realms/ichat-realm
export KEYCLOAK_CLIENT_SECRET=$(openssl rand -base64 32)   # rotate from the dev default
export SERVER_ADDRESS=127.0.0.1                            # bind localhost only; nginx fronts it
# Cookie Secure flag auto-detects from X-Forwarded-Proto via forward-headers-strategy:
# framework (already set in application.yml), so no explicit ICHAT_SECURITY_COOKIE_SECURE
# is needed when nginx forwards X-Forwarded-Proto: https.

# 4. Run behind a TLS-terminating reverse proxy (see frontend.md in this repo):
java -jar build/libs/intellistream-chat-*.jar
```

Then complete the [production hardening checklist](#production-hardening-checklist) below before flipping DNS.

## Production: reverse proxy

[`frontend.md`](frontend.md) covers what to put in front of the JVM: complete nginx and
haproxy configs, how to size `worker_connections`/`maxconn` for a connection-heavy workload,
the upstream ephemeral-port limit that bites long before the app runs out of memory, and the
one deployment mistake that silently breaks login (Keycloak must share a registrable domain
with the app, because the session cookie is `SameSite=Strict`).

## Production: systemd + JVM tuning

The bare `java -jar …` line above gets you running once. For an actual deployment, run under systemd so the OS supervises the process, restarts it on crash, captures logs to `journald`, and applies basic sandboxing.

### systemd unit

Drop this at `/etc/systemd/system/intellistream-chat.service`:

Every directive is annotated below — read top to bottom and you'll see exactly what each line buys you. Tested as-is on AlmaLinux 10.2 with SELinux enforcing; `systemd-analyze security` reports an exposure score of **4.6 OK** with this configuration. `scripts/install-almalinux.sh` writes exactly this unit, so the documented one and the installed one cannot drift.

```ini
[Unit]
Description=IntelliStream Chat
Wants=network-online.target
After=network-online.target postgresql.service

[Service]
Type=simple
User=intellistream-chat
Group=intellistream-chat
WorkingDirectory=/opt/intellistream-chat
EnvironmentFile=/etc/intellistream-chat/env
# New files default to mode 0750/0640 — no "other" read.
UMask=0027

ExecStart=/usr/lib/jvm/java-25-openjdk/bin/java $JAVA_OPTS -jar /opt/intellistream-chat/intellistream-chat.jar

Restart=on-failure
RestartSec=5s
TimeoutStopSec=30s
KillSignal=SIGTERM

# === Process-level sandbox ============================================
# Block setuid/setgid binaries from elevating privilege if the JVM ever exec's one.
NoNewPrivileges=true
# Block creation of new namespaces (CLONE_NEWUSER / NEWNET / NEWNS …). The JVM doesn't need them.
RestrictNamespaces=true
# Block personality(2) — defence against syscall-table tricks that flip x86_64 to 32-bit.
LockPersonality=true
# Only allow native-arch syscalls. Same idea: no "compat" path for an attacker to ride.
SystemCallArchitectures=native
# Block creation of files with the setuid/setgid bit set.
RestrictSUIDSGID=true
# MemoryDenyWriteExecute is intentionally NOT set — the JIT needs writable + executable
# pages, and the JVM won't start with it on.

# === Filesystem isolation =============================================
# Whole filesystem read-only EXCEPT what ReadWritePaths= explicitly opens up.
# Important caveat: this only blocks WRITES. Reads of world-readable files
# elsewhere on the host are still possible — the InaccessiblePaths= block
# below closes the read leaks that matter.
ProtectSystem=strict
# The only writable location: attachments, avatars, lucene index, heap dumps.
ReadWritePaths=/opt/intellistream-chat/data
# /home, /root, /run/user/* become inaccessible (mounted over with empty bind).
ProtectHome=true
# Service gets a private /tmp and /var/tmp. Can't see other services' temp files,
# can't leave files behind that survive the unit.
PrivateTmp=true
# Minimal /dev — /dev/null, /dev/zero, /dev/random, /dev/urandom, /dev/tty.
# No /dev/mem, /dev/sda*, /dev/kmem.
PrivateDevices=true

# === Hide trees the JVM has no business reading =======================
# `open(2)` on any of these returns ENOENT to the service — they literally
# do not exist from the JVM's point of view. Without these directives,
# ProtectSystem=strict only stops writes; everything below is still readable.
# Verified on AlmaLinux 10.2: with this list, /etc/cron.d/*, /var/log/dnf.log,
# /var/log/messages and /var/lib/* are all GONE inside the namespace.
InaccessiblePaths=/var/log /var/spool /var/lib
InaccessiblePaths=/etc/cron.d /etc/cron.daily /etc/cron.hourly /etc/cron.weekly /etc/cron.monthly /etc/crontab /etc/anacrontab
InaccessiblePaths=/etc/sudoers /etc/sudoers.d
InaccessiblePaths=/etc/sssd /etc/pam.d /etc/security
InaccessiblePaths=/etc/rsyslog.d /etc/rsyslog.conf
InaccessiblePaths=/etc/ssh /etc/NetworkManager
# /etc/audit is intentionally NOT in this list — SELinux targeted policy on
# AlmaLinux 10 / RHEL 10 denies init_t the `mounton` permission for
# auditd_etc_t, so adding it makes the unit fail to start. The audit logs
# under /var/log/audit/ are mode 600 anyway, so DAC keeps them out of reach.

# === Kernel surface ===================================================
# Block writes to /proc/sys (sysctl) and most of /sys.
ProtectKernelTunables=true
# Block init_module / finit_module / delete_module — no module load/unload.
ProtectKernelModules=true
# /proc/kmsg and /dev/kmsg become inaccessible. The JVM has no use for the kernel ring buffer.
ProtectKernelLogs=true
# /sys/fs/cgroup is read-only. Service can't escape its own cgroup.
ProtectControlGroups=true
# Block settimeofday(), adjtimex() and friends.
ProtectClock=true
# Block sethostname() and setdomainname().
ProtectHostname=true
# Hide other processes' /proc entries; only this service's PIDs are visible.
ProtectProc=invisible
# /proc shows only PID directories — no /proc/scsi, /proc/sysrq-trigger, /proc/cmdline, …
ProcSubset=pid

# === Network ==========================================================
# Restrict socket(2) families to UNIX + IP. No raw, packet, netlink, bluetooth, can, …
# (The JVM never needs anything else — outbound to Postgres / Keycloak is plain TCP.)
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
# Deny SCHED_FIFO / SCHED_RR — the JVM has no use for realtime scheduling.
RestrictRealtime=true

[Install]
WantedBy=multi-user.target
```

The companion env file at `/etc/intellistream-chat/env` (chmod 600, owned by `intellistream-chat`):

```bash
# JVM tuning — see the table below for what each flag does.
JAVA_OPTS=-Xms1g -Xmx1g -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/intellistream-chat/data/heapdumps -XX:+UseStringDeduplication -XX:+AlwaysPreTouch -Duser.timezone=UTC

# App config (see "Quick start — production" for the full list)
ICHAT_DB_URL=jdbc:postgresql://db.internal:5432/intellistream_chat
ICHAT_DB_USERNAME=ichat_role
ICHAT_DB_PASSWORD=...
KEYCLOAK_ISSUER_URI=https://auth.example.com/realms/ichat-realm
KEYCLOAK_CLIENT_SECRET=...
SERVER_ADDRESS=127.0.0.1
# ICHAT_SECURITY_COOKIE_SECURE is no longer needed — cookies auto-mark Secure based on
# request.isSecure() (which RemoteIpValve sets from X-Forwarded-Proto). Override at the
# Servlet API level (server.servlet.session.cookie.secure=true) only if you want to force
# Secure even on non-forwarded requests — e.g. behind a proxy that doesn't set the header.
```

Bring it up:

```bash
sudo useradd --system --home /opt/intellistream-chat --shell /usr/sbin/nologin intellistream-chat
sudo install -d -o intellistream-chat -g intellistream-chat /opt/intellistream-chat /opt/intellistream-chat/data /opt/intellistream-chat/data/heapdumps
sudo install -d -o root -g intellistream-chat -m 750 /etc/intellistream-chat
sudo install -m 640 -o root -g intellistream-chat /path/to/env /etc/intellistream-chat/env
sudo install -m 644 build/libs/intellistream-chat-*.jar /opt/intellistream-chat/intellistream-chat.jar
sudo chown intellistream-chat:intellistream-chat /opt/intellistream-chat/intellistream-chat.jar
sudo systemctl daemon-reload
sudo systemctl enable --now intellistream-chat
sudo systemctl status intellistream-chat
sudo journalctl -u intellistream-chat -f
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

G1 is the right default at a 1 GiB heap (10–50 ms pauses, mature, well-understood). **ZGC** and **Shenandoah** trade ~15% RAM and ~10% throughput for sub-millisecond pauses; that's only a win once heap > ~4 GiB **and** GC pauses become user-visible. If you scale up later:

```bash
JAVA_OPTS=-XX:+UseZGC -Xms4g -Xmx4g -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/intellistream-chat/data/heapdumps -Duser.timezone=UTC
```

(Drop `+UseStringDeduplication` and `+AlwaysPreTouch` under ZGC — neither applies.)

### NUMA: keep the JVM on one node

**Check whether this applies to you before changing anything:**

```bash
lscpu | grep -i '^NUMA'
# NUMA node(s):  1     -> nothing below applies; stop here.
# NUMA node(s):  2     -> read on.
```

Most cloud instances present a single node whatever the host looks like underneath, so this is a
no-op on a typical Hetzner VM. It matters on a dual-socket box, or a single-socket EPYC configured
with NPS=2 or NPS=4, where memory is split into per-node pools and reaching the wrong one crosses
the interconnect.

On such a machine an unpinned JVM is free to run threads on node 1 while its heap sits on node 0.
Remote access has both higher latency and lower bandwidth than local, and nothing in the workload
makes up for it: this app's whole heap is 1 GiB, so it fits comfortably inside one node's memory,
and there is nothing to gain from spreading it.

**Use systemd's cgroup directives, not `numactl`.** As a drop-in, so an upgrade that rewrites the
unit cannot silently drop them:

```bash
sudo systemctl edit intellistream-chat
```

```ini
[Service]
# CPUs belonging to node 0 — read the real list, don't guess it:
#   cat /sys/devices/system/node/node0/cpulist
AllowedCPUs=0-15
AllowedMemoryNodes=0
```

Both are cgroup v2 `cpuset` settings and need systemd 244+ with the `cpuset` controller available
(`cat /sys/fs/cgroup/cgroup.controllers`). AlmaLinux 10 has both.

**Why not `numactl --cpunodebind=0 --membind=0` in `ExecStart`?** It works, and on the memory side
it is not meaningfully safer — `--membind` and `cpuset.mems` are both *hard* restrictions, so under
either one a full node means an allocation failure rather than a quiet spill to the neighbour. The
difference is in the failure modes around it:

- It puts a package in the boot path. If `numactl` is not installed, or is removed by an upgrade,
  `ExecStart` fails and the service does not come up. An unsupported systemd directive is logged
  and ignored — the service still starts, just unpinned.
- It couples NUMA pinning to your syscall policy. `numactl` calls `set_mempolicy`/`mbind`, and
  neither is in systemd's `@system-service` set (only `get_mempolicy` is). The unit above has no
  `SystemCallFilter=` today, so nothing breaks now — but adding one later would stop the service
  from starting, and the cause would not be obvious.
- The cgroup settings apply to the whole service cgroup and are introspectable after the fact:
  `systemctl show intellistream-chat -p AllowedCPUs -p AllowedMemoryNodes`.

The one thing `numactl` offers that the cgroup interface has no equivalent for is
`--preferred=0` — a *soft* bind that prefers node 0 and falls back to remote memory instead of
failing. If you would rather degrade than fail, that is the reason to reach for it.

For this app a hard bind is reasonable anyway, because `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` commits
and faults in the entire heap during startup. A node too small to hold it fails loudly at boot,
where you will see it, rather than hours later under load.

**Two things to get right when you pin:**

- **Size the node for more than the heap.** The cpuset also bounds metaspace, thread stacks, direct
  byte buffers, and the page cache backing Lucene's mmapped index. Budget well above `-Xmx`.
- **Don't pin Postgres to the same node by default.** If it shares the box, the two now compete for
  one node's cores and memory bandwidth while the rest of the machine idles. Either leave Postgres
  unpinned, or give it a different node and accept the loopback traffic crossing the interconnect.

Leave `-XX:+UseNUMA` **off** when pinned. It makes G1 partition the heap per node, which is what you
want for a JVM deliberately spanning nodes — and pointless work for one confined to a single node.

Verify after restarting:

```bash
systemctl show intellistream-chat -p AllowedCPUs -p AllowedMemoryNodes
# Once the directives are set, systemd enables the controller and the effective values show up at
# /sys/fs/cgroup/system.slice/intellistream-chat.service/cpuset.{cpus,mems}.effective

# Per-node allocation for the running JVM (needs the numactl package, for the tool only):
numastat -p "$(systemctl show -p MainPID --value intellistream-chat)"
```

In `numastat`, a healthy pinned process shows its pages concentrated on the bound node. Meaningful
residency on another node means the binding is not in effect.

### Verifying the namespace lockdown

After `systemctl restart intellistream-chat`, three quick checks:

```bash
# Exposure score (target: drops into "OK" range, 4.6 with the unit above).
sudo systemd-analyze security intellistream-chat.service

# From inside the service's mount namespace — these should be Permission denied / ENOENT.
sudo nsenter -t $(systemctl show -p MainPID --value intellistream-chat) -m ls /var/log /etc/cron.d

# No SELinux denials.
sudo ausearch -m AVC -ts recent
```

The textbook whitelist alternative (`TemporaryFileSystem=/etc:ro` + `BindReadOnlyPaths=`) **doesn't work on AlmaLinux 10 with SELinux enforcing** — the targeted policy denies `init_t` the `mounton` / `create` rights needed to materialise the bind-mount destinations. Making it work needs a custom policy module; the `InaccessiblePaths=` blacklist above runs with stock policy. If you need a true whitelist, write a custom SELinux domain (see the SELinux section below).

## Production: SELinux on AlmaLinux / RHEL

AlmaLinux 10 (and Rocky / RHEL 10) ships SELinux in **enforcing** mode by default. The systemd unit above runs the JVM in the `unconfined_service_t` domain, so the JIT (writable + executable pages) and the OIDC / JDBC outbound connections work without custom policy. What you do need to set up is **file labels on the data directory** and **one nginx boolean** so the reverse proxy can reach the JVM on `127.0.0.1:8080`.

```bash
# 0. Sanity check — should print "Enforcing".
getenforce

# 1. Make sure the policy management tools are installed (they aren't always pulled in on minimal images).
sudo dnf install -y policycoreutils-python-utils setools-console

# 2. Label /opt/intellistream-chat/data so writes survive a relabel (`restorecon -R /` or a touched .autorelabel).
#    var_lib_t is the catch-all label for system services' state directories.
sudo semanage fcontext -a -t var_lib_t '/opt/intellistream-chat/data(/.*)?'
sudo restorecon -Rv /opt/intellistream-chat

# 3. Allow nginx (httpd_t) to make outbound connections to the JVM on localhost:8080.
sudo setsebool -P httpd_can_network_connect on

# 4. Port labels. Stock http_port_t on AlmaLinux 10 covers 80, 81, 443, 488, 8008, 8009,
#    8443 and 9000 — note that 8080 is NOT among them. Check yours rather than assume:
#      semanage port -l | awk '$1=="http_port_t"'
#    This only bites if you confine the JVM to a domain that enforces port labels; the
#    unit above runs unconfined_service_t, which does not. Label it anyway if you later
#    write a custom domain:
#    sudo semanage port -a -t http_port_t -p tcp 8080
```

The systemd unit's `ReadWritePaths=/opt/intellistream-chat/data` and SELinux's file context for the same path are independent layers — both must be correct. The systemd one stops the JVM from writing outside the data dir; the SELinux one stops it from writing inside the data dir if the labels are wrong.

### When something gets denied

The JVM will fail to start, attachments will fail to upload, or nginx will return `502` and there will be **nothing useful** in `journalctl -u intellistream-chat` — SELinux denials land in the audit log, not the service log. Check both:

```bash
sudo ausearch -m AVC,USER_AVC -ts recent
sudo journalctl -u intellistream-chat -p err --since "10 min ago"
```

Common AVCs and their fixes:

| Symptom | Fix |
|---|---|
| `denied { write } ... path="/opt/intellistream-chat/data/..."` | The `restorecon` step was skipped, or the directory was created **after** `semanage fcontext`. Re-run `sudo restorecon -Rv /opt/intellistream-chat`. |
| `denied { name_connect } ... port=8080` from `httpd_t` | nginx can't reach the upstream — `sudo setsebool -P httpd_can_network_connect on`. |
| `denied { name_bind } ... port=NNNN` from the JVM | You've bound to a port the policy doesn't recognise as HTTP — `sudo semanage port -a -t http_port_t -p tcp NNNN`. |
| `denied { read } ... path="/etc/intellistream-chat/env"` | Custom env file location with the wrong label. Either keep it under `/etc/` (already `etc_t`) or label it: `sudo semanage fcontext -a -t etc_t '/path/to/env'; sudo restorecon -v /path/to/env`. |

### Don't reach for `setenforce 0`

If something breaks, capture the denial and write a targeted local module — don't disable enforcement.

```bash
sudo ausearch -m AVC -ts recent | audit2allow -a -M intellistream-chat-local
less intellistream-chat-local.te                  # review before loading
sudo semodule -i intellistream-chat-local.pp
```

`sudo setenforce 0` is OK as a single-session debug hatch (turn it back on with `setenforce 1`), but never persist permissive across reboots and never edit `/etc/selinux/config` to `SELINUX=disabled` — re-enabling later forces a full relabel.

If you co-locate Postgres on the host, keep `PGDATA` under the default `/var/lib/pgsql/`; moving it elsewhere needs `semanage fcontext -a -t postgresql_db_t '...'`.

## Features

- Sign in with **Keycloak** (OAuth2 / OIDC).
- **Channels** (public + private). Anyone can join public channels; private channels require an admin invite. Channel admins can invite members, promote others, rename the channel and edit its description, and **archive** it — read-only and out of the way, reversibly. Members can **leave**; when the last admin goes, the role passes to the longest-standing member rather than stranding the channel. Deleting a channel outright is a workspace-admin action, because it takes everyone else's messages and files with it. The sidebar lists **every channel you are in**, alphabetically, with favourites pinned to the top.
- **Direct messages** (1:1 and group), the same surface as a channel: threads, typing indicators, read state, reactions, attachments, and a per-conversation notification level so a busy group DM can be muted. You can leave a group; a 1:1 you simply stop using. The DM list lives alongside channels in the sidebar, with a "Send DM" entry point on every avatar hovercard. A conversation with yourself is a real one — it is where your reminders land.
- **Real-time messaging** over native STOMP-over-WebSocket — messages, edits, deletes, and avatar updates fan out live.
- **Threaded replies** that mark the channel unread and notify the people in the thread, **emoji reactions** (including on your own messages), **mentions** with an `@`-typeahead that matches display names as well as handles, plus **`@channel` / `@here`**, **per-user read state**, **typing indicators**, and **message permalinks** that survive the login round-trip. Unread reads the way it does in Slack: a bold channel name, and a number only when someone used your name.
- **Pin** a message to the channel, **save** one to a private list, **forward** it elsewhere, or **quote** it into a reply. Forwarding out of a private channel asks first.
- **File attachments** uploaded as a raw request body streamed straight to disk — no multipart parsing, no buffering; image attachments open in a lightbox.
- **Profile pictures** with server-side resize (PNG/JPEG ≤256px), live broadcast on change.
- **Avatar hovercard** with profile info + "Send direct message" action.
- **@mention notifications**: in-tab toast plus opportunistic OS notification (Notification API) when permitted, and a notification sound you can set separately for mentions and direct messages (fifteen to choose from, synthesised in the browser — no audio files to ship or serve).
- **Do Not Disturb** that actually silences — toast, sound and OS notification — while unread counts and the mention inbox keep working, because silencing an interruption is not the same as hiding information.
- **Per-channel notification levels**, on the Slack/Mattermost model: an account-wide default (*every message* / *mentions* / *nothing*) and a per-channel override whose default value is **inherit**, not a copy — change the account setting and every channel you haven't explicitly overridden moves with it. Muting is the bottom of that same control rather than a separate flag, and a muted channel still counts unread; it just stops interrupting.
- **Markdown** message bodies — server-side render with CommonMark + GFM tables + autolinks, sanitized with jsoup, fenced-code syntax highlighting via highlight.js, and link previews / embedded YouTube.
- **Full-text search** powered by an embedded **Apache Lucene** index, on a results page with counts and paging rather than a dropdown that guesses. Slack's syntax: `from:@bob` for what someone wrote, `@bob` for where they were mentioned, `in:#channel` to narrow. It reaches **every channel you are allowed to read**, not only the ones you joined, and matches **attachment filenames** as well as message text — the authorisation is a clause inside the Lucene query, never a filter applied afterwards. Admins can additionally search private channels they are not in.
- **Themes** (20 built-in palettes, five of them dark) chosen on the profile page.
- **Slash commands** — `/help`, `/poll`, `/remind`. A `/word` that names no command is refused privately and never posted, so a mistyped `/leave` does not become a message the whole room reads. Reminders arrive as a direct message, at the time your own timezone says, not as an announcement in the channel you set them from.
- **Files** — browse what has been shared in a channel, or your own uploads across every channel and DM, with a per-account storage quota. Filenames are searchable.
- **Admin console** at `/admin` for users with the Keycloak `ichat-admin` realm role. A bare `admin` role is deliberately ignored: administering the identity provider is not the same as administering this chat.

## Stack

### Runtime / build
- **Java 25** toolchain
- **Spring Boot 4.1.0**, Gradle Kotlin DSL
- **PostgreSQL 18** (bigint identity PKs, partial indexes)
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
- **Apache Lucene 10.5** (`core`, `analysis-common`, `queryparser`) — embedded full-text index at `./data/lucene` (`MessageIndexService`); documents are written after the message row commits, on a dedicated indexer thread. No Postgres `tsvector`, no ILIKE.

### Frontend
- Thymeleaf templates + hand-written vanilla JS (no React/Vue/Svelte, no npm bundler)
- Vendored under `static/js/vendor/`: **StompJS** (WebSocket STOMP client) and **highlight.js** (code-block syntax highlighting)
- STOMP rides on **native WebSocket only** — no SockJS fallback (its inline-script transports break the strict CSP)

### Test stack
- **JUnit 5** (Jupiter) via `spring-boot-starter-test`
- **AssertJ**, **Mockito** (transitive)
- `spring-security-test` for security helpers
- **Testcontainers BOM 2.0.5** (`postgresql` + `junit-jupiter`) — real Postgres 18 per IT class (no H2)
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
  PG[("PostgreSQL 18")]
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

`podman compose up -d` reads `docker-compose.yml`, starts a `postgres:18-alpine` container with the `chat`/`chat`/`chat` (db/user/password) defaults, and a `keycloak:26.0` container that imports `keycloak/realm.json` on first start.

If `gradlew` is missing (fresh checkout into a tree where the wrapper isn't committed), run once: `gradle wrapper --gradle-version 9.0.0`.

### Without containers (native install)

If you'd rather run Postgres and Keycloak directly on the host (production deploy, air-gapped server, no container runtime), here's the path.

#### PostgreSQL 18

**RHEL / Fedora / Rocky:**
```bash
sudo dnf install -y postgresql18-server postgresql18
sudo /usr/pgsql-18/bin/postgresql-18-setup initdb
sudo systemctl enable --now postgresql-18
```

**Debian / Ubuntu:**
```bash
sudo apt install -y postgresql-18
sudo systemctl enable --now postgresql
```

**macOS (Homebrew):**
```bash
brew install postgresql@18
brew services start postgresql@18
```

Create the database and role:

```bash
sudo -u postgres psql <<'SQL'
CREATE USER chat WITH PASSWORD 'chat';
CREATE DATABASE chat OWNER chat;
GRANT ALL PRIVILEGES ON DATABASE chat TO chat;
SQL
```

That's all the SQL setup you need. Flyway runs the schema migration on first app start (a single consolidated V1__init.sql).

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

Once Keycloak is up at http://localhost:8081 the `ichat-realm` realm exists with users `alice` / `alice` and `bob` / `bob`. Point the app at it:

```bash
export KEYCLOAK_ISSUER_URI=http://localhost:8081/realms/ichat-realm
export KEYCLOAK_CLIENT_SECRET=<value from realm.json or a fresh one you rotated to>
./gradlew bootRun
```

## Keycloak realm

The bundled `keycloak/realm.json` defines everything `podman compose` and `kc.sh start-dev --import-realm` pick up. If you build a realm from scratch in the admin UI, match the settings below.

### Realm + client

| Item | Value |
|---|---|
| Realm name | `ichat-realm` |
| Login with email | enabled |
| Self-registration | enabled |
| Client id | `ichat-client` (confidential, Authorization Code + PKCE) |
| Client secret | `(generated; rotate)` (override via `KEYCLOAK_CLIENT_SECRET` in production) |
| Valid redirect URIs | `http://localhost:8080/*` |
| Web origins | `http://localhost:8080` |

If you move the app to a different host or port, update both Valid redirect URIs and Web Origins to match. A mismatch shows up as `400 invalid_redirect_uri` from Keycloak after sign-in.

### Roles

Three realm roles ship in the bundled config:

| Role | Purpose | Granted to |
|---|---|---|
| `ichat-user` | Marker assigned to every regular account. Not consumed by the chat app itself; handy for filtering in Keycloak. | alice, bob; assign as default to self-registered accounts |
| `admin` | Keycloak's own realm admin. **Intentionally ignored** by the chat app — it carries no `ichat-` prefix, so it is not one of ours. | (Keycloak internal) |
| `ichat-admin` | Application admin. Required for `/admin` and cross-channel search. Maps to Spring's `ROLE_ADMIN` in `KeycloakRolesConverter`. | alice (in the bundled realm) |

Every role this application consumes is prefixed `ichat-`. That is the whole rule, and it exists so a role granted for some other purpose in a shared realm can never be mistaken for a grant in this app. The split is deliberate: the person who admins your Keycloak instance is not automatically a chat administrator. Promote individual users to `ichat-admin` via **Users → pick user → Role mappings → Assign role**.

### Enabling user registration

Self-registration is already on in the bundled realm. To toggle (or enable on a hand-built realm):

1. Open the Keycloak admin console at http://localhost:8081 (default `admin` / `admin`).
2. Switch to the **chat** realm in the top-left dropdown.
3. **Realm settings → Login** tab.
4. Toggle **User registration** on.
5. (Optional) **Forgot password** to expose a self-service reset link on the login page.
6. (Optional) **Verify email** to require confirmation before first login. Needs SMTP configured under **Realm settings → Email**.

Then make sure new self-registered accounts get the `user` realm role automatically:

1. **Realm settings → User registration** sub-tab (or **Realm roles → default-roles-ichat-realm**).
2. Assign realm role `ichat-user` (and any others you want every account to have).

`ichat-admin` is deliberately **not** in the default role set and should never be — promote people one at a time, after you've vetted them.

### Hardening registration before you expose an instance

Open registration is convenient for evaluation and is the single biggest abuse surface in
production. A ban button is whack-a-mole if the same person can register again in ten seconds, so
these matter *more* than the moderation tools, not less.

The bundled realm now ships with **brute-force protection on** (temporary lockout after 10 failures,
backing off to a 15-minute cap, counter decaying after 12 hours). Lockout is deliberately
*temporary*: permanent lockout is itself an attack, because anyone who knows a username can lock
that account out on purpose.

What is still yours to decide, in rough order of value:

| Control | Where | Note |
|---|---|---|
| **Turn registration off** | Realm settings → Login → User registration | The strongest option by far. Invite people instead; most self-hosted workspaces are not open to the public. |
| **Verify email** | Realm settings → Login → Verify email | Needs SMTP under Realm settings → Email. Off in the bundled realm because the demo users have no deliverable address and it would make the quick start fail. |
| **reCAPTCHA on registration** | Authentication → Flows → registration | Stops scripted mass-registration, which is what actually happens to an open instance. |
| **Password policy** | Authentication → Policies → Password policy | Not set in the bundled realm on purpose: the demo users are `alice`/`alice`, and a length rule would lock them out of the quick start. Set one before you expose anything. |
| **Terminate sessions on disable** | — | Handled by the app; see the moderation section. Disabling an account in Keycloak stops new tokens but does not close a WebSocket that is already open. |

None of this is enforced by the application, because none of it belongs there: Keycloak owns
identity and this app deliberately contains no password handling of any kind.

## Configuration

Every override is plain Spring Boot env-var substitution against `application.yml`. The `dev` Spring profile (auto-active on `./gradlew bootRun`, see `application-dev.properties`) overrides the maintainer-specific LAN values; production deploys leave the profile off and supply the env vars below. A [Vault / OpenBao secret backend](#optional-vault--openbao-secret-backend) is available as an opt-in for production; off by default so the env-var path Just Works.

| Variable | Default | Purpose |
|---|---|---|
| `ICHAT_DB_URL` | `jdbc:postgresql://localhost:5432/intellistream_chat` | JDBC URL for the Postgres instance |
| `ICHAT_DB_USERNAME` | `ichat_role` | Postgres user |
| `ICHAT_DB_PASSWORD` | `ichat_role` — **rotate in production** | Postgres password — **set this in production** |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8081/realms/ichat-realm` | Keycloak realm issuer (used by both OIDC client and resource server). Must match the OIDC issuer in `keycloak/realm.json`'s redirect-URI list — change one and the other will reject the redirect with `400 invalid_redirect_uri`. |
| `KEYCLOAK_CLIENT_ID` | `ichat-client` | OIDC client id |
| `KEYCLOAK_CLIENT_SECRET` | `(generated; rotate in production)` | OIDC client secret — **set this in production** |
| `SERVER_PORT` | `8080` | HTTP port the Boot app binds to |
| `SERVER_ADDRESS` | `127.0.0.1` | Network interface to bind. The dev profile overrides this to a LAN IP for cross-device testing; prod typically keeps `127.0.0.1` and fronts the JVM with nginx. |
| `ICHAT_ATTACHMENTS_DIR` | `./data/attachments` | Where uploaded message attachments are stored |
| `ICHAT_AVATARS_DIR` | `./data/avatars` | Where uploaded avatars are stored |
| `ICHAT_BRANDING_DIR` | `./data/branding` | Where the admin-uploaded logo is stored |
| _(no env var)_ | _auto_ | The JSESSIONID and CSRF cookies' `Secure` flag is auto-detected from `request.isSecure()` per request. Behind a TLS-terminating proxy with `X-Forwarded-Proto: https`, `forward-headers-strategy: framework` flips request.isSecure() to true and the cookies are marked Secure automatically. To force Secure for every request (e.g. behind a proxy that strips the header), set `server.servlet.session.cookie.secure=true`. |

The Lucene index lives at `./data/lucene` (override with `ichat.search.lucene-dir`). Back up the whole `./data/` directory plus the Postgres database and you have everything: messages, attachments, avatars, branding, and the search index.

### Optional: Vault / OpenBao secret backend

For deployments where shipping `ICHAT_DB_PASSWORD` and `KEYCLOAK_CLIENT_SECRET` via `EnvironmentFile=` is too coarse, the app can pull them from a [HashiCorp Vault](https://www.vaultproject.io/) / [OpenBao](https://openbao.org/) KV-v2 mount at boot. **Off by default** — `ICHAT_VAULT_ENABLED=false` skips the integration entirely.

When enabled, a `VaultEnvironmentPostProcessor` runs before Spring autoconfiguration reads `spring.datasource.*` / the OAuth client config, fetches one KV-v2 record, and injects the values as a high-priority `MapPropertySource`.

| Variable | Default | Purpose |
|---|---|---|
| `ICHAT_VAULT_ENABLED` | `false` | Master switch. |
| `ICHAT_VAULT_URI` | _(empty)_ | Base URL (e.g. `http://127.0.0.1:8200`). Required when enabled. |
| `ICHAT_VAULT_TOKEN` | _(empty)_ | Token credential. Required when enabled. |
| `ICHAT_VAULT_PATH` | `intellistream-chat` | KV-v2 path; default maps to `secret/data/intellistream-chat`. |

If enabled but URI or token is missing, the app **fails fast at boot** with `IllegalStateException` — silently falling back to env-var defaults in a "vault-enabled" deploy would be a security bug.

**Vault record schema** (five keys, anything else ignored):

| Vault key | Spring property |
|---|---|
| `db.username` | `spring.datasource.username` |
| `db.password` | `spring.datasource.password` |
| `keycloak.client-id` | `spring.security.oauth2.client.registration.keycloak.client-id` |
| `keycloak.client-secret` | `spring.security.oauth2.client.registration.keycloak.client-secret` |
| `keycloak.issuer-uri` | mirrored into both `spring.security.oauth2.client.provider.keycloak.issuer-uri` and `spring.security.oauth2.resourceserver.jwt.issuer-uri` (the OIDC client and the resource server read different slots) |

**Try it locally** — the bundled `docker-compose.yml` has a profile-gated OpenBao dev container:

```bash
podman compose --profile openbao up -d
KEYCLOAK_CLIENT_SECRET=<value-from-keycloak/realm.json> ./scripts/seed-vault.sh
ICHAT_VAULT_ENABLED=true ICHAT_VAULT_URI=http://127.0.0.1:8200 \
  ICHAT_VAULT_TOKEN=intellistream-dev-token ./gradlew bootRun
```

Hit `/actuator/env` to verify the `intellistream-vault` property source appeared. The OpenBao dev container uses in-memory storage and a root token — for production, switch to sealed deployment + auto-unseal + AppRole or Kubernetes auth.

### Upload size cap

**There is no per-file cap.** A file is as large as it is; uploads stream socket → disk and are never held in memory, so size costs disk and time rather than heap.

What bounds an ordinary account is its **storage quota** — 2 GiB by default (`ICHAT_USER_QUOTA_BYTES`), a *total* rather than a per-file limit, so the largest single file someone can send is whatever is left of their allowance. Admins (anyone with the `ichat-admin` realm role) have no quota and no cap. The volume itself is protected by the free-space floor (`ICHAT_MIN_FREE_BYTES`, 64 MiB), not by the quota.

If you want a per-file ceiling for some account, it is opt-in:

1. Open the Keycloak admin console → **chat** realm → **Users** → pick the user.
2. **Attributes** tab → add an attribute named `chat_max_upload_bytes` with a positive byte count (e.g., `524288000` for 500 MiB), or `-1` for unlimited.
3. Save. The user's next login picks up the new value via the JWT claim mapper that ships in `keycloak/realm.json`.

Set `client_max_body_size 0` and `proxy_request_buffering off` in nginx to match — buffering is on by default, which spools an entire upload to the proxy's disk before the app sees a byte. See [`frontend.md`](frontend.md).

Avatars have a separate, hard 5 MiB cap (they're decoded into memory for resize, so the cap is structural rather than configurable).

Uploads are **not** `multipart/form-data`. The file is the raw request body and its metadata rides in headers (`X-Upload-Filename`, `X-Upload-Caption`, both percent-encoded), so the server copies socket → disk without parsing anything. Multipart has to scan every byte looking for the boundary, which caps a transfer well below line rate; the raw-body path moves ~380 MB/s on a loopback benchmark. Browsers send this natively with `fetch(url, {method: 'POST', body: file})`. See `RawUpload`.

Server-side errors are returned as `413 Payload Too Large` with `{ code: "upload_too_large", maxBytes: <bytes> }`; the JS upload UX in `chat.js` / `conversation.js` / `profile.js` renders that as "File too large — your account is capped at N MiB per upload." That path now only fires for an account with an explicit `chat_max_upload_bytes`; running out of storage quota is a separate `insufficient_storage` response.

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

1. Sign in as an admin (Keycloak `ichat-admin` realm role) and open `/admin`.
2. Find the **Privacy** section (right above the Users table).
3. Uncheck **Show full emails on this page** and click **Save privacy setting**.

When off, each row is rendered server-side as `al…@example.com` (first two letters of the local part, then `…`, then the full domain). The DB still stores the raw value — only the rendering is masked, so flipping the toggle back on doesn't lose anything.

The setting persists in `app_settings.expose_user_emails` (created in `V1__init.sql`). It applies only to the admin page; mention rendering, hovercards, and the user profile API don't expose email at all (and never have).

### Production hardening checklist

The systemd / SELinux / Quick start sections cover the mechanical setup. This is the punch-list of things the earlier sections don't enforce on your behalf.

| | What | Why |
|---|---|---|
| ☐ | Rotate `KEYCLOAK_CLIENT_SECRET` (Keycloak admin → **Clients → ichat-client → Credentials → Regenerate**) | The bundled secret in `keycloak/realm.json` is in this public repo. |
| ☐ | Restrict the `chat` client's **Valid redirect URIs** + **Web origins** to your real hostname | OIDC redirect-URI matching is your defence against open-redirect token theft. |
| ☐ | Change `KC_BOOTSTRAP_ADMIN_PASSWORD` from `admin` | Master key to every account in your realm. |
| ☐ | Enable **Verify email** in Keycloak before opening self-registration | Without it, bots will mass-register. |
| ☐ | Configure SMTP under Realm settings → Email | Otherwise password reset and email verification silently no-op. |
| ☐ | `client_max_body_size 0` + `proxy_request_buffering off` in nginx | There is no per-file cap in the app, and buffering (on by default) spools whole uploads to the proxy's disk first. See [`frontend.md`](frontend.md). |
| ☐ | Schedule Postgres + `./data/` backups; verify restores work | The whole product fits in `pg_dump` + that directory. |
| ☐ | Enable CVE scanning in CI (OWASP `dependency-check`, Dependabot, etc.) | Hibernate / Tomcat / Jackson ship CVEs over any deploy's lifetime. |
| ☐ | Replace the in-memory `RateLimiter` before scaling past one replica | Per-process limits don't compose across N replicas. |

`security_plan.md` has the full per-finding rationale; `SecurityBoundaryIT` and `InternetExposureSecurityIT` pin the invariants.

## Tests

The suite is **975 tests across 101 classes** — 436 unit tests that run anywhere, and 539 integration tests that need a Postgres container. Both layers run from a single `./gradlew test`.

### Run everything

```bash
# One-time: expose the Podman user socket so Testcontainers can find a Docker-compatible API
systemctl --user enable --now podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock

./gradlew test                    # full suite (unit + integration)
```

The first run pulls `postgres:18-alpine` (~80 MB); subsequent runs reuse the cached image and finish in about five minutes — the cost is dominated by starting a Postgres container per IT class, not by the tests themselves. Reports land at `build/reports/tests/test/index.html` (HTML) and `build/test-results/test/TEST-*.xml` (JUnit XML for CI).

### Run a subset

```bash
# Unit tests only — no Docker needed.
./gradlew test --tests 'ai.intellistream.chat.service.*' --tests 'ai.intellistream.chat.security.*'

# Single class / method.
./gradlew test --tests 'ai.intellistream.chat.integration.HovercardAndDmFlowIT'
./gradlew test --tests 'ai.intellistream.chat.integration.SearchFlowIT.fuzzyMatch_*'
```

### Test layers

- **Unit** (`src/test/java/.../service/`, `.../security/`) — pure-logic branches: Markdown rendering + sanitization, slug rules, search input validation, role conversion. No Docker.
- **Integration** (`src/test/java/.../integration/`) — `IntegrationTestApplication` boots a slimmed Spring context (no security / OAuth2 / web autoconfig) against Testcontainers Postgres and exercises the service layer end-to-end. Each IT class registers its own `ichat.search.lucene-dir` via `TestLuceneDirs.register(...)` so cached Spring contexts don't fight over the Lucene lock.
- **Controller-shaped ITs** (`AvatarBroadcastIT`, `HovercardAndDmFlowIT`, `MentionBroadcastIT`) wire a controller manually with mocked `CurrentUser` / `SimpMessagingTemplate` to assert broadcast wiring without a full web layer.
- **Security boundary ITs** (`SecurityBoundaryIT`, `InternetExposureSecurityIT`) pin the auth/authz invariants — see `security_plan.md`.

### Constraints worth knowing

- **No H2 fallback.** Hibernate runs in `validate` mode against the production schema; H2 won't accept some of the column types it uses.
- **Per-class Postgres container.** Each `@Container` spins up a fresh database — 34 transient containers for the full suite, since no class reuses another's. That isolation is why the run takes minutes rather than seconds.
- **Stale daemon env.** Gradle's daemon caches `DOCKER_HOST` from when it started — `./gradlew --stop` if you change the export.
- **Lucene lock.** "Failed to open Lucene index at …" usually means a stale lock — clear `build/test-lucene/` and rerun.

## Layout

```
src/main/java/ai/intellistream/chat/
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
├── db/migration/V1__init.sql          # Flyway — consolidated initial schema (add V2+ for changes)
├── templates/                          # landing, channels, conversation, profile, admin
└── static/
    ├── css/app.css
    └── js/                             # chat, conversation, hovercard, notifications, profile,
                                        # theme-loader, emoji-data + vendor/{stomp,highlight}
```

## Performance

Measured on one 12-core / 31 GB box with the load generator running **on the same machine**, so
these are floors rather than ceilings. Full method and analysis in
[`scalability.md`](scalability.md); the harness is in [`benchmark/`](benchmark/).

| | |
|---|---|
| Messages persisted + delivered | **~17,000 / second**, ~20 ms median end-to-end |
| Fan-out into 50-member rooms | **~136,000 deliveries / second**, 0 dropped |
| Concurrent connections served | **100,000**, 47,484 deliveries/s, 0 dropped, p50 792 ms |
| Memory holding 100,000 connections | **11.2 GiB** RSS, whole JVM |
| Attachment upload | **~380 MB/s** (~3 Gbps) single stream |

Two things are worth knowing if you fork this:

- **The write path is batched, and broadcast waits for the commit.** `MessageWriteBehind`
  pre-allocates message ids and inserts rows in batches; a message is broadcast and indexed only
  *after* its batch commits, so nobody is ever shown a message that then failed to persist. Queues
  are sharded by channel, so per-channel ordering holds. The sender doesn't wait for any of it — the
  composer renders an optimistic bubble and reconciles it when the broadcast arrives. The trade is a
  small durability window (one flush interval, ~5 ms) on an abrupt kill, for messages nobody saw.
  On by default; `ichat.write-behind.enabled=false` restores commit-per-message.
- **Server concurrency is explicit.** `WebSocketConfig` sets the STOMP channel executors
  unconditionally, and `StompChannelDiagnostics` logs them at startup. This is not incidental: a
  mis-wired executor once put every inbound message on a single thread and capped the whole server
  at ~109 messages/second, with nothing in any metric pointing at the cause. Check that log line
  before trusting a throughput number.

## Roadmap (still open)

- E2E test with a real Keycloak (Testcontainers Keycloak module).
- Distributed rate limiting (`RateLimiter` is per-process; replace with Bucket4j-with-Hazelcast or Redis before going multi-instance).
- OWASP `dependency-check` Gradle plugin for CVE scanning.

## Why not Rust

A fair question for a self-hosted server in 2026, and the answer is not that Rust is worse.

**Maintenance is the dominant lifetime cost, not CPU.** This is a chat server. It will spend years
being modified by whoever is around, and the pool of people who can safely change a Spring Boot
codebase is very much larger than the pool who can safely change an async Rust one. That gap is the
biggest number in the total cost of ownership, and it appears in no benchmark.

**The performance argument does not apply here.** One machine already does 17,066 messages a second
end to end and holds 100,000 concurrent connections in 11.2 GiB, while a small deployment runs in
under 500 MB. The limits that matter in a chat server are the database, the search index and the
network, not the language runtime. A faster language would move a number that is not the constraint.

**The libraries are the product.** Spring Security's OIDC support, Hibernate and Flyway, and
embedded Lucene are decades of accumulated correctness in exactly the areas where a bug is a
security incident or a data-loss event. Rust has credible equivalents for some of this and thinner
coverage for the rest, particularly enterprise SSO. Writing those parts yourself is not a saving.

**Where Rust would genuinely win:** memory per connection, cold start, and no GC pauses at the tail.
If you were building an edge relay holding a million sockets and doing almost nothing with each one,
that is the right trade and Java is the wrong one. This is a different program, where every message
is persisted, rendered, sanitised, indexed and fanned out.

Rust would make the cheap part cheaper and the expensive part more expensive.

## License

Apache License 2.0. See [`LICENSE`](LICENSE) for the full text. Source files carry the standard Apache header — fork it, run it, change it, ship it. The licence cannot be retroactively narrowed; that's the point.
