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

package ai.intellistream.chat.config;

import java.time.Duration;
import java.time.Instant;

/**
 * The {@code idle-ms} native header a browser puts on its STOMP {@code CONNECT} frame: how long
 * ago the person last touched that tab.
 *
 * <p>Opening a socket used to count as activity, unconditionally. For a page load that is right —
 * somebody just navigated here. For a <em>reconnect</em> it is wrong: the tab has been sitting in
 * the background, the browser throttled the heartbeat, the server hung up, and four seconds later
 * the client dialled back in. Nobody touched anything, but the reconnect stamped "active now" and
 * the person who had been correctly yellow came back green — and, with the heartbeat still
 * throttled, cycled grey → green → grey for as long as the tab stayed hidden, never once showing
 * the AWAY they actually were. So the client says how idle it is, and
 * {@link ai.intellistream.chat.service.PresenceTracker#connect(String, String, Instant)} backdates.
 *
 * <p>Parsed here, once, because the value crosses from transport to service: the interceptor in
 * {@link StompAuthorizationConfig} reads the header and stores an {@link Instant} on the session
 * attributes under {@link #SESSION_KEY}; {@code PresenceEventListener} reads that on the
 * {@code SessionConnectedEvent}. Neither side sees the raw string.
 */
public final class ClientIdleHeader {

    /** Native STOMP header name, set by {@code presence.js}'s {@code stompOptions()}. */
    public static final String HEADER = "idle-ms";

    /** Session-attribute key holding the {@link Instant} of the client's last reported input. */
    public static final String SESSION_KEY = "intellistream.lastInputAt";

    /**
     * Cap on how far back a client may claim to be idle. Far past any away threshold, so it never
     * changes the derived state; it only keeps a nonsense value from producing an {@code Instant}
     * in a different geological era.
     */
    static final Duration MAX_IDLE = Duration.ofDays(30);

    private ClientIdleHeader() {
    }

    /**
     * When the person last did something, given the raw header and the current time.
     *
     * <p>Lenient by design: a missing, blank, negative or unparseable value means {@code now} —
     * the behaviour every client had before the header existed, and the right default for a
     * client that does not send one. A claim beyond {@link #MAX_IDLE} is clamped rather than
     * refused, for the same reason.
     */
    public static Instant lastInputAt(String raw, Instant now) {
        if (raw == null) return now;
        long idleMs;
        try {
            idleMs = Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            return now;
        }
        if (idleMs <= 0) return now;
        var idle = Duration.ofMillis(idleMs);
        if (idle.compareTo(MAX_IDLE) > 0) idle = MAX_IDLE;
        return now.minus(idle);
    }
}
