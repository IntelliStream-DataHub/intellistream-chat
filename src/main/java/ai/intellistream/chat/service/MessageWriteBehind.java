/*
 * Copyright 2026 IntelliStream AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.intellistream.chat.service;

import ai.intellistream.chat.search.MessageIndexService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Write-behind batching for the message INSERT, and the single point where a message becomes
 * publicly visible.
 *
 * <p>One INSERT transaction per message is the dominant cost of a post once the redundant lookups
 * are gone: a JDBC round trip, a Hibernate flush, and a WAL commit for every chat line. Batching
 * turns ~N transactions into ~N/batchSize multi-row INSERTs.
 *
 * <p><b>Ids are allocated up front</b> from the table's identity sequence, in blocks, so a message
 * has its final primary key the moment it is accepted, even though the row reaches the table a few
 * milliseconds later.
 *
 * <h2>Everything downstream happens after the commit</h2>
 *
 * A batch that commits successfully then, and only then, triggers the two things that make a
 * message real to the outside world:
 * <ol>
 *   <li><b>Broadcast</b>, via each row's {@link Durability} handle, dispatched on a worker chosen
 *       by channel id so that messages in one channel are always broadcast in the order they were
 *       accepted.</li>
 *   <li><b>Search indexing</b>, handed to a dedicated indexer thread as a whole batch.</li>
 * </ol>
 * If a row fails to insert, neither happens: no client is shown a message that isn't in the
 * database, and the index never holds a document for a row that doesn't exist. Doing this the
 * other way round — broadcasting on acceptance — is a few milliseconds faster and admits phantom
 * messages, which is not a trade worth making in a chat system.
 *
 * <p><b>The remaining trade-off</b> is a durability window, not a consistency hole: an abrupt
 * process kill loses at most one flush window (single-digit milliseconds) of messages, and those
 * messages were never broadcast, never indexed, and never acknowledged to anyone. The sender's own
 * client is free to render optimistically in the meantime; that is a client-side concern.
 *
 * <p>Back-pressure, not loss: if the queue fills, {@link #enqueue} returns false and the caller
 * inserts synchronously. A batch that fails is retried row by row so one bad row can't take the
 * whole batch down, and anything still queued is flushed on shutdown.
 */
@Component
public class MessageWriteBehind {

    private static final Logger log = LoggerFactory.getLogger(MessageWriteBehind.class);

    /** One pending row: the {@code messages} columns, plus what the post-commit steps need. */
    public record PendingMessage(long id, long channelId, long authorId, String authorUsername,
                                 String body, Instant createdAt, Long parentId,
                                 Durability durability) {}

    private final JdbcTemplate jdbc;
    private final MessageIndexService messageIndex;
    private final boolean enabled;
    private final int batchSize;
    private final long flushIntervalMillis;
    private final int idBlockSize;
    /**
     * Pending rows, sharded by channel. Each shard has exactly one flusher thread, so all messages
     * for a given channel are inserted — and therefore published — in the order they were accepted,
     * while different channels commit in parallel. A single shared queue with one flusher makes
     * that thread's round trip to Postgres the ceiling for the whole server no matter how fast the
     * handlers are.
     */
    private final BlockingQueue<PendingMessage>[] queues;
    /** Committed batches waiting to be indexed. */
    private final BlockingQueue<List<PendingMessage>> indexQueue;
    /** Single-threaded broadcast workers; a channel always maps to the same one (ordering). */
    private final java.util.concurrent.ExecutorService[] broadcasters;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread[] flushers = new Thread[0];
    private volatile Thread indexer;

    /**
     * Id block currently being handed out. Slots are claimed lock-free with an atomic cursor; only
     * the refill takes the lock. Handing ids out under a {@code synchronized} block instead made
     * the allocator a serialization point — every posting thread contending on one monitor
     * thousands of times a second, which cost more than the database call it was there to avoid.
     */
    private record IdBlock(long[] ids, AtomicInteger cursor) {}

    private final Object refillLock = new Object();
    private volatile IdBlock idBlock = new IdBlock(new long[0], new AtomicInteger());

    private final LongAdder enqueued = new LongAdder();
    private final LongAdder written = new LongAdder();
    private final LongAdder batches = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder failedRows = new LongAdder();

