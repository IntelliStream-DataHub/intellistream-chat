# ThreadOrbit — WebSocket scalability benchmark

How many concurrent WebSocket connections ThreadOrbit holds, how many messages a second it can
actually persist and deliver, where it breaks, and the OS/JVM/app tuning that got it there. Run on
a single box with the app **and** the load generator co-located (the deliberate "push this box to
its limit" setup).

> Reproduce: see [`benchmark/README.md`](benchmark/README.md). Raw results are in
> `benchmark/results/*.json`.

## TL;DR

- **Message throughput: ~17,000 posts/second sustained**, every one of them committed to Postgres
  *before* it is broadcast, and indexed in Lucene, at ~20 ms median end-to-end (send → commit →
  broadcast → receive). That is **~160× the 109 posts/s this box started at**, and well past the
  10,000 posts/s target.
- **Fan-out: ~136,000 deliveries/second** into 50-member rooms, 0 dropped, p50 under 250 ms.
- **50,000 concurrent connections, held *and* served**: 50,000/50,000 established with 0 failures,
  **49,154 deliveries/s with nothing dropped** at p50 250 ms. The earlier pass recorded this tier
  as "held but not served" (1.3k deliveries/s at p50 33 s, 97% backlog) — that was the
  single-threaded inbound channel, not the socket count.
- **The original diagnosis in this document was wrong**, and instructively so. The write path was
  never limited by Lucene commits, WAL fsync, or the broker. **Every inbound chat message was
  being handled on a single thread** — a mis-wired STOMP channel executor — so the whole server
  ran one message at a time. Everything else was a symptom. See
  [How the ceiling was actually found](#how-the-ceiling-was-actually-found).

## The box

| | |
|---|---|
| CPU / RAM | 12 cores · 31 GB |
| Kernel | Linux 6.12 |
| Runtime | Java 25, Spring Boot 4.1, ZGC |
| Broker | in-process simple broker (no external message broker) |
| Search | embedded Apache Lucene, on local disk |
| Layout | **app + load generator on the same host** (server↔client over loopback) |

Co-location is a real constraint and it binds harder than it looks. In the fan-out runs the server
used only ~380% CPU of the 1200% available while throughput was flat — the generator, parsing
136k STOMP frames a second, was the limiting party. Server-side numbers below are therefore
**floors, not ceilings**.

## Methodology

- **Post throughput** (`benchmark/post-throughput.sh`) uses `--room-size 1`, so each message fans
  out to exactly one subscriber and *deliveries/s = posts/s*. The number is end-to-end: the client
  only counts a message once it comes back over the socket, so it includes persistence, Markdown
  rendering, indexing, broker fan-out, and the WebSocket write.
- **Closed loop** (`--in-flight K`): each connection keeps at most K messages outstanding and waits
  for its own to return before sending again. Offered load therefore tracks server capacity, and
  the reported figure is the real service rate rather than the size of a queue. Open-loop
  (`--send-rate R`) is still there for overload behaviour.
- **Fan-out** runs use `--room-size 50`: one sender per room, message fans out to all 50 members.
- **Warm measurements.** Every run does a throwaway warmup pass first — cold-JIT numbers on this
  code are roughly half of warm ones, which is enough to invent a bottleneck that isn't there.
- **Auth:** one OIDC login; the session cookie is reused across every connection (a load test of
  the server, not of Keycloak).
- **Rate limits off:** the `bench` profile sets `threadorbit.ratelimit.enabled=false`. All
  connections use one user, so the per-user `ws-send` cap (30/min) would otherwise cap the whole
  test at 30 messages — a test artifact, not a server limit.
- **Per-stage server timers.** The handler records where each message's time goes
  (`threadorbit.write.stage`, scraped by `benchmark/write-stages.sh`). Every optimisation below was
  chosen from that breakdown rather than from intuition, and two of the three intuitions this
  document previously recorded were wrong.

## Results

### Message throughput (full path — persist + render + index + broadcast)

Each step is cumulative; all figures are `--room-size 1`, 400 connections, closed loop.

| # | Change | Posts/s | p50 latency | Handler time/msg |
|---|---|--------:|------------:|-----------------:|
| 0 | Baseline | **109** | 1,613 ms | 8.2 ms |
| 1 | Give the STOMP inbound channel a real executor | **1,583** | 121 ms | 34 ms |
| 2 | Remove the redundant per-message queries | **3,598** | 52 ms | 14.3 ms |
| 3 | Cache channel + write-access on the hot path | **5,948** | 31 ms | 8.7 ms |
| 4 | Batch the INSERTs (write-behind) | **13,638** | 12 ms | 3.3 ms |
| 5 | Lock-free id allocation, single-parse mentions | **12,924–16,223** | 24 ms | 2.8 ms |
| 6 | Indexing off the handler; broadcast after commit, flushers sharded by channel | **16,889–18,135** | 20 ms | 0.4 ms |

Row 6 is worth reading twice: it made the system *more* correct — nothing is broadcast or indexed
until its row is committed — and *faster*, because moving indexing and broadcasting off the 48
handler threads and sharding the flusher removed the two remaining serialization points. The first
attempt at it dropped to 8.7k/s, because a single flusher thread's round trip to Postgres became
the new ceiling; sharding the queue by channel fixed that while keeping per-channel ordering.

Repeat runs: 12,924 / 13,713 / 14,350 (row 5) and 16,889 / 18,135 (row 6). Call it **~17k
sustained**, with the spread coming from the co-located generator rather than the server.

Handler time per message *rises* between rows 0 and 1 because row 0 had a concurrency of one:
8.2 ms of wall time with nothing else in flight. From row 1 on, 48 messages are in flight at once
and the per-message figure includes contention.

### Fan-out (50-member rooms, 2,000 connections)

| In flight/sender | Posts/s | Deliveries/s | Dropped | p50 latency | Server CPU |
|---:|---:|---:|---:|---:|---:|
| 4 | 2,490 | **124,477** | 0 | 60 ms | 384% |
| 16 | 2,721 | **136,043** | 0 | 228 ms | 382% |
| 48 | 2,631 | 131,536 | 0 | 679 ms | 370% |

Throughput is flat from 16 in flight while latency grows linearly — the classic saturated-queue
signature. But server CPU sits at ~380% of 1200% throughout, so what saturated is the co-located
client, not the app. **The in-process simple broker sustains at least 136k deliveries/s**, which is
an order of magnitude more than this document previously credited it with.

### Connection scalability (echo path — WS + broker only)

| Tier | Established | Setup p50 | Deliveries/s | Dropped | Delivery p50 | Server peak RSS |
|-----:|:-----------:|:---------:|-------------:|--------:|-------------:|:---------------:|
| **10k** | 10,000 / 10,000 (0 fail) | 13.5 s | — | — | — | 5.0 GB |
| **50k** | **50,000 / 50,000 (0 fail)** | **4.8 ms** | **49,154** | **0.00%** | **250 ms** | 12.3 GB |
| **100k** | **100,000 / 100,000 (0 fail)** | 4.7 ms | 17,670 | 64.13% | 10.9 s | 11.6 GB |

**50k is the operating ceiling; 100k is a capacity result, not a working one.** At 50k every one
of 3,000,000 expected deliveries arrived, at p50 250 ms and 1045% CPU. At 100k the sockets are all
there and cheap — 11.6 GB, with 3 GB still free on the box — but only 717,370 of 2,000,000
deliveries landed inside the window and latency went to double-digit seconds.

Treat the 100k *delivery* numbers as a measurement of a saturated box rather than a property of the
server: CPU was 1137% of an available 1200% with the generator competing for the same cores, and
free memory bottomed out at 2 GB. What 100k establishes is that **connection capacity is no longer
the constraint it was** — the previous pass couldn't get past ~70,800 upgrades before exhausting
28 of 31 GB, and this run held 100,000 in 11.6 GB.

### Per-connection cost

| | |
|---|---:|
| Marginal RSS per connection (measured slope, 8192-byte buffers) | **32.8 KB** |
| What the earlier pass inferred (total RSS ÷ connections at the wall) | 150–200 KB |

The old figure wasn't wrong so much as a different quantity: total RSS divided by connections
includes the JVM's fixed footprint and, more importantly, ZGC heap that is committed but not live.
The slope between two loaded points is what actually determines how many more connections fit, and
it is roughly 5× smaller. A run holding 50k at `-Xmx14g` showed 12.3 GB RSS; the same server needs
nowhere near that to hold them.

**Measure the client before believing a connection failure.** A first attempt at this tier
reported 34,887 established and 15,113 "failures" — all of them the *generator* exhausting its
~64k ephemeral source ports against a single `(dstIP, dstPort)` pair, with setup p50 climbing to
4.5 s as it thrashed. Spreading the same run across four loopback destination IPs
(`--dst-hosts 127.0.0.1,…,127.0.0.4`, server bound `0.0.0.0`) gave 50,000/50,000 with **setup p50
of 4.8 ms** — a thousandfold difference in connect latency, entirely client-side. The server had
refused nothing in either run.

## How the ceiling was actually found

This is the part worth reading, because the previous version of this document confidently blamed
three things that turned out not to matter.

### The real bottleneck: one thread

`WebSocketConfig` sized the STOMP inbound channel like this:

```java
if (inboundThreads > 0) {   // @Value field injection
    registration.taskExecutor().corePoolSize(inboundThreads)...
}
```

The condition didn't hold, nothing was configured, and message handling ended up executing on the
**single-threaded heartbeat scheduler**. Every `@MessageMapping` invocation in the entire server
ran one at a time, on `ws-heartbeat-1`.

It was invisible in every metric that was being looked at. The box wasn't CPU-bound (237% of
1200%). The database wasn't busy. Latency was seconds, which reads exactly like a slow dependency.
Throughput was 109/s and per-message handler time was 8.2 ms — and `109 × 8.2 ms ≈ 0.89`, i.e. an
average of *0.89 messages in flight across the whole server*. That ratio was the tell, and a
virtual-thread-aware dump (`jcmd Thread.dump_to_file -format=json`; a plain `Thread.print` doesn't
show virtual threads) confirmed it: every sampled message was on the same thread.

The fix is to set the executor unconditionally and explicitly — `registration.executor(...)` beats
every other resolution path — plus constructor injection instead of `@Value` fields, since
configurer callbacks can run before field injection on a `@Configuration` class that also declares
`@Bean` methods. **14.5× from one config change.**

`StompChannelDiagnostics` now logs the executor behind each STOMP channel at startup, so the next
person doesn't have to infer server concurrency from a throughput-times-latency product:

```
STOMP clientInboundChannel  -> ThreadPoolTaskExecutor[prefix=stomp-inbound-, core=48, max=48, ...]
```

### What the earlier analysis got wrong

- **"The per-message Lucene `commit()` caps the write path at ~5.6 posts/s."** Making indexing
  async was a genuine ~10× on the *serialized* path, so the measurement was real — but the ceiling
  it was measured against was the single thread. With concurrency restored, Lucene indexing is
  0.9 ms of a 2.8 ms handler, and it never was the wall.
- **"The remaining cost is one Postgres transaction per message (WAL fsync)."** Testable, and
  tested: `synchronous_commit=off` bought **7%**. Postgres sat at ~10% CPU with 43 backends busy
  and Hikari acquire times of 0.03 ms — the backends were waiting on a queue upstream, not on the
  disk. `fio` had already shown the NVMe doing ~1,200 fsync/s, ~200× the observed rate; that should
  have been read as "fsync is not the problem" rather than as a puzzle.
- **"The single-threaded simple broker is the fan-out ceiling (~10k deliveries/s)."** It does 136k
  once the inbound side stops starving it. No partitioning and no external STOMP relay were needed.

The common thread: **a saturated stage upstream makes every stage downstream look slow.** Per-stage
timers plus a "throughput × latency = concurrency" sanity check would have found it in minutes.

### What actually mattered, in order

1. **The inbound executor** (109 → 1,583). Above.
2. **Redundant per-message queries** (1,583 → 3,598). One post ran ~7 round trips across ~6
   transactions. Removed: the `CurrentUser` upsert (the CONNECT interceptor already caches the
   resolved `User` on the session — the handler just wasn't using it), the mention-row `DELETE` +
   flush on a row that was created microseconds ago and provably has none, the mention read-back
   (`post` now returns what `syncMentions` already resolved), and the poll lookup on a message that
   cannot have a poll.
3. **Caching channel + write-access** (3,598 → 5,948). Both were a round trip per message.
   `ChannelAccessCache` is safe here for two structural reasons, not by hope: `Channel` has no
   setters (no rename, no PUBLIC↔PRIVATE flip to go stale against), and membership is **add-only**
   in this codebase — nothing removes a member short of deleting the channel. So only *positive*
   access decisions are cached: a "yes" can't become a "no", and a user who just joined is never
   held back by a cached "no" because negatives are never stored. The TTL is insurance against a
   future membership-removal path, not a correctness requirement today.
4. **Write-behind INSERT batching** (5,948 → 13,638). The single biggest lever, and the only one
   with a semantic cost. See below.
5. **Lock-free id allocation + one Markdown parse** (→ ~13–14k). Handing out pre-allocated ids
   under a `synchronized` block re-created a serialization point worth ~1.5 ms/message; an atomic
   cursor over the block fixed it. Mention extraction was parsing every body a second time to strip
   code spans, even when the body contained no `@` at all.

### Broadcast happens after the commit

A message is broadcast and indexed **only once its batch has committed**. Doing it the other way
round — publishing on acceptance — is a few milliseconds faster and admits phantom messages: a
line every member of the channel saw, and then a failed INSERT, and nothing in the database. That
is not a trade worth making in a chat system, and it's why the durable-then-fan-out ordering is
what production chat systems use.

The latency this costs the sender is hidden the way every chat client hides it: an **optimistic
echo**. The composer renders your own message immediately in a `sending` state and reconciles it
when the broadcast arrives, matched on a client-generated correlation id (`clientId` on the send
frame, echoed on the `created` event) rather than on body text, which breaks the moment someone
sends the same line twice. Other people's clients only ever see durable messages.

Per-channel ordering survives the batching because the write-behind queue is **sharded by channel**
— one flusher thread owns a channel, so its messages commit and publish in the order they were
accepted, while different channels commit in parallel.

### The write-behind trade-off

`MessageWriteBehind` allocates message ids in blocks from `messages_id_seq` up front, so a message
has its real primary key the moment it's accepted, then hands the row to a queue that a single
flusher drains into batched multi-row INSERTs. Roughly 14,000 transactions/s become ~55 batches/s.

**It is on by default** (`threadorbit.write-behind.enabled=false` restores commit-before-publish).
What you buy and what you pay:

- **Durability window.** An abrupt process kill loses at most one flush window (5 ms) of messages.
  Because the broadcast waits for the commit, those messages were never shown to anyone, never
  indexed and never acknowledged — nothing has to be un-said. A clean shutdown drains the queue.
- **Read-after-write.** Unchanged for other people. The sender's own optimistic echo is local
  until the broadcast confirms it.
- **Not loss under pressure.** If the queue fills, `enqueue` refuses and the caller inserts
  synchronously — back-pressure, never a dropped message. A failed batch is retried row by row so
  one bad row can't take 255 good ones with it.
- **Mentions still commit transactionally.** A body containing `@` takes the old path, because
  `message_mentions` rows need the message row to exist for the foreign key. The test is a bare
  `'@'` scan — conservative, not a parse.

Verified over a 458,692-message run: 458,692 rows landed, zero duplicate ids, zero losses.

## Where the time goes now

Per message, at ~17k posts/s (from `benchmark/write-stages.sh`). Indexing and broadcasting no
longer appear: they happen after the commit, on the batcher's own threads.

| Stage | Mean | Share |
|---|---:|---:|
| id allocation + queue handoff | 0.24 ms | 9% |
| Markdown render + sanitize | 0.23 ms | 8% |
| broker handoff | 0.19 ms | 7% |
| channel lookup (cached) | 0.017 ms | <1% |
| write-access check (cached) | 0.013 ms | <1% |
| user resolution (session) | 0.004 ms | <1% |
| **total handler** | **0.37 ms** | |

The handler is now essentially free; the ceiling has moved to the flusher shards and the
co-located generator. Remaining levers, in impact order:

1. **Move the generator off-box.** At this point it is a first-order measurement error.
2. **A plain-text render fast path.** Deliberately not done: render is ~8% of the handler, and
   reproducing jsoup's whitespace and escaping behaviour exactly is a real divergence risk for a
   single-digit gain. Reuse the renderer's own AST for mention extraction first — same win, no risk.
3. **Move the load generator off-box.** At this point the co-located client is a first-order
   measurement error, not a rounding one.

## Per-connection memory

Measured by connecting 10,000 sockets to a freshly started instance and taking the RSS and
heap-used delta either side (small `-Xmx` so the JVM can't hide the growth in slack heap):

| | Before | After buffer tuning |
|---|---:|---:|
| Off-heap per connection | 39.4 KB | **19.6 KB** |
| Heap per connection | ~31 KB | ~31 KB |
| Total RSS delta per connection | ~70 KB | ~70 KB |

The off-heap number is the one that moved, and it moved for a specific reason. Tomcat allocates a
read buffer and a write buffer per socket, 8 KB each by default, and the WebSocket container
allocates a further 8 KB *binary* message buffer per session. This protocol is STOMP over text
frames — nothing here ever sends a binary message — and chat frames are a few hundred bytes, so
roughly 20 KB per connection was reserved for traffic that never arrives. `threadorbit.ws.socket-buffer-bytes`
and `threadorbit.ws.binary-buffer-bytes` (both 2 KB by default now) recover it.

**Total RSS at 10k did not change**, because the heap simply expanded into the space the buffers
gave up. That doesn't make the saving fictional — heap is bounded by `-Xmx` and off-heap isn't, so
freeing 20 KB per connection of *unbounded* allocation is what raises the connection ceiling on a
fixed heap. It does mean the ceiling improvement is inferred rather than measured: confirming it
means running to the ~70k wall, which needs the generator on another box to be meaningful at all.
Treat "~150–200 KB/connection" from the earlier pass as what it was — total RSS divided by
connections at the ceiling, including heap headroom — and the ~70 KB here as the marginal cost.

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
  wildcard loopback Origins, Tomcat `max-connections=300000` / `accept-count=10000` /
  `threads.max=400`, actuator metrics exposed, its own Lucene directory, write-behind on.
- **STOMP channels:** `threadorbit.ws.inbound-threads` / `outbound-threads` (48 / 96 in the runs
  above; default is `cores × 4`). The inbound pool wants to be about the size of the connection
  pool it feeds — threads beyond that just queue inside Hikari.
- **JVM:** `-XX:+UseZGC` (concurrent, sub-ms pauses — right for many connections + low latency);
  heap sized to the tier (`-Xmx8g` here, up to `-Xmx18g` chasing 100k connections).
  `--enable-native-access=ALL-UNNAMED` (Lucene).
- **DB pool:** `spring.datasource.hikari.maximum-pool-size=50` (Postgres `max_connections` is 100).
  Measured acquire time at 13k posts/s is 0.03 ms with 0 pending — the pool is not a constraint at
  this rate, and raising it would not help.

## Reaching 100k–250k connections

Not on this box co-located. Concretely:

1. **Split server and client.** Run the generator on one or more *separate* machines
   (`--base`/`--dst-hosts` are already parameterized). This frees ~half the RAM and all the
   client-side selectors, and removes CPU contention — which the fan-out numbers above show is now
   the binding constraint even at 2,000 connections.
2. **Give the server RAM.** At ~150–200 KB/connection, 100k ≈ 20 GB and 250k ≈ 50 GB of server RSS
   before message load — so 64 GB+ for 250k, plus headroom.
3. **Multi-node** needs an external STOMP relay or a pub/sub layer per the
   `horizontal-scalability-plan`, and a distributed rate limiter — but note that this is now a
   *availability and connection-count* argument, not a throughput one. A single node does 13k
   messages/s and 136k deliveries/s.
4. Persist the kernel tuning and, for 250k, raise conntrack / consider `NOTRACK` on loopback and
   lower per-socket TCP buffers.

## Bottom line

On a single 12-core / 31 GB box, co-located with its own load generator, ThreadOrbit sustains
**~13,000–14,000 persisted messages per second** at ~24 ms median, fans out **~136,000
deliveries/second** into realistic 50-member rooms with nothing dropped, and holds **10k concurrent
WebSocket connections** comfortably (~70k at the memory wall).

Getting there was 125× on the write path, and almost none of it came from where this document
originally said it would. The first 14.5× was a one-line configuration fix that no metric was
pointing at, and the biggest lesson is procedural: instrument each stage, and check that
*throughput × latency* equals the concurrency you think you have.
