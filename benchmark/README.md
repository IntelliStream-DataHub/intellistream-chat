# WebSocket load benchmark

A pure-JDK (Java 25 virtual threads + `java.net.http` WebSocket, no dependencies) STOMP-over-WS
load generator for ThreadOrbit. Measures connection scalability, delivery latency/throughput, and
message-burst behaviour. Results and analysis live in [`../scalability.md`](../scalability.md).

## Files

| File | What |
|---|---|
| `WsLoadTest.java` | The generator. One OIDC login (session reused), opens N connections across rooms, sends, measures. |
| `AuthProbe.java` | Standalone: performs the OIDC login and prints the `JSESSIONID` (used to de-risk auth). |
| `setup-rooms.sh` | Bulk-creates N `bench-room-*` channels + memberships via SQL (bypasses the SEC-9 create rate-limit); writes ids to `rooms.txt`. |
| `results/*.json` | Raw per-run metrics. |

## 1. Start the app in the `bench` profile

The `bench` profile disables per-user rate limits (one user drives all connections), binds
`0.0.0.0` (so the client can spread across loopback IPs), and raises Tomcat limits. Keep the LAN IP
out of committed files — pass it at runtime:

```bash
./gradlew bootJar
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[]|select(.clientId=="threadorbit").secret' keycloak/realm.json)
export SPRING_PROFILES_ACTIVE=bench
export KEYCLOAK_ISSUER_URI=http://<keycloak-host>:8081/realms/threadorbit
export BENCH_ALLOWED_ORIGINS='http://127.0.0.*:8080,http://<login-host>:8080,http://localhost:8080'
java -Xmx10g -XX:+UseZGC --enable-native-access=ALL-UNNAMED \
     -Dspring.datasource.hikari.maximum-pool-size=50 \
     -Dthreadorbit.ws.inbound-threads=48 -Dthreadorbit.ws.outbound-threads=96 \
     -jar build/libs/threadorbit-*.jar
```

## 2. Apply the OS tuning (once, root)

See the tuning table in `../scalability.md`. Minimum for high connection counts:

```bash
sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535" net.core.somaxconn=65535 \
  net.ipv4.tcp_max_syn_backlog=65535 net.core.netdev_max_backlog=65535 \
  net.ipv4.tcp_tw_reuse=1 net.netfilter.nf_conntrack_max=1048576
# somaxconn takes effect at bind — restart the app after.
```

## 3. Create rooms and run a tier

```bash
# rooms = connections / room-size (here 10000/50 = 200)
benchmark/setup-rooms.sh http://<login-host>:8080 alice alice 200

SPID=$(ss -tlnp | grep ':8080' | grep -oP 'pid=\K[0-9]+' | head -1)   # for RSS/CPU sampling
java benchmark/WsLoadTest.java \
  --base http://<login-host>:8080 --user alice --pass alice \
  --dst-hosts 127.0.0.1 --echo \
  --conns 10000 --room-size 50 --ramp 8 --duration 20 --burst 10000 \
  --server-pid "$SPID" --report benchmark/results/tier-10k.json
```

### Options

| Flag | Meaning |
|---|---|
| `--conns N` | Connections to open. |
| `--room-size S` | Members per room; 1 sender/room at 1 msg/s. `1` = pure post throughput (every conn a sender, no fan-out). |
| `--dst-hosts a,b,…` | Loopback IPs to spread connections over (one `HttpClient`/selector pool each). Needed past ~28–64k conns per IP. Requires the server bound to `0.0.0.0`. |
| `--echo` | Send to the `@Profile("bench")` echo endpoint (broadcast only, no DB/render/index) — isolates WS + broker fan-out. Omit for the full persist path. |
| `--ramp SEC` | Spread connection opens over SEC seconds (avoid a thundering-herd connect). |
| `--duration SEC` | Steady-state seconds (senders at 1 msg/s). |
| `--burst N` | After steady, fire N messages within ~1 s. |
| `--server-pid PID` | Sample the server's RSS + CPU from `/proc`. |
| `--report FILE` | Write metrics JSON. |

## Reaching the high tiers

- `--dst-hosts 127.0.0.1,127.0.0.2,…` — each loopback IP is a fresh ~64k ephemeral-port pool and a
  separate client selector pool.
- On a single 12-core / 31 GB box, co-located, ~70k established sessions is the ceiling (memory).
  For 100k–250k, run the generator on a **separate** machine (`--base`/`--dst-hosts` already point
  anywhere) and give the server more RAM. See `../scalability.md`.

## Cleanup

`setup-rooms.sh` deletes and recreates `bench-room-*` on each run; to remove them entirely:

```bash
podman exec -i chat_postgres_1 psql -U threadorbit -d threadorbit_chat \
  -c "delete from channels where slug like 'bench-room-%';"
```

(That leaves orphaned Lucene docs; the startup reconcile / CLEAN-3 drops them on next boot.)
