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

import ai.intellistream.chat.moderation.SuspendedSessionEvictor;
import ai.intellistream.chat.service.ChannelSubscriptionRevoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Revokes a departing member's live channel subscriptions by feeding the broker the
 * {@code UNSUBSCRIBE} frames their client would have sent.
 *
 * <p><b>Why this shape.</b> Spring's broker offers no "remove this subscription" call — the
 * subscription registry is populated by inbound {@code SUBSCRIBE} frames and drained by inbound
 * {@code UNSUBSCRIBE}/{@code DISCONNECT} ones. So the way to remove one from the server side is to
 * send the frame: {@code SimpUserRegistry} knows which subscriptions exist and what their ids are,
 * and a synthesised {@code UNSUBSCRIBE} on the client-inbound channel takes them off exactly as the
 * client's own would. Nothing about the socket is disturbed — the user keeps their DMs, their other
 * channels and their presence subscription, which is the whole advantage over the blunter
 * alternative of closing their connection and making them reconnect.
 *
 * <p><b>Why not filter on the way out instead.</b> Membership could be checked per recipient when
 * broadcasting, and that would be authoritative. It would also put a membership lookup on the
 * message send path, which is deliberately query-free and is the hottest code in the application.
 * Revoking on the rare event beats taxing the common one.
 *
 * <p><b>Why the session-id detour.</b> {@code SimpUser.getName()} is the security principal's name,
 * which is Keycloak's {@code preferred_username}; the domain username is a sanitised,
 * collision-suffixed derivative and the two differ for exactly the email-shaped accounts (see the N19
 * note in {@code ChatWebSocketController}). {@code SuspendedSessionEvictor} already maps STOMP session
 * id to domain user id — it is tagged at CONNECT for the ban feature — so that is the bridge, rather
 * than a second map saying the same thing.
 *
 * <p><b>Single node.</b> Both the registry and the session map are per-process, so this revokes on
 * this node only. That is the same scope as {@code RateLimiter}, {@code ChannelAccessCache} and the
 * ban evictor, and it is bounded by the cache TTL: on another node the departing member cannot
 * re-subscribe once their cached decision expires, and a reconnect re-authorises from the database.
 * Multi-node needs a broker relay, which is deferred with the rest of horizontal scaling.
 */
@Component
public class StompChannelSubscriptionRevoker implements ChannelSubscriptionRevoker {

    private static final Logger log = LoggerFactory.getLogger(StompChannelSubscriptionRevoker.class);

    private final SimpUserRegistry userRegistry;
    private final MessageChannel clientInboundChannel;
    private final SuspendedSessionEvictor sessions;

    /**
     * {@code @Lazy} on the broker beans: they are built by the messaging configuration that
     * {@code StompAuthorizationConfig} contributes to, and that configurer reaches back into the
     * service layer. Deferring the lookup keeps this component out of that construction order
     * entirely rather than relying on it resolving in a particular sequence.
     */
    public StompChannelSubscriptionRevoker(
            @Lazy SimpUserRegistry userRegistry,
            @Lazy @Qualifier("clientInboundChannel") MessageChannel clientInboundChannel,
            SuspendedSessionEvictor sessions) {
        this.userRegistry = userRegistry;
        this.clientInboundChannel = clientInboundChannel;
        this.sessions = sessions;
    }

    @Override
    public void revoke(long channelId, long userId) {
        var sessionIds = sessions.sessionIdsFor(userId);
        if (sessionIds.isEmpty()) {
            return;
        }
        int revoked = sweep(channelId, sessionIds::contains);
        if (revoked > 0) {
            log.debug("Revoked {} live subscription(s) on channel {} for user {}",
                    revoked, channelId, userId);
        }
    }

    /**
     * Every session, not one user's — a destroyed channel has no membership left to enumerate, and a
     * PUBLIC channel's topic could be held by people who never joined it anyway (SUBSCRIBE goes
     * through {@code requireMember}, which short-circuits to allowed for PUBLIC).
     *
     * <p>The cost is a walk of the registry, which is bounded by connections rather than channels and
     * happens once per channel deletion. That is the rarest event in the application.
     */
    @Override
    public void revokeAll(long channelId) {
        int revoked = sweep(channelId, sessionId -> true);
        if (revoked > 0) {
            log.debug("Revoked {} live subscription(s) on destroyed channel {}", revoked, channelId);
        }
    }

    /** Feed an UNSUBSCRIBE for every subscription to this channel's topics held by a matching session. */
    private int sweep(long channelId, java.util.function.Predicate<String> sessionMatches) {
        var topic = "/topic/channels/" + channelId;
        int revoked = 0;
        for (var user : userRegistry.getUsers()) {
            for (var session : user.getSessions()) {
                if (!sessionMatches.test(session.getId())) {
                    continue;
                }
                for (var subscription : session.getSubscriptions()) {
                    var destination = subscription.getDestination();
                    // The channel topic and everything under it — /typing is a separate
                    // subscription to the same channel and has to go with it. Equality-or-slash
                    // rather than a bare startsWith, so channel 42 does not take channel 420 with it.
                    if (destination == null
                            || !(destination.equals(topic) || destination.startsWith(topic + "/"))) {
                        continue;
                    }
                    unsubscribe(session.getId(), subscription.getId());
                    revoked++;
                }
            }
        }
        return revoked;
    }

    private void unsubscribe(String sessionId, String subscriptionId) {
        var accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setLeaveMutable(true);
        try {
            clientInboundChannel.send(
                    MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()));
        } catch (RuntimeException e) {
            // Best effort. A failure here leaves one stale subscription until the client reconnects;
            // the membership row is already gone, so nothing can be re-established after it.
            log.warn("Could not revoke subscription {} on session {}", subscriptionId, sessionId, e);
        }
    }
}
