#!/usr/bin/env bash
# Package a release: the runnable jar, the built JS/CSS bundles as plain files, the licence and the
# quickstart, in one gzipped tarball plus checksums. The release workflow runs this after
# `./gradlew build`, and it can be run by hand to check what a release will contain:
#
#   ./gradlew bootJar
#   ./scripts/package-release.sh v0.3
#
# The jar is the only thing needed to run the app. The bundles are already inside it, and they are
# copied out here for anyone serving them from a CDN or a proxy. Output lands in build/release/.
set -euo pipefail

tag=${1:?usage: scripts/package-release.sh <tag>}
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/build/release"
name="intellistream-chat-$tag"
stage="$out/$name"

jars=$(ls "$root"/build/libs/*.jar | grep -v -- '-plain\.jar' || true)
if [ "$(printf '%s\n' "$jars" | grep -c .)" -ne 1 ]; then
  echo "expected exactly one runnable jar in build/libs/, found:" >&2
  printf '%s\n' "$jars" >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$stage/assets"

cp "$jars" "$out/$name.jar"
cp "$jars" "$stage/$name.jar"
cp -r "$root/build/generated/assets/static/." "$stage/assets/"
cp "$root/LICENSE" "$root/THIRD-PARTY-NOTICES.md" "$root/QUICKSTART-MANUAL.md" "$stage/"

cat > "$stage/README.txt" <<EOF
IntelliStream Chat $tag
=======================

Requires Java 25 and a PostgreSQL 18 database. Keycloak 26 provides sign-in.

Run it:

    java -jar $name.jar

Settings come from environment variables, with the defaults in application.yml inside the jar.
At minimum set the database and the OIDC client:

    ICHAT_DB_URL=jdbc:postgresql://localhost:5432/intellistream_chat
    ICHAT_DB_USERNAME=ichat_role
    ICHAT_DB_PASSWORD=...
    KEYCLOAK_ISSUER_URI=https://auth.example.com/realms/ichat-realm
    KEYCLOAK_CLIENT_SECRET=...

QUICKSTART-MANUAL.md covers a full host install, including systemd and the reverse proxy.

assets/ holds the same JavaScript and CSS bundles the jar serves. They are content-versioned
(?v=<hash>), so they can be cached for a long time behind a proxy without going stale.
EOF

tar -czf "$out/$name.tar.gz" -C "$out" "$name"
(cd "$out" && sha256sum "$name.tar.gz" "$name.jar" > SHA256SUMS)

echo "release contents in $out:"
ls -1 "$out"
