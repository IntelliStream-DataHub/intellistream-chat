#!/usr/bin/env bash
# Measure sustained message-post throughput against the loopback bench instance.
#
# Closed-loop by design (--in-flight 1): each connection waits for its own message to come back
# before sending the next, so offered load tracks server capacity and the reported number is the
# real service rate rather than the size of a queue. --room-size 1 keeps fan-out at 1:1 so the
# number is posts/s, not deliveries/s.
#
# Usage: benchmark/post-throughput.sh <report-name> [conns] [duration]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
NAME=${1:?report name}; CONNS=${2:-200}; DURATION=${3:-30}
JAVA=${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}
BASE=${BASE:-http://127.0.0.1:8080}

PID=$(ss -tlnp 2>/dev/null | grep -E '127\.0\.0\.1\]?:8080' | grep -oP 'pid=\K[0-9]+' | head -1)

run() {
  "$JAVA" -Djdk.httpclient.allowRestrictedHeaders=host,origin,connection,upgrade \
    "$HERE/WsLoadTest.java" --base "$BASE" --user alice --pass alice --dst-hosts 127.0.0.1 \
    --conns "$CONNS" --room-size 1 --in-flight 1 --ramp 3 --duration "$1" \
    ${PID:+--server-pid "$PID"} --report "$2"
}

echo "### warmup (JIT) ###"
run 15 "$ROOT/build/warmup.json" | grep -E "^\[steady\].*throughput"
echo "### measured: $NAME (conns=$CONNS duration=${DURATION}s) ###"
run "$DURATION" "$HERE/results/$NAME.json" | tail -5
