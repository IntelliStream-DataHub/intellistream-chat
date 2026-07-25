# WebSocket load benchmark

A pure-JDK (Java 25 virtual threads + `java.net.http` WebSocket, no dependencies) STOMP-over-WS
load generator for IntelliStream Chat. Measures message throughput, connection scalability, delivery
latency, and burst behaviour. Results and analysis live in [`../scalability.md`](../scalability.md).

## Files

| File | What |
|---|---|
| `WsLoadTest.java` | The generator. One OIDC login (session reused), opens N connections across rooms, sends, measures. |
| `run-bench-app.sh` | Starts a bench-profile instance on `127.0.0.1:8080`. |
| `restart-bench-app.sh` | Stops the running bench instance, waits for it to exit, starts a fresh one, prints its pid. |
| `post-throughput.sh` | The headline measurement: warmup pass + measured closed-loop run at `--room-size 1`. |
| `write-stages.sh` | Per-stage server-side cost breakdown of one message, from `/actuator/metrics`. |
| `setup-rooms.sh` | Bulk-creates N `bench-room-*` channels + memberships via SQL (bypasses the SEC-9 create rate-limit); writes ids to `rooms.txt`. |
| `AuthProbe.java` | Standalone: performs the OIDC login and prints the `JSESSIONID`. |
| `results/*.json` | Raw per-run metrics. |

## 1. Start the app in the `bench` profile

The `bench` profile disables per-user rate limits (one user drives all connections), raises the
Tomcat limits, exposes actuator metrics, enables write-behind INSERT batching, and points Lucene at
its own directory.

`run-bench-app.sh` binds **`127.0.0.1:8080`** rather than `0.0.0.0`, so a dev instance bound to a
LAN IP on the same port can keep running alongside it. That requires the Keycloak client to accept
the loopback redirect URI:

```bash
KC=http://<keycloak-host>:8081
TOK=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
       -d 'grant_type=password&client_id=admin-cli&username=admin&password=admin' | jq -r .access_token)
ID=$(curl -s -H "Authorization: Bearer $TOK" "$KC/admin/realms/ichat-realm/clients?clientId=ichat-client" | jq -r '.[0].id')
CUR=$(curl -s -H "Authorization: Bearer $TOK" "$KC/admin/realms/ichat-realm/clients/$ID")
echo "$CUR" | jq '.redirectUris += ["http://127.0.0.1:8080/*"] | .webOrigins += ["http://127.0.0.1:8080"]' \
  | curl -s -X PUT -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d @- \
      "$KC/admin/realms/ichat-realm/clients/$ID"
```

Then:

```bash
./gradlew bootJar
KC_HOST=<keycloak-host> benchmark/restart-bench-app.sh
```

It prints the executor behind each STOMP channel — check this. If the inbound channel is anything
other than a properly sized pool, every number you measure afterwards is a measurement of that
mistake (see `scalability.md`):

```
STOMP clientInboundChannel  -> ThreadPoolTaskExecutor[prefix=stomp-inbound-, core=48, max=48, ...]
```

Knobs (environment variables): `HEAP`, `POOL` (Hikari), `INBOUND` / `OUTBOUND` (STOMP channel
threads), `JFR=/path/to/rec.jfr` to profile the run, `KC_HOST`.

To bind `0.0.0.0` instead — needed only for the high connection tiers, which spread the client
across several loopback IPs — set `BENCH_SERVER_ADDRESS=0.0.0.0` and don't run a second instance on
the same port.

## 2. Apply the OS tuning (once, root)

Only needed for the high *connection* tiers; throughput runs use a few hundred sockets. See the
tuning table in `../scalability.md`.

```bash
sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535" net.core.somaxconn=65535 \
  net.ipv4.tcp_max_syn_backlog=65535 net.core.netdev_max_backlog=65535 \
  net.ipv4.tcp_tw_reuse=1 net.netfilter.nf_conntrack_max=1048576
# somaxconn takes effect at bind — restart the app after.
```

## 3. Measure message throughput

