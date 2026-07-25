#!/usr/bin/env bash
#
# IntelliStream Chat — SELinux setup for AlmaLinux / Rocky / RHEL.
#
# Run this after scripts/install-almalinux.sh, on any host where `getenforce`
# reports Enforcing.
#
# Why it is a separate script: SELinux is the one layer whose failures are
# invisible where you look for them. A denial does not appear in
# `journalctl -u intellistream-chat` — it lands in the audit log — so the symptom
# is an upload that fails, or a service that will not start, with a clean service
# log. Keeping this apart means you can re-run it after a relabel, a data-directory
# move or a port change without touching the installation.
#
# What it does:
#   * labels the writable data tree so writes survive `restorecon -R /`
#   * labels the environment file if it lives outside /etc
#   * optionally labels a non-standard listen port
#   * optionally allows a reverse proxy to connect to the app (--with-proxy)
#   * verifies the result and reports recent denials
#
# The systemd unit runs the JVM in unconfined_service_t, so the JIT's
# writable+executable pages and the outbound OIDC/JDBC connections work under
# stock policy. No custom module is needed for normal operation.
#
# Re-running is safe.
#
# Copyright 2026 Olav Gjerde — Apache License 2.0.

set -euo pipefail

APP_NAME="intellistream-chat"
APP_HOME="/opt/intellistream-chat"
DATA_DIR=""                      # defaults to ${APP_HOME}/data
ENV_FILE="/etc/intellistream-chat/env"
LISTEN_PORT=""
WITH_PROXY=0
DRY_RUN=0

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
IntelliStream Chat — SELinux hardening

Usage: sudo $0 [options]

Options:
  --app-home DIR    Install prefix (default: ${APP_HOME}).
  --data-dir DIR    Writable data tree (default: <app-home>/data). Must match the
                    unit's ReadWritePaths=, or systemd and SELinux will disagree
                    about which directory is writable and you get a denial that
                    looks like a permissions bug.
  --env-file PATH   Environment file (default: ${ENV_FILE}). Only relabelled when
                    it lives outside /etc, which is already etc_t.
  --port PORT       Label a listen port as http_port_t. Stock policy on AlmaLinux 10
                    covers 80, 81, 443, 488, 8008, 8009, 8443, 9000 — 8080 is NOT
                    among them. Only matters when the JVM runs in a confined domain;
                    the shipped unit is unconfined_service_t, which ignores port
                    labels.
  --with-proxy      Set the httpd_can_network_connect boolean so nginx/haproxy
                    (httpd_t) may connect to the app. Off by default because the
                    reverse proxy is a separate concern — see frontend.md — and
                    this boolean is host-wide, not scoped to this service.
  --dry-run         Print what would happen; change nothing.
  -h, --help        This text.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app-home)  APP_HOME="${2:?}"; shift 2 ;;
    --data-dir)  DATA_DIR="${2:?}"; shift 2 ;;
    --env-file)  ENV_FILE="${2:?}"; shift 2 ;;
    --port)      LISTEN_PORT="${2:?}"; shift 2 ;;
    --with-proxy) WITH_PROXY=1; shift ;;
    --dry-run)   DRY_RUN=1; shift ;;
    -h|--help)   usage; exit 0 ;;
    *)           die "unknown option: $1  (try --help)" ;;
  esac
done
DATA_DIR="${DATA_DIR:-${APP_HOME}/data}"

# ------------------------------------------------------------------ checks ----
step "Preflight"
(( DRY_RUN )) || [[ $EUID -eq 0 ]] || die "must run as root (use sudo)"

if ! command -v getenforce >/dev/null 2>&1; then
  info "SELinux tooling not present — nothing to do on this host."
  exit 0
fi
MODE="$(getenforce)"
info "SELinux mode: ${MODE}"
case "$MODE" in
  Disabled)
    info "SELinux is disabled — nothing to do."
    info "If you enable it later, re-run this script before starting the service."
    exit 0 ;;
  Permissive)
    warn "Permissive: denials are logged but not enforced. Applying the labels anyway"
    warn "so the host is correct if you switch to Enforcing." ;;
esac

step "Policy tools"
missing=()
for pkg in policycoreutils-python-utils setools-console; do
  rpm -q "$pkg" >/dev/null 2>&1 || missing+=("$pkg")
