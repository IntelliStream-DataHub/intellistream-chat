#!/usr/bin/env bash
# How many WebSocket connections fit in a fixed memory budget — the number the connection ceiling
# is actually made of.
#
# Design notes, both of them earned the hard way:
#
#  * The heap limit must be well BELOW the cgroup budget. Setting -Xmx equal to the budget lets the
#    heap claim the whole allowance on its own, and the process is OOM-killed on JVM overhead
#    before it has accepted a meaningful number of connections — which measures nothing.
#  * Connections are counted continuously and the maximum is kept, rather than sampled once at a
#    guessed moment. A single `sleep N` lands in the teardown as easily as the plateau, and reports
#    a couple of hundred connections for a run that actually held tens of thousands.
#
# The budget is deliberately small: it makes each run quick, keeps an OOM inside this cgroup rather
# than letting the kernel pick a victim on a shared box, and connections-per-budget is a ratio that
# projects to a real machine anyway.
#
# Usage: benchmark/connection-ceiling.sh <label> <sockbuf> <binbuf> [budget] [heap] [target]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
LABEL=${1:?label}; SOCKBUF=${2:?socket buffer bytes}; BINBUF=${3:?binary buffer bytes}
BUDGET=${4:-3G}; HEAP=${5:-1500m}; TARGET=${6:-60000}
JAVA=${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}
PORT=${PORT:-8090}
SAMPLES=$ROOT/build/ceiling-$LABEL.csv
LOG=$ROOT/build/ceiling-$LABEL.log

established() { ss -tanH 2>/dev/null | awk -v p=":$PORT\$" '$1=="ESTAB" && $4 ~ p' | wc -l; }

PID=$(PORT="$PORT" HEAP="$HEAP" BUDGET="$BUDGET" SOCKBUF="$SOCKBUF" BINBUF="$BINBUF" \
      BENCH_LUCENE_DIR="./data/lucene-ceiling-$LABEL" \
      LOG="$LOG" "$HERE/run-bench-app-wide.sh" | tail -1)
[ -n "$PID" ] || { echo "server did not start"; exit 1; }
echo "  budget=$BUDGET heap=$HEAP buffers=${SOCKBUF}/${BINBUF} pid=$PID"

echo "seconds,established,rss_kb" > "$SAMPLES"
( for i in $(seq 1 900); do
    kill -0 "$PID" 2>/dev/null || break
    echo "$i,$(established),$(awk '/VmRSS/{print $2}' /proc/$PID/status 2>/dev/null || echo 0)" >> "$SAMPLES"
    sleep 1
  done ) &
SAMPLER=$!

"$JAVA" -Xmx6g -Djdk.httpclient.allowRestrictedHeaders=host,origin,connection,upgrade \
  "$HERE/WsLoadTest.java" --base "http://127.0.0.1:$PORT" --port "$PORT" --user alice --pass alice \
  --dst-hosts 127.0.0.1,127.0.0.2,127.0.0.3,127.0.0.4 \
  --conns "$TARGET" --room-size 50 --echo --ramp 150 --duration 30 --send-rate 0 \
  --report "$HERE/results/ceiling-$LABEL.json" >>"$LOG" 2>&1 || true

kill $SAMPLER 2>/dev/null || true; wait $SAMPLER 2>/dev/null || true
PEAK=$(awk -F, 'NR>1 && $2+0>m {m=$2+0} END {print m+0}' "$SAMPLES")
PEAK_RSS=$(awk -F, -v p="$PEAK" 'NR>1 && $2+0==p {print $3; exit}' "$SAMPLES")
if kill -0 "$PID" 2>/dev/null; then SURVIVED="yes (budget not exhausted — raise --conns)"; else SURVIVED="no (OOM-killed at the budget)"; fi
echo "RESULT $LABEL: peak_established=$PEAK  rss_at_peak=$(( ${PEAK_RSS:-0} / 1024 ))MB  survived=$SURVIVED"
# Wait for it to actually exit. The listening socket closes before the JVM releases Lucene's
# index lock, so a run that starts the moment the port frees up dies on "Lock held by another
# program" — which is exactly how the tuned half of this A/B failed the first time.
kill "$PID" 2>/dev/null || true
for _ in $(seq 1 60); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
kill -9 "$PID" 2>/dev/null || true
