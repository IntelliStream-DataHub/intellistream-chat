#!/usr/bin/env bash
#
# IntelliStream Chat — installer for AlmaLinux / Rocky / RHEL 9 and 10.
#
# Installs Java, optionally PostgreSQL, creates the service account and directory
# layout, writes the environment file, installs the hardened systemd unit and starts
# the service.
#
# It deliberately does NOT configure a reverse proxy or TLS. That is a separate
# concern with its own decisions (nginx vs haproxy, certificate source, HTTP/2,
# WebSocket timeouts) and it has its own guide: frontend.md. This script leaves the
# app listening on 127.0.0.1:8080, which is exactly what you want in front of one.
#
# It also does not install Keycloak. A production identity provider needs its own
# database, hostname and TLS, and most sites already have one. Point --issuer-uri at
# yours; --import-realm will load the bundled realm into it over kcadm if you want the
# roles and client created for you.
#
# Re-running is safe: every step checks before it acts.
#
# Copyright 2026 Olav Gjerde — Apache License 2.0.

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
JAVA_BIN=""                       # resolved after install

DB_NAME="intellistream_chat"
DB_USER="ichat_role"
DB_PASSWORD=""                    # generated when empty
DB_HOST="localhost"
DB_PORT="5432"

ISSUER_URI=""
CLIENT_ID="ichat-client"
CLIENT_SECRET=""

JAR_SRC=""
HEAP_MAX="2g"
BIND_ADDRESS="127.0.0.1"
BIND_PORT="8080"

INSTALL_POSTGRES=1
IMPORT_REALM=0
START_SERVICE=1
DRY_RUN=0

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
IntelliStream Chat installer (AlmaLinux / Rocky / RHEL)

Usage: sudo $0 --issuer-uri URI --client-secret SECRET [options]

Required unless --dry-run:
  --issuer-uri URI        Keycloak realm issuer, e.g.
                          https://auth.example.com/realms/ichat-realm
  --client-secret SECRET  OIDC client secret for '${CLIENT_ID}'.
                          Use '-' to read it from stdin instead of argv.

Options:
  --jar PATH              Pre-built application jar. Default: build it from
                          ${REPO_ROOT} with ./gradlew bootJar.
  --client-id ID          OIDC client id (default: ${CLIENT_ID}).
  --db-name NAME          Database name (default: ${DB_NAME}).
  --db-user USER          Database role (default: ${DB_USER}).
  --db-password PASS      Database password (default: generated, 32 chars).
  --db-host HOST          Database host (default: ${DB_HOST}).
  --db-port PORT          Database port (default: ${DB_PORT}).
  --skip-postgres         Don't install or configure PostgreSQL. Use for a managed
                          or remote database; the role and database must already
                          exist and be reachable.
  --import-realm          Import keycloak/realm.json into the Keycloak at
                          --issuer-uri using kcadm.sh. Requires KC_ADMIN and
                          KC_ADMIN_PASSWORD in the environment, and kcadm.sh on
                          PATH or at /opt/keycloak/bin/kcadm.sh.
  --heap SIZE             JVM max heap (default: ${HEAP_MAX}).
  --bind ADDR             Listen address (default: ${BIND_ADDRESS} — keep this on
                          loopback and put a reverse proxy in front, see frontend.md).
  --port PORT             Listen port (default: ${BIND_PORT}).
  --no-start              Install everything but leave the service stopped.
  --dry-run               Print what would happen; change nothing.
  -h, --help              This text.

After this finishes, run scripts/selinux-harden.sh if SELinux is enforcing, then
set up your reverse proxy following frontend.md.
EOF
}

