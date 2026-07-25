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

package ai.intellistream.chat.config;

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.messaging.simp.config.ChannelRegistration;

import java.security.Principal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Authorize STOMP {@code SUBSCRIBE} frames so a connected user can only subscribe to channels
 * they're allowed to read. Without this, anyone with a valid handshake could
 * {@code SUBSCRIBE /topic/channels/{any-id}} and silently observe private channels.
 *
 * <p>The resolved {@link User} is cached on the STOMP session attributes on the {@code CONNECT}
 * frame; subsequent {@code SUBSCRIBE} frames read from the cache instead of going back through
 * {@link CurrentUser#resolve} (which provisions on first sight and writes a per-request
 * last-active stamp). Without the cache, a chatty client triggers a DB upsert per frame.
 *
 * <p><b>Presence visibility (SEC-15) is intentionally workspace-wide.</b> Only
 * {@code /topic/channels/{id}} and {@code /topic/conversations/{id}} are membership-gated here;
 * {@code /topic/presence} and {@code /topic/users} are deliberately NOT — any authenticated user
 * may observe every user's online/offline transitions and custom status, matching the
 * Slack/Mattermost model this app follows. If a deployment needs presence scoped to shared
 * channels, gate those destinations and scope the broadcasts in PresenceEventListener /
 * PresenceRestController.
 */
@Configuration
@EnableWebSocketMessageBroker
public class StompAuthorizationConfig implements WebSocketMessageBrokerConfigurer {

    static final Pattern CHANNEL_TOPIC =
            Pattern.compile("^/topic/channels/(\\d+)(?:/[a-zA-Z0-9_-]+)?$");
    static final Pattern CONVERSATION_TOPIC =
            Pattern.compile("^/topic/conversations/(\\d+)(?:/[a-zA-Z0-9_-]+)?$");
    /**
     * Session-attribute key holding the domain {@code User} resolved once at CONNECT. Public
     * because the message handlers read it instead of re-resolving the principal per frame —
     * see {@code ChatWebSocketController.sessionUser}.
     */
    public static final String SESSION_USER_KEY = "intellistream.chatUser";

    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public StompAuthorizationConfig(ChannelService channelService,
                                    ConversationService conversationService,
                                    CurrentUser currentUser,
                                    RateLimiter rateLimiter) {
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) return message;
                var command = accessor.getCommand();

                if (StompCommand.CONNECT.equals(command)) {
                    // Resolve once at session start and cache on the session attributes;
                    // re-resolving on every SUBSCRIBE / SEND would write to users.last_active_at
                    // per frame and amplify any DoS surface against the DB.
                    var principal = accessor.getUser();
                    var sessionAttrs = accessor.getSessionAttributes();
                    if (principal != null && sessionAttrs != null) {
                        sessionAttrs.put(SESSION_USER_KEY, currentUser.resolve(principal));
                    }
                    return message;
                }

                if (!StompCommand.SUBSCRIBE.equals(command)) {
                    return message;
                }
                var dest = accessor.getDestination();
                if (dest == null) return message;

                var channelMatch = CHANNEL_TOPIC.matcher(dest);
                var convMatch = CONVERSATION_TOPIC.matcher(dest);
                if (!channelMatch.matches() && !convMatch.matches()) return message;

                // Cap SUBSCRIBE frames per session so a client can't flood them to amplify the
                // authorization work below. 200/min comfortably covers the initial burst of
                // subscribing to every sidebar channel on connect; excess frames are dropped
                // (return null) rather than throwing, so the connection isn't torn down.
                var sessionId = accessor.getSessionId();
                if (sessionId != null
                        && !rateLimiter.tryAcquire(sessionId, "ws-subscribe", 200, java.time.Duration.ofMinutes(1))) {
                    return null;
                }

                var user = resolveCached(accessor.getSessionAttributes(), accessor.getUser());

                if (channelMatch.matches()) {
                    Long channelId;
                    try { channelId = Long.parseLong(channelMatch.group(1)); }
                    catch (IllegalArgumentException ex) { return message; }
                    // Cached lookup: this runs once per subscription, so on a mass reconnect it is
                    // a database round trip per client on the same threads that are accepting the
                    // connections. Read-only and no lazy associations touched, which is the
                    // contract requireByIdForMessaging asks for.
                    var ch = channelService.requireByIdForMessaging(channelId);
                    // Subscribe = read; reuses the read-access semantic so PUBLIC channels
                    // remain subscribable by any authenticated user.
                    channelService.requireMember(ch, user);
                } else {
                    Long conversationId;
                    try { conversationId = Long.parseLong(convMatch.group(1)); }
                    catch (IllegalArgumentException ex) { return message; }
                    var conv = conversationService.requireById(conversationId);
                    // DMs are private to their participants — no PUBLIC analogue.
                    conversationService.requireMember(conv, user);
                }
                return message;
            }
        });
    }

    private User resolveCached(Map<String, Object> sessionAttrs, Principal principal) {
        if (sessionAttrs != null) {
            var cached = sessionAttrs.get(SESSION_USER_KEY);
            if (cached instanceof User u) return u;
        }
        // CONNECT didn't fire (test harness, mid-deploy session that pre-existed the cache,
        // etc.) — fall back to resolving and stash for next time.
        if (principal == null) {
            throw new AccessDeniedException("Authentication required to subscribe");
        }
        var user = currentUser.resolve(principal);
        if (sessionAttrs != null) {
            sessionAttrs.put(SESSION_USER_KEY, user);
        }
        return user;
    }
}
