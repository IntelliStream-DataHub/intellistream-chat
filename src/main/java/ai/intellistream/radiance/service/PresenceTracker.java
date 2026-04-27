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

package ai.intellistream.radiance.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory STOMP-session counter. A user is "online" while at least one of their browser
 * tabs has a live STOMP connection. Two-tab users get a single {@code 0→1} transition on
 * first connect and a single {@code 1→0} transition when the last tab closes — the
 * {@link #connect}/{@link #disconnect} return values let callers fire a broadcast only on
 * those edges, so we don't spam {@code /topic/presence} on every page load.
 *
 * <p>State resets on app restart by design; the persisted custom status is the only thing
 * that survives. With a single Spring Boot instance this is fine — switching to multi-node
 * would mean swapping this for a Redis / Hazelcast distributed counter (mirrors the
 * {@code RateLimiter} migration path called out in CLAUDE.md).
 */
@Component
public class PresenceTracker {

    private final ConcurrentHashMap<String, AtomicInteger> sessions = new ConcurrentHashMap<>();

    /** Returns true when this is the first session for {@code username} (i.e. they just came online). */
    public boolean connect(String username) {
        if (username == null || username.isBlank()) return false;
        var counter = sessions.computeIfAbsent(username, k -> new AtomicInteger());
        return counter.incrementAndGet() == 1;
    }

    /** Returns true when this was the last session for {@code username} (i.e. they just went offline). */
    public boolean disconnect(String username) {
        if (username == null || username.isBlank()) return false;
        var counter = sessions.get(username);
        if (counter == null) return false;
        var remaining = counter.decrementAndGet();
        if (remaining <= 0) {
            // Compute-remove guard: only drop if still <=0 — another connect could have raced
            // between decrement and remove.
            sessions.computeIfPresent(username, (k, v) -> v.get() <= 0 ? null : v);
            return remaining == 0;
        }
        return false;
    }

    public boolean isOnline(String username) {
        if (username == null) return false;
        var counter = sessions.get(username);
        return counter != null && counter.get() > 0;
    }

    public Set<String> onlineUsernames() {
        return Set.copyOf(sessions.keySet());
    }

    /** Test hook — wipes all sessions. */
    public void resetForTests() {
        sessions.clear();
    }
}
