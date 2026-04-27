package ai.intellistream.radiance.config;

import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.security.CurrentUser;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.ConversationService;
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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Authorize STOMP {@code SUBSCRIBE} frames so a connected user can only subscribe to channels
 * they're allowed to read. Without this, anyone with a valid handshake could
 * {@code SUBSCRIBE /topic/channels/{any-uuid}} and silently observe private channels.
 *
 * <p>The resolved {@link User} is cached on the STOMP session attributes on the {@code CONNECT}
 * frame; subsequent {@code SUBSCRIBE} frames read from the cache instead of going back through
 * {@link CurrentUser#resolve} (which provisions on first sight and writes a per-request
 * last-active stamp). Without the cache, a chatty client triggers a DB upsert per frame.
 */
@Configuration
@EnableWebSocketMessageBroker
public class StompAuthorizationConfig implements WebSocketMessageBrokerConfigurer {

    static final Pattern CHANNEL_TOPIC =
            Pattern.compile("^/topic/channels/([0-9a-fA-F-]{36})(?:/[a-zA-Z0-9_-]+)?$");
    static final Pattern CONVERSATION_TOPIC =
            Pattern.compile("^/topic/conversations/([0-9a-fA-F-]{36})(?:/[a-zA-Z0-9_-]+)?$");
    static final String SESSION_USER_KEY = "radiance.chatUser";

    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final CurrentUser currentUser;

    public StompAuthorizationConfig(ChannelService channelService,
                                    ConversationService conversationService,
                                    CurrentUser currentUser) {
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.currentUser = currentUser;
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

                var user = resolveCached(accessor.getSessionAttributes(), accessor.getUser());

                if (channelMatch.matches()) {
                    UUID channelId;
                    try { channelId = UUID.fromString(channelMatch.group(1)); }
                    catch (IllegalArgumentException ex) { return message; }
                    var ch = channelService.requireById(channelId);
                    // Subscribe = read; reuses the read-access semantic so PUBLIC channels
                    // remain subscribable by any authenticated user.
                    channelService.requireMember(ch, user);
                } else {
                    UUID conversationId;
                    try { conversationId = UUID.fromString(convMatch.group(1)); }
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
