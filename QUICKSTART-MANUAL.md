# IntelliStream Chat — quick start (manual install + systemd)

PostgreSQL, Keycloak and the app installed natively on one Linux host, no containers. For local
development use [`QUICKSTART-COMPOSE.md`](QUICKSTART-COMPOSE.md) instead.

## 1. PostgreSQL

Install PostgreSQL 16+ (18 recommended):

```bash
# RHEL / Fedora / Rocky
sudo dnf install -y postgresql18-server postgresql18
sudo /usr/pgsql-18/bin/postgresql-18-setup initdb
sudo systemctl enable --now postgresql-18

# Debian / Ubuntu
sudo apt install -y postgresql-18
sudo systemctl enable --now postgresql

# macOS (Homebrew)
brew install postgresql@18
brew services start postgresql@18
```

Then create the role and database:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE ichat_role LOGIN PASSWORD 'CHANGE-ME';
CREATE DATABASE intellistream_chat OWNER ichat_role;
SQL

psql "postgresql://ichat_role:CHANGE-ME@localhost:5432/intellistream_chat" -c 'select 1'
```

Flyway creates the schema on first start. No manual DDL.

## 2. Keycloak

Install Keycloak 26 under `/opt/keycloak` — Keycloak needs Java 21+, so the Java 25 install this
guide already assumes is fine:

```bash
KC_VERSION=26.0.5
curl -L https://github.com/keycloak/keycloak/releases/download/${KC_VERSION}/keycloak-${KC_VERSION}.tar.gz \
  | tar -xz -C /opt --transform 's|^keycloak-'"${KC_VERSION}"'|keycloak|'
```

Then either import the bundled realm, or build it by hand.

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
4. **Put realm roles in the ID token.** Clients → `ichat-client` → Client scopes →
   `ichat-client-dedicated` → Add mapper → *By configuration* → **User Realm Role**: name anything,
   Token Claim Name `realm_access.roles`, Multivalued ON, *Add to ID token* ON, *Add to access token*
   ON, *Add to userinfo* ON. Save.

   This is the step that is easy to skip and impossible to notice: Keycloak's default `roles`
   scope puts `realm_access` in the *access* token only, and the browser login reads the *ID*
   token, so without this mapper `ichat-admin` is granted in Keycloak and never seen by the app.
   Nobody is admin, no error anywhere. The bundled `realm.json` ships the mapper (it is the
   `realm roles in id token` entry); a hand-built realm has to add it. To check a realm, Clients →
   `ichat-client` → Client scopes → **Evaluate** → pick a user → *Generated ID token* and look for
   `realm_access.roles`.
5. **Users**: create accounts, give everyone `ichat-user`, and `ichat-admin` to at least one, or
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

### Optional: sign-in through each organisation's own IdP

If more than one company uses your instance, each of them can sign in with the identity provider
they already have (Entra ID, Okta, Google Workspace, a SAML IdP…) while the app keeps talking to
one realm. Keycloak's **Organizations** feature does the routing: a person types their work email
on the login page, Keycloak matches the domain to an organisation, and if that organisation has a
linked IdP with redirect enabled, sends them straight to it. Everyone else — anyone whose domain
matches nothing — gets the ordinary username/password form, exactly as before. Nothing changes in
the app or its env file; the app only ever sees `ichat-realm`.

The bundled `realm.json` already has the feature on. On a hand-built realm, turn it on first:
**Realm settings → General → Organizations** → ON → Save. An **Organizations** entry appears in
the left menu.

Before the first organisation, two things on the realm itself, both of which an imported
`realm.json` already has and a hand-built realm may not:

- **Realm roles must be in the ID token** — step 4 of *Or build it by hand* above. Brokered
  accounts are ordinary realm users, and their `ichat-admin` reaches the app the same way
  everyone else's does: through `realm_access.roles` on the ID token. Without that mapper the
  role is granted in Keycloak and never seen. Check with Clients → `ichat-client` → Client scopes
  → **Evaluate** → *Generated ID token*.
- **`ichat-user` in `default-roles-ichat-realm`** (README, *Enabling user registration*), so a
  brokered account gets it at first login like a self-registered one.

Then, once per organisation:

1. **Identity providers → Add provider** — pick the type (*OpenID Connect v1.0*, *SAML v2.0*, or
   one of the named ones), give it an alias you will recognise in a list (`acme-entra`), and fill
   in what the organisation's IdP admin gives you: discovery URL or metadata, client id and secret.
   Copy the *Redirect URI* Keycloak shows on that page back to them — it is what they must
   register on their side; its shape is
   `https://<keycloak-host>/realms/ichat-realm/broker/<alias>/endpoint`, so the alias is baked in
   and the host is the one browsers reach Keycloak at. Keep **Hide on login page** in mind for
   step 4; it is fine to leave off here.
