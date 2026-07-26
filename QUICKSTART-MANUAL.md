# IntelliStream Chat — quick start (manual install + systemd)

PostgreSQL, Keycloak and the app installed natively on one Linux host, no containers. For local
development use [`QUICKSTART-COMPOSE.md`](QUICKSTART-COMPOSE.md) instead.

## 1. PostgreSQL

Install PostgreSQL 16+ (18 recommended), then:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE ichat_role LOGIN PASSWORD 'CHANGE-ME';
CREATE DATABASE intellistream_chat OWNER ichat_role;
SQL

psql "postgresql://ichat_role:CHANGE-ME@localhost:5432/intellistream_chat" -c 'select 1'
```

Flyway creates the schema on first start. No manual DDL.

## 2. Keycloak

Install Keycloak 26 under `/opt/keycloak`. Then either import the bundled realm, or build it by
hand.

### Import the bundled realm

```bash
/opt/keycloak/bin/kc.sh import --file /path/to/repo/keycloak/realm.json
/opt/keycloak/bin/kc.sh start
```

Then **regenerate the client secret** — the one in the repo is a public dev value:
admin console → `ichat-realm` → Clients → `ichat-client` → Credentials → Regenerate. Delete the
demo users `alice` and `bob` too.

### Or build it by hand

1. **Create realm** `ichat-realm`.
2. **Clients → Create client**: ID `ichat-client`, OpenID Connect, *Client authentication* ON.
   - Valid redirect URI: `https://your-domain/login/oauth2/code/keycloak`
   - Web origins: `https://your-domain`
   - Copy the secret from the Credentials tab.
3. **Realm roles → Create role**, twice: `ichat-user` and `ichat-admin`.

   Not a role named `admin` — Keycloak has its own by that name and the app ignores it deliberately.
   Only `ichat-admin` grants the admin console.
4. **Users**: create accounts, give everyone `ichat-user`, and `ichat-admin` to at least one, or
   nobody can reach `/admin`.

### Brand the login page

Keycloak serves the sign-in page, so it does not inherit the app's styling. The repo ships a
matching theme.

```bash
sudo cp -r /path/to/repo/keycloak/themes/intellistream /opt/keycloak/themes/
sudo chown -R root:keycloak /opt/keycloak/themes/intellistream
sudo find /opt/keycloak/themes/intellistream -type d -exec chmod 750 {} +
sudo find /opt/keycloak/themes/intellistream -type f -exec chmod 640 {} +
```

Select it: **`ichat-realm`** → Realm settings → Themes → Login theme → `intellistream` → Save.
(Pick the realm first — setting it on `master` does nothing for your users.) The bundled
`realm.json` already sets `"loginTheme": "intellistream"`, so an imported realm comes up themed.

The wordmark under the logo comes from Realm settings → General → **HTML Display name**.

Three things that will cost you an hour if nobody says them:

- No `kc.sh build` is needed. Themes in `themes/` are read at runtime; only JAR-packaged themes
  under `providers/` need a rebuild.
- **After editing a theme, restart Keycloak** — nothing else clears its theme and template caches.
- Browsers cache theme resources for 30 days, and a restart cannot reach them. Rename the changed
  file (`intellistream.css` → `intellistream.2.css`, updated in `theme.properties`) to invalidate.
  Do not disable the caches on a production server to work around this; those flags belong to
  `start-dev`.

Forking the theme? It overrides one FreeMarker template (`footer.ftl`) and does everything else in
`theme.properties` and one stylesheet. Keep it that way — a theme that copies `login.ftl` or
`template.ftl` keeps rendering its stale copy after an upgrade, and new required actions silently
stop appearing.

## 3. Install the app

```bash
# Java, PostgreSQL (unless --skip-postgres), service account, env file, systemd unit, then start
# and health-check. Both scripts are idempotent and take --dry-run.
sudo scripts/install-almalinux.sh \
  --issuer-uri https://auth.your-domain/realms/ichat-realm \
  --client-secret -              # '-' reads it from stdin, keeping it out of argv and history

# If `getenforce` says Enforcing:
sudo scripts/selinux-harden.sh
```

`--help` lists the rest: alternate paths, external database, pre-built jar, heap, listen address.

