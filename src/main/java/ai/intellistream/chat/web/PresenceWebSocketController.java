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

package ai.intellistream.chat.web;

import ai.intellistream.chat.config.StompAuthorizationConfig;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.PresenceService;
import ai.intellistream.chat.service.PresenceTracker;
import ai.intellistream.chat.security.RateLimiter;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpAttributesContextHolder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;

/**
 * "I am still here" — the browser telling the server that a person, not a timer, is doing something.
 *
 * <p>Auto-AWAY used to be derived from {@code users.last_active_at}, the timestamp of the last
 * authenticated HTTP request, and that is not the same question. Reading a channel is not an HTTP
 * request. Neither is sending a message: it goes over this socket, and the send path is
 * deliberately query-free, so it never touches the column. The result was that the person doing the
 * most talking went yellow after ten minutes of talking, while a forgotten background tab stayed
 * green because its polls kept the column warm.
 *
 * <p>This is the honest signal: {@code presence.js} sends here on real input — pointer, key, scroll,
 * touch — throttled hard, and only while the tab is visible. Everything else follows from it:
 * {@link PresenceTracker#isIdle} answers the AWAY question, {@code PresenceAwaySweeper} publishes
 * the going-idle edge, and this handler publishes the coming-back edge.
 *
 * <p>Over STOMP rather than as a REST ping for two reasons. It costs no HTTP request, no session
 * touch and no database read — the user comes from the CONNECT-time session cache. And a client
 * with no socket has nothing to report: without one they are OFFLINE, which is a different state
 * that needs no heartbeat.
 */
@Controller
public class PresenceWebSocketController {

    private final PresenceTracker tracker;
    private final PresenceService presenceService;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;

    public PresenceWebSocketController(PresenceTracker tracker,
                                       PresenceService presenceService,
                                       CurrentUser currentUser,
                                       SimpMessagingTemplate broker,
                                       RateLimiter rateLimiter) {
        this.tracker = tracker;
        this.presenceService = presenceService;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Record activity, and announce the return from AWAY when there is one.
     *
     * <p>The broadcast is conditional on the user having actually been idle, which makes the common
     * case — a ping from somebody who was already green — free. Without that test this would fan a
     * frame out to every subscriber several times a minute per active user, which is the shape of
     * outage a presence feature is famous for.
     */
    @MessageMapping("/presence/activity")
    public void activity(Principal principal) {
        var user = sessionUser(principal);
        if (user == null) return;
        var username = user.getUsername();

        // Client-throttled to one per 15s; this is the backstop against a client that stops
        // throttling. Dropped silently, like typing pings — a lost heartbeat costs at worst one
        // sweep's delay before the dot turns yellow.
        if (!rateLimiter.tryAcquire(username, "ws-presence-activity", 60, Duration.ofMinutes(1))) {
            return;
        }

        var wasIdle = tracker.isIdle(username, presenceService.awayThreshold(), Instant.now());
        tracker.noteActivity(username);
        if (wasIdle) {
            broker.convertAndSend("/topic/presence", presenceService.presenceFor(user));
        }
    }

    /**
     * The domain user from the STOMP session, resolved at CONNECT and cached there.
     *
     * <p>Same shape as {@code ChatWebSocketController.sessionUser} and for the same reason: this
     * runs several times a minute per connected tab, and a database read per heartbeat would make
     * the presence signal more expensive than the messages it sits alongside.
     */
    private User sessionUser(Principal principal) {
        var attributes = SimpAttributesContextHolder.getAttributes();
        if (attributes != null
                && attributes.getAttribute(StompAuthorizationConfig.SESSION_USER_KEY)
                        instanceof User cached) {
            return cached;
        }
        return principal == null ? null : currentUser.resolve(principal);
    }
}