# ------------------------------------------------------------------- args -----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --issuer-uri)     ISSUER_URI="${2:?}"; shift 2 ;;
    --client-id)      CLIENT_ID="${2:?}"; shift 2 ;;
    --client-secret)  CLIENT_SECRET="${2:?}"; shift 2 ;;
    --jar)            JAR_SRC="${2:?}"; shift 2 ;;
    --db-name)        DB_NAME="${2:?}"; shift 2 ;;
    --db-user)        DB_USER="${2:?}"; shift 2 ;;
    --db-password)    DB_PASSWORD="${2:?}"; shift 2 ;;
    --db-host)        DB_HOST="${2:?}"; shift 2 ;;
    --db-port)        DB_PORT="${2:?}"; shift 2 ;;
    --skip-postgres)  INSTALL_POSTGRES=0; shift ;;
    --import-realm)   IMPORT_REALM=1; shift ;;
    --heap)           HEAP_MAX="${2:?}"; shift 2 ;;
    --bind)           BIND_ADDRESS="${2:?}"; shift 2 ;;
    --port)           BIND_PORT="${2:?}"; shift 2 ;;
    --no-start)       START_SERVICE=0; shift ;;
    --dry-run)        DRY_RUN=1; shift ;;
    -h|--help)        usage; exit 0 ;;
    *)                die "unknown option: $1  (try --help)" ;;
  esac
done

if [[ "$CLIENT_SECRET" == "-" ]]; then
  read -rs CLIENT_SECRET
  [[ -n "$CLIENT_SECRET" ]] || die "empty client secret on stdin"
fi

# --------------------------------------------------------------- preflight ----
step "Preflight"

(( DRY_RUN )) || [[ $EUID -eq 0 ]] || die "must run as root (use sudo)"

[[ -r /etc/os-release ]] || die "cannot read /etc/os-release"
# shellcheck disable=SC1091
. /etc/os-release
case "${ID}${ID_LIKE:-}" in
  *rhel*|*fedora*|almalinux*|rocky*) info "OS: ${PRETTY_NAME}" ;;
  *) warn "This installer targets the RHEL family; ${PRETTY_NAME:-unknown} is untested."
     warn "Package names and the postgresql-setup step are the parts most likely to differ." ;;
esac

command -v dnf >/dev/null || die "dnf not found — this installer needs a dnf-based distro"

if (( ! DRY_RUN )); then
  [[ -n "$ISSUER_URI" ]]    || die "--issuer-uri is required (see --help)"
  [[ -n "$CLIENT_SECRET" ]] || die "--client-secret is required (see --help)"
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

if [[ -z "$DB_PASSWORD" ]]; then
  DB_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-32)"
  DB_PASSWORD_GENERATED=1
else
  DB_PASSWORD_GENERATED=0
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
if (( DRY_RUN )) && [[ -z "$JAVA_BIN" ]]; then JAVA_BIN="/usr/lib/jvm/java-25-openjdk/bin/java"; fi
[[ -n "$JAVA_BIN" ]] || die "installed ${JAVA_PKG} but found no java binary"
info "java: ${JAVA_BIN}"

# --------------------------------------------------------------- postgresql ---
if (( INSTALL_POSTGRES )); then
  step "PostgreSQL"
  if ! command -v psql >/dev/null 2>&1; then
    run dnf install -y postgresql-server postgresql-contrib || die "could not install postgresql-server"
  else
    info "postgresql client already present"
  fi

  if [[ ! -s /var/lib/pgsql/data/PG_VERSION ]]; then
    info "initialising the data directory"
    run /usr/bin/postgresql-setup --initdb || die "postgresql-setup --initdb failed"
  else
    info "data directory already initialised"
  fi

  run systemctl enable --now postgresql

  # Wait for the socket rather than assuming systemctl returning means ready.
  if (( ! DRY_RUN )); then
    for _ in {1..30}; do sudo -u postgres psql -tAc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
    sudo -u postgres psql -tAc 'select 1' >/dev/null 2>&1 || die "PostgreSQL did not become ready"
  fi

  step "Database role and schema owner"
  if (( DRY_RUN )); then
    info "[dry-run] create role ${DB_USER} and database ${DB_NAME}"
  else
    if sudo -u postgres psql -tAc "select 1 from pg_roles where rolname='${DB_USER}'" | grep -q 1; then
      info "role ${DB_USER} exists — leaving its password alone"
      warn "If you don't know its password, the env file below will be wrong. Reset with:"
      warn "  sudo -u postgres psql -c \"ALTER ROLE ${DB_USER} PASSWORD '<new>'\""
      DB_PASSWORD_GENERATED=0
    else
      sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
