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

package ai.intellistream.radiance.integration;

import ai.intellistream.radiance.domain.Channel;
import ai.intellistream.radiance.domain.ChannelType;
import ai.intellistream.radiance.domain.Message;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks in the search-result permalink contract: jumping to message N in a 100k-message
 * channel renders 25 messages of context on either side, in chronological order, regardless
 * of where the anchor sits in the timeline.
 *
 * <p>The endpoint exists for the controller-level path too (validated implicitly via the
 * existing {@code ChannelFlowIT} smoke), but the meat is in {@link MessageService#around}.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MessageAroundIT {

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

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-around-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }

    /**
     * Post {@code count} messages in {@code channel} as {@code author}; small sleep keeps
     * the {@code created_at} timestamps strictly ordered (Postgres timestamptz has microsecond
     * resolution but the JVM's {@code Instant.now()} is usually finer-grained — without the
     * sleep two adjacent posts can land in the same microsecond on fast machines, breaking
     * the createdAt ordering this test relies on).
     */
    private List<Message> postSequence(Channel channel, User author, int count) {
        var out = new java.util.ArrayList<Message>(count);
        for (int i = 0; i < count; i++) {
            out.add(messages.post(channel, author, "msg-" + i + "-" + UUID.randomUUID()));
            try { Thread.sleep(2); } catch (InterruptedException ignored) {}
        }
        return out;
    }

    @Test
    void anchorInTheMiddleReturns25BeforeAndAfter() {
        var alice = newUser("alice");
        var room = channels.create("Around-mid-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        // 100 messages — pick #40 as the anchor so 25 before + 25 after both fit comfortably.
        var posted = postSequence(room, alice, 100);
        var anchor = posted.get(40);

        var ctx = messages.around(room, alice, anchor.getId(), 25);

        assertThat(ctx).hasSize(25 + 1 + 25); // before + anchor + after
        // Sorted ascending by createdAt.
        for (int i = 1; i < ctx.size(); i++) {
            assertThat(ctx.get(i).getCreatedAt()).isAfterOrEqualTo(ctx.get(i - 1).getCreatedAt());
        }
        // Anchor sits at position 25 (zero-indexed).
        assertThat(ctx.get(25).getId()).isEqualTo(anchor.getId());
        // Bracketing messages are the 25 before + 25 after.
        assertThat(ctx.get(0).getId()).isEqualTo(posted.get(15).getId());
        assertThat(ctx.get(50).getId()).isEqualTo(posted.get(65).getId());
    }

    @Test
    void anchorAtChannelStartReturnsAnchorPlusUpTo25After() {
        var alice = newUser("alice");
        var room = channels.create("Around-start-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        var posted = postSequence(room, alice, 30);
        var anchor = posted.get(0); // very first message

        var ctx = messages.around(room, alice, anchor.getId(), 25);

        // Nothing before the first message; full 25 after.
        assertThat(ctx).hasSize(1 + 25);
        assertThat(ctx.get(0).getId()).isEqualTo(anchor.getId());
        assertThat(ctx.get(25).getId()).isEqualTo(posted.get(25).getId());
    }

    @Test
    void anchorAtChannelEndReturnsUpTo25BeforePlusAnchor() {
        var alice = newUser("alice");
        var room = channels.create("Around-end-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        var posted = postSequence(room, alice, 30);
        var anchor = posted.get(29); // very last

        var ctx = messages.around(room, alice, anchor.getId(), 25);

        assertThat(ctx).hasSize(25 + 1);
        assertThat(ctx.get(ctx.size() - 1).getId()).isEqualTo(anchor.getId());
        // First context message is the 25th-most-recent (posted index 4).
        assertThat(ctx.get(0).getId()).isEqualTo(posted.get(4).getId());
    }

    @Test
    void anchorInTinyChannelReturnsAllMessagesAndStillCentersAnchor() {
        var alice = newUser("alice");
        var room = channels.create("Around-tiny-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        var posted = postSequence(room, alice, 5);
        var anchor = posted.get(2);

        var ctx = messages.around(room, alice, anchor.getId(), 25);

        // All 5 messages, ascending, anchor still at its natural index.
        assertThat(ctx).hasSize(5);
        assertThat(ctx.get(0).getId()).isEqualTo(posted.get(0).getId());
        assertThat(ctx.get(2).getId()).isEqualTo(anchor.getId());
        assertThat(ctx.get(4).getId()).isEqualTo(posted.get(4).getId());
    }

    @Test
    void radiusIsClampedToServerPageSize() {
        var alice = newUser("alice");
        var room = channels.create("Around-clamp-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        var posted = postSequence(room, alice, 200);
        var anchor = posted.get(100);

        // Caller asks for 1000 either side — service caps at DEFAULT_PAGE_SIZE (50).
        var ctx = messages.around(room, alice, anchor.getId(), 1000);
        assertThat(ctx).hasSize(50 + 1 + 50);
    }

    @Test
    void anchorInDifferentChannelIsRejected() {
        var alice = newUser("alice");
        var roomA = channels.create("A-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var roomB = channels.create("B-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var msgInA = messages.post(roomA, alice, "lives in A");

        // Caller asks "load context for msgInA in roomB" — defensively rejected as 404 so
        // probing for cross-channel message ids is a no-op rather than a leak.
        assertThatThrownBy(() -> messages.around(roomB, alice, msgInA.getId(), 25))
                .isInstanceOf(ai.intellistream.radiance.security.ResourceNotFoundException.class);
    }

    @Test
    void anchorThatIsAThreadReplyIsRejected() {
        var alice = newUser("alice");
        var room = channels.create("Around-thread-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        var parent = messages.post(room, alice, "top-level");
        var reply = messages.replyInThread(parent.getId(), alice, "in a thread");

        assertThatThrownBy(() -> messages.around(room, alice, reply.getId(), 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thread reply");
    }

    @Test
    void unknownAnchorIdIsRejected() {
        var alice = newUser("alice");
        var room = channels.create("Around-404-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);

        assertThatThrownBy(() -> messages.around(room, alice, UUID.randomUUID(), 25))
                .isInstanceOf(ai.intellistream.radiance.security.ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void afterReturnsForwardPageOldestFirst() {
        var alice = newUser("alice");
        var room = channels.create("After-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        var posted = postSequence(room, alice, 60);

        var rows = messages.after(room, alice, posted.get(10).getCreatedAt(), 50);

        assertThat(rows).hasSize(49); // posted 11..59 inclusive
        assertThat(rows.get(0).getId()).isEqualTo(posted.get(11).getId());
        assertThat(rows.get(rows.size() - 1).getId()).isEqualTo(posted.get(59).getId());
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).getCreatedAt()).isAfterOrEqualTo(rows.get(i - 1).getCreatedAt());
        }
    }

    @Test
    void afterAtChannelTailReturnsEmpty() {
        var alice = newUser("alice");
        var room = channels.create("After-tail-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        var posted = postSequence(room, alice, 5);

        var rows = messages.after(room, alice, posted.get(4).getCreatedAt(), 50);

        // Nothing newer than the latest message — the down-scroll observer uses this to know
        // it's caught up and disconnects.
        assertThat(rows).isEmpty();
    }

    @Test
    void aroundExcludesThreadRepliesFromContext() {
        var alice = newUser("alice");
        var room = channels.create("Around-mix-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        // Post 10 top-level messages and stick some thread replies on a few of them; the
        // around() result should only contain top-level messages, matching what the timeline
        // actually shows.
        var posted = postSequence(room, alice, 10);
        for (var p : posted) {
            messages.replyInThread(p.getId(), alice, "reply on " + p.getId());
        }
        var anchor = posted.get(5);

        var ctx = messages.around(room, alice, anchor.getId(), 25);
        // Only the 10 top-level messages — 5 before + anchor + 4 after.
        assertThat(ctx).hasSize(10);
        assertThat(ctx).allSatisfy(m -> assertThat(m.isThreadReply()).isFalse());
    }
}
