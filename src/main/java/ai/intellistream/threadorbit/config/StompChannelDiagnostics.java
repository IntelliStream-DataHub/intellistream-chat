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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Logs the executor actually backing each STOMP channel once the context is up.
 *
 * <p>This exists because the resolution order for those executors is easy to get wrong and fails
 * silently: a mis-wired {@code configureClientInboundChannel} once left every inbound chat message
 * running on the single-threaded heartbeat scheduler, which capped the entire write path at one
 * message in flight. Nothing in the logs said so — throughput just sat at a number that looked
 * like a database limit. One line at startup makes the concurrency of the write path an observable
 * fact rather than an assumption.
 */
@Component
public class StompChannelDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(StompChannelDiagnostics.class);

    private final AbstractSubscribableChannel inbound;
    private final AbstractSubscribableChannel outbound;

    public StompChannelDiagnostics(@Qualifier("clientInboundChannel") AbstractSubscribableChannel inbound,
                                   @Qualifier("clientOutboundChannel") AbstractSubscribableChannel outbound) {
        this.inbound = inbound;
        this.outbound = outbound;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logChannelExecutors() {
        log.info("STOMP clientInboundChannel  -> {}", describe(inbound));
        log.info("STOMP clientOutboundChannel -> {}", describe(outbound));
    }

    private static String describe(AbstractSubscribableChannel channel) {
        if (!(channel instanceof ExecutorSubscribableChannel executorChannel)) {
            return channel.getClass().getName();
        }
        return describe(executorChannel.getExecutor());
    }

    private static String describe(Executor executor) {
        if (executor == null) {
            // No executor = the channel dispatches inline on the caller's thread.
            return "none (inline on the calling thread)";
        }
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            return "%s[prefix=%s, core=%d, max=%d, queueCapacity=%d]".formatted(
                    pool.getClass().getSimpleName(), pool.getThreadNamePrefix(),
                    pool.getCorePoolSize(), pool.getMaxPoolSize(), pool.getQueueCapacity());
        }
        return executor.getClass().getName();
    }
}
