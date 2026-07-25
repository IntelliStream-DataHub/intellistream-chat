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

package ai.intellistream.chat.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory sliding-window rate limiter scoped per (key, action). Cheap and dependency-free —
 * adequate for a single-process deployment. Replace with a distributed limiter (Redis,
 * Bucket4j-with-Hazelcast, etc.) before going multi-instance.
 *
 * <p>Each call to {@link #tryAcquire(String, String, int, Duration)} appends a timestamp to a
 * per-key/per-action deque and trims entries older than the window. If the trimmed deque is
 * shorter than {@code limit}, the call succeeds.
 *
 * <p>A scheduled sweep prunes deques that are empty after their last trim, so the {@code windows}
 * map doesn't grow unboundedly across user churn (deleted accounts, transient federation IDs,
 * etc.) over a long-lived process.
 */
@Component
public class RateLimiter {

    /** Largest sane sliding window we use (10 min for slow actions like uploads). Anything older
     *  than this for any bucket is safe to drop. */
    private static final long PRUNE_HORIZON_NANOS = Duration.ofMinutes(10).toNanos();

    private final ConcurrentMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * Master switch. Defaults on. Setting {@code ichat.ratelimit.enabled=false} (the load-test
     * {@code bench} profile does this) makes every acquire succeed, so a benchmark driving many
     * connections from one user isn't capped by the per-user limits it's not trying to measure.
     * Never disable in production.
     */
    @org.springframework.beans.factory.annotation.Value("${ichat.ratelimit.enabled:true}")
    private boolean enabled = true;

    /**
     * @return {@code true} when the action is permitted, {@code false} when the caller has
     *         exceeded {@code limit} in the trailing {@code window}.
     */
    public boolean tryAcquire(String key, String action, int limit, Duration window) {
        if (!enabled) return true;
        var bucketKey = key + "|" + action;
        var now = System.nanoTime();
        var floor = now - window.toNanos();
        var allowed = new boolean[1];
        // Mutate the deque INSIDE compute (under the map's per-bin lock) rather than via a
        // separate synchronized(deque): that keeps the trim+add atomic with prune's removal, so a
        // prune can't drop a bucket in the gap between a concurrent add and the map removal and
        // silently discard the just-recorded event (BUG-22).
        windows.compute(bucketKey, (k, deque) -> {
            if (deque == null) deque = new ArrayDeque<>();
            while (!deque.isEmpty() && deque.peekFirst() < floor) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                allowed[0] = false;
            } else {
                deque.addLast(now);
                allowed[0] = true;
            }
            return deque;
        });
        return allowed[0];
    }

    /** Drop buckets whose most-recent entry is older than the prune horizon. Cheap; runs every 5 min. */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    void prune() {
        var floor = System.nanoTime() - PRUNE_HORIZON_NANOS;
        // computeIfPresent so the emptiness check and the map removal happen atomically under the
        // same bin lock that tryAcquire's compute uses — no add-vs-remove race.
        for (var key : windows.keySet()) {
            windows.computeIfPresent(key, (k, deque) -> {
                while (!deque.isEmpty() && deque.peekFirst() < floor) {
                    deque.pollFirst();
                }
                return deque.isEmpty() ? null : deque;
            });
        }
    }
}
