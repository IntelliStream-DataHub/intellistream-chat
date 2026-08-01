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

package ai.intellistream.chat.calls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retires calls that rang until nobody was going to answer.
 *
 * <p>Polls rather than scheduling a timer per call. A per-call timer is the obvious design and is
 * worse: it needs cancelling on every one of the five ways a call can end early, and a timer that
 * survives its call fires against an id that has been reused or gone, which is a class of bug worth
 * more than the two seconds of latency this costs. The poll has one code path and no cleanup.
 *
 * <p>The interval is deliberately much shorter than the timeout itself, so the ring stops close to
 * when it was promised to; the sweep is a walk over the handful of live calls, so its cost is
 * nothing at any interval.
 *
 * <p><b>Single-instance only</b>, like the other schedulers here: {@code @EnableScheduling} runs on
 * every node, and {@link CallRegistry} is per-process anyway, so with several nodes each would
 * sweep only the calls it knows about — which is coincidentally the right answer, but not one to
 * rely on before the rest of horizontal scaling lands.
 */
@Component
public class CallScheduler {

    private static final Logger log = LoggerFactory.getLogger(CallScheduler.class);

    private final CallService calls;

    public CallScheduler(CallService calls) {
        this.calls = calls;
    }

    @Scheduled(fixedDelayString = "${ichat.calls.sweep-ms:2000}")
    public void sweep() {
        try {
            calls.sweepTimeouts();
        } catch (RuntimeException e) {
            // A throw here would stop the scheduled task for the life of the process, and with it
            // every future ring timeout — one bad call would leave the next one ringing forever.
            log.warn("Call timeout sweep failed", e);
        }
    }
}
