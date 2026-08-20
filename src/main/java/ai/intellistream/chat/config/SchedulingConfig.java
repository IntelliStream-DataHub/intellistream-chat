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
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The thread pool behind every {@code @Scheduled} method.
 *
 * <p>Without this there is none. Boot's auto-configured {@code taskScheduler} backs off as soon as
 * the context holds any {@code TaskScheduler} bean, and this one holds two — Spring WebSocket's
 * {@code messageBrokerTaskScheduler} and {@link WebSocketConfig#heartbeatScheduler()} — neither
 * named {@code taskScheduler}, so {@code TaskSchedulerRouter} logs a warning at boot and falls back
 * to a <b>single-threaded</b> local executor. Every scheduled job then queues behind every other:
 * the Lucene↔Postgres reconciles, the retention purge, the reminder and call schedulers, and
 * {@code PresenceAwaySweeper}, whose whole reason to exist is publishing the going-idle edge within
 * seconds. A slow cleanup sweep was a late yellow dot for everyone.
 *
 * <p>This is a {@link SchedulingConfigurer} rather than a bean named {@code taskScheduler}, so it
 * binds explicitly to annotation scheduling and changes nothing about how the two existing
 * schedulers are looked up by type. The pool is deliberately small: the jobs are few, each is
 * serial with itself ({@code fixedDelay}), and the point is that they no longer share one thread —
 * not that they run wide.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    private final int poolSize;

    public SchedulingConfig(@Value("${ichat.scheduling.pool-size:4}") int poolSize) {
        this.poolSize = Math.max(1, poolSize);
    }

    @Bean
    public ThreadPoolTaskScheduler scheduledJobsScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("scheduled-");
        // Let a job in progress finish on shutdown rather than be interrupted mid-transaction.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setScheduler(scheduledJobsScheduler());
    }
}