2. On that provider, **Trust Email** → ON. It marks brokered accounts' emails verified, which is
   what lets the app match a person to the account they already had under a previous subject
   (README, `ICHAT_IDENTITY_LINK_BY_VERIFIED_EMAIL`) — without it, moving an existing user base
   into this realm gives everyone a second account, and the first symptom is a handle with a
   numeric suffix. While there, **Advanced settings → Pass login_hint** → ON, so the email typed
   on the first screen is prefilled at the IdP instead of asked for again.
3. **Organizations → Create organization** — Name `Acme`, Alias `acme`, and under **Domains** every
   email domain the organisation signs in with (`acme.com`, `acme.co.uk`). Save.
4. Open the organisation → **Identity providers** tab → **Link identity provider**. Choose the
   provider from step 1, set **Domain** to one of the domains from step 3, and turn on **Redirect
   when email domain matches**. Turn on **Hide on login page** as well unless you want an "Acme"
   button on the shared login page for everyone to see.
5. Test with an address in that domain. The login page asks for the email first, then either
   redirects to the IdP or shows the password field. If the redirect does not happen, check step 4
   before anything else — the org, the domain and the link are three separate things and the
   redirect needs all three.

What happens on the far side of that redirect is Keycloak's normal *first broker login*: the
account is created in `ichat-realm`, added as a member of the organisation, and given whatever is
in **default-roles-ichat-realm** — so keep `ichat-user` in that set, and never `ichat-admin`. From
the app's point of view a brokered account is just another user.

**Roles for brokered accounts.** Membership of an organisation grants nothing — Keycloak
Organizations has no per-organisation roles — and the IdP's own roles and groups do not cross the
broker on their own. So `ichat-admin` is either granted by hand as before (**Users → Role
mappings**), or delegated to the organisation's directory with a mapper on the provider:
Identity providers → the provider → **Mappers → Add mapper**, type **Advanced Claim to Role**
(SAML: *Advanced Attribute to Role*), the claim and value the IdP sends for its admins (an Entra
group id in `groups`, an Okta group name — or, when the upstream is another Keycloak realm that
already carries `ichat-admin`, claim `realm_access.roles` value `ichat-admin`), role `ichat-admin`,
and **Sync mode override → Force**. Force is the part that matters: the default *import* runs a
mapper once, at first login, and never again, so a person removed from the group upstream would
stay an administrator here forever; Force re-evaluates on every login and takes the role away when
the claim no longer matches. Never put `ichat-admin` in a *Hardcoded Role* mapper — that makes
the whole company administrators. A *Hardcoded Role* mapper is fine for `ichat-user`, or a marker
role of your own, if you would rather not rely on the realm's default set.

Two things worth knowing:

- Once the first organisation exists, the login page becomes *identity-first*: email on one
  screen, password on the next (with the feature on but no organisation yet, the classic form
  stays). That is by design — the domain has to be known before the password field can be the
  right one — and the bundled theme handles it without changes because it inherits every template
  from the base theme (see the theme note above). If you have replaced the realm's
  **browser** authentication flow with a custom one, add the *Organization* step to it, or the
  domain lookup never runs; the built-in flow gets it automatically.
