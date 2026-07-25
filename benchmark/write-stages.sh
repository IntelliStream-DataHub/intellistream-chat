#!/usr/bin/env bash
# Print the per-stage write-path cost breakdown from the running bench instance.
# Requires the bench profile (which exposes /actuator/metrics). Usage:
#   benchmark/write-stages.sh [base-url] [user] [pass]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BASE=${1:-http://127.0.0.1:8080}; USER=${2:-alice}; PASS=${3:-alice}
JAVA=${JAVA:-/usr/lib/jvm/java-25-openjdk/bin/java}

JS=$("$JAVA" "$HERE/AuthProbe.java" "$BASE" "$USER" "$PASS" 2>/dev/null | grep -oP '^JSESSIONID=\K\S+')
[ -n "$JS" ] || { echo "could not authenticate to $BASE"; exit 1; }

for s in total resolve-user load-channel slash-dispatch persist persist.access-check persist.insert persist.mention-sync persist.enqueue persist.index mention-readback poll-lookup render broadcast; do
  curl -s -H "Cookie: JSESSIONID=$JS" "$BASE/actuator/metrics/threadorbit.write.stage?tag=stage:$s" \
    | jq -r --arg s "$s" '[$s,
        ([.measurements[]|select(.statistic=="COUNT").value][0] // 0),
        ([.measurements[]|select(.statistic=="TOTAL_TIME").value][0] // 0),
        ([.measurements[]|select(.statistic=="MAX").value][0] // 0)] | @tsv'
done | awk 'BEGIN{printf "%-18s %9s %11s %10s\n","STAGE","COUNT","MEAN_ms","MAX_ms"}
            {printf "%-18s %9d %11.3f %10.3f\n",$1,$2,($2>0?$3/$2*1000:0),$4*1000}'
