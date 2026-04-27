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

package ai.intellistream.radiance.web;

import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.service.PresenceService;
import ai.intellistream.radiance.service.PresenceTracker;
import ai.intellistream.radiance.web.dto.PresenceDto;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
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
        resolveUser(event.getUser()).ifPresent(user -> {
            if (!tracker.connect(user.getUsername())) return;
            // First session for this user — load their persisted status (if any) and announce.
            broker.convertAndSend("/topic/presence", presenceService.presenceFor(user));
        });
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        resolveUser(event.getUser()).ifPresent(user -> {
            if (!tracker.disconnect(user.getUsername())) return;
            // Last session closed — broadcast offline. Custom status stays in the DB; we strip it
            // from the wire here because clients render online/offline as the primary signal.
            broker.convertAndSend("/topic/presence", PresenceDto.offline(user.getUsername()));
        });
    }

    /**
     * STOMP principals come in as the Spring Security Authentication for the HTTP handshake;
     * its {@code getName()} is the underlying token's subject (Keycloak {@code sub}). We map
     * that to the domain user via {@link UserRepository#findBySubject} — no provisioning here
     * because the user must already exist (the handshake went through the resource-server
     * filter chain which provisions on first sight).
     */
    private Optional<User> resolveUser(Principal principal) {
        if (principal == null) return Optional.empty();
        var subject = principal.getName();
        if (subject == null || subject.isBlank()) return Optional.empty();
        return userRepository.findBySubject(subject);
    }
}