- To rehearse this without a real corporate IdP, make a second realm in the same Keycloak
  (`acme-idp`, one client `ichat-broker` with *Client authentication* ON and Valid redirect URI
  `https://your-keycloak/realms/ichat-realm/broker/acme-idp/endpoint`, one user with an
  `@acme.com` email) and add it to `ichat-realm` as an *OpenID Connect v1.0* provider with
  discovery URL `https://your-keycloak/realms/acme-idp/.well-known/openid-configuration`, that
  client id and its secret. It behaves exactly like an external IdP for the purpose of steps 2–4,
  and you can delete the realm afterwards. If sign-out then errors with *invalid redirect uri* on
  the `acme-idp` side, that client also needs
  `…/realms/ichat-realm/broker/acme-idp/endpoint/logout_response` in its Valid post logout
  redirect URIs — Keycloak logs the person out of the IdP they came in through and needs a way
  back — or clear the provider's *Logout URL* in `ichat-realm` to keep sign-out local.

## 3. Install the app

### Service account and directories

Both install paths need these. `install-almalinux.sh` runs the same commands itself and skips
any that are already done, so doing them by hand first is harmless — and on anything that is not
AlmaLinux / Rocky / RHEL, this is the only way.

```bash
sudo groupadd --system intellistream-chat
sudo useradd --system --gid intellistream-chat --home-dir /opt/intellistream-chat \
             --shell /usr/sbin/nologin --comment "IntelliStream Chat" intellistream-chat

sudo install -d -o root -g intellistream-chat -m 0750 /etc/intellistream-chat
sudo install -d -o intellistream-chat -g intellistream-chat -m 0750 \
     /opt/intellistream-chat /opt/intellistream-chat/data \
     /opt/intellistream-chat/data/{attachments,avatars,branding,link-previews,lucene,heapdumps}
```

A system account with no shell and no password: nothing can log in as it. `useradd --system`
does not create the home directory, which is why `install -d` follows.
`/opt/intellistream-chat/data` is the **only** writable path — attachments, avatars, branding,
link-preview pictures, the Lucene index and heap dumps all live under it, and it must match the
unit's `ReadWritePaths=`.
The app creates its own subdirectories on first use; `heapdumps` is the one it never touches, and
the JVM will not create it either, so make it now.

### AlmaLinux / Rocky / RHEL: the installer

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

### Any distro: by hand

```bash
./gradlew bootJar
sudo install -o root -g intellistream-chat -m 0640 \
     build/libs/intellistream-chat-*.jar /opt/intellistream-chat/intellistream-chat.jar
```

Then write the env file (step 4) and the unit (step 5) yourself.

Neither path touches your reverse proxy. The app listens on `127.0.0.1:8080` and is unreachable
from outside the host until you put one in front (step 6).

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
ICHAT_LINK_PREVIEWS_DIR=/opt/intellistream-chat/data/link-previews
ICHAT_SEARCH_LUCENE_DIR=/opt/intellistream-chat/data/lucene

JAVA_OPTS=-Xms512m -Xmx1g -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/intellistream-chat/data/heapdumps -XX:+UseStringDeduplication -Duser.timezone=UTC --enable-native-access=ALL-UNNAMED
```

```bash
sudo chown root:intellistream-chat /etc/intellistream-chat/env
sudo chmod 640 /etc/intellistream-chat/env
```

systemd reads this file itself: no quoting, no `$` expansion, no trailing comments.

### Optional: secrets from OpenBao

The two lines above worth not having in a file are `ICHAT_DB_PASSWORD` and
`KEYCLOAK_CLIENT_SECRET`. The app can read them from one KV-v2 record in
[OpenBao](https://openbao.org/) instead: `VaultEnvironmentPostProcessor` fetches the record at
`ICHAT_VAULT_PATH` before Spring reads any datasource or OAuth property and lays it over the
environment, so a key present in OpenBao wins and a key absent from it leaves the env file in
charge. It recognises ten keys — `db.url`, `db.username`, `db.password`, `db.replica-enabled`,
`db.replica-url`, `db.replica-username`, `db.replica-password`, `keycloak.client-id`,
`keycloak.client-secret`, `keycloak.issuer-uri` — and ignores everything else, so tuning stays in
the env file. (OpenBao is a fork of HashiCorp Vault and speaks the same API, which is why the
app's property names say `vault`; the recipe below is written for `bao`.)

The app reads OpenBao exactly once, at boot, and authenticates either with a token you hold or —
the shape you want on a server — with an **AppRole**: a `role_id`/`secret_id` pair that can read
one path and nothing else. With AppRole the app logs in, reads the record with the token it was
given, and revokes that token again; it never appears in the environment, the log, or a file.

**On OpenBao**, with an admin token. The app gets a KV-v2 mount of its own, `intellistream-chat/`,
with the record at `config` — a store nothing else lives in, so the policy can be scoped to the
mount and a `bao kv list` of it is the whole inventory.

```bash
export BAO_ADDR=https://vault.example.org:8200