done
if (( ${#missing[@]} )); then
  info "installing: ${missing[*]}"
  run dnf install -y "${missing[@]}" || die "could not install ${missing[*]}"
else
  info "already installed"
fi
command -v semanage  >/dev/null || (( DRY_RUN )) || die "semanage missing after install"
command -v restorecon >/dev/null || (( DRY_RUN )) || die "restorecon missing after install"

# -------------------------------------------------------------- data label ----
step "Label the data directory"
(( DRY_RUN )) || [[ -d "$DATA_DIR" ]] || die "data directory does not exist: ${DATA_DIR}
Run scripts/install-almalinux.sh first, or pass --data-dir."

# var_lib_t is the catch-all for a system service's own state. The fcontext rule is
# what makes the label survive a filesystem relabel; restorecon only applies it now.
PATTERN="${DATA_DIR}(/.*)?"
if semanage fcontext -l 2>/dev/null | grep -qF "$PATTERN"; then
  info "fcontext rule already present for ${DATA_DIR}"
else
  run semanage fcontext -a -t var_lib_t "$PATTERN" \
    || die "semanage fcontext failed for ${PATTERN}"
  info "added fcontext: ${PATTERN} -> var_lib_t"
fi
run restorecon -Rv "$APP_HOME" >/dev/null 2>&1 || true
info "relabelled ${APP_HOME}"

# --------------------------------------------------------------- env label ----
step "Environment file label"
if [[ "$ENV_FILE" == /etc/* ]]; then
  info "${ENV_FILE} is under /etc — already etc_t, nothing to do"
elif (( DRY_RUN )) || [[ -e "$ENV_FILE" ]]; then
  P="${ENV_FILE}"
  if semanage fcontext -l 2>/dev/null | grep -qF "$P"; then
    info "fcontext rule already present for ${P}"
  else
    run semanage fcontext -a -t etc_t "$P" || die "semanage fcontext failed for ${P}"
  fi
  run restorecon -v "$ENV_FILE" >/dev/null 2>&1 || true
  info "relabelled ${ENV_FILE} as etc_t"
else
  warn "env file not found: ${ENV_FILE} (skipping label)"
fi

# -------------------------------------------------------------- port label ----
if [[ -n "$LISTEN_PORT" ]]; then
  step "Listen port label"
  [[ "$LISTEN_PORT" =~ ^[0-9]+$ ]] || die "--port must be numeric: ${LISTEN_PORT}"
  if semanage port -l 2>/dev/null | awk '$1=="http_port_t"' | grep -qw "$LISTEN_PORT"; then
    info "port ${LISTEN_PORT} already labelled http_port_t"
  else
    if run semanage port -a -t http_port_t -p tcp "$LISTEN_PORT" 2>/dev/null; then
      info "labelled tcp/${LISTEN_PORT} as http_port_t"
    else
      run semanage port -m -t http_port_t -p tcp "$LISTEN_PORT" \
        && info "re-labelled tcp/${LISTEN_PORT} as http_port_t" \
        || warn "could not label tcp/${LISTEN_PORT}; check 'semanage port -l'"
    fi
  fi
fi

# ------------------------------------------------------------ proxy boolean ---
step "Reverse-proxy boolean"
if (( WITH_PROXY )); then
  cur="$(getsebool httpd_can_network_connect 2>/dev/null | awk '{print $3}')"
  if [[ "$cur" == "on" ]]; then
    info "httpd_can_network_connect already on"
  else
    run setsebool -P httpd_can_network_connect on || die "setsebool failed"
    info "httpd_can_network_connect -> on (persistent)"
  fi
  warn "This boolean is host-wide: every httpd_t process on this machine may now make"
  warn "outbound network connections, not just your proxy to this app."
else
  info "skipped (pass --with-proxy when you set the reverse proxy up)"
  info "Without it nginx gets 502 and the audit log shows:"
  info "  denied { name_connect } ... port=8080  scontext=...httpd_t"
fi

# ----------------------------------------------------------------- verify -----
step "Verify"
if (( DRY_RUN )); then
  info "[dry-run] skipping verification"
else
  ctx="$(ls -Zd "$DATA_DIR" 2>/dev/null | awk '{print $1}')"
  info "context of ${DATA_DIR}: ${ctx:-unknown}"
  case "${ctx:-}" in
    *var_lib_t*) info "${c_grn}data directory label is correct${c_off}" ;;
    *)           warn "expected var_lib_t. Try: restorecon -Rv ${APP_HOME}" ;;
  esac

  if systemctl is-active --quiet "${APP_NAME}.service" 2>/dev/null; then
    info "${APP_NAME} is running"
  else
    info "${APP_NAME} is not running (start it with: systemctl start ${APP_NAME})"
  fi

  step "Recent denials"
  if command -v ausearch >/dev/null 2>&1; then
    if out="$(ausearch -m AVC,USER_AVC -ts recent 2>/dev/null)" && [[ -n "$out" ]]; then
      warn "AVC denials found in the recent window:"
      printf '%s\n' "$out" | tail -20
      cat <<EOF

  Capture and allow a specific denial rather than disabling enforcement:

    sudo ausearch -m AVC -ts recent | audit2allow -a -M ${APP_NAME}-local
    less ${APP_NAME}-local.te            # read it before loading
    sudo semodule -i ${APP_NAME}-local.pp

  Do not reach for 'setenforce 0'.
EOF
    else
      info "${c_grn}none${c_off}"
    fi
  else
    info "ausearch not available (auditd not installed) — skipped"
  fi
fi

step "Done"
cat <<EOF
  Labelled   ${DATA_DIR} -> var_lib_t (survives a relabel)
  Proxy      httpd_can_network_connect $( (( WITH_PROXY )) && echo "on" || echo "untouched — pass --with-proxy when you add the proxy" )

  If something breaks later, the denial is in the audit log, not the service log:
    sudo ausearch -m AVC,USER_AVC -ts recent
    sudo journalctl -u ${APP_NAME} -p err --since "10 min ago"
EOF
