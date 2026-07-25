# IntelliStream Chat — quick start (manual install + systemd)

Sets up IntelliStream Chat on a single Linux host without containers: PostgreSQL and Keycloak
installed natively, the app running as a hardened systemd service. Paths below assume a
RHEL/Fedora-family or Debian-family server; adjust package commands to taste.

For local development prefer the compose route — see `QUICKSTART-COMPOSE.md`.

## 1. PostgreSQL

Install PostgreSQL 16+ (18 recommended) from your distro or PGDG, then create the role and
database:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE intellistream LOGIN PASSWORD 'CHANGE-ME';
CREATE DATABASE intellistream_chat OWNER intellistream;
SQL
```

Flyway creates the schema on first app start — no manual DDL. Verify connectivity:

```bash
psql "postgresql://intellistream:CHANGE-ME@localhost:5432/intellistream_chat" -c 'select 1'
```

## 2. Keycloak

Install Keycloak 26 (unzip the distribution under e.g. `/opt/keycloak`, run behind your
reverse proxy in production). Two ways to get the realm:

### Option A — import the bundled realm (fastest)

```bash
# One-time import of realm, client, roles, and the two demo users (remove them for prod!):
/opt/keycloak/bin/kc.sh import --file /path/to/repo/keycloak/realm.json
/opt/keycloak/bin/kc.sh start   # (or start-dev while evaluating)
```

**Change the client secret after import** (it's a public dev value from the repo):
admin console → realm `ichat-realm` → Clients → `ichat-client` → Credentials → Regenerate.

### Option B — create the realm by hand

In the admin console:

1. **Create realm** named `ichat-realm`.
2. **Clients → Create client**: client ID `ichat-client`, type OpenID Connect,
   *Client authentication* ON (confidential).
   - Valid redirect URIs: `https://your-domain/login/oauth2/code/keycloak`
     (for local testing: `http://localhost:8080/login/oauth2/code/keycloak`)
   - Web origins: `https://your-domain`
   - Note the generated secret (Credentials tab).
3. **Realm roles → Create role**, twice. Every role this app consumes is prefixed `ichat-`:
   - `ichat-user` — marker for a regular account. The app doesn't read it; it's there so you can
     filter and set it as the realm's default role for self-registration.
   - `ichat-admin` — grants the admin console and `scope=all` search (Spring `ROLE_ADMIN`).

   Do **not** use a role named `admin`. Keycloak has its own realm-admin role by that name, and the
   app ignores it on purpose — administering your Keycloak is not the same as administering this
   chat. Only `ichat-admin` is honoured, and `KeycloakRolesConverterTest` pins that.
4. **Users**: create your accounts, give everyone `ichat-user`, and assign `ichat-admin` to at
   least one — otherwise nobody can reach `/admin`.

## 3. Build and install the app

```bash
./gradlew bootJar          # builds JS/CSS bundles + the executable jar
sudo useradd --system --home /opt/intellistream-chat --create-home intellistream-chat
sudo mkdir -p /opt/intellistream-chat /etc/intellistream-chat
sudo cp build/libs/intellistream-chat-0.1.0-SNAPSHOT.jar /opt/intellistream-chat/intellistream-chat.jar
```

The app writes runtime data (attachments, avatars, branding, Lucene index) under its working
directory — the unit below uses `/opt/intellistream-chat`.

## 4. Configuration

`/etc/intellistream-chat/intellistream-chat.env` (readable by the service user only):

```bash
# --- database ---
ICHAT_DB_URL=jdbc:postgresql://localhost:5432/intellistream_chat
ICHAT_DB_USERNAME=intellistream
ICHAT_DB_PASSWORD=CHANGE-ME

# --- Keycloak ---
KEYCLOAK_ISSUER_URI=https://auth.your-domain/realms/ichat-realm
KEYCLOAK_CLIENT_ID=ichat-client
KEYCLOAK_CLIENT_SECRET=CHANGE-ME

# --- HTTP: bind localhost; terminate TLS in nginx/caddy in front (see frontend.md) ---
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080

# --- data directories (inside WorkingDirectory) ---
ICHAT_ATTACHMENTS_DIR=/opt/intellistream-chat/attachments
ICHAT_AVATARS_DIR=/opt/intellistream-chat/avatars
ICHAT_BRANDING_DIR=/opt/intellistream-chat/branding
ICHAT_SEARCH_LUCENE_DIR=/opt/intellistream-chat/lucene
```

```bash
sudo chown root:intellistream-chat /etc/intellistream-chat/intellistream-chat.env
sudo chmod 640 /etc/intellistream-chat/intellistream-chat.env
```

(Prefer a secret manager? The app has optional Vault/OpenBao support —
`ICHAT_VAULT_*`, see `scripts/seed-vault.sh`.)

## 5. systemd service

There is **one** unit for this project and it lives in the README, under
[Production: systemd + JVM tuning](README.md#production-systemd--jvm-tuning). It is annotated
directive by directive, runs the JVM sandboxed (`ProtectSystem=strict`, `NoNewPrivileges`,
`RestrictNamespaces`, an explicit `InaccessiblePaths` list) and scores **4.7 OK** on
`systemd-analyze security`. Copy it to `/etc/systemd/system/intellistream-chat.service`.

Earlier revisions of this guide carried a second, weaker unit that disagreed with the README's on
paths and on the environment-file location, so following both in sequence produced a broken
install. Don't reintroduce one — if the unit needs to change, change it in the README.

Paths throughout assume `/opt/intellistream-chat`, which is a convention rather than a
requirement. If you relocate it, three things must move together: `WorkingDirectory`,
`ReadWritePaths` (and the SELinux `fcontext` rule for the same directory, if SELinux is enforcing),
and wherever you install the jar.

Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now intellistream-chat
systemctl status intellistream-chat
journalctl -u intellistream-chat -f   # startup: Flyway migrations, then Tomcat on :8080
```

## 6. Reverse proxy + smoke test

Put nginx (or caddy) in front for TLS — [`frontend.md`](frontend.md) has a working
config for nginx and haproxy, plus the sizing and cookie gotchas (it forwards
`X-Forwarded-Proto`, which the app trusts via
`forward-headers-strategy: framework`, and proxies the `/ws` WebSocket endpoint).

Then browse to `https://your-domain`, sign in through Keycloak, create a channel, and post a
message. Health endpoint for monitoring: `GET /actuator/health` on the loopback port.

## Upgrading

```bash
./gradlew bootJar
sudo cp build/libs/intellistream-chat-*.jar /opt/intellistream-chat/intellistream-chat.jar
sudo systemctl restart intellistream-chat    # Flyway applies any new migrations on boot
```
