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

package com.example.chat.security;

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
 */
@Component
public class RateLimiter {

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
}