    public MessageWriteBehind(DataSource dataSource,
                              MessageIndexService messageIndex,
                              @Value("${ichat.write-behind.enabled:true}") boolean enabled,
                              @Value("${ichat.write-behind.batch-size:256}") int batchSize,
                              @Value("${ichat.write-behind.flush-interval-ms:5}") long flushIntervalMillis,
                              @Value("${ichat.write-behind.queue-capacity:100000}") int queueCapacity,
                              @Value("${ichat.write-behind.id-block-size:4096}") int idBlockSize,
                              @Value("${ichat.write-behind.broadcast-threads:8}") int broadcastThreads,
                              @Value("${ichat.write-behind.flush-threads:4}") int flushThreads) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.messageIndex = messageIndex;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMillis = Math.max(1, flushIntervalMillis);
        this.idBlockSize = Math.max(1, idBlockSize);
        int shards = Math.max(1, flushThreads);
        int perShard = Math.max(1024, queueCapacity / shards);
        // LinkedBlockingQueue, not ArrayBlockingQueue: it uses separate put/take locks, so the
        // posting threads and the flusher don't contend on one monitor.
        @SuppressWarnings("unchecked")
        BlockingQueue<PendingMessage>[] shardQueues = new BlockingQueue[shards];
        for (int i = 0; i < shards; i++) {
            shardQueues[i] = new LinkedBlockingQueue<>(perShard);
        }
        this.queues = shardQueues;
        this.indexQueue = new LinkedBlockingQueue<>(1024);
        int workers = Math.max(1, broadcastThreads);
        this.broadcasters = new java.util.concurrent.ExecutorService[workers];
        for (int i = 0; i < workers; i++) {
            int index = i;
            this.broadcasters[i] = java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "message-broadcast-" + index));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Reserve the next message id. Draws a fresh block from {@code messages_id_seq} when the
     * current one is exhausted, so the common case is an atomic increment rather than a database
     * round trip.
     *
     * <p>Ids from an abandoned block are simply never used — the sequence is a counter, not a
     * dense allocator, and gaps are already possible with an identity column and rolled-back
     * transactions.
     */
    public long nextMessageId() {
        while (true) {
            var block = idBlock;
            int slot = block.cursor().getAndIncrement();
            if (slot < block.ids().length) {
                return block.ids()[slot];
            }
            refill(block);
        }
    }

    /** Replace {@code exhausted} with a freshly drawn block. Only the first arrival does the work. */
    private void refill(IdBlock exhausted) {
        synchronized (refillLock) {
            if (idBlock != exhausted) {
                return; // another thread already refilled; loop around and take a slot from it
            }
            // Draw the whole block and keep the values, rather than deriving a range from the
            // highest one: nothing guarantees a sequence hands out a contiguous run, and a
            // cache/restart gap would silently produce ids that belong to somebody else.
            var values = jdbc.queryForList(
                    "select nextval('messages_id_seq') from generate_series(1, ?)",
                    Long.class, idBlockSize);
            if (values.isEmpty()) {
                throw new IllegalStateException("messages_id_seq returned no values");
            }
            idBlock = new IdBlock(values.stream().mapToLong(Long::longValue).toArray(),
                    new AtomicInteger());
        }
    }

    /** Queue a row for batched insertion. Returns false when the queue is full — insert it yourself. */
    public boolean enqueue(PendingMessage message) {
        if (!enabled || !running.get()) {
            return false;
        }
        if (!shardFor(message.channelId()).offer(message)) {
            rejected.increment();
            return false;
        }
        enqueued.increment();
        return true;
    }

    private BlockingQueue<PendingMessage> shardFor(long channelId) {
        return queues[(int) Math.floorMod(channelId, queues.length)];
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("Message write-behind batching disabled; every post commits before it is broadcast");
            return;
        }
        running.set(true);
        var started = new Thread[queues.length];
        for (int i = 0; i < queues.length; i++) {
            int shard = i;
            started[i] = startThread(() -> drainLoop(shard), "message-write-behind-" + shard);
        }
        this.flushers = started;
        this.indexer = startThread(this::indexLoop, "message-indexer");
        log.info("Message write-behind enabled (batchSize={}, flushInterval={}ms, flushThreads={},"
                        + " broadcastThreads={}); messages are broadcast and indexed only after"
                        + " their batch commits",
                batchSize, flushIntervalMillis, queues.length, broadcasters.length);
    }

    private static Thread startThread(Runnable body, String name) {
        var thread = new Thread(body, name);
        thread.setDaemon(false); // must finish its queue before the JVM exits
        thread.start();
        return thread;
    }

    @PreDestroy
    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (var thread : flushers) {
            join(thread);
        }
        for (int shard = 0; shard < queues.length; shard++) {
            flushRemaining(shard);
        }
        join(indexer);
        drainIndexQueue();
        for (var broadcaster : broadcasters) {
            broadcaster.shutdown();
        }
        log.info("Message write-behind stopped: enqueued={} written={} batches={} rejected={} failedRows={}",
                enqueued.sum(), written.sum(), batches.sum(), rejected.sum(), failedRows.sum());
    }

    private static void join(Thread thread) {
        if (thread == null) return;
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainLoop(int shard) {
        var queue = queues[shard];
        var buffer = new ArrayList<PendingMessage>(batchSize);
        while (running.get()) {
            try {
                // Block for the first row so an idle server doesn't spin, then take whatever else
                // is already queued without waiting — under load the queue is never empty and the
                // batch fills immediately; when it is quiet a message waits at most one poll.
                var first = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                buffer.add(first);
                queue.drainTo(buffer, batchSize - 1);
                insertBatch(buffer);
                buffer.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.error("Write-behind batch failed unexpectedly", e);
                buffer.forEach(row -> row.durability().failed());
                buffer.clear();
            }
        }
    }

    private void flushRemaining(int shard) {
        var queue = queues[shard];
        var buffer = new ArrayList<PendingMessage>(batchSize);
        while (queue.drainTo(buffer, batchSize) > 0) {
            try {
                insertBatch(buffer);
            } catch (RuntimeException e) {
                log.error("Write-behind drain on shutdown failed for {} row(s)", buffer.size(), e);
                buffer.forEach(row -> row.durability().failed());
            }
            buffer.clear();
        }
    }

    private static final String INSERT_SQL =
            "insert into messages (id, channel_id, author_id, body_markdown, created_at, parent_id) "
                    + "values (?, ?, ?, ?, ?, ?)";

    private void insertBatch(List<PendingMessage> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<PendingMessage> durable;
        try {
            jdbc.batchUpdate(INSERT_SQL, rows, rows.size(), (ps, row) -> {
                ps.setLong(1, row.id());
                ps.setLong(2, row.channelId());
                ps.setLong(3, row.authorId());
                ps.setString(4, row.body());
                ps.setTimestamp(5, Timestamp.from(row.createdAt()));
                if (row.parentId() == null) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, row.parentId());
                }
            });
            written.add(rows.size());
            batches.increment();
            durable = List.copyOf(rows);
        } catch (RuntimeException batchFailure) {
            // One offending row (a channel deleted underneath us, say) must not cost the whole
            // batch. Retry individually so the rest still land, and drop only what really failed.
            log.warn("Write-behind batch of {} row(s) failed; retrying rows individually", rows.size(),
                    batchFailure);
            batches.increment();
            durable = new ArrayList<>(rows.size());
            for (var row : rows) {
                try {
                    jdbc.update(INSERT_SQL, row.id(), row.channelId(), row.authorId(), row.body(),
                            Timestamp.from(row.createdAt()), row.parentId());
                    written.increment();
                    durable.add(row);
                } catch (RuntimeException rowFailure) {
                    failedRows.increment();
                    // Never broadcast, never indexed — the message is lost, but no client was ever
                    // told otherwise, so nothing has to be un-shown.
                    row.durability().failed();
                    log.error("Write-behind dropped message id={} channel={} (not broadcast)",
                            row.id(), row.channelId(), rowFailure);
                }
            }
        }
        publish(durable);
    }

    /** Post-commit fan-out: broadcast in channel order, and hand the batch to the indexer. */
    private void publish(List<PendingMessage> durable) {
        if (durable.isEmpty()) {
            return;
        }
        for (var row : durable) {
            var worker = broadcasters[(int) Math.floorMod(row.channelId(), broadcasters.length)];
            try {
                worker.execute(row.durability()::committed);
            } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                row.durability().committed(); // run inline rather than silently swallow the message
            }
        }
        if (!indexQueue.offer(durable)) {
            // Indexer is behind; index on this thread rather than drop the documents. Slows the
            // flusher, which is the correct back-pressure signal.
            indexBatch(durable);
        }
    }

    private void indexLoop() {
        while (running.get()) {
            try {
                var batch = indexQueue.poll(flushIntervalMillis * 4, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    indexBatch(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void drainIndexQueue() {
        var pending = new ArrayList<List<PendingMessage>>();
        indexQueue.drainTo(pending);
        pending.forEach(this::indexBatch);
    }

    private void indexBatch(List<PendingMessage> batch) {
        try {
            messageIndex.indexNew(batch.stream()
                    .map(row -> new MessageIndexService.IndexedMessage(
                            row.id(), row.channelId(), row.authorUsername(), row.body()))
                    .toList());
        } catch (RuntimeException e) {
            // The index is derived state; Postgres is the source of truth and the reconcile sweep
            // repairs it. Losing a batch here costs searchability, not data.
            log.warn("Failed to index a batch of {} message(s); reconcile will repair", batch.size(), e);
        }
    }

    /** Snapshot for metrics / tests. */
    public Stats stats() {
        int depth = 0;
        for (var shard : queues) {
            depth += shard.size();
        }
        return new Stats(enqueued.sum(), written.sum(), batches.sum(), rejected.sum(),
                failedRows.sum(), depth);
    }

    public record Stats(long enqueued, long written, long batches, long rejected, long failedRows,
                        int queueDepth) {}
}
