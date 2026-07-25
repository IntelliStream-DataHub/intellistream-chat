#!/usr/bin/env bash
#
# One-command benchmark: what does IntelliStream Chat do on YOUR machine?
#
#   ./gradlew benchmark
#
# Starts a benchmark instance, drives real WebSocket clients through the full write path
# (persist, render, index, broadcast, deliver), prints a summary, and stops the instance
# again. Every message is counted only once it comes back over the socket, so the number is
# end-to-end and not a queue depth.
#
# It measures message throughput, which is the figure that scales with your CPU and disk.
# The connection-count tiers are a separate exercise with their own kernel tuning; see
# scalability.md and benchmark/connection-ceiling.sh.
#
# Copyright 2026 IntelliStream AS — Apache License 2.0.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"

CONNS=${CONNS:-200}
DURATION=${DURATION:-30}
KC_HOST=${KC_HOST:-localhost}
JAVA=${JAVA:-$(command -v java)}
BASE="http://127.0.0.1:8080"
REPORT="${ROOT}/build/benchmark.json"
APP_LOG="${ROOT}/build/benchmark-app.log"

c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_red=$'\033[31m'; c_off=$'\033[0m'
[[ -t 1 ]] || { c_grn=""; c_yel=""; c_red=""; c_off=""; }
say()  { printf '\n%s==>%s %s\n' "$c_grn" "$c_off" "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '%s !! %s%s\n' "$c_yel" "$*" "$c_off" >&2; }
die()  { printf '%serror:%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }

APP_PID=""
cleanup() {
  # Benchmark rooms are throwaway. Leaving thousands of "bench-room-N" channels in the sidebar
  # of a dev workspace is its own kind of rude.
  if [[ "${ROOMS_CREATED:-0}" == "1" ]]; then
    podman exec -i chat_postgres_1 psql -U ichat_role -d intellistream_chat -q \
      -c "delete from channels where slug like 'bench-room-%';" >/dev/null 2>&1 || true
    rm -f "$HERE/rooms.txt"
  fi
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    say "Stopping the benchmark instance"
    kill "$APP_PID" 2>/dev/null || true
    for _ in {1..30}; do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# ------------------------------------------------------------------ checks ----
say "Preflight"
[[ -n "$JAVA" && -x "$JAVA" ]] || die "no java on PATH; set JAVA=/path/to/bin/java"
info "java: $("$JAVA" -version 2>&1 | head -1)"

if ss -ltn 2>/dev/null | grep -qE '127\.0\.0\.1\]?:8080|\*:8080|0\.0\.0\.0:8080'; then
  die "something is already listening on 127.0.0.1:8080.
Stop it first — a dev instance and the benchmark instance cannot share the port."
fi

probe() { (exec 3<>"/dev/tcp/${1}/${2}") 2>/dev/null && { exec 3<&- 3>&-; return 0; }; return 1; }
probe localhost 5432 || die "PostgreSQL is not answering on localhost:5432.
Start the stack first:  podman compose up -d"
curl -sf "http://${KC_HOST}:8081/realms/ichat-realm" >/dev/null 2>&1 \
  || die "Keycloak realm 'ichat-realm' is not reachable on ${KC_HOST}:8081.
Start the stack first:  podman compose up -d"
info "postgres and keycloak are up"

command -v jq >/dev/null || die "jq is required to read the client secret out of keycloak/realm.json"

CORES=$(nproc)
MEM_GB=$(awk '/MemTotal/{printf "%.0f", $2/1024/1024}' /proc/meminfo)
info "this machine: ${CORES} cores, ${MEM_GB} GB RAM"
if (( CONNS > 50 && CORES < 4 )); then
  warn "Few cores for ${CONNS} connections. The load generator runs on this same machine and"
  warn "will compete with the server for CPU, so the result is a floor, not a ceiling."
fi

# -------------------------------------------------------------------- run -----
say "Building"
( cd "$ROOT" && ./gradlew --quiet bootJar ) || die "bootJar failed"

say "Starting the benchmark instance"
info "profile=bench, log=${APP_LOG}"
KC_HOST="$KC_HOST" JAVA="$JAVA" "$HERE/run-bench-app.sh" >/dev/null 2>&1 &
sleep 2
for _ in {1..90}; do
  APP_PID=$(ss -tlnp 2>/dev/null | grep -E '127\.0\.0\.1\]?:8080' | grep -oP 'pid=\K[0-9]+' | head -1 || true)
  [[ -n "$APP_PID" ]] && curl -sf "${BASE}/actuator/health" >/dev/null 2>&1 && break
  sleep 2
done
[[ -n "$APP_PID" ]] || die "the benchmark instance did not start; see ${APP_LOG}"
info "pid ${APP_PID}, healthy"

# The load generator authenticates with the password grant (see WsLoadTest.fetchToken), so it
# needs the same client credentials the app uses, not just the app's environment.
export KEYCLOAK_CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-$(jq -r '.clients[]|select(.clientId=="ichat-client").secret' "$ROOT/keycloak/realm.json")}"
export KEYCLOAK_ISSUER_URI="${KEYCLOAK_ISSUER_URI:-http://${KC_HOST}:8081/realms/ichat-realm}"

# The generator drives one room per connection (--room-size 1), reading their ids from
# benchmark/rooms.txt. Create them fresh: a stale rooms.txt from an earlier run points at
# channels that no longer exist, and the run then connects perfectly and posts nothing.
say "Preparing ${CONNS} benchmark rooms"
"$HERE/setup-rooms.sh" "$BASE" alice alice "$CONNS" >/dev/null 2>&1 \
  || die "could not create benchmark rooms (is the compose Postgres container named chat_postgres_1?)"
ROOMS_CREATED=1
info "rooms ready"

say "Measuring (${CONNS} connections, ${DURATION}s, plus a warmup pass)"
info "cold JIT is roughly half of warm on this code, hence the throwaway pass"
CONNS="$CONNS" BASE="$BASE" JAVA="$JAVA" "$HERE/post-throughput.sh" benchmark "$CONNS" "$DURATION" || true
[[ -f "$HERE/results/benchmark.json" ]] && cp "$HERE/results/benchmark.json" "$REPORT"

# ----------------------------------------------------------------- report -----
say "Result"
if [[ ! -f "$REPORT" ]]; then
  warn "no report was written; see ${APP_LOG}"
  exit 1
fi

CORES_PCT=$(( CORES * 100 ))
jq -r --argjson cores "$CORES_PCT" '
  .steady as $s |
  "    Messages/second              \($s.postsPerSec)",
  "    Latency p50                  \($s.latP50ms) ms",
  "    Latency p99                  \($s.latP99ms) ms",
  "    Dropped                      \($s.dropped)",
  "    Connections                  \(.established) / \(.conns)",
  "    Server CPU (peak)            \(.serverMaxCpuPct | floor)% of \($cores)%",
  "    Server memory (peak)         \((.serverMaxRssMb / 1024) | .*10 | floor / 10) GiB"
' "$REPORT"

cat <<'NOTE'

    Every message counted here was committed to PostgreSQL, rendered, indexed in Lucene and
    delivered back over the WebSocket before the client counted it. It is end-to-end, not a
    queue depth.

    The load generator ran on this same machine and competed with the server for CPU, so read
    the number as a floor rather than a ceiling. Method, tuning and the published results are
    in scalability.md; the connection-count tiers are a separate exercise with their own
    kernel tuning (benchmark/connection-ceiling.sh).
NOTE
info "raw JSON: ${REPORT}"
