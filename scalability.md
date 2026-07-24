# ThreadOrbit — WebSocket scalability benchmark

How many concurrent WebSocket connections ThreadOrbit holds, how it behaves under a message
burst, where it breaks, and the OS/JVM/app tuning that got it there. Run on a single box with the
app **and** the load generator co-located (the deliberate "push this box to its limit" setup).

> Reproduce: see [`benchmark/README.md`](benchmark/README.md). Raw results are in
> `benchmark/results/*.json`.

## TL;DR

- **Concurrent open sockets held:** **10k** fully healthy · **50k** established and held but
  message delivery degrades · **~70k** is this box's hard ceiling (memory + connect throughput).
  **100k / 250k are not reachable co-located on this box** — see [Reaching 100k–250k](#reaching-100k250k).
- **Two very different ceilings, found by testing with and without persistence:**
  - **Broker/WebSocket fan-out** (a bench echo path, no DB) sustains ~**10k deliveries/s** cleanly
    at 10k connections; a 10k-message burst (→500k fan-out deliveries into 50-member rooms) drains
    over ~10–15 s.
  - **The real write path** (a normal message: DB insert → Markdown render → Lucene index) was
    capped at **~5.6 message-posts/s** by a **per-message Lucene `commit()`** — not the WebSocket
    layer. **Fixed this pass** by making Lucene indexing async/batched: **~60 posts/s (~10×)**, with
    a startup reconcile so an unclean shutdown never needs a rebuild.
- **Reaching the 10,000 posts/s target** from here is architectural (batched DB writes, async/
  parallel rendering, a partitioned/external broker) — see
  [Path to 10k posts/s](#path-to-10k-postss). It is *not* a WebSocket/broker limit.

## The box

| | |
|---|---|
| CPU / RAM | 12 cores · 31 GB |
| Kernel | Linux 6.12 |
| Runtime | Java 25, Spring Boot 4.1, ZGC |
| Broker | in-process simple broker (no external message broker) |
| Search | embedded Apache Lucene, on local disk |
| Layout | **app + load generator on the same host** (server↔client over loopback) |

Co-location is a real constraint: at 50k connections the *server* alone used ~10 GB, and the
generator holding 50k client-side sockets used several GB more — so "push to the limit" is really
"push half a box of server against half a box of client."

## Methodology

- **Topology — realistic rooms.** Connections are spread across rooms of 50. One connection per
  room sends 1 msg/s; the message fans out to that room's 50 members. So at *N* connections there
  are *N*/50 rooms, ~*N*/50 msgs/s offered, and ~*N* deliveries/s. (This is the topology chosen for
  the run; the broker load is bounded by room size, not total connections.)
- **Burst** = 10,000 messages fired within ~1 s, i.e. ~500,000 fan-out deliveries.
- **Two message paths, measured separately** to tell the WebSocket/broker cost apart from the
  persistence cost:
  - **echo** (`--echo`): a `@Profile("bench")` endpoint (`/app/bench/{id}/echo`) that broadcasts to
    the room topic with **no** DB / render / index. Isolates the pure WS + broker + fan-out.
  - **full** (default): the real `/app/channels/{id}/send` — persist + render + index + broadcast.
- **Auth:** one OIDC login; the session cookie is reused across every connection (a load test of
  connection capacity, not of Keycloak).
- **Rate limits off:** the `bench` profile sets `threadorbit.ratelimit.enabled=false`. All
  connections use one user, so the per-user `ws-send` cap (30/min) would otherwise cap the whole
  test at 30 messages — a test artifact, not a server limit.
- **Metrics:** connection setup time & failures; delivery latency p50/p99/max (send→received, all
  connections in one JVM so `nanoTime` is comparable); throughput & dropped/late; and the server
  process's peak RSS + CPU sampled from `/proc`.

## Results

Delivery latency and "dropped" are within a bounded drain window (10 s after the burst); "dropped"
therefore means **not delivered within the window**, i.e. backlog, not necessarily lost.

### Connection scalability (echo path — WS + broker only)

| Tier | Established | Connect time | Steady (1 msg/s/room) | Burst 10k msgs | Server peak RSS |
|-----:|:-----------:|:------------:|:----------------------|:---------------|:---------------:|
| **10k** | 10,000 / 10,000 (0 fail) | 13.5 s | **0 dropped**, 10k deliv/s, lat p50 **202 ms** / p99 1.1 s | 326k of 500k in 10 s, lat p50 6.1 s | 5.0 GB |
| **50k** | 50,000 / 50,000 (0 fail) | 40.6 s | **97% backlog**, 1.3k deliv/s, lat p50 **33 s** | did not clear | 10.0 GB |
| **~70k** | ceiling — see below | — | — | — | ~28 GB (box) |
| **100k** | **not reached** | timed out | — | — | RAM exhausted |

- **10k is the comfortable operating point** on this box: every socket up, steady state clean at
  200 ms, and the burst is absorbed (drains over ~10 s).
- **50k sockets can be *held*** (0 connection failures, 10 GB server RSS) **but not *served*** —
  steady delivery falls to ~1.3k/s at 33 s latency. The broker + co-located client can't push
  50k deliveries/s through 50k sockets on one box.
- **~70k is the hard ceiling.** Driving 100k: the client opened all **100,001** TCP connections,
  but only **~70,800** completed the STOMP handshake before the box ran out of memory (28 / 31 GB
  used, ~0 free) and connect throughput stalled; the run was killed after 7 min. So the server
  upgraded ~70k of 100k. **Cost ≈ 150–200 KB of server RSS per connection**, plus client-side
  socket memory — memory is the binding constraint.

### Write-path throughput (full path — persist + render + index)

| Tier | Established | Steady offered | Steady delivered | Effective posts/s | Steady lat p50 |
|-----:|:-----------:|:--------------:|:-----------------|:-----------------:|:--------------:|
| **10k** | 10,000 / 10,000 | 200 msg/s (200 senders) | 283 deliv/s (**97% backlog**) | **~5.6 posts/s** | **25 s** |

Even at 10k connections with only 200 msg/s offered, the **write path saturates at ~5–6
posts/second** and the backlog grows to a 25 s delivery latency. The WebSocket layer is not the
limit here — the per-message persistence work is.

## Bottleneck analysis

### Bottleneck 1 — the write path (per-message Lucene commit **[FIXED]**, then DB + render)

Originally `MessageService.post` → `MessageIndexService.index()` did `writer.updateDocument(...)`
**followed by `writer.commit()` on every message**. `commit()` is heavyweight (flush segments,
write the commit point, fsync) and the `IndexWriter` is one shared instance, so concurrent posts
serialized on it — dropping the write path to **~5.6 posts/s**. This was **not** slow storage: a
single-threaded `fio --fdatasync=1` on this NVMe does **~1,200 fsync/s** (≈800 µs, p99 3.4 ms), so
the app was ~200× below the fsync ceiling. The cost was the per-message `commit()` *overhead*
serialized under concurrency, not the fsync itself.

**Fixed** (`MessageIndexService`): indexing is now **async and batched**. Per message we
`updateDocument` (in-memory) and only *stage* the refresh + commit; a scheduled maintainer batches
the NRT `maybeRefresh()` (visibility) and the `commit()` (durability) every ~250 ms across all
messages since the last tick. Lucene's `SearcherManager` is NRT (built from the writer), so docs
are searchable after the refresh **without** a commit. Result: **5.6 → ~60 posts/s (~10×)**.
(Tests set `threadorbit.search.async-indexing=false` for immediate synchronous visibility, so the
post-then-search assertions stay deterministic.)

**Durability with async commit — no WAL, no rebuild:** Lucene has no write-ahead log; between
commits, staged docs live in memory and are lost on an unclean shutdown. That's safe here because
**Postgres is the source of truth** and the index is derived: segments are write-once (a crash
never corrupts the index — it reopens at the last commit), and `LuceneBootstrap` now runs a
**reconcile at startup** — it diffs DB message-ids vs index-ids and re-indexes the ≤250 ms tail
that a crash dropped, so the tail heals in seconds instead of waiting for the periodic CLEAN-3
reconcile. Worst case after a hard crash: the last fraction of a second of messages is briefly
unsearchable, never lost.

**Remaining write-path cost (the next bottleneck):** after the Lucene fix the ceiling is ~60
posts/s, and the box is *not* CPU-bound (~570% of 1200%), so it's still serialized on the
remaining per-message work: **one Postgres transaction per message** (WAL fsync — though
`synchronous_commit=off` and a bigger inbound pool barely moved it, pointing at back-pressure
rather than the fsync) and **2–3 CommonMark parses + a jsoup sanitize per message** for
server-side rendering, funnelled through the `clientInboundChannel`. Getting from 60 to the
**10,000 posts/s target** is an architecture effort — see [Path to 10k posts/s](#path-to-10k-postss).

### Bottleneck 2 — broker fan-out & the simple broker

The in-process simple broker is single-node and routes on one core (thread `MessageBroker-1`); the
`clientOutboundChannel` then fans each message out to every subscriber. At 10k connections it
sustains ~10k deliveries/s and absorbs a 500k-delivery burst over ~10 s. At 50k it can't keep up.
Levers: raise `clientOutboundChannel` threads (`WebSocketConfig.configureClientOutboundChannel`),
but the simple broker has a hard single-node ceiling. Real horizontal scale needs an external
broker relay (RabbitMQ/ActiveMQ STOMP) or a Redis/pub-sub fan-out — see the
`horizontal-scalability-plan` (deferred while on the embedded broker + Lucene).

### Bottleneck 3 — connect throughput & memory

- **Connect throughput ≈ 1–2k handshakes/s.** Each WS handshake runs the servlet chain +
  `CurrentUser` upsert (a DB round-trip) and each STOMP `SUBSCRIBE` runs a channel-membership
  query. Under a ramp these queue (setup p50 rose to 3.5–5.5 s). Levers: cache membership /
  `CurrentUser` per session, and raise the DB pool (we used 50; capped by Postgres `max_connections`
  = 100).
- **Memory ≈ 150–200 KB/connection** server-side. On a 31 GB box that's the ~70k wall (server
  heap + off-heap NIO buffers + kernel socket structs; on loopback every connection is *two*
  sockets on the same host).

## OS / kernel tuning applied

The stock kernel is provisioned for a workstation, not 10⁵ sockets. Applied with `sysctl -w`
(non-persistent; to persist, drop these in `/etc/sysctl.d/99-threadorbit-bench.conf` and
`sysctl --system`):

| Tunable | Default | Set to | Why |
|---|---:|---:|---|
| `net.ipv4.ip_local_port_range` | `32768 60999` (~28k) | `1024 65535` (~64k) | Client ephemeral ports — one dst IP tops out at ~28k connections otherwise. |
| `net.core.somaxconn` | `4096` | `65535` | Listen accept-queue depth; caps Tomcat `accept-count`. **Requires an app restart** (backlog is set at `bind()`). |
| `net.ipv4.tcp_max_syn_backlog` | `2048` | `65535` | SYN queue during a connect burst. |
| `net.core.netdev_max_backlog` | `1000` | `65535` | Per-CPU ingress packet queue. |
| `net.ipv4.tcp_tw_reuse` | `2` | `1` | Reuse `TIME_WAIT` sockets on rapid reconnect (ramp churn). |
| `net.ipv4.tcp_fin_timeout` | `60` | `15` | Recycle closing sockets faster. |
| `net.netfilter.nf_conntrack_max` | `262144` | `1048576` | conntrack is loaded and tracks loopback; ~250k connections would exhaust 262k → dropped connections. |

Left at defaults but relevant: `fs.file-max` and `fs.nr_open` are already effectively unlimited;
`ulimit -n` is **524288** (enough for both server and client). `net.ipv4.tcp_rmem`/`tcp_wmem`
maxima (6 MB / 4 MB) were left alone — for a chat workload (tiny messages) buffers stay near the
minimum, but at 250k× two-ended loopback sockets they are a memory lever worth *lowering* if you
chase that tier.

### Beyond one dst IP

Even with a 64k ephemeral range, one client can only make ~64k connections to a single
`(dstIP, dstPort)`. The generator spreads connections across loopback IPs `127.0.0.1 … 127.0.0.k`
(the server binds `0.0.0.0`), which also parallelizes the client's `HttpClient` selector pools —
one client per dst IP. Each extra dst IP adds a fresh ~64k ephemeral-port pool.

## App / JVM tuning

- **`bench` Spring profile** (`application-bench.properties`): `ratelimit.enabled=false`,
  `server.address=0.0.0.0`, wildcard loopback Origins, and Tomcat `max-connections=300000` /
  `accept-count=10000` / `threads.max=400`.
- **JVM:** `-XX:+UseZGC` (concurrent, sub-ms pauses — right for many connections + low latency);
  heap sized to the tier (`-Xmx10g` at 10k, up to `-Xmx18g` chasing 100k). `--enable-native-access=ALL-UNNAMED` (Lucene).
- **DB pool:** `spring.datasource.hikari.maximum-pool-size=50` (Postgres `max_connections` is 100;
  100 exhausted it and starved `psql`/Keycloak). Raise Postgres `max_connections` before raising
  the pool further.

## Path to 10k posts/s

The write path is ~60 posts/s single-node after the Lucene fix. Reaching 10,000 posts/s is an
architecture effort, in impact order:

1. **Batch the DB writes — the biggest lever.** One INSERT transaction per message is the dominant
   remaining serialization. Accumulate messages and write them in batches (multi-row INSERT /
   write-behind queue): 10k posts/s becomes ~100 transactions/s ≈ 100 fsync/s, an order of
   magnitude under the 1,200 fsync/s the disk sustains. Trade-off: a message is durable after a
   short batch delay (tens of ms); the broadcast can carry a client temp-id reconciled on ack.
   Expect 10–50× on the DB layer alone.
2. **Take rendering off the hot path / parallelize it.** Each post runs 2–3 CommonMark parses +
   a jsoup sanitize; once #1 unblocks the pipeline this becomes the CPU wall. Levers: extract
   mentions from the *same* AST the renderer already builds (kill the redundant double-parse),
   cache/reuse the parser, or render asynchronously — broadcast `bodyMarkdown` immediately and
   `bodyHtml` when ready.
3. **Fan out beyond the single simple-broker thread.** 10k posts/s × room fan-out is up to
   millions of deliveries/s, and the in-process simple broker routes on one thread. Partition
   topics across worker threads, or move to an external STOMP relay (RabbitMQ / ActiveMQ) or a
   Redis pub/sub — which is also the multi-node story (`horizontal-scalability-plan`).
4. **Channel/DB tuning (now configurable, no rebuild):** `threadorbit.ws.inbound-threads` /
   `outbound-threads`, `spring.datasource.hikari.maximum-pool-size` (with Postgres
   `max_connections`), and `synchronous_commit=off` if the durability window is acceptable.

**Bottom line for throughput:** the Lucene commit was the first wall (fixed, 10×); #1–#3 are the
rest of the way to 10k — batched writes, async/parallel render, and a partitioned/external broker.
None is a config knob; #1 alone should reach the low thousands.

## Reaching 100k–250k

Not on this box co-located. Concretely:

1. **Split server and client.** Run the generator on one or more *separate* machines
   (`--base`/`--dst-hosts` are already parameterized). This frees ~half the RAM and all the
   client-side selectors, and removes CPU contention.
2. **Give the server RAM.** At ~150–200 KB/connection, 100k ≈ 20 GB and 250k ≈ 50 GB of server RSS
   before message load — so 64 GB+ for 250k, plus headroom.
3. **Fix the write path (Bottleneck 1)** if the tier must *serve* messages, not just *hold* sockets.
4. **Replace the simple broker (Bottleneck 2)** for real multi-node fan-out — an external STOMP
   relay or a pub/sub layer, per the `horizontal-scalability-plan`.
5. Persist the kernel tuning and, for 250k, raise conntrack / consider `NOTRACK` on loopback and
   lower per-socket TCP buffers.

## Bottom line

On a single 12-core / 31 GB box, co-located, ThreadOrbit **comfortably holds 10k concurrent
WebSocket connections** with clean sub-second delivery, **holds up to ~50–70k sockets** (delivery
degrades well before the socket ceiling), and **cannot reach 100k** without more RAM and a separate
load generator. The gating issue for *throughput* is not the WebSocket/broker layer at all — it's
the **per-message Lucene commit** in the write path (~5–6 posts/s under load), which is the first
thing to fix for any serious message volume.
