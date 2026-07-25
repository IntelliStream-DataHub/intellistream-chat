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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelReadRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.ReactionService;
import ai.intellistream.chat.service.ReadStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * N1 regression guard: the idempotent insert paths must survive a genuine concurrent race without
 * a 500. Before the fix these used {@code saveAndFlush} + {@code catch(DataIntegrityViolation)} +
 * re-read, which cannot recover on Postgres — the failed INSERT aborts the transaction, so the
 * loser's re-read threw. The fix routes each through a native {@code INSERT … ON CONFLICT}, which
 * blocks on the concurrent inserter and then no-ops, keeping the transaction usable.
 *
 * <p>Each thread invokes the {@code @Transactional} service method directly, so every call runs in
 * its own transaction — exactly the multi-request race the fix targets.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class InsertRaceIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired ReadStateService reads;
    @Autowired ReactionService reactions;
    @Autowired ChannelMemberRepository members;
    @Autowired ChannelReadRepository readRepo;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final int THREADS = 8;

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-race-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }

    /** Run {@code op} on {@value #THREADS} threads that all start together; fail on any exception. */
    private void raceAll(Callable<?> op) throws Exception {
        var barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return op.call();
                }));
            }
            for (var f : futures) {
                try {
                    assertThat(f.get()).isNotNull();
                } catch (java.util.concurrent.ExecutionException e) {
                    fail("A concurrent insert-race call failed instead of recovering", e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFirstJoinDoesNotThrowAndYieldsOneMembership() throws Exception {
        var creator = newUser("creator");
        var joiner = newUser("joiner");
        Channel room = channels.create("Race-join-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, creator);

        raceAll(() -> channels.join(room, joiner));

        assertThat(members.findByChannelAndUser(room, joiner)).isPresent();
        // Exactly one membership row for the joiner (plus the creator's ADMIN row = 2 total).
        assertThat(members.countByChannel(room)).isEqualTo(2);
    }

    @Test
    void concurrentFirstMarkReadDoesNotThrowAndYieldsOneRow() throws Exception {
        var creator = newUser("creator");
        Channel room = channels.create("Race-read-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, creator);

        raceAll(() -> reads.markRead(room, creator));

        assertThat(readRepo.findByChannelAndUser(room, creator)).isPresent();
    }

    @Test
    void concurrentSameReactionDoesNotThrow() throws Exception {
        var author = newUser("author");
        var reactor = newUser("reactor");
        Channel room = channels.create("Race-react-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, author);
        channels.join(room, reactor);
        var msgId = messages.post(room, author, "react to me").getId();

        raceAll(() -> reactions.addReaction(msgId, reactor, "👍"));
    }
}
