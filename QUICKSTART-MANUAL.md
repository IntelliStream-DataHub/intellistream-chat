# ThreadOrbit — quick start (manual install + systemd)

Sets up ThreadOrbit on a single Linux host without containers: PostgreSQL and Keycloak
installed natively, the app running as a hardened systemd service. Paths below assume a
RHEL/Fedora-family or Debian-family server; adjust package commands to taste.

For local development prefer the compose route — see `QUICKSTART-COMPOSE.md`.

## 1. PostgreSQL

Install PostgreSQL 16+ (18 recommended) from your distro or PGDG, then create the role and
database:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE threadorbit LOGIN PASSWORD 'CHANGE-ME';
CREATE DATABASE threadorbit_chat OWNER threadorbit;
SQL
```

Flyway creates the schema on first app start — no manual DDL. Verify connectivity:

```bash
psql "postgresql://threadorbit:CHANGE-ME@localhost:5432/threadorbit_chat" -c 'select 1'
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
admin console → realm `threadorbit` → Clients → `threadorbit` → Credentials → Regenerate.

### Option B — create the realm by hand

In the admin console:

1. **Create realm** named `threadorbit`.
2. **Clients → Create client**: client ID `threadorbit`, type OpenID Connect,
   *Client authentication* ON (confidential).
   - Valid redirect URIs: `https://your-domain/login/oauth2/code/keycloak`
     (for local testing: `http://localhost:8080/login/oauth2/code/keycloak`)
   - Web origins: `https://your-domain`
   - Note the generated secret (Credentials tab).
3. **Realm roles → Create role**: `admin` — members of this role get the ThreadOrbit admin
   console and `scope=all` search.
4. **Users**: create your accounts; assign the `admin` realm role to at least one.

## 3. Build and install the app

```bash
./gradlew bootJar          # builds JS/CSS bundles + the executable jar
sudo useradd --system --home /var/lib/threadorbit --create-home threadorbit
sudo mkdir -p /opt/threadorbit /etc/threadorbit
sudo cp build/libs/threadorbit-0.1.0-SNAPSHOT.jar /opt/threadorbit/threadorbit.jar
```

The app writes runtime data (attachments, avatars, branding, Lucene index) under its working
directory — the unit below uses `/var/lib/threadorbit`.

## 4. Configuration

`/etc/threadorbit/threadorbit.env` (readable by the service user only):

```bash
# --- database ---
THREADORBIT_DB_URL=jdbc:postgresql://localhost:5432/threadorbit_chat
THREADORBIT_DB_USERNAME=threadorbit
THREADORBIT_DB_PASSWORD=CHANGE-ME

# --- Keycloak ---
KEYCLOAK_ISSUER_URI=https://auth.your-domain/realms/threadorbit
KEYCLOAK_CLIENT_ID=threadorbit
KEYCLOAK_CLIENT_SECRET=CHANGE-ME

# --- HTTP: bind localhost; terminate TLS in nginx/caddy in front (see nginx_example.conf) ---
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080

# --- data directories (inside WorkingDirectory) ---
THREADORBIT_ATTACHMENTS_DIR=/var/lib/threadorbit/attachments
THREADORBIT_AVATARS_DIR=/var/lib/threadorbit/avatars
THREADORBIT_BRANDING_DIR=/var/lib/threadorbit/branding
THREADORBIT_SEARCH_LUCENE_DIR=/var/lib/threadorbit/lucene
```

```bash
sudo chown root:threadorbit /etc/threadorbit/threadorbit.env
sudo chmod 640 /etc/threadorbit/threadorbit.env
```

(Prefer a secret manager? The app has optional Vault/OpenBao support —
`THREADORBIT_VAULT_*`, see `scripts/seed-vault.sh`.)

## 5. systemd service

`/etc/systemd/system/threadorbit.service`:

```ini
[Unit]
Description=ThreadOrbit chat
Wants=network-online.target
After=network-online.target postgresql.service

[Service]
Type=simple
User=threadorbit
Group=threadorbit
EnvironmentFile=/etc/threadorbit/threadorbit.env
WorkingDirectory=/var/lib/threadorbit
ExecStart=/usr/bin/java -Xms256m -Xmx1g -jar /opt/threadorbit/threadorbit.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

# --- hardening ---
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/threadorbit
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now threadorbit
systemctl status threadorbit
journalctl -u threadorbit -f        # watch startup: Flyway migrations, then Tomcat on :8080
```

## 6. Reverse proxy + smoke test

Put nginx (or caddy) in front for TLS — `nginx_example.conf` in the repo is a working
template (it forwards `X-Forwarded-Proto`, which the app trusts via
`forward-headers-strategy: framework`, and proxies the `/ws` WebSocket endpoint).

Then browse to `https://your-domain`, sign in through Keycloak, create a channel, and post a
message. Health endpoint for monitoring: `GET /actuator/health` on the loopback port.

## Upgrading

```bash
./gradlew bootJar
sudo cp build/libs/threadorbit-*.jar /opt/threadorbit/threadorbit.jar
sudo systemctl restart threadorbit    # Flyway applies any new migrations on boot
```
