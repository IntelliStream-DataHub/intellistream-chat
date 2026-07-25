#!/usr/bin/env bash
# Find how many WebSocket connections fit in a fixed memory budget.
#
# The server runs inside its own memory-capped cgroup, for two reasons. It bounds the experiment
# (a smaller budget means a faster run, and connections-per-budget scales, so the result projects
# to a real box), and it means the OOM killer can only ever reach this process — a co-located dev
# instance on the same machine is never at risk, which an uncapped run to the physical wall
# absolutely cannot promise.
#
# Connections are counted server-side from `ss`, not from the generator's own bookkeeping, so a
# client that gives up early can't be mistaken for a server that ran out of room.
#
# Usage: benchmark/connection-ceiling.sh <label> <socket-buf-bytes> <binary-buf-bytes> [budget] [target]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
LABEL=${1:?label}; SOCKBUF=${2:?socket buffer bytes}; BINBUF=${3:?binary buffer bytes}
BUDGET=${4:-2G}; TARGET=${5:-60000}
JAVA=${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}
KC_HOST=${KC_HOST:-192.168.100.98}
LOG=$ROOT/build/ceiling-$LABEL.log
SAMPLES=$ROOT/build/ceiling-$LABEL.csv

pid_on_loopback() { ss -tlnp 2>/dev/null | grep -E '127\.0\.0\.1\]?:8080' | grep -oP 'pid=\K[0-9]+' | head -1; }
established() { ss -tan state established "( sport = :8080 )" 2>/dev/null | tail -n +2 | wc -l; }

OLD=$(pid_on_loopback || true)
if [ -n "$OLD" ]; then kill "$OLD" 2>/dev/null || true; for _ in $(seq 1 60); do kill -0 "$OLD" 2>/dev/null || break; sleep 1; done; fi

export SPRING_PROFILES_ACTIVE=bench
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[]|select(.clientId=="threadorbit").secret' "$ROOT/keycloak/realm.json")
export KEYCLOAK_ISSUER_URI="http://${KC_HOST}:8081/realms/threadorbit"
export BENCH_SERVER_ADDRESS=127.0.0.1
export BENCH_ALLOWED_ORIGINS='http://127.0.0.*:8080,http://localhost:8080'

: > "$LOG"
# -Xmx is set to the whole budget on purpose: the cgroup, not the heap, must be what binds, or
# an off-heap saving would have nowhere to show up.
systemd-run --user --scope --quiet --unit="ceiling-$LABEL-$$" \
  -p MemoryMax="$BUDGET" -p MemorySwapMax=0 \
  "$JAVA" -Xmx"$BUDGET" -XX:+UseZGC --enable-native-access=ALL-UNNAMED \
    -Dspring.datasource.hikari.maximum-pool-size=20 \
    -Dthreadorbit.ws.socket-buffer-bytes="$SOCKBUF" \
    -Dthreadorbit.ws.binary-buffer-bytes="$BINBUF" \
    -Dthreadorbit.write-behind.enabled=false \
    -jar "$ROOT"/build/libs/threadorbit-*-SNAPSHOT.jar >"$LOG" 2>&1 &

for _ in $(seq 1 120); do grep -q "Started ChatApplication" "$LOG" 2>/dev/null && break; sleep 1; done
PID=$(pid_on_loopback || true)
[ -n "$PID" ] || { echo "server did not start"; tail -20 "$LOG"; exit 1; }
grep -E "per-socket app buffers|did NOT apply" "$LOG" | sed 's/.*WebSocketConfig *: /  /'

echo "seconds,established,rss_kb" > "$SAMPLES"
( for i in $(seq 1 900); do
    kill -0 "$PID" 2>/dev/null || break
    echo "$i,$(established),$(awk '/VmRSS/{print $2}' /proc/$PID/status 2>/dev/null || echo 0)" >> "$SAMPLES"
    sleep 1
  done ) &
SAMPLER=$!

"$JAVA" -Djdk.httpclient.allowRestrictedHeaders=host,origin,connection,upgrade \
  "$HERE/WsLoadTest.java" --base http://127.0.0.1:8080 --user alice --pass alice \
  --dst-hosts 127.0.0.1 --conns "$TARGET" --room-size 50 --echo --ramp 120 --duration 5 \
  --send-rate 0 --report "$HERE/results/ceiling-$LABEL.json" >>"$LOG" 2>&1 || true

kill $SAMPLER 2>/dev/null || true; wait $SAMPLER 2>/dev/null || true
PEAK=$(awk -F, 'NR>1 && $2+0>m {m=$2+0} END {print m+0}' "$SAMPLES")
PEAK_RSS=$(awk -F, 'NR>1 && $3+0>m {m=$3+0} END {print m+0}' "$SAMPLES")
ALIVE=$(kill -0 "$PID" 2>/dev/null && echo yes || echo "no (OOM-killed at the budget)")
echo "RESULT $LABEL: peak_established=$PEAK  peak_rss=$((PEAK_RSS/1024))MB  budget=$BUDGET  survived=$ALIVE"
[ "$PEAK" -gt 0 ] && echo "         bytes/connection = $(( PEAK_RSS * 1024 / PEAK ))"
kill "$PID" 2>/dev/null || true