# The mount, the record, and a policy that reads only that record.
bao secrets enable -path=intellistream-chat kv-v2
bao kv put -mount=intellistream-chat config \
    db.password='CHANGE-ME' \
    keycloak.client-secret='CHANGE-ME'
bao policy write intellistream-chat - <<'HCL'
path "intellistream-chat/data/config" {
  capabilities = ["read"]
}
HCL

# An AppRole bound to that policy. Tokens are short-lived because the app reads once at boot and
# revokes what it minted; the CIDRs are the app host's address as OpenBao sees it, so a leaked
# pair is useless elsewhere.
bao auth enable approle
bao write auth/approle/role/intellistream-chat \
    token_policies=intellistream-chat \
    token_ttl=5m token_max_ttl=5m \
    secret_id_bound_cidrs=203.0.113.10/32 token_bound_cidrs=203.0.113.10/32

bao read  -field=role_id      auth/approle/role/intellistream-chat/role-id
bao write -field=secret_id -f auth/approle/role/intellistream-chat/secret-id
```

The policy path is `intellistream-chat/data/config` even though `kv put` never mentions `data/`
— KV-v2 inserts it on the wire, and a policy written on the CLI path grants nothing. The
`secret_id` does not expire and has no use limit by default; rotate it by running the last command
again and updating the app host.

**On the app host.** In `/etc/intellistream-chat/env`, delete `ICHAT_DB_PASSWORD` and
`KEYCLOAK_CLIENT_SECRET` and add:

```bash
ICHAT_VAULT_ENABLED=true
ICHAT_VAULT_URI=https://vault.example.org:8200
ICHAT_VAULT_PATH=intellistream-chat/config
ICHAT_VAULT_ROLE_ID=<role_id>
ICHAT_VAULT_SECRET_ID=<secret_id>
```

That is the whole change; the unit from step 5 is untouched. There is no `ICHAT_VAULT_TOKEN` in
AppRole mode — setting both is refused as ambiguous, and so is enabling with neither.
`ICHAT_VAULT_APPROLE_PATH` exists for an AppRole mount enabled under another name; the default is
`approle`.

`ICHAT_VAULT_PATH` is `<mount>/<key>`; the part before the first slash is the mount, and a value
with no slash is a key under `secret/` (the default, `intellistream-chat`, is
`secret/data/intellistream-chat`). If you are reading the path off the OpenBao UI, the URL is
`/ui/vault/secrets/<mount>/kv/<key>` — so `…/secrets/intellistream-chat/kv/config` is
`intellistream-chat/config`, and `ICHAT_VAULT_URI` is the origin alone, without `/ui/…`. If OpenBao
uses a private CA, add it to the system trust store (`update-ca-trust` /
`update-ca-certificates`); the packaged JDK reads it from there.

<details>
<summary>Keeping the pair out of the env file</summary>

The env file is `root:intellistream-chat 0640` — readable by the service for its whole life. If you
would rather the pair were root-only on disk, hand it over as systemd credentials instead: the
files stay `0600 root`, and systemd copies them at start into a per-run directory only the service
user can read. (The app itself cannot read `/etc/intellistream-chat/bao-*` — that is the point —
so the `_FILE` variables point at the copies, not the originals.)

```bash
# Paste the value, Enter, Ctrl-D. Nothing lands in shell history; the umask makes it 0600.
sudo sh -c 'umask 077; cat > /etc/intellistream-chat/bao-role-id'
sudo sh -c 'umask 077; cat > /etc/intellistream-chat/bao-secret-id'
sudo install -d /etc/systemd/system/intellistream-chat.service.d
```

`/etc/systemd/system/intellistream-chat.service.d/bao.conf` — a drop-in, so the unit in step 5
stays byte-identical to what the installer writes:

```ini
[Service]
LoadCredential=bao-role-id:/etc/intellistream-chat/bao-role-id
LoadCredential=bao-secret-id:/etc/intellistream-chat/bao-secret-id
```

Then in the env file, instead of `ICHAT_VAULT_ROLE_ID` and `ICHAT_VAULT_SECRET_ID`:

```bash
ICHAT_VAULT_ROLE_ID_FILE=/run/credentials/intellistream-chat.service/bao-role-id
ICHAT_VAULT_SECRET_ID_FILE=/run/credentials/intellistream-chat.service/bao-secret-id
```

(`$CREDENTIALS_DIRECTORY` for a system service is always `/run/credentials/<unit name>`.) A
trailing newline in the files is fine; a value *and* its `_FILE` set together is refused.
`systemctl daemon-reload` before the restart. The `role_id` is not secret in the way the
`secret_id` is — mixing, `ICHAT_VAULT_ROLE_ID` in the env file and only the `secret_id` as a
credential, is equally valid.
</details>

Once the unit from step 5 is in place:

```bash
sudo systemctl restart intellistream-chat
journalctl -u intellistream-chat | grep -i vault
# Vault / OpenBao secret backend enabled: uri=https://vault.example.org:8200, path=intellistream-chat/config, auth=AppRole (mount 'approle', role-id …)
# Vault configuration loaded successfully: 2 recognised key(s) at path intellistream-chat/config [db.password, keycloak.client-secret] → overriding [spring.datasource.password, spring.security.oauth2.client.registration.keycloak.client-secret]
```

Two lines, key names only, never values. The second one also lists any key in the record it did
*not* recognise, which is how a typo like `db.passwd` shows up — otherwise it would be silently
nothing. Failure is deliberately loud: enabled with no URI, no
credential, or both a token and an AppRole; an unreachable OpenBao; or a refused login — each is a
startup crash carrying OpenBao's own error text, not a fallback to the env file. The one soft case
is a record that fetched cleanly but held none of the ten keys — that logs a warning listing what
it expected and carries on with the environment, since it is far more likely a typo in the path
than an outage.

## 5. systemd service

`/etc/systemd/system/intellistream-chat.service` — or let `install-almalinux.sh` write it, which it
does verbatim, so the documented unit and the installed one cannot drift. Every directive is
annotated. Tested as-is on AlmaLinux 10.2 with SELinux enforcing; `systemd-analyze security` scores
it **4.6 OK**.

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

# === NUMA =============================================================
# Only on a host with more than one NUMA node — check with `lscpu | grep -i '^NUMA'`. With two or
# more, the JVM can run on one node while its heap sits on another and pay interconnect latency on
# every access. The heap fits inside a single node, so pin both to the same one and uncomment:
# AllowedCPUs=0-11
# AllowedMemoryNodes=0
# (Directives rather than wrapping ExecStart in numactl: an unsupported directive is logged and
# ignored, while a missing numactl binary means the service does not start at all.)

[Install]
WantedBy=multi-user.target
```

