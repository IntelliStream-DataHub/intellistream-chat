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

package ai.intellistream.radiance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Origins permitted to open the {@code /ws} STOMP endpoint. The previous {@code "*"}
     * let any malicious site (visited by a logged-in user) negotiate a WebSocket against
     * the chat server — combined with cookie-based auth, that's a CSWSH primitive. Lock it
     * down to the deploy host(s); production deployments should set
     * {@code radiance.allowed-origins=https://chat.example.com} explicitly.
     * {@code SameSite=Strict} on the session cookie is a parallel defence — removing the
     * wildcard is the belt to its braces.
     *
     * <p>The default below covers solo local-dev on {@code localhost} / {@code 127.0.0.1}.
     * The {@code dev} profile (see {@code application-dev.properties}) extends the list
     * with the maintainer's LAN IP so phones / other devices on the LAN can reach the WS
     * endpoint during mobile-layout testing.
     */
    private final String[] allowedOrigins;

    public WebSocketConfig(@Value("${radiance.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
                           String allowedOriginsCsv) {
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Native WebSocket only. Don't add .withSockJS() — its iframe / htmlfile / jsonp-polling
        // transports inject inline <script>, which collides with the strict CSP (script-src 'self')
        // configured in SecurityConfig.
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }
}
