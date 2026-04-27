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

import ai.intellistream.radiance.domain.ChannelType;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Threaded replies (Slack/Mattermost-style):
 *   - Any channel member can reply in a thread to a top-level message.
 *   - The parent message stays in the main feed; replies live under it.
 *   - Replying to a reply is rejected — threads are flat (one level deep).
 *   - Non-members of a private channel cannot read or reply to threads.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class ThreadFlowIT {

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

    @Test
    void messageBecomesThread_withRepliesFromMultipleUsers() {
        var alice = users.save(new User("kc-alice-thread", "alice-thread", "a@x", "Alice"));
        var bob   = users.save(new User("kc-bob-thread",   "bob-thread",   "b@x", "Bob"));
        var clark = users.save(new User("kc-clark-thread", "clark-thread", "c@x", "Clark"));

        var general = channels.create("Threads General", null, ChannelType.PUBLIC, alice);
        channels.join(general, bob);
        channels.join(general, clark);

        var parent = messages.post(general, alice, "Anyone up for lunch?");
        assertThat(parent.isThreadReply()).isFalse();

        var bobReply = messages.replyInThread(parent.getId(), bob, "Yes — pizza?");
        var clarkReply = messages.replyInThread(parent.getId(), clark, "Sushi works for me.");
        var aliceReply = messages.replyInThread(parent.getId(), alice, "Pizza wins. 12:30.");

        assertThat(bobReply.isThreadReply()).isTrue();
        assertThat(bobReply.getParent().getId()).isEqualTo(parent.getId());

        // The thread reads in chronological order.
        var thread = messages.threadReplies(parent.getId(), bob);
        assertThat(thread).extracting(m -> m.getAuthor().getUsername())
                .containsExactly(bob.getUsername(), clark.getUsername(), alice.getUsername());
        assertThat(thread).extracting(m -> m.getBodyMarkdown())
                .containsExactly("Yes — pizza?", "Sushi works for me.", "Pizza wins. 12:30.");

        assertThat(messages.threadReplyCount(parent)).isEqualTo(3);

        // Replies are NOT included in the main channel feed (only top-level messages).
        var mainFeed = messages.recent(general, bob, 50);
        assertThat(mainFeed).extracting(m -> m.getId()).containsExactly(parent.getId());

        // Replying to a reply is rejected — threads stay flat.
        assertThatThrownBy(() -> messages.replyInThread(bobReply.getId(), alice, "nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void privateChannelThreads_rejectNonMembers() {
        var alice = users.save(new User("kc-alice-pthread", "alice-pthread", "a@x", "Alice"));
        var bob   = users.save(new User("kc-bob-pthread",   "bob-pthread",   "b@x", "Bob"));
        var clark = users.save(new User("kc-clark-pthread", "clark-pthread", "c@x", "Clark"));

        var room = channels.create("Private Thread Room", null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);

        var parent = messages.post(room, alice, "Heads up: launch is Friday.");
        messages.replyInThread(parent.getId(), bob, "Got it, prepping notes.");

        // Clark is not a member — cannot read or reply to the thread.
        assertThatThrownBy(() -> messages.threadReplies(parent.getId(), clark))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> messages.replyInThread(parent.getId(), clark, "leak"))
                .isInstanceOf(AccessDeniedException.class);

        // Once invited, Clark can both read the existing thread and add to it.
        channels.invite(room, clark, alice);
        var visible = messages.threadReplies(parent.getId(), clark);
        assertThat(visible).hasSize(1);
        var clarkReply = messages.replyInThread(parent.getId(), clark, "Joining the thread.");
        assertThat(clarkReply.getParent().getId()).isEqualTo(parent.getId());
        assertThat(messages.threadReplyCount(parent)).isEqualTo(2);
    }
}
