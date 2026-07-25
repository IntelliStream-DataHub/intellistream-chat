#!/usr/bin/env bash
# Marginal memory cost of a WebSocket connection, and the connection ceiling that follows from it.
#
# Measures the SLOPE between two loaded points rather than the delta from an idle baseline. That
# matters: a JVM's idle footprint includes a large and variable amount of already-committed but
# unused heap, and ZGC doesn't hand it back promptly, so "RSS at 10k minus RSS at idle" mostly
# measures where the collector happened to be. Two loaded points cancel the fixed overhead and the
# slack, leaving the number that actually determines the ceiling.
#
# Usage: benchmark/connection-memory.sh <label> <socket-buf-bytes> <binary-buf-bytes> [low] [high]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
LABEL=${1:?label}; SOCKBUF=${2:?socket buffer bytes}; BINBUF=${3:?binary buffer bytes}
LOW=${4:-10000}; HIGH=${5:-30000}
JAVA=${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}
KC_HOST=${KC_HOST:-localhost}
HEAP=${HEAP:-6g}

pid_on_loopback() { ss -tlnp 2>/dev/null | grep -E '127\.0\.0\.1\]?:8080' | grep -oP 'pid=\K[0-9]+' | head -1; }
established() { ss -tan state established "( sport = :8080 )" 2>/dev/null | tail -n +2 | wc -l; }

start_server() {
  local log=$1
  local old; old=$(pid_on_loopback || true)
  if [ -n "$old" ]; then kill "$old" 2>/dev/null || true
    for _ in $(seq 1 60); do kill -0 "$old" 2>/dev/null || break; sleep 1; done; fi
  export SPRING_PROFILES_ACTIVE=bench
  export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[]|select(.clientId=="ichat-client").secret' "$ROOT/keycloak/realm.json")
  export KEYCLOAK_ISSUER_URI="http://${KC_HOST}:8081/realms/ichat-realm"
  export BENCH_SERVER_ADDRESS=127.0.0.1
  export BENCH_ALLOWED_ORIGINS='http://127.0.0.*:8080,http://localhost:8080'
  : > "$log"
  nohup "$JAVA" -Xmx"$HEAP" -XX:+UseZGC --enable-native-access=ALL-UNNAMED \
    -Dspring.datasource.hikari.maximum-pool-size=20 \
    -Dichat.ws.socket-buffer-bytes="$SOCKBUF" \
    -Dichat.ws.binary-buffer-bytes="$BINBUF" \
    -Dichat.write-behind.enabled=false \
    -jar "$ROOT"/build/libs/intellistream-chat-*-SNAPSHOT.jar >"$log" 2>&1 &
  for _ in $(seq 1 150); do grep -q "Started ChatApplication" "$log" 2>/dev/null && break; sleep 1; done
}

# Hold N connections open, settle, force a collection, then read RSS. Returns "established rss_kb".
measure_at() {
  local n=$1 log=$2
  start_server "$log"
  local pid; pid=$(pid_on_loopback || true)
  [ -n "$pid" ] || { echo "0 0"; return; }
  "$JAVA" -Djdk.httpclient.allowRestrictedHeaders=host,origin,connection,upgrade \
    "$HERE/WsLoadTest.java" --base http://127.0.0.1:8080 --user alice --pass alice \
    --dst-hosts 127.0.0.1 --conns "$n" --room-size 50 --echo --ramp 40 --duration 150 \
    --send-rate 0 --report "$HERE/results/connmem-$LABEL-$n.json" >>"$log" 2>&1 &
  local loadpid=$!
  # Sample well inside the steady phase. Sampling at ramp+duration measured the teardown
  # instead of the load, which is how the first attempt reported 227 connections.
  sleep 95
  local est; est=$(established)
  jcmd "$pid" GC.run >/dev/null 2>&1 || true
  sleep 3
  local rss; rss=$(awk '/VmRSS/{print $2}' /proc/"$pid"/status 2>/dev/null || echo 0)
  echo "$est $rss"
  kill $loadpid 2>/dev/null || true; wait $loadpid 2>/dev/null || true
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
}

read LOW_EST LOW_RSS  <<<"$(measure_at "$LOW"  "$ROOT/build/connmem-$LABEL-low.log")"
read HIGH_EST HIGH_RSS <<<"$(measure_at "$HIGH" "$ROOT/build/connmem-$LABEL-high.log")"

grep -E "per-socket app buffers|did NOT apply" "$ROOT/build/connmem-$LABEL-high.log" \
  | sed 's/.*WebSocketConfig *: /  /' | tail -1

echo "  $LABEL @ ${LOW_EST} conns: $((LOW_RSS/1024)) MB"
echo "  $LABEL @ ${HIGH_EST} conns: $((HIGH_RSS/1024)) MB"
if [ "$HIGH_EST" -gt "$LOW_EST" ]; then
  PER=$(( (HIGH_RSS - LOW_RSS) * 1024 / (HIGH_EST - LOW_EST) ))
  echo "RESULT $LABEL: marginal = ${PER} bytes/connection"
  # Project the ceiling: usable RAM for the server, divided by the marginal cost.
  for gb in 24 64; do
    echo "         ceiling on ${gb}GB of server RAM ~= $(( gb * 1024 * 1024 * 1024 / PER )) connections"
  done
else
  echo "RESULT $LABEL: inconclusive (high point did not exceed low point)"
fi
