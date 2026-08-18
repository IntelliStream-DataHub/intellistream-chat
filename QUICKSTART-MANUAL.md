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
     /opt/intellistream-chat/data/{attachments,avatars,branding,lucene,heapdumps}
```

A system account with no shell and no password: nothing can log in as it. `useradd --system`
does not create the home directory, which is why `install -d` follows.
`/opt/intellistream-chat/data` is the **only** writable path — attachments, avatars, branding, the
Lucene index and heap dumps all live under it, and it must match the unit's `ReadWritePaths=`.
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
[JVM options](README.md#jvm-options) in the README for what each flag buys. Every tunable the
application reads is listed in [`.env.example`](.env.example).

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now intellistream-chat
systemctl status intellistream-chat
journalctl -u intellistream-chat -f     # Flyway migrations, then Tomcat on :8080
```

Relocating from `/opt/intellistream-chat`? Four things move together: `WorkingDirectory`,
`ReadWritePaths`, the jar, and the SELinux fcontext rule (`selinux-harden.sh --data-dir`).

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