Neither script touches your reverse proxy. The app listens on `127.0.0.1:8080` and is unreachable
from outside the host until you put one in front (step 6).

<details>
<summary>By hand instead</summary>

```bash
./gradlew bootJar
sudo groupadd --system intellistream-chat
sudo useradd --system --gid intellistream-chat --home-dir /opt/intellistream-chat \
             --shell /usr/sbin/nologin intellistream-chat
sudo install -d -o root -g intellistream-chat -m 0750 /etc/intellistream-chat
sudo install -d -o intellistream-chat -g intellistream-chat -m 0750 \
     /opt/intellistream-chat /opt/intellistream-chat/data
sudo install -o root -g intellistream-chat -m 0640 \
     build/libs/intellistream-chat-*.jar /opt/intellistream-chat/intellistream-chat.jar
```
</details>

`/opt/intellistream-chat/data` is the **only** writable path — attachments, avatars, branding, the
Lucene index and heap dumps all live under it, and it must match the unit's `ReadWritePaths=`.

## 4. Configuration

`/etc/intellistream-chat/env`, mode 0640, owner `root:intellistream-chat`:

```bash
ICHAT_DB_URL=jdbc:postgresql://localhost:5432/intellistream_chat
ICHAT_DB_USERNAME=ichat_role
ICHAT_DB_PASSWORD=CHANGE-ME

KEYCLOAK_ISSUER_URI=https://auth.your-domain/realms/ichat-realm
KEYCLOAK_CLIENT_ID=ichat-client
KEYCLOAK_CLIENT_SECRET=CHANGE-ME

# Loopback only; TLS terminates in the proxy (see frontend.md).
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080

ICHAT_ATTACHMENTS_DIR=/opt/intellistream-chat/data/attachments
ICHAT_AVATARS_DIR=/opt/intellistream-chat/data/avatars
ICHAT_BRANDING_DIR=/opt/intellistream-chat/data/branding
ICHAT_SEARCH_LUCENE_DIR=/opt/intellistream-chat/data/lucene

JAVA_OPTS=-Xms256m -Xmx2g -XX:+UseZGC -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED
```

```bash
sudo chown root:intellistream-chat /etc/intellistream-chat/env
sudo chmod 640 /etc/intellistream-chat/env
```

systemd reads this file itself: no quoting, no `$` expansion, no trailing comments.

Optional: Vault/OpenBao instead of a plaintext file — `ICHAT_VAULT_*`, see `scripts/seed-vault.sh`.

## 5. systemd service

The unit lives in the README, under
[Production: systemd + JVM tuning](README.md#production-systemd--jvm-tuning) — annotated directive
by directive, sandboxed, scoring 4.6 OK on `systemd-analyze security`. `install-almalinux.sh`
writes exactly that unit, so the two cannot drift. Don't add a second one here.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now intellistream-chat
systemctl status intellistream-chat
journalctl -u intellistream-chat -f     # Flyway migrations, then Tomcat on :8080
```

Relocating from `/opt/intellistream-chat`? Four things move together: `WorkingDirectory`,
`ReadWritePaths`, the jar, and the SELinux fcontext rule (`selinux-harden.sh --data-dir`).

On a dual-socket or NPS-partitioned host, check:

```bash
lscpu | grep -i '^NUMA'
```

More than one node means the JVM can run on one while its heap sits on another. Pin it with a
`systemctl edit` drop-in (`AllowedCPUs=` / `AllowedMemoryNodes=`) — see
[NUMA: keep the JVM on one node](README.md#numa-keep-the-jvm-on-one-node). One node, nothing to do.

## 6. Reverse proxy + smoke test

Put nginx or haproxy in front for TLS — [`frontend.md`](frontend.md) has a complete config for
each, including the WebSocket upgrade, the upload settings and the same-domain Keycloak rule.

Then sign in, create a channel, post a message. Monitoring endpoint: `GET /actuator/health` on the
loopback port.

## Upgrading

```bash
./gradlew bootJar
sudo cp build/libs/intellistream-chat-*.jar /opt/intellistream-chat/intellistream-chat.jar
sudo systemctl restart intellistream-chat    # Flyway applies new migrations on boot
```
