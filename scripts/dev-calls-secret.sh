#!/usr/bin/env bash
# Generate a fresh TURN secret for local 1:1 calls and wire it into both halves that must agree
# on it byte-for-byte: coturn (via .env, which podman-compose reads for variable substitution)
# and the app (via application-dev.properties, which `./gradlew bootRun` auto-loads through the
# dev profile). Re-run any time you want a new secret — there is no state to converge, only two
# files to overwrite.
#
# WHY THIS EXISTS: CallProperties.isConfigured() requires ICHAT_TURN_URLS and ICHAT_TURN_SECRET,
# and a mismatch with coturn's --static-auth-secret looks exactly like a network problem, not an
# auth one. The example files ship one static secret (dev-turn-secret) so a fresh clone has a
# working quickstart with zero config; this script swaps that shared, checked-in-adjacent value
# for one nobody else has, without you hand-editing two files to keep them in sync.
#
#   ./scripts/dev-calls-secret.sh
#   podman compose up -d      # recreates coturn — only a *changed* .env triggers that
#   ./gradlew bootRun         # picks up the same secret via application-dev.properties
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=.env
DEV_PROPS=src/main/resources/application-dev.properties

[[ -f "$ENV_FILE" ]] || cp .env.example "$ENV_FILE"
[[ -f "$DEV_PROPS" ]] || cp src/main/resources/application-dev.properties.example "$DEV_PROPS"

SECRET="$(openssl rand -base64 32)"
TURN_URL="turn:127.0.0.1:3478?transport=udp"

# Drop any previous TURN lines — commented or not — before appending fresh ones, so re-running
# this replaces rather than accumulates duplicates.
sed -i -E '/^#?[[:space:]]*ICHAT_TURN_(URLS|SECRET)=/d' "$ENV_FILE"
{
  echo "ICHAT_TURN_URLS=${TURN_URL}"
  echo "ICHAT_TURN_SECRET=${SECRET}"
} >> "$ENV_FILE"

sed -i -E '/^#?[[:space:]]*ichat\.calls\.turn-(urls|secret)=/d' "$DEV_PROPS"
{
  echo "ichat.calls.turn-urls=${TURN_URL}"
  echo "ichat.calls.turn-secret=${SECRET}"
} >> "$DEV_PROPS"

echo "New TURN secret written to $ENV_FILE and $DEV_PROPS."
echo "Recreate coturn so it picks up the change, then start the app:"
echo "  podman compose up -d"
echo "  ./gradlew bootRun"
