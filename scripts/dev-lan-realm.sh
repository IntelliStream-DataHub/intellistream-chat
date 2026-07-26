#!/usr/bin/env bash
# Whitelist a LAN origin on the dev Keycloak realm. Development only.
#
# WHY THIS EXISTS: the compose stack runs Keycloak with `start-dev`, which keeps the whole realm
# in an in-memory H2. Anything you change through the admin console or kcadm is gone the moment
# the container is recreated — including by `podman compose up -d`, which recreates it whenever
# the compose file or .env changes. The realm is then re-imported from keycloak/realm.json, which
# deliberately whitelists loopback only so the quickstart works on any host.
#
# So this is not a one-time setup step. Re-run it after every recreate. The symptom when you
# forget is a Keycloak log line reading error="invalid_redirect_uri" and a browser sitting on a
# 400 at the sign-in page.
#
#   ./scripts/dev-lan-realm.sh 192.168.100.98
#
# Reaching the app from the LAN needs three more things, none of which this script touches:
#   - KC_HOSTNAME=http://<ip>:8081 in .env      (what Keycloak advertises to the browser)
#   - the LAN binding in compose.override.yml   (see compose.override.yml.example)
#   - server.address, the issuer-uri pair and ichat.allowed-origins in
#     src/main/resources/application-dev.properties
set -euo pipefail

IP="${1:-}"
if [[ -z "$IP" ]]; then
  echo "usage: $0 <lan-ip>   e.g. $0 192.168.100.98" >&2
  exit 2
fi

REALM=ichat-realm
CLIENT=ichat-client
KC=(podman compose exec -T keycloak /opt/keycloak/bin/kcadm.sh)

# Idempotent: the URI lists are set outright rather than appended, so re-running converges on the
# same state instead of accumulating duplicates.
REDIRECTS="[\"http://localhost:8080/*\",\"http://127.0.0.1:8080/*\",\"http://${IP}:8080/*\"]"
ORIGINS="[\"http://localhost:8080\",\"http://127.0.0.1:8080\",\"http://${IP}:8080\"]"

"${KC[@]}" config credentials --server http://localhost:8080 \
  --realm master --user admin --password admin >/dev/null

ID="$("${KC[@]}" get clients -r "$REALM" -q "clientId=$CLIENT" \
      --fields id --format csv --noquotes 2>/dev/null | tr -d '\r\n')"
if [[ -z "$ID" ]]; then
  echo "client $CLIENT not found in realm $REALM — is Keycloak finished importing?" >&2
  exit 1
fi

# post.logout.redirect.uris is a THIRD list, and forgetting it is its own bug: sign-in works,
# the app works, and only signing out fails — with "Invalid redirect uri" from Keycloak, which
# reads like a login problem. "+" means "whatever the redirect URIs are", so the two lists cannot
# drift apart again.
"${KC[@]}" update "clients/$ID" -r "$REALM" \
  -s "redirectUris=$REDIRECTS" -s "webOrigins=$ORIGINS" \
  -s 'attributes."post.logout.redirect.uris"=+' >/dev/null

echo "ichat-client now accepts http://${IP}:8080"
"${KC[@]}" get "clients/$ID" -r "$REALM" --fields redirectUris,webOrigins,attributes
