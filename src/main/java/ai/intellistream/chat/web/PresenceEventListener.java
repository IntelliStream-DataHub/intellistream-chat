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

import ai.intellistream.chat.config.ClientIdleHeader;
import ai.intellistream.chat.config.StompAuthorizationConfig;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.PresenceService;
import ai.intellistream.chat.service.PresenceTracker;
import ai.intellistream.chat.web.dto.PresenceDto;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpAttributesContextHolder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;

/**
 * Bridges Spring's STOMP session lifecycle events to {@link PresenceTracker}: the first connect
 * for a user emits a {@code /topic/presence} broadcast announcing them online, and the last
 * disconnect emits the offline counterpart. Multi-tab users only see one transition at each
 * end thanks to the tracker's per-username session counter.
 */
@Component
public class PresenceEventListener {

    private final PresenceTracker tracker;
    private final PresenceService presenceService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate broker;

    public PresenceEventListener(PresenceTracker tracker,
                                 PresenceService presenceService,
                                 UserRepository userRepository,
                                 SimpMessagingTemplate broker) {
        this.tracker = tracker;
        this.presenceService = presenceService;
        this.userRepository = userRepository;
        this.broker = broker;
    }

    @EventListener
    public void onConnect(SessionConnectedEvent event) {
        var sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        var lastInputAt = lastInputAt();
        resolveUser(sessionUser(), event.getUser()).ifPresent(user -> {
            if (!tracker.connect(user.getUsername(), sessionId, lastInputAt)) return;
            // First session for this user — load their persisted status (if any) and announce.
            // presenceFor derives the kind from the backdated stamp, so a reconnecting background
            // tab announces itself as AWAY rather than ACTIVE.
            broker.convertAndSend("/topic/presence", presenceService.presenceFor(user));
        });
    }

    /**
     * When the connecting client says its person last did something, or now if it did not say.
     *
     * <p>{@code StompAuthorizationConfig} parsed the {@code idle-ms} CONNECT header onto the session
     * attributes; Spring publishes {@code SessionConnectedEvent} with those attributes bound to
     * {@link SimpAttributesContextHolder}, which is the only handle this listener has on them —
     * the CONNECTED frame it receives is built fresh and carries none of the client's headers.
     * No attributes (a test harness, a future transport) means "now", the pre-header behaviour.
     */
    private static Instant lastInputAt() {
        var attributes = SimpAttributesContextHolder.getAttributes();
        if (attributes != null
                && attributes.getAttribute(ClientIdleHeader.SESSION_KEY) instanceof Instant at) {
            return at;
        }
        return Instant.now();
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        // event.getSessionId() identifies the exact STOMP session; membership-based tracking
        // makes a duplicate disconnect for an already-removed session a no-op (see PresenceTracker).
        var sessionId = event.getSessionId();
        resolveUser(sessionUser(), event.getUser()).ifPresent(user -> {
            if (!tracker.disconnect(user.getUsername(), sessionId)) return;
            // Last session closed — broadcast offline. Custom status stays in the DB; we strip it
            // from the wire here because clients render online/offline as the primary signal.
            broker.convertAndSend("/topic/presence", PresenceDto.offline(user.getUsername()));
        });
    }

    /**
     * The domain {@link User} that {@code StompAuthorizationConfig} resolved from the OIDC subject
     * at CONNECT and cached on the session attributes, or null when the attributes are not bound
     * or hold no user. Spring binds them to {@link SimpAttributesContextHolder} for both the
     * connected and the disconnect event, which is the same handle {@link #lastInputAt()} uses.
     */
    private static User sessionUser() {
        var attributes = SimpAttributesContextHolder.getAttributes();
        if (attributes != null
                && attributes.getAttribute(StompAuthorizationConfig.SESSION_USER_KEY) instanceof User user) {
            return user;
        }
        return null;
    }

    /**
     * The session's cached user when there is one, else a lookup by the principal's name.
     *
     * <p><b>The cached user comes first because the name is not the handle.</b> The principal's
     * {@code getName()} is Keycloak's {@code preferred_username} (the OIDC client sets
     * {@code user-name-attribute: preferred_username}, and {@code KeycloakRolesConverter} pins the
     * JWT principal to the same claim). The domain username is derived from it by
     * {@code UserService.sanitizeUsername} — the local part of an email-shaped login — and
     * collision-suffixed by {@code uniqueUsername}, so for exactly those accounts the two differ
     * (the N19 note in {@code ChatWebSocketController}). Looking the name up as a handle then finds
     * nothing, and the connect is never tracked: the person types, sends and receives over a live
     * socket while every dot for them stays grey. With a suffixed handle it is worse — the lookup
     * finds the <em>other</em> holder of the bare name, and one person's connects drive someone
     * else's presence. Every other STOMP handler already reads the user the interceptor cached
     * (keyed on the subject, which never drifts); this is the same user, so the key the tracker
     * sees on connect is the one it sees again on disconnect.
     *
     * <p>The name lookup remains as the fallback for a session with no cached user — a test
     * harness, or a socket that pre-dates the interceptor mid-deploy. No provisioning here: the
     * handshake went through the OIDC / resource-server chain, which provisions on first sight.
     */
    private Optional<User> resolveUser(User cached, Principal principal) {
        if (cached != null) return Optional.of(cached);
        if (principal == null) return Optional.empty();
        var username = principal.getName();
        if (username == null || username.isBlank()) return Optional.empty();
        return userRepository.findByUsernameIgnoreCase(username);
    }
}
