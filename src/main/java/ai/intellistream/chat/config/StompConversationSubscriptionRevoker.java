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
import ai.intellistream.chat.service.ConversationSubscriptionRevoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

/**
 * {@link ConversationSubscriptionRevoker} over the STOMP broker — the conversation twin of
 * {@link StompChannelSubscriptionRevoker}, sharing its walk via {@link StompSubscriptionSweeper}.
 *
 * <p>There is no {@code revokeAll} here, and there should not be. Its channel counterpart exists
 * because a PUBLIC channel's topic can be held by people who never joined it, so deleting a channel
 * has to sweep every session rather than iterate a membership list. A conversation has no such
 * tier: SUBSCRIBE requires membership, full stop, so the members *are* the subscribers and there is
 * nothing a per-user revoke would miss. Conversations are also never deleted, which is the event
 * that method exists for.
 *
 * <p>Single node, like everything else that reads the broker's in-process registry. On another node
 * the departing member cannot re-subscribe once their SUBSCRIBE is re-authorised against the
 * database — which for a conversation is every time, since there is no access cache in front of it.
 */
@Component
public class StompConversationSubscriptionRevoker implements ConversationSubscriptionRevoker {

    private static final Logger log = LoggerFactory.getLogger(StompConversationSubscriptionRevoker.class);

    private final SimpUserRegistry userRegistry;
    private final MessageChannel clientInboundChannel;
    private final SuspendedSessionEvictor sessions;

    /**
     * {@code @Lazy} on the broker beans for the reason spelled out on the channel revoker: they are
     * built by the messaging configuration that {@code StompAuthorizationConfig} contributes to, and
     * that configurer reaches back into the service layer.
     */
    public StompConversationSubscriptionRevoker(
            @Lazy SimpUserRegistry userRegistry,
            @Lazy @Qualifier("clientInboundChannel") MessageChannel clientInboundChannel,
            SuspendedSessionEvictor sessions) {
        this.userRegistry = userRegistry;
        this.clientInboundChannel = clientInboundChannel;
        this.sessions = sessions;
    }

    @Override
    public void revoke(long conversationId, long userId) {
        // Session id to domain user id, via the map the ban feature already tags at CONNECT.
        // SimpUser.getName() is the security principal's name — Keycloak's preferred_username —
        // which is not the domain username for email-shaped accounts (the N19 note in
        // ChatWebSocketController), so matching on it would silently miss exactly those users.
        var sessionIds = sessions.sessionIdsFor(userId);
        if (sessionIds.isEmpty()) {
            return;
        }
        int revoked = StompSubscriptionSweeper.sweep(userRegistry, clientInboundChannel,
                "/topic/conversations/" + conversationId, sessionIds::contains);
        if (revoked > 0) {
            log.debug("Revoked {} live subscription(s) on conversation {} for user {}",
                    revoked, conversationId, userId);
        }
    }
}