CREATE ROLE ${DB_USER} LOGIN PASSWORD '${DB_PASSWORD}';
SQL
      info "created role ${DB_USER}"
    fi

    if sudo -u postgres psql -tAc "select 1 from pg_database where datname='${DB_NAME}'" | grep -q 1; then
      info "database ${DB_NAME} exists"
    else
      sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER}"
      info "created database ${DB_NAME} owned by ${DB_USER}"
    fi
  fi
  info "Flyway creates the schema on first start — no manual DDL."
else
  step "PostgreSQL (skipped)"
  info "Using an external database at ${DB_HOST}:${DB_PORT}/${DB_NAME} as ${DB_USER}."
  info "It must already exist and accept connections from this host."
fi

# ------------------------------------------------------------ service user ----
step "Service account and layout"
if getent group "$APP_GROUP" >/dev/null; then info "group ${APP_GROUP} exists"
else run groupadd --system "$APP_GROUP"; fi

if getent passwd "$APP_USER" >/dev/null; then info "user ${APP_USER} exists"
else
  run useradd --system --gid "$APP_GROUP" --home-dir "$APP_HOME" \
              --shell /usr/sbin/nologin --comment "IntelliStream Chat" "$APP_USER"
fi

# data/ is the single writable tree; the systemd unit's ReadWritePaths matches it exactly.
run install -d -o root      -g "$APP_GROUP" -m 0750 "$ETC_DIR"
run install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "$APP_HOME"
for sub in data data/attachments data/avatars data/branding data/lucene data/heapdumps; do
  run install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "${APP_HOME}/${sub}"
done
info "layout: ${APP_HOME} (data/ writable), ${ETC_DIR} (config)"

# -------------------------------------------------------------------- jar -----
step "Application jar"
if [[ -z "$JAR_SRC" ]]; then
  [[ -x "${REPO_ROOT}/gradlew" ]] || die "no --jar given and ${REPO_ROOT}/gradlew is missing"
  info "building from ${REPO_ROOT} (this takes a couple of minutes)"
  if (( ! DRY_RUN )); then
    ( cd "$REPO_ROOT" && ./gradlew --quiet bootJar ) || die "gradle bootJar failed"
  fi
  JAR_SRC="$(ls -1t "${REPO_ROOT}"/build/libs/${APP_NAME}-*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)"
  (( DRY_RUN )) && JAR_SRC="${JAR_SRC:-${REPO_ROOT}/build/libs/${APP_NAME}-<version>.jar}"
  [[ -n "$JAR_SRC" ]] || die "build succeeded but no ${APP_NAME}-*.jar found under build/libs"
fi
(( DRY_RUN )) || [[ -r "$JAR_SRC" ]] || die "jar not readable: $JAR_SRC"
info "installing $(basename "$JAR_SRC")"
run install -o root -g "$APP_GROUP" -m 0640 "$JAR_SRC" "${APP_HOME}/${APP_NAME}.jar"

# ---------------------------------------------------------------- env file ----
step "Environment file"
if [[ -e "$ENV_FILE" ]]; then
  warn "${ENV_FILE} exists — leaving it untouched."
  warn "Delete it and re-run if you want it regenerated."
else
  if (( DRY_RUN )); then
    info "[dry-run] write ${ENV_FILE} (0640 root:${APP_GROUP})"
  else
    umask 077
    cat > "$ENV_FILE" <<EOF
# IntelliStream Chat — service environment. Read by systemd, not a shell script:
# no quoting, no expansion, no comments after values.
# Written by scripts/install-almalinux.sh.

