#!/usr/bin/env bash
# Start a benchmark instance bound to 0.0.0.0 on a spare port (8090 by default), so the load
# generator can spread its connections across several loopback destination IPs.
#
# Why this exists alongside run-bench-app.sh: a client can only open ~64k connections to a single
# (dstIP, dstPort) pair, because that's how many ephemeral source ports it has. Past roughly 30k —
# earlier once TIME_WAIT churn is in play — the *client* starts failing to connect and the run
# measures the generator's port table rather than the server. Each extra destination IP is a fresh
# port pool, and reaching them requires the server to listen on more than loopback-one.
#
# Port 8090 rather than 8080 so this can coexist with both a dev instance on the LAN IP and a
# loopback bench instance. The Keycloak client needs http://127.0.0.1:8090/* in its redirect URIs.
#
# Usage: benchmark/run-bench-app-wide.sh   (env: HEAP, PORT, BUDGET, SOCKBUF, BINBUF, JFR)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
JAVA=${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}
KC_HOST=${KC_HOST:-192.168.100.98}
PORT=${PORT:-8090}
HEAP=${HEAP:-12g}
SOCKBUF=${SOCKBUF:-2048}
BINBUF=${BINBUF:-2048}
BUDGET=${BUDGET:-}            # optional cgroup MemoryMax; keeps an OOM inside this process
LOG=${LOG:-$ROOT/build/bench-wide.log}

pid_on_port() { ss -tlnp 2>/dev/null | grep -E "0\.0\.0\.0:$PORT|:::$PORT|\*:$PORT" | grep -oP 'pid=\K[0-9]+' | head -1; }

OLD=$(pid_on_port || true)
if [ -n "$OLD" ]; then
  kill "$OLD" 2>/dev/null || true
  for _ in $(seq 1 60); do kill -0 "$OLD" 2>/dev/null || break; sleep 1; done
fi

export SPRING_PROFILES_ACTIVE=bench
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[]|select(.clientId=="threadorbit").secret' "$ROOT/keycloak/realm.json")
export KEYCLOAK_ISSUER_URI="http://${KC_HOST}:8081/realms/threadorbit"
export BENCH_SERVER_ADDRESS=0.0.0.0
# The generator sends Origin: http://<dstIP>:<port>, so every loopback address it uses must match.
export BENCH_ALLOWED_ORIGINS="http://127.0.0.*:${PORT},http://localhost:${PORT}"
# Per-run index directory by default. Lucene takes an OS-level write lock, which a still-exiting
# JVM keeps holding after its listening socket has closed — so back-to-back runs sharing one
# directory fail on LockObtainFailedException even when the port looks free. Deleting write.lock
# doesn't help; the lock lives with the process, not the file.
export BENCH_LUCENE_DIR=${BENCH_LUCENE_DIR:-./data/lucene-bench-wide}

CMD=("$JAVA" -Xmx"$HEAP" -XX:+UseZGC --enable-native-access=ALL-UNNAMED
     -Dserver.port="$PORT"
     -Dspring.datasource.hikari.maximum-pool-size=30
     -Dthreadorbit.ws.socket-buffer-bytes="$SOCKBUF"
     -Dthreadorbit.ws.binary-buffer-bytes="$BINBUF"
     -Dthreadorbit.write-behind.enabled=false
     -jar "$ROOT"/build/libs/threadorbit-*-SNAPSHOT.jar)

: > "$LOG"
if [ -n "$BUDGET" ]; then
  # A hard memory cap on this process only. Pushing to the physical wall on a box that is also
  # running someone's dev instance would let the kernel OOM-killer pick the victim; this makes
  # the choice for it.
  systemd-run --user --scope --quiet -p MemoryMax="$BUDGET" -p MemorySwapMax=0 \
    "${CMD[@]}" >"$LOG" 2>&1 &
else
  nohup "${CMD[@]}" >"$LOG" 2>&1 &
fi

for _ in $(seq 1 150); do
  grep -q "Started ChatApplication" "$LOG" 2>/dev/null && break
  grep -q "APPLICATION FAILED TO START\|cancelling refresh" "$LOG" 2>/dev/null && { tail -20 "$LOG"; exit 1; }
  sleep 1
done
PID=$(pid_on_port || true)
[ -n "$PID" ] || { echo "did not bind 0.0.0.0:$PORT"; tail -20 "$LOG"; exit 1; }
grep -E "per-socket app buffers|did NOT apply" "$LOG" | sed 's/.*WebSocketConfig *: /  /'
echo "$PID"
