#!/usr/bin/env bash
#
# IntelliStream Chat — application installer for AlmaLinux / Rocky / RHEL 9 and 10.
#
# Installs the application and nothing else: a Java runtime, the service account,
# the directory layout, the environment file, the hardened systemd unit.
#
# THREE THINGS THIS DELIBERATELY DOES NOT DO
#
#   PostgreSQL. Your database is yours — managed service, existing cluster, separate
#   host, whatever. A script that installs and initdb's a database server has opinions
#   about backups, tuning, authentication and upgrades that it has no business having.
#   Create the role and database yourself (QUICKSTART-MANUAL.md step 1) and point this
#   at them. Flyway builds the schema on first start.
#
#   Keycloak. A production identity provider needs its own database, hostname and TLS,
#   and most sites already have one. Create the realm and client
#   (QUICKSTART-MANUAL.md step 2) and pass --issuer-uri.
#
#   The reverse proxy and TLS. Separate concern, separate decisions, separate guide:
#   frontend.md. The app ends up on 127.0.0.1:8080, which is exactly what belongs
#   behind a proxy, and nothing outside the host can reach it until you set one up.
#
# What it does instead is check that the database and the issuer are actually reachable
# before writing anything, so the common misconfigurations surface here rather than as a
# service that fails to start for reasons buried in a stack trace.
#
# Re-running is safe: every step checks before it acts.
#
# Copyright 2026 IntelliStream AS — Apache License 2.0.

set -euo pipefail

# ---------------------------------------------------------------- defaults ----
APP_NAME="intellistream-chat"
APP_USER="intellistream-chat"
APP_GROUP="intellistream-chat"
APP_HOME="/opt/intellistream-chat"
ETC_DIR="/etc/intellistream-chat"
ENV_FILE="${ETC_DIR}/env"
UNIT_FILE="/etc/systemd/system/${APP_NAME}.service"
JAVA_PKG="java-25-openjdk-headless"
JAVA_BIN=""

DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="intellistream_chat"
DB_USER="ichat_role"
DB_PASSWORD="${ICHAT_DB_PASSWORD:-}"
DB_URL=""                          # derived from host/port/name unless given

ISSUER_URI=""
CLIENT_ID="ichat-client"
CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-}"

JAR_SRC=""
HEAP_MAX="1g"
BIND_ADDRESS="127.0.0.1"
BIND_PORT="8080"

