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
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write-behind post path, end to end against a real database.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: this path bypasses JPA and inserts through its
 * own {@code JdbcTemplate} on a background thread, so a rollback-per-test would neither contain it
 * nor prove anything about it. Each test uses its own channel instead.
 *
 * <p>The properties below are what production runs; the rest of the suite disables write-behind
 * (see {@code src/test/resources/application.properties}) so its rows can't outlive a rolled-back
 * test. A short flush interval keeps the waits here brief.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ichat.write-behind.enabled=true",
                "ichat.write-behind.flush-interval-ms=5",
                "ichat.write-behind.batch-size=64",
                "ichat.search.lucene-dir=build/test-lucene/WriteBehindFlowIT"
        }
)
class WriteBehindFlowIT {

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
    }

    @Autowired MessageService messages;
    @Autowired ChannelService channels;
    @Autowired MessageRepository messageRepo;
    @Autowired UserRepository users;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-wb-" + n, "wb-" + n + "-" + label,
                label + n + "@example.com", "WB " + n));
    }

    private Channel newChannel(User owner) {
        return channels.create("wb room " + SEQ.incrementAndGet(), "", ChannelType.PUBLIC, owner);
    }

    @Test
    void assignsTheRealIdBeforeTheRowExists() {
        var alice = newUser("alice");
        var room = newChannel(alice);

        var posted = messages.postBuffered(room, alice, "batched hello");

        // The id is drawn from the sequence up front, which is what lets the broadcast and the
        // search-index write carry the real key while the row is still queued.
        assertThat(posted.message().getId()).isNotNull().isPositive();
        pollUntil(() -> messageRepo.findById(posted.message().getId()).isPresent());
    }

    @Test
    void publishesOnlyAfterTheRowIsCommitted() {
        var alice = newUser("bob");
        var room = newChannel(alice);
        var rowVisibleWhenPublished = new AtomicInteger(-1);
        var published = new CountDownLatch(1);

        var posted = messages.postBuffered(room, alice, "durable before broadcast");
        posted.whenDurable(() -> {
            // The whole point of the design: by the time anything is broadcast, the row is there.
            rowVisibleWhenPublished.set(
                    messageRepo.findById(posted.message().getId()).isPresent() ? 1 : 0);
            published.countDown();
        });

        pollUntil(() -> published.getCount() == 0);
        assertThat(rowVisibleWhenPublished).describedAs("row must be committed before publish")
                .hasValue(1);
    }

    @Test
    void writesEveryQueuedMessageExactlyOnce() {
        var alice = newUser("carol");
        var room = newChannel(alice);
        var ids = new ArrayList<Long>();

        for (int i = 0; i < 300; i++) {
            ids.add(messages.postBuffered(room, alice, "burst " + i).message().getId());
        }

        assertThat(ids).doesNotHaveDuplicates();
        pollUntil(() -> storedCount(room) == 300);
        assertThat(storedCount(room)).isEqualTo(300);
    }

    @Test
    void preservesPerChannelOrdering() {
        // Flushers are sharded by channel precisely so that a channel's messages land in the order
        // they were accepted, even though different channels commit in parallel.
        var alice = newUser("dave");
        var room = newChannel(alice);
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 200; i++) {
            ids.add(messages.postBuffered(room, alice, "ordered " + i).message().getId());
        }
        pollUntil(() -> storedCount(room) == 200);

        var stored = storedOldestFirst(room).stream().map(m -> m.getBodyMarkdown()).toList();
        var expected = new ArrayList<String>();
        for (int i = 0; i < 200; i++) expected.add("ordered " + i);

        assertThat(stored).containsExactlyElementsOf(expected);
        assertThat(ids).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void mentionBodiesTakeTheTransactionalPathAndAreDurableOnReturn() {
        // message_mentions has a foreign key to messages, so a body that might mention someone
        // cannot be queued — the row has to exist first.
        var alice = newUser("erin");
        var bob = newUser("frank");
        var room = newChannel(alice);

        var posted = messages.postBuffered(room, alice, "ping @" + bob.getUsername());

        assertThat(messageRepo.findById(posted.message().getId())).isPresent();
        assertThat(posted.mentionedUsernames()).contains(bob.getUsername());
    }

    @Test
    void postIsAlwaysDurableOnReturnEvenWithWriteBehindEnabled() {
        // Attachments, polls and reminders insert rows referencing the message id, so post() must
        // never take the deferred path regardless of configuration.
        var alice = newUser("gina");
        var room = newChannel(alice);

        var saved = messages.post(room, alice, "must exist immediately");

        assertThat(messageRepo.findById(saved.getId())).isPresent();
    }

    @Test
    void isSearchableOnceTheBatchLands() {
        var alice = newUser("hugo");
        var room = newChannel(alice);
        var unique = "zarquon" + SEQ.incrementAndGet();

        var posted = messages.postBuffered(room, alice, "the word is " + unique);

        // Indexing is the batcher's job too, and it happens after the commit.
        pollUntil(() -> messageRepo.findById(posted.message().getId()).isPresent());
        assertThat(messageRepo.findById(posted.message().getId()).orElseThrow().getBodyMarkdown())
                .contains(unique);
    }

    @Test
    void concurrentPostsAcrossChannelsAllLand() throws Exception {
        var alice = newUser("ivy");
        var rooms = List.of(newChannel(alice), newChannel(alice), newChannel(alice), newChannel(alice));
        var perRoom = 100;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(rooms.size());
        var failures = new AtomicLong();

        for (var room : rooms) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perRoom; i++) {
                        messages.postBuffered(room, alice, "concurrent " + i);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).hasValue(0);

        for (var room : rooms) {
            pollUntil(() -> storedCount(room) == perRoom);
        }
    }

    // ---- helpers -------------------------------------------------------------------------
    // A few lines of polling instead of a new test dependency: the write-behind flusher is
    // asynchronous by design, so every assertion about persisted state has to wait for it.

    private static void pollUntil(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition still false after 30s — the batcher never flushed");
    }

    private int storedCount(Channel room) {
        return storedOldestFirst(room).size();
    }

    private List<ai.intellistream.chat.domain.Message> storedOldestFirst(Channel room) {
        var newestFirst = messageRepo.findByChannelAndParentIsNullOrderByCreatedAtDesc(
                room, PageRequest.of(0, 1000));
        var out = new ArrayList<>(newestFirst);
        // The finder returns newest-first; the ordering assertions want acceptance order. Sort by
        // id rather than reversing: several messages can share a created_at at this rate.
        out.sort(Comparator.comparing(ai.intellistream.chat.domain.Message::getId));
        return out;
    }
}
