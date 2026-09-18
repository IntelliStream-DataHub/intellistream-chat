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

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code /ws} endpoint must be registered with {@link DomainHandleHandshakeHandler}.
 *
 * <p>That handler is what names a session after the domain handle. Without it the sessions are named
 * after Keycloak's {@code preferred_username} again, and every {@code convertAndSendToUser} in the
 * application — DM alerts, call invitations, reminders, secret receipts, slash-command notices — goes
 * to the wrong person or to nobody for any account whose login and handle differ. Nothing throws when
 * that happens, which is why it is pinned here as well as in
 * {@link DomainHandleHandshakeHandlerTest}.
 */
class WebSocketEndpointWiringTest {

    @Test
    void theStompEndpointIsRegisteredWithTheHandleNamingHandshakeHandler() {
        var handshakeHandler = mock(DomainHandleHandshakeHandler.class);
        var config = new WebSocketConfig(handshakeHandler, "http://localhost:8080",
                0, 100, 0, 100, 8192, 8192);
        var registration = mock(StompWebSocketEndpointRegistration.class);
        when(registration.setHandshakeHandler(any())).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(any())).thenReturn(registration);
        var registry = mock(StompEndpointRegistry.class);
        when(registry.addEndpoint("/ws")).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
        verify(registration).setHandshakeHandler(handshakeHandler);
    }
}
