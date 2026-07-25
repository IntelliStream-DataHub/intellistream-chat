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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.stereotype.Component;

/**
 * Sizes the broker's destination cache to the number of channels actually in use.
 *
 * <p>Every broadcast asks {@code DefaultSubscriptionRegistry} which sessions are subscribed to a
 * destination. It answers from an LRU cache keyed by destination; on a miss it walks <em>every
 * session's every subscription</em> to recompute the answer. The cache holds
 * {@value DefaultSubscriptionRegistry#DEFAULT_CACHE_LIMIT} destinations by default, which is
 * generous for a workspace with a few dozen channels and actively harmful past that: once the
 * working set of channels exceeds the cache, nearly every message misses, and the cost of a
 * broadcast becomes proportional to total subscriptions rather than to the room's size.
 *
 * <p>It shows up as a wall you can't see from the message pipeline, because the pipeline isn't the
 * thing that's busy. Profiling a 100,000-connection run over 2,000 rooms put <b>47% of all server
 * CPU</b> inside this registry — 28% of it in {@code ConcurrentHashMap$Traverser.advance}, which is
 * the full scan — while both STOMP channel executors sat idle. Deliveries topped out around 22k/s
 * against 50k/s offered.
 *
 * <p>The cache entry per destination is small (a list of matching subscriptions), so sizing this to
 * comfortably exceed the number of channels a deployment expects to be busy at once is cheap
 * insurance. It is a plain LRU, so an over-large limit costs only the memory of the entries the
 * workload actually creates.
 */
@Component
public class BrokerSubscriptionCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(BrokerSubscriptionCacheConfig.class);

    private final org.springframework.beans.factory.ObjectProvider<AbstractBrokerMessageHandler> brokerMessageHandler;
    private final int cacheLimit;

    /**
     * Spring declares both a simple-broker and a STOMP-relay handler bean, and the one that isn't
     * in use is null — so this is qualified by name and resolved lazily rather than injected
     * directly, which lets a relay-based deployment start with this component simply doing nothing.
     */
    public BrokerSubscriptionCacheConfig(
            @org.springframework.beans.factory.annotation.Qualifier("simpleBrokerMessageHandler")
            org.springframework.beans.factory.ObjectProvider<AbstractBrokerMessageHandler> brokerMessageHandler,
            @Value("${ichat.ws.subscription-cache-limit:16384}") int cacheLimit) {
        this.brokerMessageHandler = brokerMessageHandler;
        this.cacheLimit = cacheLimit;
    }

    /**
     * Applied after startup rather than in a configurer, because the registry is created inside
     * Spring's own broker configuration and isn't exposed to {@code WebSocketMessageBrokerConfigurer}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void raiseDestinationCacheLimit() {
        if (!(brokerMessageHandler.getIfAvailable() instanceof SimpleBrokerMessageHandler simpleBroker)) {
            // An external STOMP relay does its own routing; there is no local registry to size.
            return;
        }
        if (!(simpleBroker.getSubscriptionRegistry() instanceof DefaultSubscriptionRegistry registry)) {
            log.info("Broker subscription registry is {}; leaving its cache alone",
                    simpleBroker.getSubscriptionRegistry().getClass().getSimpleName());
            return;
        }
        int previous = registry.getCacheLimit();
        registry.setCacheLimit(cacheLimit);
        log.info("Broker destination cache limit {} -> {} (a working set larger than this makes "
                + "every broadcast rescan all subscriptions)", previous, registry.getCacheLimit());
    }
}
