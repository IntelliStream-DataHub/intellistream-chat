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

package ai.intellistream.threadorbit.integration;

import ai.intellistream.threadorbit.domain.ChannelType;
import ai.intellistream.threadorbit.domain.User;
import ai.intellistream.threadorbit.repository.UserRepository;
import ai.intellistream.threadorbit.service.ChannelService;
import ai.intellistream.threadorbit.service.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the bug where Bob hits the channel page and the controller
 * builds {@code MessageDto} from messages. {@code Message.author} is lazy and
 * the service transaction has already committed, so calling
 * {@code message.getAuthor().getUsername()} during DTO mapping threw
 * {@code LazyInitializationException}.
 *
 * Mirrors the controller flow by running WITHOUT a class-level
 * {@code @Transactional}: setup commits, the service call returns, and the
 * test then touches {@code Message.author} OUTSIDE any open transaction.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MessageAuthorFetchIT {

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
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;

    @Test
    void recentMessages_authorAccessibleOutsideTransaction() {
        var alice = users.save(new User("kc-alice-msgfetch", "alice-msgfetch", "a@x", "Alice"));
        var bob   = users.save(new User("kc-bob-msgfetch",   "bob-msgfetch",   "b@x", "Bob"));

        var general = channels.create("Msg Fetch", null, ChannelType.PUBLIC, alice);
        channels.join(general, bob);

        messages.post(general, alice, "Hello team");
        messages.post(general, bob,   "Reporting in");

        // Mirror HomeController.channel(): fetch then map → calls m.getAuthor().getUsername().
        var rows = messages.recent(general, bob, 50);

        assertThat(rows).extracting(m -> m.getAuthor().getUsername())
                .containsExactly("alice-msgfetch", "bob-msgfetch");
        assertThat(rows).extracting(m -> m.getAuthor().getDisplayName())
                .containsExactly("Alice", "Bob");
    }

    @Test
    void pinnedMessages_authorAccessibleOutsideTransaction() {
        var alice = users.save(new User("kc-alice-pinfetch", "alice-pinfetch", "a@x", "Alice"));
        var bob   = users.save(new User("kc-bob-pinfetch",   "bob-pinfetch",   "b@x", "Bob"));

        var general = channels.create("Pin Fetch", null, ChannelType.PUBLIC, alice);
        channels.join(general, bob);
        var msg = messages.post(general, bob, "Pin me");
        messages.pin(msg.getId(), alice);

        var pinned = messages.pinned(general, bob);
        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).getAuthor().getUsername()).isEqualTo("bob-pinfetch");
    }

    @Test
    void threadReplies_authorAccessibleOutsideTransaction() {
        var alice = users.save(new User("kc-alice-thrfetch", "alice-thrfetch", "a@x", "Alice"));
        var bob   = users.save(new User("kc-bob-thrfetch",   "bob-thrfetch",   "b@x", "Bob"));

        var general = channels.create("Thread Fetch", null, ChannelType.PUBLIC, alice);
        channels.join(general, bob);
        var parent = messages.post(general, alice, "Lunch?");
        messages.replyInThread(parent.getId(), bob, "Pizza");

        var thread = messages.threadReplies(parent.getId(), bob);
        assertThat(thread).hasSize(1);
        assertThat(thread.get(0).getAuthor().getUsername()).isEqualTo("bob-thrfetch");
    }

    @Test
    void editedMessage_authorAccessibleOutsideTransaction() {
        var alice = users.save(new User("kc-alice-editfetch", "alice-editfetch", "a@x", "Alice"));
        var general = channels.create("Edit Fetch", null, ChannelType.PUBLIC, alice);
        var posted = messages.post(general, alice, "first version");

        // Mirror MessageRestController.edit(): service edits and returns Message,
        // controller then maps to MessageDto outside any transaction.
        var edited = messages.edit(posted.getId(), alice, "second version");

        assertThat(edited.getBodyMarkdown()).isEqualTo("second version");
        assertThat(edited.getAuthor().getUsername()).isEqualTo("alice-editfetch");
        assertThat(edited.getAuthor().getDisplayName()).isEqualTo("Alice");
    }
}
