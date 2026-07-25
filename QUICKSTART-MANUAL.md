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
CREATE ROLE ichat_role LOGIN PASSWORD 'CHANGE-ME';
CREATE DATABASE intellistream_chat OWNER ichat_role;
SQL
```

Flyway creates the schema on first app start — no manual DDL. Verify connectivity:

```bash
psql "postgresql://ichat_role:CHANGE-ME@localhost:5432/intellistream_chat" -c 'select 1'
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

### Branding the login page

The sign-in page is served by Keycloak, not by this app, so it does not inherit any of the app's
styling. Without a theme your users go from the product's landing page straight to stock Keycloak
and back — which is the moment a self-hosted deployment looks least like a product. The repository
ships a login theme (`keycloak/themes/intellistream`) that matches the app: same navy chrome, same
orbit mark, same self-hosted Figtree, light and dark from `prefers-color-scheme`.

**Where themes live.** `$KEYCLOAK_HOME/themes/<name>/<type>/` — for a distribution unzipped under
`/opt/keycloak`, that is `/opt/keycloak/themes/intellistream/login/`. Keycloak scans this directory
at runtime; installing a theme here needs **no `kc.sh build`** and no rebuild of the optimized
image. (Only themes packaged as a JAR under `providers/` require `kc.sh build`.)

```bash
sudo cp -r /path/to/repo/keycloak/themes/intellistream /opt/keycloak/themes/
sudo chown -R root:keycloak /opt/keycloak/themes/intellistream
sudo find /opt/keycloak/themes/intellistream -type d -exec chmod 750 {} +
sudo find /opt/keycloak/themes/intellistream -type f -exec chmod 640 {} +
```

Keycloak only ever reads a theme, so it does not need write access. On an SELinux host, files
copied into `/opt/keycloak` inherit the right label; files *moved* in from a home directory keep
the old one — run `sudo restorecon -R /opt/keycloak/themes` if the server logs a permission error
it should not be getting.

**Selecting it for the realm.** Three equivalent routes; use whichever matches how you manage the
realm.

| Route | What to do |
| --- | --- |
| Admin console | Pick **`ichat-realm`** in the realm selector (top left — the setting on `master` does nothing for your users), then **Realm settings → Themes → Login theme → `intellistream` → Save**. The dropdown only lists themes present on disk, so if `intellistream` is missing, Keycloak did not see the directory — check the path and the permissions above, then restart. |
| Realm import | `"loginTheme": "intellistream"` as a top-level key of the realm object. That is how `keycloak/realm.json` in this repo sets it, so a fresh `kc.sh import` comes up already themed. |
| `kcadm` | `kcadm.sh config credentials --server https://auth.your-domain --realm master --user admin`<br>`kcadm.sh update realms/ichat-realm -s loginTheme=intellistream` |

The realm also supplies the wordmark under the logo: it renders **Realm settings → General → HTML
Display name**, so set that to your own name (`<strong>IntelliStream</strong> Chat` in the bundled
realm) rather than editing the theme.

**Theme caching in production — the part that will confuse you once.** `kc.sh start` caches
resolved themes and compiled FreeMarker templates in memory, and serves theme resources (CSS, the
font, the logo) with `Cache-Control: max-age=2592000` — thirty days. So after you edit a theme on a
production server:

- **Restart Keycloak.** Nothing short of that clears the theme and template caches.
- **Expect stale CSS in browsers that already loaded the page.** The 30-day max-age is on the
  client, and a restart cannot reach it. Rename the changed file (`intellistream.css` →
  `intellistream.2.css`, updated in `theme.properties`) so the URL changes; that is the only
  reliable invalidation. Hard-reloading works for you and not for your users.
- Do **not** solve this by turning the caches off in production. `--spi-theme-cache-themes=false
  --spi-theme-cache-templates=false --spi-theme-static-max-age=-1` are development settings, and
  they are what `docker-compose.yml` passes to `start-dev` so a theme edit shows up on a browser
  reload. (`start-dev` already defaults to them; the compose file states them because that default
  is invisible from the file and disappears the moment the command becomes `start`.) On a real
  server they mean re-reading and re-compiling the login page from disk on every request.

A note on the theme itself, if you plan to fork it: it overrides exactly one FreeMarker template
(`footer.ftl`, which Keycloak ships empty precisely so themes can fill it in) and does everything
else through `theme.properties` and one stylesheet. Keep it that way. A theme that copies
`login.ftl` or `template.ftl` keeps rendering your copy after an upgrade changes the originals, and
the failure mode is silent — a new required action or credential type simply stops appearing.

## 3. Install the app

Two scripts do everything from here. Both are idempotent and both take `--dry-run`, which prints
what they would do and changes nothing — worth running first.

