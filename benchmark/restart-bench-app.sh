#!/usr/bin/env bash
# Stop the bench instance bound to 127.0.0.1:8080 (if any), wait for it to actually exit, then
# start a fresh one and block until it reports ready.
#
# Waiting matters: Lucene holds an exclusive write lock on the index directory, so a new instance
# started before the old one has finished shutting down dies on "Failed to open Lucene index".
# Only the loopback-bound instance is touched — a dev instance on another address is left alone.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
LOG=${LOG:-$ROOT/build/bench-app.log}

# The bind shows up as [::ffff:127.0.0.1]:8080 on this stack, so match the address and the port
# separately rather than as one "127.0.0.1:8080" literal (which never matches, silently skipping
# the shutdown and leaving the new instance to die on Lucene's index lock).
pid_on_loopback() { ss -tlnp 2>/dev/null | grep -E '127\.0\.0\.1\]?:8080' | grep -oP 'pid=\K[0-9]+' | head -1; }

OLD=$(pid_on_loopback || true)
if [ -n "$OLD" ]; then
  echo "stopping bench app pid=$OLD"
  kill "$OLD" 2>/dev/null || true
  for _ in $(seq 1 60); do kill -0 "$OLD" 2>/dev/null || break; sleep 1; done
  if kill -0 "$OLD" 2>/dev/null; then echo "pid $OLD ignored SIGTERM; sending SIGKILL"; kill -9 "$OLD"; sleep 3; fi
fi

: > "$LOG"
LOG="$LOG" nohup "$HERE/run-bench-app.sh" >/dev/null 2>&1 &
for _ in $(seq 1 120); do
  grep -q "Started ChatApplication" "$LOG" 2>/dev/null && break
  grep -q "APPLICATION FAILED TO START\|cancelling refresh attempt" "$LOG" 2>/dev/null && { echo "startup failed:"; tail -20 "$LOG"; exit 1; }
  sleep 1
done
NEW=$(pid_on_loopback || true)
[ -n "$NEW" ] || { echo "bench app did not bind 127.0.0.1:8080"; tail -20 "$LOG"; exit 1; }
grep -E "clientInboundChannel|clientOutboundChannel" "$LOG" | sed 's/.*StompChannelDiagnostics *: /  /'
echo "$NEW"
