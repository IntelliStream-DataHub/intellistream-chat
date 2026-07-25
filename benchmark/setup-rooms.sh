#!/usr/bin/env bash
# Provision the benchmark user and bulk-create N public "bench-room-*" channels (member: the user),
# writing their ids to benchmark/rooms.txt. Bypasses the API (SEC-9 rate-limits channel creation);
# this is benchmark setup, not production.
#
# Usage: benchmark/setup-rooms.sh <base-url> <user> <pass> <room-count>
set -euo pipefail
BASE=${1:?base url}; USER=${2:?user}; PASS=${3:?pass}; ROOMS=${4:?room count}
JAVA=${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}
HERE="$(cd "$(dirname "$0")" && pwd)"
PSQL=(podman exec -i chat_postgres_1 psql -U intellistream -d intellistream_chat -tA)

# 1) Provision the user (any authenticated request upserts their row via CurrentUser).
"$JAVA" "$HERE/AuthProbe.java" "$BASE" "$USER" "$PASS" >/dev/null
USERID=$(echo "select id from users where username='$USER';" | "${PSQL[@]}")
[ -n "$USERID" ] || { echo "ERROR: user $USER was not provisioned"; exit 1; }

# 2) Recreate the bench rooms + memberships, emit their ids.
echo "delete from channels where slug like 'bench-room-%';" | "${PSQL[@]}" >/dev/null
"${PSQL[@]}" > "$HERE/rooms.txt" <<SQL
with new_ch as (
  insert into channels (slug, name, description, type, created_by)
  select 'bench-room-'||g, 'bench-room-'||g, 'bench', 'PUBLIC', $USERID
  from generate_series(0, $((ROOMS-1))) g
  returning id
), mem as (
  insert into channel_members (channel_id, user_id, role)
  select id, $USERID, 'ADMIN' from new_ch returning 1
)
select id from new_ch order by id;
SQL
COUNT=$(grep -c '^[0-9]\+$' "$HERE/rooms.txt")
echo "created $COUNT bench rooms for user $USER (id=$USERID) -> $HERE/rooms.txt"
