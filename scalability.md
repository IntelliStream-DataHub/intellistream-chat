# IntelliStream Chat — performance and scalability on a single machine

IntelliStream Chat is designed to be a chat server you run on one box. This document reports what one box
does: how many messages a second it persists and delivers, how many concurrent WebSocket
connections it holds while still serving them, what governs each of those limits, and how to size a
machine for your own workload.

Every figure below is a measurement, not a projection. Raw results are committed under
[`benchmark/results/*.json`](benchmark/results); the harness and how to re-run it are in
[`benchmark/README.md`](benchmark/README.md).

## Headline

On a single 12-core / 31 GB machine, with no external message broker and no external search
service:

| | Measured | Conditions |
|---|---:|---|
| **Messages persisted + delivered** | **17,066 / second** | p50 21.6 ms end-to-end, p99 61.5 ms, 0 dropped over 427,251 messages |
| **Fan-out** | **136,043 deliveries / second** | 2,000 connections, 50-member rooms, 0 dropped, p50 228 ms |
| **Concurrent connections, served** | **100,000** | 100,000/100,000 established, 2,000,000/2,000,000 deliveries, 0 dropped, p50 792 ms |
| **Connection setup** | **p50 5.0 ms** | at the 100,000-connection tier |
| **Memory at 100k connections** | **11.2 GiB RSS** | whole JVM, including heap |