# --- database ---
ICHAT_DB_URL=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
ICHAT_DB_USERNAME=${DB_USER}
ICHAT_DB_PASSWORD=${DB_PASSWORD}

# --- identity (Keycloak / OIDC) ---
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
JAVA_OPTS=-Xms256m -Xmx${HEAP_MAX} -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${APP_HOME}/data/heapdumps --enable-native-access=ALL-UNNAMED
EOF
    chown root:"$APP_GROUP" "$ENV_FILE"
    chmod 0640 "$ENV_FILE"
    info "wrote ${ENV_FILE} (0640 root:${APP_GROUP})"
  fi
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

# ------------------------------------------------------------ realm import ----
if (( IMPORT_REALM )); then
  step "Keycloak realm import"
  KCADM="$(command -v kcadm.sh || echo /opt/keycloak/bin/kcadm.sh)"
  if [[ ! -x "$KCADM" ]]; then
    warn "kcadm.sh not found — skipping. Import keycloak/realm.json by hand"
    warn "(admin console → Realms → Import), then regenerate the client secret."
  elif [[ -z "${KC_ADMIN:-}" || -z "${KC_ADMIN_PASSWORD:-}" ]]; then
    warn "KC_ADMIN / KC_ADMIN_PASSWORD not set — skipping realm import."
  else
    KC_BASE="${ISSUER_URI%%/realms/*}"
    info "importing keycloak/realm.json into ${KC_BASE}"
    run "$KCADM" config credentials --server "$KC_BASE" --realm master \
        --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD"
    run "$KCADM" create realms -f "${REPO_ROOT}/keycloak/realm.json" \
      || warn "realm import failed (it may already exist) — check the admin console"
    warn "The bundled realm ships a PUBLIC dev client secret and two demo users."
    warn "Regenerate the secret and delete alice/bob before this faces anyone."
  fi
fi

# ----------------------------------------------------------------- service ----
if (( START_SERVICE )); then
  step "Starting ${APP_NAME}"
  run systemctl enable --now "${APP_NAME}.service"
  if (( ! DRY_RUN )); then
    ok=0
    for _ in {1..60}; do
      if curl -sf "http://${BIND_ADDRESS}:${BIND_PORT}/actuator/health" >/dev/null 2>&1; then ok=1; break; fi
      systemctl is-active --quiet "${APP_NAME}.service" || break
      sleep 2
    done
    if (( ok )); then
      info "${c_grn}healthy${c_off} — http://${BIND_ADDRESS}:${BIND_PORT}/actuator/health"
    else
      warn "Service did not report healthy. Look at:"
      warn "  journalctl -u ${APP_NAME} -n 80 --no-pager"
      warn "  sudo ausearch -m AVC -ts recent      # if SELinux is enforcing"
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
  Listening  ${BIND_ADDRESS}:${BIND_PORT}   (loopback — not reachable from outside yet)
  Config     ${ENV_FILE}
  Data       ${APP_HOME}/data
  Logs       journalctl -u ${APP_NAME} -f

Two things this script deliberately did not do:

  1. Reverse proxy and TLS. The app is on loopback and nothing outside can reach it.
     Follow frontend.md — it covers nginx and haproxy, the WebSocket upgrade headers,
     and the SameSite cookie gotcha that silently breaks OIDC login behind a proxy.

  2. SELinux labelling. If 'getenforce' says Enforcing, run:
       sudo scripts/selinux-harden.sh
     Without it the JVM may be denied writes to ${APP_HOME}/data, and the denial
     appears in the audit log rather than in journalctl.
EOF
if (( DB_PASSWORD_GENERATED )); then
  cat <<EOF

  A database password was generated and written to ${ENV_FILE}.
  It is not printed here on purpose. Read it back with:
    sudo grep ICHAT_DB_PASSWORD ${ENV_FILE}
EOF
fi