START_SERVICE=1
SKIP_CHECKS=0
DRY_RUN=0
APP_HOME_GIVEN=0

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ------------------------------------------------------------------ output ----
c_red=$'\033[31m'; c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
[[ -t 1 ]] || { c_red=""; c_grn=""; c_yel=""; c_dim=""; c_off=""; }
step() { printf '\n%s==>%s %s\n' "$c_grn" "$c_off" "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '%s !! %s%s\n' "$c_yel" "$*" "$c_off" >&2; }
die()  { printf '%serror:%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }
run()  {
  if (( DRY_RUN )); then printf '    %s[dry-run]%s %s\n' "$c_dim" "$c_off" "$*"; return 0; fi
  "$@"
}

usage() {
  cat <<EOF
IntelliStream Chat — application installer (AlmaLinux / Rocky / RHEL)

Installs the app, its service account and a hardened systemd unit. It does NOT
install PostgreSQL, Keycloak or a reverse proxy — set those up first (see
QUICKSTART-MANUAL.md steps 1 and 2, and frontend.md).

Usage: sudo $0 --issuer-uri URI [options]

Required:
  --issuer-uri URI        Keycloak realm issuer of an existing realm, e.g.
                          https://auth.example.com/realms/ichat-realm

Secrets — pass by environment (preferred; keeps them out of argv and shell
history), by flag, or leave unset to be prompted on a terminal:
  ICHAT_DB_PASSWORD       or  --db-password PASS
  KEYCLOAK_CLIENT_SECRET  or  --client-secret SECRET

Database (must already exist and accept connections from this host):
  --db-host HOST          default: ${DB_HOST}
  --db-port PORT          default: ${DB_PORT}
  --db-name NAME          default: ${DB_NAME}
  --db-user USER          default: ${DB_USER}
  --db-url JDBC_URL       full JDBC URL; overrides host/port/name. Use for
                          options the parts can't express, e.g. ?sslmode=require

Application:
  --jar PATH              Pre-built jar. Default: build from ${REPO_ROOT}
                          with ./gradlew bootJar.
  --client-id ID          OIDC client id (default: ${CLIENT_ID}).
  --heap SIZE             JVM max heap (default: ${HEAP_MAX}).
  --bind ADDR             Listen address (default: ${BIND_ADDRESS} — keep it on
                          loopback and put a reverse proxy in front).
  --port PORT             Listen port (default: ${BIND_PORT}).
  --app-home DIR          Install prefix: the jar, and the data/ tree that holds
                          attachments, avatars, branding and the Lucene index.
                          Default ${APP_HOME}. Asked for interactively when not
                          given. Put it on the filesystem you actually want the
                          data on, e.g. a ZFS dataset: /tank/intellistream-chat
  --etc-dir DIR           Config directory (default: ${ETC_DIR}). Kept under /etc
                          by default because that is already labelled etc_t for
                          SELinux; move it and selinux-harden.sh will relabel.
  --no-start              Install everything but leave the service stopped.
  --skip-checks           Don't test database / issuer reachability.
  --dry-run               Print what would happen; change nothing.
  -h, --help              This text.

Afterwards: run scripts/selinux-harden.sh if SELinux is enforcing, then set up
the reverse proxy following frontend.md.
EOF
}

# ------------------------------------------------------------------- args -----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --issuer-uri)    ISSUER_URI="${2:?}"; shift 2 ;;
    --client-id)     CLIENT_ID="${2:?}"; shift 2 ;;
    --client-secret) CLIENT_SECRET="${2:?}"; shift 2 ;;
    --db-host)       DB_HOST="${2:?}"; shift 2 ;;
    --db-port)       DB_PORT="${2:?}"; shift 2 ;;
    --db-name)       DB_NAME="${2:?}"; shift 2 ;;
    --db-user)       DB_USER="${2:?}"; shift 2 ;;
    --db-password)   DB_PASSWORD="${2:?}"; shift 2 ;;
    --db-url)        DB_URL="${2:?}"; shift 2 ;;
    --jar)           JAR_SRC="${2:?}"; shift 2 ;;
    --heap)          HEAP_MAX="${2:?}"; shift 2 ;;
    --bind)          BIND_ADDRESS="${2:?}"; shift 2 ;;
    --port)          BIND_PORT="${2:?}"; shift 2 ;;
    --app-home)      APP_HOME="${2:?}"; APP_HOME_GIVEN=1; shift 2 ;;
    --etc-dir)       ETC_DIR="${2:?}"; shift 2 ;;
    --no-start)      START_SERVICE=0; shift ;;
    --skip-checks)   SKIP_CHECKS=1; shift ;;
    --dry-run)       DRY_RUN=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *)               die "unknown option: $1  (try --help)" ;;
  esac
done
DB_URL="${DB_URL:-jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}}"

# Where the application and its data live. Asked rather than assumed: the data tree
# grows without bound (attachments, avatars, the Lucene index), so it usually belongs
# on a chosen filesystem — a ZFS dataset, an LVM volume, a separate disk — not on
# whatever /opt happens to sit on.
if (( ! APP_HOME_GIVEN )) && (( ! DRY_RUN )) && [[ -t 0 ]]; then
  printf '\n%s==>%s Install location\n' "$c_grn" "$c_off"
  printf '    The jar and the data tree (attachments, avatars, branding, search index)\n'
  printf '    go here. On ZFS or a separate volume, give the dataset path.\n'
  read -rp "    Install prefix [${APP_HOME}]: " _reply
  [[ -n "${_reply:-}" ]] && APP_HOME="${_reply%/}"
fi

