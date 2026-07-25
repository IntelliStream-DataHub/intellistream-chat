#!/usr/bin/env bash
# Start a benchmark instance of ThreadOrbit on 127.0.0.1:8080.
#
# Deliberately binds the loopback address rather than 0.0.0.0 so it can coexist with a dev
# instance bound to the LAN IP on the same port (different bind address = no conflict), and
# uses its own Lucene directory because Lucene takes an exclusive write lock on the index.
#
# The Keycloak client must have http://127.0.0.1:8080/* in its redirect URIs:
#   curl ... /admin/realms/threadorbit/clients/<id>  (see benchmark/README.md)
#
# Usage: benchmark/run-bench-app.sh [extra JVM args...]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
JAVA=${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}
KC_HOST=${KC_HOST:-192.168.100.98}
HEAP=${HEAP:-8g}
LOG=${LOG:-$ROOT/build/bench-app.log}
JFR=${JFR:-}                       # set JFR=/path/to/rec.jfr to profile the run

export SPRING_PROFILES_ACTIVE=bench
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[]|select(.clientId=="threadorbit").secret' "$ROOT/keycloak/realm.json")
export KEYCLOAK_ISSUER_URI="http://${KC_HOST}:8081/realms/threadorbit"
export BENCH_SERVER_ADDRESS=127.0.0.1
export BENCH_ALLOWED_ORIGINS='http://127.0.0.*:8080,http://localhost:8080'

JFR_OPT=()
[ -n "$JFR" ] && JFR_OPT=(-XX:StartFlightRecording=settings=profile,filename="$JFR",dumponexit=true)

exec "$JAVA" \
  -Xmx"$HEAP" -XX:+UseZGC --enable-native-access=ALL-UNNAMED \
  "${JFR_OPT[@]}" \
  -Dspring.datasource.hikari.maximum-pool-size="${POOL:-50}" \
  -Dthreadorbit.ws.inbound-threads="${INBOUND:-48}" \
  -Dthreadorbit.ws.outbound-threads="${OUTBOUND:-96}" \
  "$@" \
  -jar "$ROOT"/build/libs/threadorbit-*-SNAPSHOT.jar >"$LOG" 2>&1
