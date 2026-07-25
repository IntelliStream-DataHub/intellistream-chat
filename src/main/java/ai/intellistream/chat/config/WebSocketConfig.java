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

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(WebSocketConfig.class);

    /**
     * Origins permitted to open the {@code /ws} STOMP endpoint. The previous {@code "*"}
     * let any malicious site (visited by a logged-in user) negotiate a WebSocket against
     * the chat server — combined with cookie-based auth, that's a CSWSH primitive. Lock it
     * down to the deploy host(s); production deployments should set
     * {@code ichat.allowed-origins=https://chat.example.com} explicitly.
     * {@code SameSite=Strict} on the session cookie is a parallel defence — removing the
     * wildcard is the belt to its braces.
     *
     * <p>The default below covers solo local-dev on {@code localhost} / {@code 127.0.0.1}.
     * The {@code dev} profile (see {@code application-dev.properties}) extends the list
     * with the maintainer's LAN IP so phones / other devices on the LAN can reach the WS
     * endpoint during mobile-layout testing.
     */
    private final String[] allowedOrigins;
    private final int inboundThreads;
    private final int inboundQueue;
    private final int outboundThreads;
    private final int outboundQueue;
    private final int binaryBufferBytes;
    private final int socketBufferBytes;

    public WebSocketConfig(@Value("${ichat.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
                           String allowedOriginsCsv,
                           @Value("${ichat.ws.inbound-threads:0}") int inboundThreads,
                           @Value("${ichat.ws.inbound-queue:100000}") int inboundQueue,
                           @Value("${ichat.ws.outbound-threads:0}") int outboundThreads,
                           @Value("${ichat.ws.outbound-queue:200000}") int outboundQueue,
                           @Value("${ichat.ws.binary-buffer-bytes:8192}") int binaryBufferBytes,
                           @Value("${ichat.ws.socket-buffer-bytes:8192}") int socketBufferBytes) {
        this.binaryBufferBytes = binaryBufferBytes;
        this.socketBufferBytes = socketBufferBytes;
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        // Constructor-injected, not @Value fields: the channel-configurer callbacks can run
        // before field injection on a @Configuration class that also declares @Bean methods,
        // and a zero read there silently drops the pool sizing.
        int cores = Runtime.getRuntime().availableProcessors();
        this.inboundThreads = inboundThreads > 0 ? inboundThreads : cores * 4;
        this.inboundQueue = inboundQueue;
        this.outboundThreads = outboundThreads > 0 ? outboundThreads : cores * 4;
        this.outboundQueue = outboundQueue;
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
     *
     * <p><b>Always set an explicit executor.</b> {@code registration.executor(...)} takes
     * precedence over every other resolution path in {@code AbstractMessageBrokerConfiguration};
     * the previous {@code registration.taskExecutor().corePoolSize(...)} form was conditional on a
     * field-injected property and, when it didn't apply, message handling ended up running on the
     * single-threaded heartbeat scheduler — every inbound chat message serialized onto one thread,
     * which capped the whole write path at ~1 message in flight (~110 posts/s) regardless of how
     * fast the DB or renderer were. The pools are sized from properties so a load test can retune
     * them without a rebuild.
     *
     * <p>The post path is blocking and DB-bound, so the inbound pool wants to be roughly the size
     * of the connection pool it feeds — threads beyond that just queue inside Hikari.
     */
    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.executor(stompInboundExecutor());
    }

    @Override
    public void configureClientOutboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.executor(stompOutboundExecutor());
    }

    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor stompInboundExecutor() {
        return pool("stomp-inbound-", inboundThreads, inboundQueue);
    }

    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor stompOutboundExecutor() {
        return pool("stomp-outbound-", outboundThreads, outboundQueue);
    }

    private static org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool(
            String prefix, int threads, int queue) {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        // A full queue must not run the task on the caller — that would push handler work back
        // onto the WebSocket I/O thread and stall reads for every session it serves.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

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
     *
     * <p>Also exposes the per-session binary message buffer as a knob. It defaults to the
     * container's 8 KB: shrinking it <em>looks</em> like free memory, since this protocol is STOMP
     * over text frames and no binary message is ever sent, but an A/B against 2 KB could not
     * measure any difference in per-connection RSS — the effect is smaller than the run-to-run
     * variation from ZGC deciding when to commit heap. See the per-connection memory section of
     * {@code scalability.md}. The knob stays for a deployment that knows its message sizes and has
     * measured its own workload; the default does not claim a saving that isn't there.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(60_000L);
        container.setMaxBinaryMessageBufferSize(binaryBufferBytes);
        return container;
    }

    /**
     * Shrink Tomcat's per-socket application read/write buffers.
     *
     * <p>Tomcat allocates both for every connection, 8 KB each by default — sizing appropriate to
     * request/response HTTP traffic, not to a chat socket that sits open for hours exchanging
     * frames of a few hundred bytes, so on paper 10k connections hold ~160 MB of buffer the
     * workload never fills. In practice a controlled A/B could not detect the difference (see
     * {@code scalability.md}), so the default matches Tomcat's and this remains a tunable rather
     * than an optimisation.
     */
    @Bean
    public org.springframework.boot.web.server.WebServerFactoryCustomizer<
            org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> socketBufferCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            var handler = connector.getProtocolHandler();
            if (handler instanceof org.apache.coyote.AbstractProtocol<?> protocol) {
                // setProperty returns false when the name doesn't resolve to a real Tomcat
                // property — a silent no-op otherwise, and the whole point of this bean is the
                // memory it saves per connection. Log what actually took, the same way
                // StompChannelDiagnostics does for the channel executors.
                boolean read = protocol.setProperty("socket.appReadBufSize", String.valueOf(socketBufferBytes));
                boolean write = protocol.setProperty("socket.appWriteBufSize", String.valueOf(socketBufferBytes));
                if (read && write) {
                    LOG.info("Tomcat per-socket app buffers set to {} B read / {} B write; "
                            + "WebSocket binary message buffer {} B",
                            socketBufferBytes, socketBufferBytes, binaryBufferBytes);
                } else {
                    LOG.warn("Tomcat per-socket buffer sizing did NOT apply (read={}, write={}) —"
                            + " connections will use the {} B defaults", read, write, 8192);
                }
            } else {
                LOG.warn("Unexpected protocol handler {}; per-socket buffer sizing skipped",
                        handler.getClass().getName());
            }
        });
    }
}