"Persisted + delivered" is end-to-end and includes every step: the client only counts a message
once it arrives back over its socket, so the number covers the Postgres commit, Markdown rendering
and sanitization, Lucene indexing, broker fan-out and the WebSocket write. **A message is committed
to the database before anyone sees it** — see [Durability](#durability-broadcast-happens-after-the-commit).

For context on the throughput figure: the same code and box measured **109 messages/second** before
the tuning described here, a factor of ~157.

## The machine

| | |
|---|---|
| CPU / RAM | 12 cores · 31 GB |
| Kernel | Linux 6.12 |
| Runtime | Java 25, Spring Boot 4.1, ZGC |
| Storage | local NVMe |
| Broker | in-process simple broker — no RabbitMQ, no external STOMP relay |
| Search | embedded Apache Lucene on local disk |
| Database | Postgres 18, same host |

This is a workstation-class machine, deliberately. The point of the exercise was to find what a
single ordinary server does before anyone needs to think about clustering.

## How it was measured, and the one caveat that matters

**The load generator runs on the same box as the server.** That is the honest constraint on every
number here, and it cuts one way: the numbers are floors, not ceilings.

In the fan-out runs the server used ~380% CPU of the 1,200% available while throughput was flat —
the generator, parsing 136,000 STOMP frames a second, was the party that ran out. Past roughly
50,000 connections the co-located client stops being a small correction and becomes the dominant
cost; see [Where this measurement stops](#where-this-measurement-stops-being-about-the-server).

The rest of the method:

- **Closed loop.** Each connection keeps at most K messages outstanding and waits for its own to
  come back before sending again, so offered load tracks server capacity and the reported figure is
  a real service rate rather than the size of a queue. Open-loop (`--send-rate`) exists for
  studying overload behaviour.
- **Throughput runs use `--room-size 1`**, so each message fans out to exactly one subscriber and
  deliveries/s equals posts/s. Fan-out runs use `--room-size 50`.
- **Warm measurements.** Every run does a throwaway warmup first. Cold-JIT numbers on this code are
  roughly half of warm ones — enough to invent a bottleneck that isn't there.
- **Rate limits off** (`bench` profile). All connections authenticate as one user, so the per-user
  30 messages/minute cap would otherwise cap the entire test at 30 messages. That is a test
  artifact, not a server limit; the limiter is on in every other profile.
- **One OIDC login,** with the session reused across connections. This measures IntelliStream Chat, not
  Keycloak.
- **Per-stage server timers** (`intellistream.write.stage`, scraped by `benchmark/write-stages.sh`)
  record where each message's time goes, so tuning decisions came from the breakdown rather than
  from intuition.

## Message throughput

Full path, `--room-size 1`, closed loop. The final configuration, verified over a 427,251-message
run ([`final-verified.json`](benchmark/results/final-verified.json)):

| | |
|---|---:|
| Posts/second | **17,066** |
| Latency p50 | 21.6 ms |
| Latency p99 | 61.5 ms |
| Dropped | **0** |
| Server CPU | 504% of 1200% |

Run-to-run spread across the final configuration is roughly 12,500–18,100/s, with the variance
coming from the co-located generator rather than the server; the two runs of the last change
recorded 16,889 and 18,135. Treat **~17,000/s** as the sustained figure and the spread as
measurement noise.

Server CPU at 504% is the number to notice: at 17k messages/second, on a 12-core box, **more than
half the machine is still idle.** The constraint at this point is the generator.

## Fan-out

Realistic rooms: 2,000 connections spread over 50-member channels, one sender per room.

| In flight per sender | Posts/s | Deliveries/s | Dropped | p50 | Server CPU |
|---:|---:|---:|---:|---:|---:|
| 4 | 2,490 | 124,477 | 0 | 60 ms | 384% |
| 16 | 2,721 | **136,043** | 0 | 228 ms | 382% |
| 48 | 2,631 | 131,536 | 0 | 679 ms | 370% |

Throughput is flat from 16 in flight while latency grows linearly — the signature of a saturated
queue. But server CPU sits at ~380% throughout, so the saturated party is the co-located client.
**The in-process broker sustains at least 136,000 deliveries/second**, with no external message
broker involved.

## Concurrent connections

Echo path (WebSocket + broker), measuring how many sockets the server holds *and still serves*.

| Tier | Established | Setup p50 | Deliveries/s | Dropped | Delivery p50 | Server RSS | CPU |
|-----:|:---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 10,000 / 10,000 | 5.5 s | 10,000 | 0 | 202 ms | 4.9 GiB | 767% |
| 50,000 | 50,000 / 50,000 | 4.8 ms | 49,154 | **0.00%** | 250 ms | 12.1 GiB | 1045% |
| 100,000 | 100,000 / 100,000 | 5.0 ms | 47,484 | **0.00%** | 792 ms | 11.2 GiB | 1166% |

At 50,000 every one of 3,000,000 expected deliveries arrived. At 100,000, every one of 2,000,000
did. Neither tier recorded a single connect failure.

Two things in that table look wrong and aren't:

- **The 10,000 tier has the worst setup latency** (5.5 s p50, versus 4.8 ms at 50,000). That run
  drove all connections at a single destination IP and thrashed the client's ephemeral port range.
  Spreading the same load across four loopback destination IPs fixed it. The difference is entirely
  client-side — the server refused nothing in either run. See
  [Beyond one destination IP](#beyond-one-destination-ip).
- **50,000 connections show more RSS than 100,000.** The 50k run was given a larger `-Xmx`, and ZGC
  commits heap it isn't using. RSS is a poor instrument for per-connection cost; see
  [Per-connection cost](#per-connection-cost-and-sizing-your-machine).

The tail at 100,000 is long — p99 13.1 s at 1,166% CPU. That is a genuinely saturated box, and with
the generator on the same twelve cores, roughly half of what saturates it is the measurement.

## The single configuration line that governs fan-out at scale

Before this was set, the 100,000-connection tier dropped **half its traffic**: 21,991 deliveries/s
against 50,000 offered, 51.65% dropped, p50 10.7 seconds. It looked exactly like an
under-provisioned box — CPU really was pinned at 1,130% of 1,200%.

It wasn't. Profiling during a live run put the cost somewhere the message pipeline never appears:

| CPU by area | |
|---|---:|
| **broker subscription registry** | **47.2%** |
| WebSocket frame encode/decode | 19.0% |
| socket writes | 11.5% |
| heartbeats | 0.4% |

The hottest single method was `ConcurrentHashMap$Traverser.advance` at 28.4%, underneath
`DefaultSubscriptionRegistry$DestinationCache.computeMatchingSubscriptions`. Meanwhile both STOMP
channel executors were **completely idle**.

Every broadcast asks the registry which sessions subscribe to a destination. It answers from an LRU
cache; on a miss it walks every session's every subscription. **That cache holds 1,024 destinations
by default.** The run used 2,000 rooms, so most broadcasts missed, and each miss rescanned all
100,000 subscriptions — making broadcast cost proportional to total subscriptions rather than to
room size.

`BrokerSubscriptionCacheConfig` raises the limit (`intellistream.ws.subscription-cache-limit`,
default **16384**):

| 100k tier | before | after |
|---|---:|---:|
| Deliveries/s | 21,991 | **47,484** |
| Dropped | 51.65% | **0.00%** |
| Delivery p50 | 10.7 s | **792 ms** |

**Size this above the number of channels you expect to be busy at once.** Cache entries are small —
a list of matching subscriptions per destination — so over-provisioning costs almost nothing, and
under-provisioning produces a cliff that is invisible from the outside. If your deployment has
thousands of channels and delivery collapses under load, check this before you buy hardware.

## Per-connection cost, and sizing your machine

| | |
|---|---:|
| Marginal RSS per connection (slope between two loaded points) | **32.8 KB** |
| Server RSS growth per connection during a 150k ramp | **82 KB** |
| Total server RSS holding 100,000 connections | **11.2 GiB** |

The two per-connection figures differ because they measure different things: the 32.8 KB slope is
steady-state marginal cost, the 82 KB ramp figure includes transient handshake state while
connections are being established as fast as the client can open them. Both are far below the
150–200 KB an earlier analysis inferred by dividing total RSS by connection count — that method
folds in the JVM's fixed footprint and, more importantly, ZGC heap that is committed but not live.

**For sizing, use the empirical anchor rather than any per-connection figure:** 100,000 connections
were held and served in 11.2 GiB of total RSS. Budget from there, add heap for your message rate,
and leave headroom.

A note on method, because it cost real time here: an earlier revision of this document reported
off-heap memory falling from 39.4 KB to 19.6 KB per connection after a buffer-size change. That
came from sampling `GC.heap_info` after `GC.run` and subtracting, which does not reliably report the
live set under ZGC — the same method also reported per-connection heap *rising* between two runs of
identical code. A controlled A/B in a fixed cgroup then showed the buffer change was not measurable
at all: two ways of drawing a line through the same two runs disagreed by 70% and disagreed about
which configuration was better. The defaults are back to Tomcat's 8192 bytes.
`intellistream.ws.socket-buffer-bytes` and `.binary-buffer-bytes` remain as knobs for a deployment
that knows its message sizes. **Use Native Memory Tracking (`-XX:NativeMemoryTracking=summary`
plus `jcmd VM.native_memory`) rather than RSS arithmetic if you need to settle this.**

## Where this measurement stops being about the server

At 150,000 connections the box runs out — but the process that runs out is the *generator*, not the
server. The recorded ramp established 84,959 of 150,000 with 65,041 connect failures, and **not one
server-side refusal.**

Measuring both sides during the ramp explains it:

| | per connection | × 150,000 |
|---|---:|---:|
| Server | 82 KB | ~12 GB |
| Co-located generator | **174 KB** | ~26 GB |
| | | **~38 GB needed, 31 GB available** |

The generator costs 2.1× more per connection than the server, because the JDK's `java.net.http`
WebSocket client carries much heavier per-connection state than Tomcat does. So the thing measuring
the server is what exhausts the machine, and no amount of server tuning changes that.

**A single node would very likely hold 150,000 connections in ~12–14 GB. Demonstrating it requires the
load generator on separate hardware** — which the harness already supports (`--base`,
`--dst-hosts`). Past ~100,000, co-located numbers stop describing the server.

## Durability: broadcast happens after the commit

**A message is broadcast and indexed only once its database row has committed.** Publishing on
acceptance instead is a few milliseconds faster and admits phantom messages: a line every member of
the channel saw, followed by a failed INSERT, and nothing in the database. That is not a trade worth
making in a chat system.

The latency this costs the sender is hidden the way every chat client hides it — an **optimistic
echo**. The composer renders your own message immediately in a `sending` state and reconciles it
when the broadcast arrives, matched on a client-generated correlation id round-tripped through the
send frame, not on body text (which breaks the moment someone sends the same line twice). Other
people's clients only ever see durable messages.

### Write-behind batching

`MessageWriteBehind` (on by default, `intellistream.write-behind.enabled=false` to disable) allocates
message ids in blocks from `messages_id_seq` up front, so a message has its real primary key the
moment it is accepted, then hands the row to a queue that flushers drain into batched multi-row
INSERTs. Roughly 14,000 transactions/second become ~55 batches/second. This was the single largest
throughput lever (5,948 → 13,638 posts/s) and the only one with a semantic cost:

- **Durability window.** An abrupt process kill loses at most one flush window (5 ms) of messages.
  Because broadcast waits for the commit, those messages were never shown to anyone, never indexed
  and never acknowledged — nothing has to be un-said. A clean shutdown drains the queue.
- **Not loss under pressure.** If the queue fills, `enqueue` refuses and the caller inserts
  synchronously — back-pressure, never a dropped message. A failed batch is retried row by row so
  one bad row cannot take 255 good ones with it.
- **Per-channel ordering holds.** The queue is sharded by channel, so one flusher owns a channel and
  its messages commit and publish in acceptance order, while different channels commit in parallel.
- **Mentions stay transactional.** A body containing `@` takes the synchronous path, because
  `message_mentions` rows need the message row to exist for the foreign key.

Verified over a 458,692-message run: 458,692 rows landed, zero duplicate ids, zero losses.

## Where the time goes

Per message at ~17,000 posts/second, from `benchmark/write-stages.sh`. Indexing and broadcasting no
longer appear in the handler at all — they happen after the commit, on the batcher's threads.

| Stage | Mean |
|---|---:|
| id allocation + queue handoff | 0.24 ms |
| Markdown render + sanitize | 0.23 ms |
| broker handoff | 0.19 ms |
| channel lookup (cached) | 0.017 ms |
| write-access check (cached) | 0.013 ms |
| user resolution (from STOMP session) | 0.004 ms |
| **sum** | **≈0.7 ms** |

The handler is essentially free; the remaining ceiling is the flusher shards and the co-located
generator. The message send path is deliberately query-free — the domain `User` comes from the STOMP
session cached at CONNECT, the channel and write-access decision from `ChannelAccessCache`, and
mentions from what mention-sync already resolved. **If you add work to `ChatWebSocketController.send`
or `MessageService.postWithMentions`, re-run `benchmark/write-stages.sh` afterwards.**

`ChannelAccessCache` is safe for two structural reasons rather than by hope: only *positive* access
decisions are cached and membership is add-only, so a "yes" cannot silently become a "no"; and
`Channel` is immutable, so a cached copy cannot go stale against a rename or a PUBLIC↔PRIVATE flip.
That second property is load-bearing for authorization and is enforced by `ChannelImmutabilityTest`.

## Configuration that governs these numbers

### STOMP channel executors

`WebSocketConfig` sets both channel executors **unconditionally**, with sizes constructor-injected.
This is load-bearing: if the inbound executor is missing, Spring silently lands every
`@MessageMapping` on the single-threaded heartbeat scheduler, and the whole server processes one
message at a time. That failure produced the original 109 messages/second, and it is invisible in
every obvious metric — the box is not CPU-bound, the database is not busy, and latency looks like a
slow dependency.

`StompChannelDiagnostics` logs the resolved executors at startup:

```
STOMP clientInboundChannel  -> ThreadPoolTaskExecutor[prefix=stomp-inbound-, core=48, max=48, ...]
```

**Check that line before trusting any throughput number.** Tunable via
`intellistream.ws.inbound-threads` / `outbound-threads` (48 / 96 in these runs; default `cores × 4`).
The inbound pool wants to be about the size of the connection pool it feeds — threads beyond that
just queue inside Hikari.

### JVM

`-XX:+UseZGC` (concurrent, sub-millisecond pauses — the right collector for many connections plus
low latency), heap sized to the tier (`-Xmx8g` for throughput runs, up to `-Xmx18g` at 100k
connections), and `--enable-native-access=ALL-UNNAMED` for Lucene.

### Database pool

`spring.datasource.hikari.maximum-pool-size=50` against Postgres `max_connections=100`. Measured
acquire time at 13,000 posts/second is 0.03 ms with zero pending — the pool is not a constraint at
this rate, and raising it would not help. Postgres itself sat at ~10% CPU.

### Kernel

The stock kernel is provisioned for a workstation, not 10⁵ sockets. Persist these in
`/etc/sysctl.d/` and run `sysctl --system`:

| Tunable | Default | Set to | Why |
|---|---:|---:|---|
| `net.ipv4.ip_local_port_range` | `32768 60999` | `1024 65535` | Ephemeral ports; one destination IP tops out ~28k connections otherwise. |
| `net.core.somaxconn` | `4096` | `65535` | Accept-queue depth; caps Tomcat `accept-count`. **Needs an app restart** — the backlog is set at `bind()`. |
| `net.ipv4.tcp_max_syn_backlog` | `2048` | `65535` | SYN queue during a connect burst. |
| `net.core.netdev_max_backlog` | `1000` | `65535` | Per-CPU ingress packet queue. |
| `net.ipv4.tcp_tw_reuse` | `2` | `1` | Reuse `TIME_WAIT` sockets on rapid reconnect. |
| `net.ipv4.tcp_fin_timeout` | `60` | `15` | Recycle closing sockets faster. |
| `net.netfilter.nf_conntrack_max` | `262144` | `1048576` | conntrack tracks loopback; ~250k connections would exhaust the default. |

`fs.file-max` and `fs.nr_open` are already effectively unlimited on a modern kernel; `ulimit -n`
needs to be large (524288 here). `tcp_rmem`/`tcp_wmem` maxima were left alone — for chat-sized
messages buffers stay near the minimum, but at 250k sockets they become a memory lever worth
*lowering*.

### Beyond one destination IP

Even with a 64k ephemeral range, one client can only make ~64k connections to a single
`(destination IP, port)` pair. The generator spreads connections across `127.0.0.1 … 127.0.0.k`
with the server bound to `0.0.0.0`, which also parallelizes the client's selector pools. Each extra
destination IP adds a fresh ~64k port pool. **Measure the client before believing a connection
failure** — the 15,113 "failures" in an early 50k run were entirely client-side port exhaustion.

## Reproducing

```bash
# 1. Kernel tuning (above), then start the app under the bench profile
benchmark/run-bench-app.sh

# 2. Throughput: full path, room size 1, closed loop
benchmark/post-throughput.sh

# 3. Per-stage breakdown of the write path
benchmark/write-stages.sh

# 4. Connection tiers
benchmark/connection-ceiling.sh
```

The `bench` profile (`application-bench.properties`) disables rate limiting, widens the Tomcat
connection limits, exposes actuator metrics and uses its own Lucene directory. It is
`@Profile("bench")`-gated and never registers in production — but it exists to remove safety limits,
so do not run it on an internet-facing host.

## What would need to change for more

A single node handling 17,000 messages/second and 136,000 deliveries/second is not throughput-bound
in any realistic chat deployment. The arguments for going multi-node are **availability** and
**connection count**, not messages per second. If you need it:

1. **Move the load generator off-box first** if what you actually want is a better measurement.
   Past 100,000 connections the co-located client is a first-order measurement error.
2. **An external STOMP relay or pub/sub layer** so broadcasts reach sessions on other nodes, plus a
   distributed rate limiter — the current `RateLimiter` is per-process, and
   `BrokerSubscriptionCacheConfig` sizes a *local* registry that a relay-based deployment does not
   use.
3. **Postgres capacity**, which at these rates is not close to being the limit: ~10% CPU at 17k
   messages/second, with write-behind turning ~14,000 transactions/second into ~55 batches.

## Appendix: how the write path got from 109 to 17,000

Each step is cumulative, `--room-size 1`, closed loop.

| # | Change | Posts/s | p50 |
|---|---|--------:|----:|
| 0 | Baseline | 109 | 1,613 ms |
| 1 | Give the STOMP inbound channel a real executor | 1,583 | 121 ms |
| 2 | Remove redundant per-message queries | 3,598 | 52 ms |
| 3 | Cache channel + write-access on the hot path | 5,948 | 31 ms |
| 4 | Batch the INSERTs (write-behind) | 13,638 | 12 ms |
| 5 | Lock-free id allocation, single-parse mentions | 14,263 | 24 ms |
| 6 | Indexing off the handler; broadcast after commit; flushers sharded by channel | 16,889–18,135 | 20 ms |

Step 6 made the system *more* correct — nothing is broadcast or indexed until its row is committed
— and *faster*, by moving indexing and broadcasting off the handler threads. The first attempt at it
dropped to 8,704/s because a single flusher's round trip to Postgres became the new ceiling;
sharding by channel fixed that while preserving per-channel ordering.

Step 2 removed roughly seven database round trips across six transactions per message: a
`CurrentUser` upsert the CONNECT interceptor had already done, a mention-row `DELETE` and flush on a
row created microseconds earlier that provably had none, a mention read-back, and a poll lookup on a
message that cannot have a poll.

Three things that were confidently blamed along the way and turned out not to matter, recorded
because they are the plausible-sounding answers:

- **"Per-message Lucene `commit()` caps the write path."** Making indexing async was a real ~10× on
  the *serialized* path, but the ceiling it was measured against was step 1's single thread. With
  concurrency restored, indexing is a fraction of a millisecond and never was the wall.
- **"The remaining cost is WAL fsync."** Tested: `synchronous_commit=off` bought 7%. Postgres sat at
  ~10% CPU with Hikari acquire times of 0.03 ms — the backends were waiting on a queue upstream, not
  on the disk.
- **"The single-threaded simple broker is the fan-out ceiling at ~10k deliveries/s."** It does
  136,043 once the inbound side stops starving it. No partitioning and no external relay needed.

The common thread: **a saturated stage upstream makes every stage downstream look slow.** The check
that would have found it immediately is arithmetic — at 109 posts/second and 8.2 ms per message,
throughput × latency ≈ 0.89, meaning an average of less than one message in flight across the entire
server. If that product does not match the concurrency you think you have, stop optimizing and find
out why.
