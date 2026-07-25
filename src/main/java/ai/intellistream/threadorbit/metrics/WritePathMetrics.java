/*
 * Copyright 2026 Olav Gjerde
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

package ai.intellistream.threadorbit.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Per-stage timers for the message write path, published as {@code threadorbit.write.stage}
 * tagged by {@code stage}. Scrape {@code /actuator/metrics/threadorbit.write.stage?tag=stage:persist}
 * during a load run to get the cost breakdown of a single post — which stage actually owns the
 * latency — instead of inferring it from a flame graph.
 *
 * <p>Deliberately plain {@link Timer}s (count + total + max, no percentile histograms): the point
 * is a mean-cost breakdown across millions of messages, and a histogram per stage would add
 * measurable overhead to the very path being measured. End-to-end latency percentiles come from
 * the load generator, which sees them from the client side anyway.
 */
@Component
public class WritePathMetrics {

    /** Resolve the STOMP principal to the domain {@code User}. */
    public final Timer resolveUser;
    /** Load the target {@code Channel}. */
    public final Timer loadChannel;
    /** Slash-command dispatch (a no-op for ordinary bodies). */
    public final Timer slashDispatch;
    /** {@code MessageService.post} — access check, INSERT, mention sync, and the tx commit. */
    public final Timer persist;
    /** Sub-stage of {@link #persist}: the channel write-access (membership) check. */
    public final Timer accessCheck;
    /** Sub-stage of {@link #persist}: the message INSERT itself. */
    public final Timer insert;
    /** Sub-stage of {@link #persist}: parsing mentions and writing their rows. */
    public final Timer mentionSync;
    /** Sub-stage of {@link #persist}: handing the message to the search index. */
    public final Timer index;
    /** Sub-stage of {@link #persist}: id allocation + handoff to the write-behind queue. */
    public final Timer enqueue;
    /** Reading back the mention rows for the broadcast payload. */
    public final Timer mentionReadback;
    /** Poll lookup for the broadcast payload. */
    public final Timer pollLookup;
    /** Markdown render + sanitize. */
    public final Timer render;
    /** Handing the DTO to the broker (serialization + enqueue, not the fan-out itself). */
    public final Timer broadcast;
    /** Whole handler, begin to end. */
    public final Timer total;

    public WritePathMetrics(MeterRegistry registry) {
        this.resolveUser = stage(registry, "resolve-user");
        this.loadChannel = stage(registry, "load-channel");
        this.slashDispatch = stage(registry, "slash-dispatch");
        this.persist = stage(registry, "persist");
        this.accessCheck = stage(registry, "persist.access-check");
        this.insert = stage(registry, "persist.insert");
        this.mentionSync = stage(registry, "persist.mention-sync");
        this.index = stage(registry, "persist.index");
        this.enqueue = stage(registry, "persist.enqueue");
        this.mentionReadback = stage(registry, "mention-readback");
        this.pollLookup = stage(registry, "poll-lookup");
        this.render = stage(registry, "render");
        this.broadcast = stage(registry, "broadcast");
        this.total = stage(registry, "total");
    }

    private static Timer stage(MeterRegistry registry, String stage) {
        return Timer.builder("threadorbit.write.stage")
                .description("Per-stage cost of handling one inbound chat message")
                .tag("stage", stage)
                .register(registry);
    }

    /** Start timing one message. */
    public Lap lap() {
        return new Lap();
    }

    /**
     * A running stopwatch over one message. {@link #mark} closes the current stage and opens the
     * next, so stages are contiguous and sum to {@link #total} — no double counting, no gaps.
     * Single-threaded by construction (one message is handled on one thread).
     */
    public static final class Lap {
        private final long start = System.nanoTime();
        private long last = start;

        public void mark(Timer timer) {
            long now = System.nanoTime();
            timer.record(now - last, TimeUnit.NANOSECONDS);
            last = now;
        }

        public void finish(Timer timer) {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
