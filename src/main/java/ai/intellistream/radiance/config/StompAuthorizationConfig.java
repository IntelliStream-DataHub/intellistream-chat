package ai.intellistream.radiance.config;

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

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Authorize STOMP {@code SUBSCRIBE} frames so a connected user can only subscribe to channels
 * they're allowed to read. Without this, anyone with a valid handshake could
 * {@code SUBSCRIBE /topic/channels/{any-uuid}} and silently observe private channels.
 */
@Configuration
@EnableWebSocketMessageBroker
public class StompAuthorizationConfig implements WebSocketMessageBrokerConfigurer {

    static final Pattern CHANNEL_TOPIC =
            Pattern.compile("^/topic/channels/([0-9a-fA-F-]{36})(?:/[a-zA-Z0-9_-]+)?$");
    static final Pattern CONVERSATION_TOPIC =
            Pattern.compile("^/topic/conversations/([0-9a-fA-F-]{36})(?:/[a-zA-Z0-9_-]+)?$");

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
                if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    return message;
                }
                var dest = accessor.getDestination();
                if (dest == null) return message;

                var channelMatch = CHANNEL_TOPIC.matcher(dest);
                var convMatch = CONVERSATION_TOPIC.matcher(dest);
                if (!channelMatch.matches() && !convMatch.matches()) return message;

                var principal = accessor.getUser();
                if (principal == null) {
                    throw new AccessDeniedException("Authentication required to subscribe");
                }
                var user = currentUser.resolve(principal);

                if (channelMatch.matches()) {
                    UUID channelId;
                    try { channelId = UUID.fromString(channelMatch.group(1)); }
                    catch (IllegalArgumentException ex) { return message; }
                    var ch = channelService.requireById(channelId);
                    // Re-uses the same membership semantic the message-read path uses
                    // (PUBLIC channels are subscribable by any authenticated user).
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
}