[[ "$APP_HOME" = /* ]] || die "install prefix must be an absolute path: ${APP_HOME}"
case "$APP_HOME" in
  /|/usr|/etc|/var|/home|/root|/boot|/bin|/sbin|/lib|/lib64|/proc|/sys|/dev)
    die "refusing to install into ${APP_HOME}" ;;
esac
_parent="$(dirname "$APP_HOME")"
if [[ ! -d "$_parent" ]]; then
  # Fatal for a real run, a warning for a preview: --dry-run should be able to show
  # the plan for a host you have not provisioned yet.
  if (( DRY_RUN )); then
    warn "parent directory ${_parent} does not exist on this host (dry run, continuing)"
  else
    die "parent directory does not exist: ${_parent}
Create it first, then re-run. For a ZFS dataset:
  zfs create -o mountpoint=${APP_HOME} tank/intellistream-chat"
  fi
fi

ENV_FILE="${ETC_DIR}/env"
UNIT_FILE="/etc/systemd/system/${APP_NAME}.service"

# --------------------------------------------------------------- preflight ----
step "Preflight"
(( DRY_RUN )) || [[ $EUID -eq 0 ]] || die "must run as root (use sudo)"

[[ -r /etc/os-release ]] || die "cannot read /etc/os-release"
# shellcheck disable=SC1091
. /etc/os-release
case "${ID}${ID_LIKE:-}" in
  *rhel*|*fedora*|almalinux*|rocky*) info "OS: ${PRETTY_NAME}" ;;
  *) warn "This installer targets the RHEL family; ${PRETTY_NAME:-unknown} is untested."
     warn "The Java package name is the part most likely to differ." ;;
esac
command -v dnf >/dev/null || die "dnf not found — this installer needs a dnf-based distro"

if (( ! DRY_RUN )); then
  [[ -n "$ISSUER_URI" ]] || die "--issuer-uri is required (see --help)"
fi
[[ -z "$ISSUER_URI" || "$ISSUER_URI" =~ ^https?://.+/realms/.+ ]] \
  || die "--issuer-uri does not look like a Keycloak realm URL: $ISSUER_URI"
if [[ "$ISSUER_URI" == http://* ]]; then
  warn "Issuer is plain HTTP. Fine for a lab; in production the OIDC flow carries"
  warn "tokens and must be HTTPS end to end."
fi
if [[ "$BIND_ADDRESS" != "127.0.0.1" && "$BIND_ADDRESS" != "localhost" ]]; then
  warn "Binding to ${BIND_ADDRESS} exposes the JVM directly. This installer does not"
  warn "configure TLS — see frontend.md and put a reverse proxy in front instead."
fi

# Secrets: environment first, then flag, then prompt. Never echoed; only in argv if
# the caller chose to put them there.
prompt_secret() {
  local var="$1" label="$2"
  [[ -n "${!var}" ]] && return 0
  if (( DRY_RUN )); then printf -v "$var" '%s' "dry-run-placeholder"; return 0; fi
  [[ -t 0 ]] || die "${label} is not set and stdin is not a terminal.
Set it in the environment (${var}=…) or pass the matching flag."
  local v=""
  read -rsp "    ${label}: " v; echo
  [[ -n "$v" ]] || die "${label} must not be empty"
  printf -v "$var" '%s' "$v"
}
prompt_secret DB_PASSWORD   "Database password for ${DB_USER}"
prompt_secret CLIENT_SECRET "OIDC client secret for ${CLIENT_ID}"

# ------------------------------------------------------ prerequisite checks ----
if (( SKIP_CHECKS )) || (( DRY_RUN )); then
  step "Prerequisite checks (skipped)"
else
  step "Prerequisite checks"
  info "This installer does not create the database or the realm — it verifies them."

  # TCP reachability, without requiring a psql client on this host.
  probe_tcp() {
    (exec 3<>"/dev/tcp/${1}/${2}") 2>/dev/null && { exec 3<&- 3>&-; return 0; }
    return 1
  }
  if [[ "$DB_URL" =~ //([^:/]+):([0-9]+)/ ]]; then
    _h="${BASH_REMATCH[1]}"; _p="${BASH_REMATCH[2]}"
    if probe_tcp "$_h" "$_p"; then
      info "database endpoint ${_h}:${_p} is reachable"
    else
      warn "cannot open a TCP connection to ${_h}:${_p}."
      warn "PostgreSQL must already be running, with the '${DB_NAME}' database and the"
      warn "'${DB_USER}' role — see QUICKSTART-MANUAL.md step 1. Continuing, but the"
      warn "service will not start until the database answers."
    fi
  else
    info "custom JDBC URL — skipping the endpoint probe"
  fi

  # If a psql client happens to be here, prove the credentials as well.
  if command -v psql >/dev/null 2>&1 && [[ "$DB_URL" =~ ^jdbc:postgresql://(.+)$ ]]; then
    if PGPASSWORD="$DB_PASSWORD" psql "postgresql://${DB_USER}@${BASH_REMATCH[1]}" \
         -tAc 'select 1' >/dev/null 2>&1; then
      info "database credentials accepted"
    else
      warn "could not authenticate to the database as '${DB_USER}'. Check the password,"
      warn "and that pg_hba.conf allows connections from this host."
    fi
  fi

  # Spring resolves OIDC discovery during startup; a failure there aborts the whole
  # application context, and the stack trace does not make the cause obvious.
  if command -v curl >/dev/null 2>&1; then
    if curl -sfL --max-time 10 "${ISSUER_URI%/}/.well-known/openid-configuration" >/dev/null 2>&1; then
      info "OIDC discovery document is reachable"
    else
      warn "cannot fetch ${ISSUER_URI%/}/.well-known/openid-configuration."
      warn "The realm must exist and be reachable from this host, or startup fails with"
      warn "'Unable to resolve Configuration with the provided Issuer'."
      warn "See QUICKSTART-MANUAL.md step 2. Continuing."
    fi
  fi
fi

# ------------------------------------------------------------------- java -----
step "Java runtime"
if rpm -q "$JAVA_PKG" >/dev/null 2>&1; then
  info "${JAVA_PKG} already installed"
else
  run dnf install -y "$JAVA_PKG" || die "could not install ${JAVA_PKG}"
fi
JAVA_BIN="$(rpm -ql "$JAVA_PKG" 2>/dev/null | grep -m1 '/bin/java$' || true)"
[[ -n "$JAVA_BIN" ]] || JAVA_BIN="$(command -v java || true)"
(( DRY_RUN )) && JAVA_BIN="${JAVA_BIN:-/usr/lib/jvm/java-25-openjdk/bin/java}"
[[ -n "$JAVA_BIN" ]] || die "installed ${JAVA_PKG} but found no java binary"
info "java: ${JAVA_BIN}"

# ------------------------------------------------------------ service user ----
step "Service account and layout"
if getent group "$APP_GROUP" >/dev/null; then info "group ${APP_GROUP} exists"
else run groupadd --system "$APP_GROUP"; fi
if getent passwd "$APP_USER" >/dev/null; then info "user ${APP_USER} exists"
else
  run useradd --system --gid "$APP_GROUP" --home-dir "$APP_HOME" \
              --shell /usr/sbin/nologin --comment "IntelliStream Chat" "$APP_USER"
fi

# data/ is the single writable tree; the unit's ReadWritePaths matches it exactly.
run install -d -o root        -g "$APP_GROUP" -m 0750 "$ETC_DIR"
run install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "$APP_HOME"
for sub in data data/attachments data/avatars data/branding data/lucene data/heapdumps; do
  run install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "${APP_HOME}/${sub}"
done
info "layout: ${APP_HOME} (data/ writable), ${ETC_DIR} (config)"
if (( ! DRY_RUN )) && command -v findmnt >/dev/null 2>&1; then
  _fs="$(findmnt -no SOURCE,FSTYPE --target "$APP_HOME" 2>/dev/null || true)"
  [[ -n "$_fs" ]] && info "filesystem: ${_fs}   (confirm this is the volume you intended)"
fi

# -------------------------------------------------------------------- jar -----
step "Application jar"
if [[ -z "$JAR_SRC" ]]; then
  [[ -x "${REPO_ROOT}/gradlew" ]] || die "no --jar given and ${REPO_ROOT}/gradlew is missing"
  info "building from ${REPO_ROOT} (a couple of minutes)"
  (( DRY_RUN )) || ( cd "$REPO_ROOT" && ./gradlew --quiet bootJar ) || die "gradle bootJar failed"
  JAR_SRC="$(ls -1t "${REPO_ROOT}"/build/libs/${APP_NAME}-*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)"
  (( DRY_RUN )) && JAR_SRC="${JAR_SRC:-${REPO_ROOT}/build/libs/${APP_NAME}-<version>.jar}"
  [[ -n "$JAR_SRC" ]] || die "build succeeded but no ${APP_NAME}-*.jar under build/libs"
fi
(( DRY_RUN )) || [[ -r "$JAR_SRC" ]] || die "jar not readable: $JAR_SRC"
info "installing $(basename "$JAR_SRC")"
run install -o root -g "$APP_GROUP" -m 0640 "$JAR_SRC" "${APP_HOME}/${APP_NAME}.jar"

# ---------------------------------------------------------------- env file ----
step "Environment file"
if [[ -e "$ENV_FILE" ]]; then
  warn "${ENV_FILE} exists — leaving it untouched."
  warn "Delete it and re-run if you want it regenerated."
elif (( DRY_RUN )); then
  info "[dry-run] write ${ENV_FILE} (0640 root:${APP_GROUP})"
else
  umask 077
  cat > "$ENV_FILE" <<EOF
# IntelliStream Chat — service environment. Read by systemd, not by a shell:
# no quoting, no expansion, no trailing comments after a value.
# Written by scripts/install-almalinux.sh.

# --- database (must already exist; Flyway builds the schema on first start) ---
ICHAT_DB_URL=${DB_URL}
ICHAT_DB_USERNAME=${DB_USER}
ICHAT_DB_PASSWORD=${DB_PASSWORD}

# --- identity (existing Keycloak realm) ---
KEYCLOAK_ISSUER_URI=${ISSUER_URI}
KEYCLOAK_CLIENT_ID=${CLIENT_ID}
KEYCLOAK_CLIENT_SECRET=${CLIENT_SECRET}

# --- HTTP ---
# Loopback on purpose: terminate TLS in a reverse proxy in front. See frontend.md.
SERVER_ADDRESS=${BIND_ADDRESS}
SERVER_PORT=${BIND_PORT}

# --- data directories (all under the unit's single ReadWritePaths) ---
ICHAT_ATTACHMENTS_DIR=${APP_HOME}/data/attachments
ICHAT_AVATARS_DIR=${APP_HOME}/data/avatars
ICHAT_BRANDING_DIR=${APP_HOME}/data/branding
ICHAT_SEARCH_LUCENE_DIR=${APP_HOME}/data/lucene

# --- JVM ---
JAVA_OPTS=-Xms256m -Xmx${HEAP_MAX} -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${APP_HOME}/data/heapdumps -XX:+UseStringDeduplication -Duser.timezone=UTC --enable-native-access=ALL-UNNAMED
EOF
  chown root:"$APP_GROUP" "$ENV_FILE"
  chmod 0640 "$ENV_FILE"
  info "wrote ${ENV_FILE} (0640 root:${APP_GROUP})"
fi

# ------------------------------------------------------------- systemd unit ---
step "systemd unit"
if (( DRY_RUN )); then
  info "[dry-run] write ${UNIT_FILE}"
else
  cat > "$UNIT_FILE" <<EOF
[Unit]
Description=IntelliStream Chat
Wants=network-online.target
After=network-online.target postgresql.service

[Service]
Type=simple
User=${APP_USER}
Group=${APP_GROUP}
WorkingDirectory=${APP_HOME}
EnvironmentFile=${ENV_FILE}
# New files default to 0750/0640 — no "other" read.
UMask=0027

ExecStart=${JAVA_BIN} \$JAVA_OPTS -jar ${APP_HOME}/${APP_NAME}.jar

Restart=on-failure
RestartSec=5s
TimeoutStopSec=30s
KillSignal=SIGTERM

# === Process-level sandbox ===========================================
NoNewPrivileges=true
RestrictNamespaces=true
LockPersonality=true
SystemCallArchitectures=native
RestrictSUIDSGID=true
# MemoryDenyWriteExecute is intentionally NOT set — the JIT needs writable +
# executable pages and the JVM will not start with it on.

# === Filesystem isolation ============================================
ProtectSystem=strict
ReadWritePaths=${APP_HOME}/data
ProtectHome=true
PrivateTmp=true
PrivateDevices=true

# === Hide trees the JVM has no business reading ======================
# ProtectSystem=strict only blocks writes; these make the paths vanish.
InaccessiblePaths=/var/log /var/spool /var/lib
InaccessiblePaths=/etc/cron.d /etc/cron.daily /etc/cron.hourly /etc/cron.weekly /etc/cron.monthly /etc/crontab /etc/anacrontab
InaccessiblePaths=/etc/sudoers /etc/sudoers.d
InaccessiblePaths=/etc/sssd /etc/pam.d /etc/security
InaccessiblePaths=/etc/rsyslog.d /etc/rsyslog.conf
InaccessiblePaths=/etc/ssh /etc/NetworkManager
# /etc/audit is deliberately absent: SELinux targeted policy on RHEL 10 denies
# init_t the 'mounton' permission for auditd_etc_t, so listing it makes the unit
# fail to start. Those logs are mode 0600 anyway.

# === Kernel surface ==================================================
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
ProtectHostname=true
ProtectProc=invisible
# Only this service's own PIDs are visible in /proc.
ProcSubset=pid
RestrictRealtime=true
# The JVM needs unix sockets and IP; nothing else (no AF_PACKET, AF_NETLINK, ...).
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6

[Install]
WantedBy=multi-user.target
EOF
  info "wrote ${UNIT_FILE}"
fi
run systemctl daemon-reload

# ----------------------------------------------------------------- service ----
if (( START_SERVICE )); then
  step "Starting ${APP_NAME}"
  run systemctl enable --now "${APP_NAME}.service"
  if (( ! DRY_RUN )); then
    ok=0
    for _ in {1..60}; do
      curl -sf "http://${BIND_ADDRESS}:${BIND_PORT}/actuator/health" >/dev/null 2>&1 && { ok=1; break; }
      systemctl is-active --quiet "${APP_NAME}.service" || break
      sleep 2
    done
    if (( ok )); then
      info "${c_grn}healthy${c_off} — http://${BIND_ADDRESS}:${BIND_PORT}/actuator/health"
    else
      warn "Service did not report healthy. The usual causes, in order:"
      warn "  * database unreachable, or wrong credentials"
      warn "  * Keycloak realm unreachable (OIDC discovery is resolved at startup)"
      warn "  * SELinux denying writes to ${APP_HOME}/data — run scripts/selinux-harden.sh"
      warn "Look at:"
      warn "  journalctl -u ${APP_NAME} -n 80 --no-pager"
      warn "  sudo ausearch -m AVC -ts recent"
      exit 1
    fi
  fi
else
  step "Service installed but not started (--no-start)"
  info "systemctl enable --now ${APP_NAME}"
fi

# ------------------------------------------------------------------- next -----
step "Done"
cat <<EOF
  Service    ${APP_NAME}.service
  Listening  ${BIND_ADDRESS}:${BIND_PORT}   (loopback — unreachable from outside)
  Config     ${ENV_FILE}
  Data       ${APP_HOME}/data
  Logs       journalctl -u ${APP_NAME} -f

Still to do, both out of this script's scope on purpose:

  1. SELinux labelling — if 'getenforce' says Enforcing:
       sudo scripts/selinux-harden.sh
     Without it the JVM may be denied writes to ${APP_HOME}/data, and the denial
     lands in the audit log rather than in journalctl.

  2. Reverse proxy and TLS — follow frontend.md. It covers nginx and haproxy, the
     WebSocket upgrade headers, and the SameSite cookie gotcha that silently
     breaks OIDC login behind a proxy.
EOF