`JAVA_OPTS` lives in the env file from step 4; see
[JVM tuning](https://intellistream-datahub.github.io/intellistream-chat/docs.html#config-service-jvm)
in the full manual for what each flag buys, including the G1-vs-ZGC tradeoff. Every tunable the
application reads is listed in [`.env.example`](.env.example).

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now intellistream-chat
systemctl status intellistream-chat
journalctl -u intellistream-chat -f     # Flyway migrations, then Tomcat on :8080
```

Relocating from `/opt/intellistream-chat`? Four things move together: `WorkingDirectory`,
`ReadWritePaths`, the jar, and the SELinux fcontext rule (`selinux-harden.sh --data-dir`).

## 6. Calls (TURN relay)

Optional — skip this and the call buttons in a direct message simply don't render.
`CallProperties.isConfigured()` requires `ICHAT_TURN_URLS` and `ICHAT_TURN_SECRET` both set, so an
unconfigured deployment fails closed rather than offering a control with no media path behind it.

The app is never in the media path: it relays SDP and ICE candidates over its own WebSocket, and
the audio/video goes through [coturn](https://github.com/coturn/coturn), which forwards encrypted
UDP without being able to read it.

### Install and configure coturn

```bash
# AlmaLinux/RHEL (needs EPEL)
sudo dnf install -y epel-release && sudo dnf install -y coturn
# Debian/Ubuntu
sudo apt install -y coturn
```

Generate a secret — this is a real, durable credential, not something to regenerate per boot.
Treat it like `ICHAT_DB_PASSWORD` above: generate once, store it, rotate deliberately.

```bash
openssl rand -base64 32
```

`/etc/coturn/turnserver.conf` (RHEL family) or `/etc/turnserver.conf` (Debian/Ubuntu):

```ini
listening-port=3478
tls-listening-port=5349
listening-ip=0.0.0.0
external-ip=YOUR.PUBLIC.IP          # the address browsers will reach this host at
relay-ip=YOUR.PUBLIC.IP
min-port=49160
max-port=49200                       # widen this if you expect many concurrent calls

realm=chat.example.com               # your actual domain

fingerprint
use-auth-secret
static-auth-secret=PASTE_THE_SECRET_FROM_ABOVE

# Real TLS — reuse the same cert your reverse proxy uses, or issue a dedicated one.
cert=/etc/letsencrypt/live/chat.example.com/fullchain.pem
pkey=/etc/letsencrypt/live/chat.example.com/privkey.pem

no-cli
no-multicast-peers
denied-peer-ip=169.254.0.0-169.254.255.255
```

Two things worth knowing before you enable it:

- **The TLS key needs to be readable by whatever user runs coturn** — most distro packages run it
  as root by default, but if yours doesn't, Let's Encrypt's `privkey.pem` is `0600 root` and needs
  a deploy hook to copy+chown a readable copy.
- **Open the firewall.** Nothing here does that for you (`install-almalinux.sh` doesn't touch
  firewalld, and neither does this):
  ```bash
  sudo firewall-cmd --permanent --add-port=3478/udp --add-port=3478/tcp \
                     --add-port=5349/tcp --add-port=49160-49200/udp
  sudo firewall-cmd --reload
  ```

```bash
# Debian/Ubuntu only — the package ships disabled by default
sudo sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn

sudo systemctl enable --now coturn
```

### Point the app at it

In `/etc/intellistream-chat/env`, add the matching pair — the UDP entry is the fast path, the
TURNS entry is what survives a firewall that only allows outbound HTTPS:

```bash
ICHAT_TURN_URLS=turn:chat.example.com:3478?transport=udp,turns:chat.example.com:5349?transport=tcp
ICHAT_TURN_SECRET=PASTE_THE_SAME_SECRET_FROM_ABOVE
```

`ICHAT_CALLS_VIDEO` and `ICHAT_CALLS_ENABLED` both already default to `true` — nothing else to add.

```bash
sudo systemctl restart intellistream-chat
```

Sign in as two different users, open a direct message, press the call button. If it rings but
never connects, the app and coturn disagree about the secret, or coturn isn't reachable on the
port(s) above — every failure here looks like a network problem, because that's exactly what a
mismatched TURN credential produces from the browser's side.

**If your users are behind a network that allows nothing but outbound port 443** — a common
corporate-firewall policy — 3478 and 5349 above won't be reachable at all, and TURN needs to share
port 443 with your reverse proxy instead. See
[`frontend.md`](frontend.md#calls-through-the-same-port-443-sni-routing-for-turn) for the SNI
routing that makes that work without a second IP address.

## 7. Reverse proxy + smoke test

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
