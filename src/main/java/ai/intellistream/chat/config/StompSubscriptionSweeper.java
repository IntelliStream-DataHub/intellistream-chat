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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;

import java.util.function.Predicate;

/**
 * Take subscriptions off the broker by feeding it the {@code UNSUBSCRIBE} frames the client would
 * have sent.
 *
 * <p>Spring's broker offers no "remove this subscription" call — the registry is populated by
 * inbound SUBSCRIBE frames and drained by inbound UNSUBSCRIBE/DISCONNECT ones — so synthesising the
 * frame is the way. {@code SimpUserRegistry} knows which subscriptions exist and what their ids are;
 * nothing about the socket is disturbed, which is the whole advantage over closing the connection.
 *
 * <p>Extracted when conversations became leavable and needed the identical sweep over a different
 * topic prefix. A second copy of this loop is exactly the drift this codebase keeps getting bitten
 * by, and the two callers are far enough apart that nobody would have diffed them.
 */
final class StompSubscriptionSweeper {

    private static final Logger log = LoggerFactory.getLogger(StompSubscriptionSweeper.class);

    private StompSubscriptionSweeper() {}

    /**
     * Feed an UNSUBSCRIBE for every subscription to {@code topic} — or anything under it — held by a
     * session {@code sessionMatches} accepts.
     *
     * <p>Equality-or-slash rather than a bare {@code startsWith}, so room 42 does not take room 420
     * with it, and so a room's sub-destinations ({@code /typing}) go with the room: they are
     * separate subscriptions to the same thing.
     *
     * @return how many subscriptions were revoked
     */
    static int sweep(SimpUserRegistry userRegistry, MessageChannel clientInboundChannel,
                     String topic, Predicate<String> sessionMatches) {
        int revoked = 0;
        for (var user : userRegistry.getUsers()) {
            for (var session : user.getSessions()) {
                if (!sessionMatches.test(session.getId())) {
                    continue;
                }
                for (var subscription : session.getSubscriptions()) {
                    var destination = subscription.getDestination();
                    if (destination == null
                            || !(destination.equals(topic) || destination.startsWith(topic + "/"))) {
                        continue;
                    }
                    unsubscribe(clientInboundChannel, session.getId(), subscription.getId());
                    revoked++;
                }
            }
        }
        return revoked;
    }

    private static void unsubscribe(MessageChannel clientInboundChannel,
                                    String sessionId, String subscriptionId) {
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
