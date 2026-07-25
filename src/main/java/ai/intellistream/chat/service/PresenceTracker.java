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

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory STOMP-session tracker. A user is "online" while at least one of their browser tabs
 * has a live STOMP connection. We track the actual set of STOMP session ids per user (not a bare
 * counter) so the connect/disconnect edges are idempotent: Spring documents that
 * {@code SessionDisconnectEvent} may be published more than once for a single session, and a
 * counter would double-decrement on the duplicate and wrongly drop a still-connected user
 * offline. Set membership makes a repeat add/remove of the same session id a no-op.
 *
 * <p>All mutations run inside {@link ConcurrentHashMap#compute} so the "did the set transition
 * empty↔non-empty" edge is computed atomically per user; the inner set is itself concurrent so
 * {@link #isOnline}/{@link #onlineUsernames} can read it without locking. The map never holds an
 * empty set (compute returns {@code null} to drop it), so its key set is exactly the online users.
 *
 * <p>State resets on app restart by design; the persisted custom status is the only thing that
 * survives. Single-instance only — multi-node would swap this for shared state (see the
 * horizontal-scaling notes / AGENT.md's RateLimiter migration path).
 */
@Component
public class PresenceTracker {

    private final ConcurrentHashMap<String, Set<String>> sessions = new ConcurrentHashMap<>();

    /** Returns true when this is the first session for {@code username} (i.e. they just came online). */
    public boolean connect(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null) return false;
        boolean[] cameOnline = {false};
        sessions.compute(username, (k, set) -> {
            if (set == null) set = ConcurrentHashMap.newKeySet();
            boolean wasEmpty = set.isEmpty();
            set.add(sessionId);
            cameOnline[0] = wasEmpty && !set.isEmpty();
            return set;
        });
        return cameOnline[0];
    }

    /** Returns true when this was the last session for {@code username} (i.e. they just went offline). */
    public boolean disconnect(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null) return false;
        boolean[] wentOffline = {false};
        sessions.compute(username, (k, set) -> {
            if (set == null) return null; // already gone — duplicate disconnect, no edge
            boolean removed = set.remove(sessionId);
            if (set.isEmpty()) {
                wentOffline[0] = removed; // only an edge if we actually removed a live session
                return null;              // drop the empty set so keySet stays == online users
            }
            return set;
        });
        return wentOffline[0];
    }

    public boolean isOnline(String username) {
        if (username == null) return false;
        var set = sessions.get(username);
        return set != null && !set.isEmpty();
    }

    public Set<String> onlineUsernames() {
        return Set.copyOf(sessions.keySet());
    }

    /** Test hook — wipes all sessions. */
    public void resetForTests() {
        sessions.clear();
    }
}
