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

package ai.intellistream.radiance.security;

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
     * @return {@code true} when the action is permitted, {@code false} when the caller has
     *         exceeded {@code limit} in the trailing {@code window}.
     */
    public boolean tryAcquire(String key, String action, int limit, Duration window) {
        var bucketKey = key + "|" + action;
        var now = System.nanoTime();
        var floor = now - window.toNanos();
        var deque = windows.computeIfAbsent(bucketKey, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < floor) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /** Drop buckets whose most-recent entry is older than the prune horizon. Cheap; runs every 5 min. */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    void prune() {
        var floor = System.nanoTime() - PRUNE_HORIZON_NANOS;
        windows.entrySet().removeIf(entry -> {
            var deque = entry.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && deque.peekFirst() < floor) {
                    deque.pollFirst();
                }
                return deque.isEmpty();
            }
        });
    }
}