```bash
# Installs Java, PostgreSQL (unless --skip-postgres), the service account and layout,
# the env file, the hardened systemd unit; then starts and health-checks the service.
sudo scripts/install-almalinux.sh \
  --issuer-uri https://auth.your-domain/realms/ichat-realm \
  --client-secret -              # '-' reads the secret from stdin, keeping it out of argv/history

# Then, if `getenforce` says Enforcing:
sudo scripts/selinux-harden.sh
```

**Neither script touches your reverse proxy.** That is deliberate — it is a separate concern with
its own decisions, and it has its own guide (`frontend.md`, step 6 below). The app ends up
listening on `127.0.0.1:8080` and nothing outside the host can reach it until you put a proxy in
front.

`--help` on either script lists the knobs: alternate paths, an external database
(`--skip-postgres`), a pre-built jar (`--jar`), heap size, listen address and port.

### What the installer does, if you'd rather do it by hand

```bash
./gradlew bootJar                                     # JS/CSS bundles + executable jar
sudo groupadd --system intellistream-chat
sudo useradd --system --gid intellistream-chat --home-dir /opt/intellistream-chat \
             --shell /usr/sbin/nologin intellistream-chat
sudo install -d -o root -g intellistream-chat -m 0750 /etc/intellistream-chat
sudo install -d -o intellistream-chat -g intellistream-chat -m 0750 \
     /opt/intellistream-chat /opt/intellistream-chat/data
sudo install -o root -g intellistream-chat -m 0640 \
     build/libs/intellistream-chat-*.jar /opt/intellistream-chat/intellistream-chat.jar
```

`/opt/intellistream-chat/data` is the **only** writable path — attachments, avatars, branding, the
Lucene index and heap dumps all live under it, and the unit's `ReadWritePaths=` names exactly that
directory. Keep the two in agreement; a data directory outside `ReadWritePaths` fails at runtime as
a permission error with a healthy-looking service log.

## 4. Configuration

`/etc/intellistream-chat/env`, mode 0640, owned `root:intellistream-chat`:

```bash
# --- database ---
ICHAT_DB_URL=jdbc:postgresql://localhost:5432/intellistream_chat
ICHAT_DB_USERNAME=ichat_role
ICHAT_DB_PASSWORD=CHANGE-ME

# --- Keycloak ---
KEYCLOAK_ISSUER_URI=https://auth.your-domain/realms/ichat-realm
KEYCLOAK_CLIENT_ID=ichat-client
KEYCLOAK_CLIENT_SECRET=CHANGE-ME

# --- HTTP: loopback only; terminate TLS in the proxy in front (see frontend.md) ---
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080

# --- data directories (all under the unit's single ReadWritePaths) ---
ICHAT_ATTACHMENTS_DIR=/opt/intellistream-chat/data/attachments
ICHAT_AVATARS_DIR=/opt/intellistream-chat/data/avatars
ICHAT_BRANDING_DIR=/opt/intellistream-chat/data/branding
ICHAT_SEARCH_LUCENE_DIR=/opt/intellistream-chat/data/lucene

# --- JVM ---
JAVA_OPTS=-Xms256m -Xmx2g -XX:+UseZGC -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED
```

systemd reads this file itself, so it is not shell: no quoting, no `$` expansion, no trailing
comments after a value.

```bash
sudo chown root:intellistream-chat /etc/intellistream-chat/env
sudo chmod 640 /etc/intellistream-chat/env
```

(Prefer a secret manager? The app has optional Vault/OpenBao support — `ICHAT_VAULT_*`, see
`scripts/seed-vault.sh`.)

## 5. systemd service

There is **one** unit for this project and it lives in the README, under
[Production: systemd + JVM tuning](README.md#production-systemd--jvm-tuning). It is annotated
directive by directive, runs the JVM sandboxed (`ProtectSystem=strict`, `NoNewPrivileges`,
`RestrictNamespaces`, `RestrictAddressFamilies`, an explicit `InaccessiblePaths` list) and scores
**4.6 OK** on `systemd-analyze security`. `scripts/install-almalinux.sh` writes exactly that unit,
so the documented one and the installed one cannot drift.

Earlier revisions of this guide carried a second, weaker unit that disagreed with the README's on
paths and on the environment-file location, so following both in sequence produced a broken
install. Don't reintroduce one — if the unit needs to change, change it in the README and in the
installer's heredoc together.

Paths assume `/opt/intellistream-chat`, a convention rather than a requirement. If you relocate it,
four things move together: `WorkingDirectory`, `ReadWritePaths`, wherever you install the jar, and
the SELinux `fcontext` rule for the data directory (`selinux-harden.sh --data-dir`).

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
