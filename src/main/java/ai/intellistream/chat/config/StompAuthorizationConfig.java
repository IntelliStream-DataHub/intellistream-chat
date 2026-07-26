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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.AccountSuspendedException;
import ai.intellistream.chat.moderation.SuspendedSessionEvictor;
import ai.intellistream.chat.moderation.SuspensionRegistry;
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
 * <p><b>That cache is also why suspension is enforced here.</b> The cached {@code User} is a
 * snapshot taken at CONNECT, so its {@code suspended} flag is exactly as old as the connection —
 * on a session opened before the ban it says "fine" forever. Re-resolving per frame to find out
 * would reintroduce the query the cache exists to avoid, on the hottest path in the application, so
 * the check instead reads {@link SuspensionRegistry}: an in-memory set behind one volatile boolean,
 * which costs a single field read in the overwhelmingly common case where nobody is suspended at
 * all. Refusing the frame is the second half of the job — {@code BanService} closes the sockets
 * outright — and it is what covers frames already in flight when that happened.
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
    private final SuspensionRegistry suspensions;
    private final SuspendedSessionEvictor evictor;

    public StompAuthorizationConfig(ChannelService channelService,
                                    ConversationService conversationService,
                                    CurrentUser currentUser,
                                    RateLimiter rateLimiter,
                                    SuspensionRegistry suspensions,
                                    SuspendedSessionEvictor evictor) {
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
        this.suspensions = suspensions;
        this.evictor = evictor;
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
                        // Throws for a suspended account: resolve() re-reads the row, so a new
                        // session can never be opened by one however stale the registry is.
                        var user = currentUser.resolve(principal);
                        sessionAttrs.put(SESSION_USER_KEY, user);
                        // Tie the socket to the account so a later ban can hang up on it.
                        evictor.bind(accessor.getSessionId(), user.getId());
                    }
                    return message;
                }

                if (StompCommand.SEND.equals(command) || StompCommand.SUBSCRIBE.equals(command)) {
                    refuseIfSuspended(accessor);
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
                // authorization work below. Excess frames are dropped (return null) rather than
                // throwing, so the connection isn't torn down.
                //
                // The budget is 2000/min because the client now subscribes to *every* channel the
                // user is a member of, not to the ten a curated sidebar happened to render. At the
                // old 200 a user in 200 channels lost the tail of their own subscriptions to this
                // limiter — silently, since a dropped frame produces no error — and the symptom
                // would have been "notifications work for most of my channels", which is close to
                // undebuggable. A budget has to be above legitimate use before it is a defence, and
                // legitimate use here is now bounded by membership count.
                //
                // Dropping frames is still the right response above it: the authorization work per
                // frame is a cache hit plus, for a private channel, one cached membership check, so
                // the flood this guards against is cheap to absorb and expensive only in aggregate.
                var sessionId = accessor.getSessionId();
                if (sessionId != null
                        && !rateLimiter.tryAcquire(sessionId, "ws-subscribe", 2000, java.time.Duration.ofMinutes(1))) {
                    return null;
                }

                var user = resolveCached(accessor);

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
                    // remain subscribable by any authenticated user. Cached because a client now
                    // subscribes once per channel it is a member of, so this runs membership-count
                    // times per connect — free for PUBLIC, one cached decision for PRIVATE.
                    channelService.requireMemberCached(ch, user);
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

    private User resolveCached(StompHeaderAccessor accessor) {
        var sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs != null) {
            var cached = sessionAttrs.get(SESSION_USER_KEY);
            if (cached instanceof User u) return u;
        }
        // CONNECT didn't fire (test harness, mid-deploy session that pre-existed the cache,
        // etc.) — fall back to resolving and stash for next time.
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new AccessDeniedException("Authentication required to subscribe");
        }
        var user = currentUser.resolve(principal);
        if (sessionAttrs != null) {
            sessionAttrs.put(SESSION_USER_KEY, user);
        }
        // Same binding as the CONNECT branch, for the session that got here without one — a socket
        // this interceptor has authorised must be a socket a ban can close.
        evictor.bind(accessor.getSessionId(), user.getId());
        return user;
    }

    /**
     * Refuse a frame from an account suspended since this session connected.
     *
     * <p>The volatile read comes first and short-circuits the whole check when nobody is suspended,
     * which is the state this branch is in on every frame of a healthy deployment — the session
     * attribute lookup below is not on that path.
     *
     * <p>Throwing rather than dropping the frame: the sender's client is about to be disconnected
     * anyway, and an ERROR frame naming the reason is a great deal easier to diagnose than a client
     * whose messages silently stop arriving. The exception is an {@code AccessDeniedException},
     * which is what this interceptor already throws to refuse a subscription.
     */
    private void refuseIfSuspended(StompHeaderAccessor accessor) {
        if (!suspensions.anySuspended()) return;
        var sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs == null) return;
        // Only the cached user is consulted. A session with no cached user has not completed
        // CONNECT, and the resolve that gives it one checks the database itself.
        if (sessionAttrs.get(SESSION_USER_KEY) instanceof User user
                && suspensions.isSuspended(user.getId())) {
            throw new AccountSuspendedException(user.getUsername());
        }
    }
}
