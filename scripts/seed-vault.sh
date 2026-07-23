#!/usr/bin/env bash
# Seed ThreadOrbit secrets into a running OpenBao / Vault.
#
# Usage:
#   ./scripts/seed-vault.sh                       # uses dev defaults below
#   BAO_ADDR=http://vault.example:8200 \
#   BAO_TOKEN=<root-or-write-token> \
#   THREADORBIT_DB_USERNAME=threadorbit \
#   THREADORBIT_DB_PASSWORD='...' \
#   KEYCLOAK_CLIENT_ID=threadorbit \
#   KEYCLOAK_CLIENT_SECRET='...' \
#   KEYCLOAK_ISSUER_URI=https://auth.example/realms/threadorbit \
#   ./scripts/seed-vault.sh
#
# Writes to KV-v2 path `secret/threadorbit` (mount: secret, key: threadorbit) — matches
# VaultEnvironmentPostProcessor's default `threadorbit.vault.path=threadorbit`.
#
# Idempotent: re-running overwrites the record with whatever's in the env vars at the
# time. The KV-v2 backend keeps a version history, so a bad seed is recoverable via
# `bao kv rollback`.
#
# Why curl + the HTTP API instead of `bao kv put`: works without the `bao` CLI installed
# locally, and avoids compose-profile-awareness quirks (`podman compose exec openbao` hides
# profile-gated services unless --profile openbao is on the command line).

set -euo pipefail

: "${BAO_ADDR:=http://127.0.0.1:8200}"
: "${BAO_TOKEN:=threadorbit-dev-token}"

: "${THREADORBIT_DB_USERNAME:=threadorbit}"
: "${THREADORBIT_DB_PASSWORD:=threadorbit}"
: "${KEYCLOAK_CLIENT_ID:=threadorbit}"
: "${KEYCLOAK_CLIENT_SECRET:?KEYCLOAK_CLIENT_SECRET must be set (no safe default — pull from keycloak/realm.json)}"
: "${KEYCLOAK_ISSUER_URI:=http://localhost:8081/realms/threadorbit}"

# Export so the inline python below can read them via os.environ.
export BAO_ADDR BAO_TOKEN THREADORBIT_DB_USERNAME THREADORBIT_DB_PASSWORD \
       KEYCLOAK_CLIENT_ID KEYCLOAK_CLIENT_SECRET KEYCLOAK_ISSUER_URI

# Build the KV-v2 write payload: { "data": { "key": "value", ... } }. Single-line jq
# avoids dependency on jq itself; python3 is on every modern distro.
PAYLOAD=$(python3 -c '
import json, os
print(json.dumps({"data": {
    "db.username":            os.environ["THREADORBIT_DB_USERNAME"],
    "db.password":            os.environ["THREADORBIT_DB_PASSWORD"],
    "keycloak.client-id":     os.environ["KEYCLOAK_CLIENT_ID"],
    "keycloak.client-secret": os.environ["KEYCLOAK_CLIENT_SECRET"],
    "keycloak.issuer-uri":    os.environ["KEYCLOAK_ISSUER_URI"],
}}))')

echo "Seeding $BAO_ADDR/v1/secret/data/threadorbit ..."
HTTP_CODE=$(curl -sS -o /tmp/seed-vault-response.json -w "%{http_code}" \
  -X POST "$BAO_ADDR/v1/secret/data/threadorbit" \
  -H "X-Vault-Token: $BAO_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD")

if [[ "$HTTP_CODE" != "200" && "$HTTP_CODE" != "204" ]]; then
  echo "Vault write failed (HTTP $HTTP_CODE):" >&2
  cat /tmp/seed-vault-response.json >&2
  echo >&2
  exit 1
fi

echo "Seeded — version metadata:"
python3 -c "import json;d=json.load(open('/tmp/seed-vault-response.json'));print('  version', d['data']['version'], '  created_time', d['data']['created_time'])"

echo
echo "To run the app against OpenBao:"
echo "  export THREADORBIT_VAULT_ENABLED=true"
echo "  export THREADORBIT_VAULT_URI=$BAO_ADDR"
echo "  export THREADORBIT_VAULT_TOKEN=$BAO_TOKEN"
echo "  ./gradlew bootRun"
