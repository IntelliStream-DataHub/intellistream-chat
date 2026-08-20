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

import java.time.Duration;
import java.time.Instant;
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
 * <p>It also holds <b>when each online user last did something</b>, which is what auto-AWAY is
 * derived from. That used to be {@code users.last_active_at}, and that column is a record of the
 * last authenticated <em>HTTP request</em> — which is a poor proxy for a person and was wrong in
 * both directions here. Someone reading a busy channel makes no HTTP requests at all, and neither
 * does someone chatting: a send goes over STOMP, and the send path is deliberately query-free, so
 * it never touches the column. Ten minutes of active conversation turned the sender yellow. Going
 * the other way, background polls kept the column fresh for a tab nobody was looking at.
 *
 * <p>So the browser reports it instead — {@code presence.js} pings over the socket when there is
 * real input — and it is kept here rather than written to the database because it changes every
 * few seconds, is worthless after a restart, and is only ever read together with the session set
 * next to it. Held only for users who are online; the entry is dropped with their last session.
 *
 * <p>State resets on app restart by design; the persisted custom status is the only thing that
 * survives. Single-instance only — multi-node would swap this for shared state (see the
 * horizontal-scaling notes / AGENTS.md's RateLimiter migration path).
 */
@Component
public class PresenceTracker {

    private final ConcurrentHashMap<String, Set<String>> sessions = new ConcurrentHashMap<>();

    /**
     * Last time each online user was observed doing something. Only ever holds keys that are also
     * in {@link #sessions}: an offline user has no activity to be stale, and leaving the entry
     * behind would make a returning user's first moments look idle.
     */
    private final ConcurrentHashMap<String, Instant> lastActivity = new ConcurrentHashMap<>();

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
        // Opening a tab is activity, and it is stamped on every connect rather than only on the
        // first. A second window is a person doing something; treating it as nothing would leave
        // them AWAY while they were plainly there.
        lastActivity.put(username, Instant.now());
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
        if (!isOnline(username)) {
            lastActivity.remove(username);
        }
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

    /**
     * Record that {@code username} just did something in their browser.
     *
     * <p>Ignored for a user with no live session: activity without a socket is not a state this
     * can represent, and storing it would leak an entry that nothing ever removes.
     */
    public void noteActivity(String username) {
        noteActivity(username, Instant.now());
    }

    /** As {@link #noteActivity(String)}, with an explicit instant — for tests and for backdating. */
    public void noteActivity(String username, Instant at) {
        if (username == null || at == null || !isOnline(username)) return;
        lastActivity.put(username, at);
    }

    /** When this user was last seen doing something, or null if they are not online. */
    public Instant lastActivityAt(String username) {
        return username == null ? null : lastActivity.get(username);
    }

    /**
     * True when this user has a live socket but has not done anything for {@code threshold}.
     *
     * <p>False for a user who is not online at all — being offline is a different state, and
     * conflating the two is what would let "no socket" render as the same colour as "at lunch".
     */
    public boolean isIdle(String username, Duration threshold, Instant now) {
        if (!isOnline(username)) return false;
        var last = lastActivity.get(username);
        // A connected user we have never heard from counts as active rather than idle: connect
        // stamps an activity, so this only happens in the window before that lands.
        if (last == null) return false;
        return Duration.between(last, now).compareTo(threshold) >= 0;
    }

    /** Test hook — wipes all sessions. */
    public void resetForTests() {
        sessions.clear();
        lastActivity.clear();
    }
}