```bash
benchmark/setup-rooms.sh http://127.0.0.1:8080 alice alice 400   # rooms = connections at room-size 1
benchmark/post-throughput.sh my-run 400 30                       # <report-name> [conns] [duration]
benchmark/write-stages.sh                                        # where the time went, per stage
```

`post-throughput.sh` runs a throwaway warmup pass first. Don't skip it: cold-JIT throughput on this
code is roughly half of warm, which is enough to invent a bottleneck that doesn't exist.

## 4. Measure fan-out, connection tiers, bursts

```bash
# realistic rooms: 2000 connections in 50-member rooms, closed loop
java benchmark/WsLoadTest.java --base http://127.0.0.1:8080 --user alice --pass alice \
  --conns 2000 --room-size 50 --in-flight 16 --ramp 5 --duration 25 \
  --server-pid "$(pgrep -f 'intellistream-chat.*SNAPSHOT.jar' | head -1)" \
  --report benchmark/results/rooms50.json
```

### Options

| Flag | Meaning |
|---|---|
| `--conns N` | Connections to open. |
| `--room-size S` | Members per room; one sender per room. `1` = pure post throughput, no fan-out amplification, so deliveries/s == posts/s. |
| `--in-flight K` | **Closed loop**: each sender keeps at most K messages outstanding, waiting for its own to return. Offered load self-adjusts to server capacity, so throughput is the real service rate and latency is service time. Use this to measure a ceiling. |
| `--send-rate R` | **Open loop**: each sender fires R msg/s regardless of whether the server keeps up. Use this to probe overload behaviour, not capacity. Ignored when `--in-flight` is set. |
| `--dst-hosts a,b,…` | Loopback IPs to spread connections over (one `HttpClient`/selector pool each). Needed past ~28–64k conns per IP. Requires the server bound to `0.0.0.0`. |
| `--echo` | Send to the `@Profile("bench")` echo endpoint (broadcast only, no DB/render/index) — isolates WS + broker fan-out. Omit for the full persist path. |
| `--ramp SEC` | Spread connection opens over SEC seconds (avoid a thundering-herd connect). |
| `--duration SEC` | Steady-state seconds. |
| `--burst N` | After steady, fire N messages within ~1 s. |
| `--server-pid PID` | Sample the server's RSS + CPU from `/proc`. |
| `--report FILE` | Write metrics JSON. |

Throughput is computed over the measured steady-state window only, sampled before the drain sleep,
so a backlog draining afterwards is never counted as capacity.

## Reading the results

- `postsPerSec` — messages accepted, persisted and delivered per second. The headline.
- `deliveredPerSec` — fan-out deliveries per second (`postsPerSec × roomSize`).
- `dropped` — expected minus delivered within the window: backlog, not necessarily lost.
- `serverMaxCpuPct` — out of `100 × cores`. If this is well under the box's total while throughput
  is flat, the **generator** is the limit, not the server. Co-located, that happens early.

Sanity check any surprising number with *throughput × latency = concurrency*. If that product is
far below the number of threads you think are working, something upstream is serializing.

## Reaching the high connection tiers

- `--dst-hosts 127.0.0.1,127.0.0.2,…` — each loopback IP is a fresh ~64k ephemeral-port pool and a
  separate client selector pool. Requires `BENCH_SERVER_ADDRESS=0.0.0.0`.
- On a single 12-core / 31 GB box, co-located, **100k established and served** is reproducible
  (0 connect failures, 0 dropped deliveries). 150k is not — but the limit is the *generator*,
  which costs 174 KB/connection against the server's 82 KB, so it exhausts the box first. For
  150k+, run the generator on a **separate** machine (`--base`/`--dst-hosts` already point
  anywhere). See `../scalability.md`.

## Cleanup

`setup-rooms.sh` deletes and recreates `bench-room-*` on each run. A throughput run inserts
millions of rows, so to reclaim the space entirely:

```bash
podman exec -i chat_postgres_1 psql -U intellistream -d intellistream_chat \
  -c "delete from channels where slug like 'bench-room-%';"
```

(That leaves orphaned Lucene docs in the bench index; the startup reconcile / CLEAN-3 drops them on
next boot. The bench index lives in `./data/lucene-bench`, separate from the dev one, and can just
be deleted while the bench app is stopped.)
