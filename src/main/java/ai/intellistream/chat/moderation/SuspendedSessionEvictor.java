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

package ai.intellistream.chat.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the live WebSocket sessions so a ban can hang up on them.
 *
 * <p>Blocking future frames is not enough. A suspended user with an open socket keeps a subscribed,
 * receiving connection to every channel they were in: the moderation action people expect from
 * "suspend" is that the client goes dead, not that it silently stops being able to type. Nothing in
 * Spring's messaging layer offers that — {@code SimpUserRegistry} enumerates sessions but its
 * {@code SimpSession} is a read-only view with no handle on the transport, and there is no
 * "disconnect this user" on the broker. The socket itself is reachable only from the
 * {@link WebSocketHandler}, so this class installs a decorator to keep a reference to each one.
 *
 * <p><b>Sessions are keyed by STOMP session id and tagged with a user id at CONNECT.</b> The
 * decorator sees the socket before STOMP exists, so a freshly-accepted connection is recorded
 * untagged and {@code StompAuthorizationConfig} calls {@link #bind} once it has resolved the domain
 * user for that session. An untagged session is one that has not completed a STOMP CONNECT, and
 * therefore has not been authorized to do anything either — it cannot subscribe or send, and its
 * CONNECT will be refused because that path re-reads the account from the database.
 *
 * <p>The user id, not the principal name, is the tag. Those two are not interchangeable here: the
 * principal name is Keycloak's {@code preferred_username} while the domain username is a sanitized,
 * collision-suffixed derivative of it, and they differ for exactly the email-shaped accounts that a
 * ban is most likely to be aimed at (see the N19 note in {@code ChatWebSocketController}).
 *
 * <p>Eviction scans the map rather than maintaining a per-user index. At 100k connections that is a
 * sub-millisecond walk of a few thousand entries per suspension, a few times a year, and it buys a
 * single structure that cannot disagree with itself about what is open.
 */
@Component
public class SuspendedSessionEvictor implements WebSocketMessageBrokerConfigurer, WebSocketHandlerDecoratorFactory {

    private static final Logger log = LoggerFactory.getLogger(SuspendedSessionEvictor.class);

    /** 1008 Policy Violation — the standard "you are not allowed to be here" close code. */
    private static final CloseStatus SUSPENDED = CloseStatus.POLICY_VIOLATION.withReason("Account suspended");

    private record Live(WebSocketSession socket, Long userId) {}

    private final ConcurrentHashMap<String, Live> sessions = new ConcurrentHashMap<>();

    /**
     * Registered as a plain {@code WebSocketMessageBrokerConfigurer} bean rather than by editing
     * {@code WebSocketConfig}: Spring collects every configurer in the context, so the machinery for
     * a feature can live with the feature. The decorator only wraps lifecycle callbacks — it adds
     * nothing to the per-frame path.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(this);
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), new Live(session, null));
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /**
     * Tag a live socket with the account that owns it. Idempotent, and a no-op for a session that
     * has already gone away — the close callback is the only thing that removes entries, so a race
     * with a disconnect resolves as "not there, nothing to tag".
     */
    public void bind(String sessionId, Long userId) {
        if (sessionId == null || userId == null) return;
        sessions.computeIfPresent(sessionId,
                (id, live) -> userId.equals(live.userId()) ? live : new Live(live.socket(), userId));
    }

    /**
     * Close every open socket belonging to {@code userId}. Returns how many were closed, which the
     * caller records in the audit trail — "suspended alice, closed 3 live sessions" is the line that
     * answers whether the ban actually took effect.
     *
     * <p>Failures are logged and counted as not-closed rather than propagated: a socket that cannot
     * be closed (already half-dead, peer gone) must not abort a suspension, and the frame-level
     * refusal in {@code StompAuthorizationConfig} covers it regardless.
     */
    public int closeAllFor(Long userId) {
        if (userId == null) return 0;
        int closed = 0;
        for (var live : sessions.values()) {
            if (!userId.equals(live.userId())) continue;
            try {
                live.socket().close(SUSPENDED);
                closed++;
            } catch (Exception e) {
                log.warn("Could not close WebSocket session {} for suspended user {}",
                        live.socket().getId(), userId, e);
            }
        }
        return closed;
    }

    /** How many sockets this process currently holds open. Diagnostics and tests. */
    public int liveSessionCount() {
        return sessions.size();
    }
}
