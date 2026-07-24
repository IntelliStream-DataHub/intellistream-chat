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

package ai.intellistream.threadorbit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Origins permitted to open the {@code /ws} STOMP endpoint. The previous {@code "*"}
     * let any malicious site (visited by a logged-in user) negotiate a WebSocket against
     * the chat server — combined with cookie-based auth, that's a CSWSH primitive. Lock it
     * down to the deploy host(s); production deployments should set
     * {@code threadorbit.allowed-origins=https://chat.example.com} explicitly.
     * {@code SameSite=Strict} on the session cookie is a parallel defence — removing the
     * wildcard is the belt to its braces.
     *
     * <p>The default below covers solo local-dev on {@code localhost} / {@code 127.0.0.1}.
     * The {@code dev} profile (see {@code application-dev.properties}) extends the list
     * with the maintainer's LAN IP so phones / other devices on the LAN can reach the WS
     * endpoint during mobile-layout testing.
     */
    private final String[] allowedOrigins;

    public WebSocketConfig(@Value("${threadorbit.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
                           String allowedOriginsCsv) {
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Heartbeats (10s each way) let the server detect a silently-dead client — a half-open
        // TCP after a laptop sleep or dropped wifi sends no FIN, so without this the STOMP session
        // never ends, SessionDisconnectEvent never fires, and the user shows "online" forever
        // (and PresenceTracker's cleanup never runs). Requires a TaskScheduler.
        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler());
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    /**
     * Inbound channel = threads that run @MessageMapping handlers (the per-message post work: DB
     * insert + Markdown render). Outbound channel = threads that deliver broadcasts to clients.
     * Spring's defaults are small (~2×cores); the post path is I/O-bound (DB), so more threads lift
     * throughput until CPU/DB saturates. Sized from properties so a load test can raise them
     * without a rebuild; the defaults are a modest prod bump. 0 = leave Spring's default untouched.
     */
    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        if (inboundThreads > 0) {
            registration.taskExecutor().corePoolSize(inboundThreads).maxPoolSize(inboundThreads)
                    .queueCapacity(inboundQueue);
        }
    }

    @Override
    public void configureClientOutboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        if (outboundThreads > 0) {
            registration.taskExecutor().corePoolSize(outboundThreads).maxPoolSize(outboundThreads)
                    .queueCapacity(outboundQueue);
        }
    }

    @org.springframework.beans.factory.annotation.Value("${threadorbit.ws.inbound-threads:0}")
    private int inboundThreads;
    @org.springframework.beans.factory.annotation.Value("${threadorbit.ws.inbound-queue:100000}")
    private int inboundQueue;
    @org.springframework.beans.factory.annotation.Value("${threadorbit.ws.outbound-threads:0}")
    private int outboundThreads;
    @org.springframework.beans.factory.annotation.Value("${threadorbit.ws.outbound-queue:200000}")
    private int outboundQueue;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Native WebSocket only. Don't add .withSockJS() — its iframe / htmlfile / jsonp-polling
        // transports inject inline <script>, which collides with the strict CSP (script-src 'self')
        // configured in SecurityConfig.
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    /** Drives the STOMP heartbeats configured above. */
    @Bean
    public ThreadPoolTaskScheduler heartbeatScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Container-level backstop to the STOMP heartbeats: force-close any WebSocket session idle
     * past 60s (heartbeats are traffic, so a live client never trips this — only a truly dead
     * one whose heartbeats have stopped). 60s > the 10s heartbeat interval by design.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(60_000L);
        return container;
    }
}
