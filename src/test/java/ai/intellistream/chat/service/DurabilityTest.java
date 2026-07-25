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

package ai.intellistream.chat.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Durability} decides whether a message is ever shown to anyone, so the case that matters
 * most is the negative one: a write that failed must never run its broadcast.
 */
class DurabilityTest {

    @Test
    void runsTheActionOnceTheWriteCommits() {
        var handle = new Durability();
        var ran = new AtomicInteger();

        handle.whenDurable(ran::incrementAndGet);
        assertThat(ran).hasValue(0); // registered, but not durable yet — nothing may be published

        handle.committed();
        assertThat(ran).hasValue(1);
    }

    @Test
    void runsImmediatelyWhenTheWriteAlreadyCommitted() {
        // The batch can commit before the caller has finished rendering its payload; registering
        // late must still publish rather than silently drop the message.
        var handle = new Durability();
        handle.committed();
        var ran = new AtomicInteger();

        handle.whenDurable(ran::incrementAndGet);

        assertThat(ran).hasValue(1);
    }

    @Test
    void alreadyCommittedHandleRunsInline() {
        var ran = new AtomicInteger();
        Durability.alreadyCommitted().whenDurable(ran::incrementAndGet);
        assertThat(ran).hasValue(1);
    }

    @Test
    void neverRunsTheActionWhenTheWriteFailed() {
        var handle = new Durability();
        var ran = new AtomicInteger();
        handle.whenDurable(ran::incrementAndGet);

        handle.failed();

        assertThat(ran).hasValue(0);
    }

    @Test
    void neverRunsAnActionRegisteredAfterAFailure() {
        var handle = new Durability();
        handle.failed();
        var ran = new AtomicInteger();

        handle.whenDurable(ran::incrementAndGet);

        assertThat(ran).hasValue(0);
    }

    @Test
    void isOneShot() {
        var handle = new Durability();
        var ran = new AtomicInteger();
        handle.whenDurable(ran::incrementAndGet);

        handle.committed();
        handle.committed();  // a retry path calling it twice must not double-broadcast
        handle.failed();     // and a late failure must not undo or re-run anything

        assertThat(ran).hasValue(1);
    }

    @Test
    void aThrowingActionDoesNotPropagateToTheWriter() {
        // committed() runs on the flusher thread, which is committing other people's messages.
        // A broken broadcast must not take it down.
        var handle = new Durability();

        handle.whenDurable(() -> { throw new IllegalStateException("broker exploded"); });

        handle.committed(); // must not throw
    }

    @Test
    void registrationRacingTheCommitStillPublishesExactlyOnce() throws Exception {
        // The real race: the flusher commits on its own thread while the handler is still
        // registering. Either interleaving must publish, and neither may publish twice.
        for (int i = 0; i < 500; i++) {
            var handle = new Durability();
            var ran = new AtomicInteger();
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);

            Runnable register = () -> {
                await(start);
                handle.whenDurable(ran::incrementAndGet);
                done.countDown();
            };
            Runnable commit = () -> {
                await(start);
                handle.committed();
                done.countDown();
            };
            new Thread(register).start();
            new Thread(commit).start();
            start.countDown();

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ran).hasValue(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
