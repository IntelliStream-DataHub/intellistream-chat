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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.ReadStateService;
import ai.intellistream.chat.service.SidebarService;
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

    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;
    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired ReadStateService readState;
    @Autowired SidebarService sidebar;
    @Autowired ai.intellistream.chat.repository.ChannelMemberRepository members;

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

    // ------------------------------------------------------------------------------------------
    // Unread + who gets told. A reply used to be invisible to every unread signal the application
    // has, which is how a threaded conversation dies without anyone deciding to end it.
    // ------------------------------------------------------------------------------------------

    @Test
    void threadRepliesCountTowardChannelUnread() {
        var alice = users.save(new User("kc-a-unr", "a-unr", "a@x", "Alice"));
        var bob = users.save(new User("kc-b-unr", "b-unr", "b@x", "Bob"));
        var room = channels.create("Unread Threads", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        var parent = messages.post(room, alice, "Anyone looked at the flaky test?");
        readState.markRead(room, bob);
        em.flush();

        // Two replies and nothing top-level: under the old parent_id-is-null filter this was zero.
        messages.replyInThread(parent.getId(), alice, "I have a theory.");
        messages.replyInThread(parent.getId(), alice, "Confirmed - it is the clock.");
        em.flush();

        assertThat(readState.unreadCounts(bob, java.util.List.of(room.getId())))
                .containsEntry(room.getId(), 2L);
        // Ordinary unread, not a mention: bold name, no number. Nobody said Bob's name.
        assertThat(readState.mentionCounts(bob, java.util.List.of(room.getId()))).isEmpty();
        var row = sidebar.joinedFor(users.findById(bob.getId()).orElseThrow()).channels().stream()
                .filter(c -> c.id().equals(room.getId())).findFirst().orElseThrow();
        assertThat(row.unreadCue(ai.intellistream.chat.domain.NotificationLevel.MENTIONS))
                .isEqualTo(ai.intellistream.chat.web.dto.ChannelSidebarDto.UnreadCue.BOLD);
    }

    @Test
    void yourOwnReplyIsNotUnreadForYou() {
        var alice = users.save(new User("kc-a-own", "a-own", "a@x", "Alice"));
        var room = channels.create("Own Replies", null, ChannelType.PUBLIC, alice);
        var parent = messages.post(room, alice, "note to self");
        readState.markRead(room, alice);
        em.flush();

        messages.replyInThread(parent.getId(), alice, "and another thing");
        em.flush();

        assertThat(readState.unreadCounts(alice, java.util.List.of(room.getId()))).isEmpty();
    }

    @Test
    void threadParticipantsAreTheParentAuthorPlusEveryReplier() {
        var alice = users.save(new User("kc-a-part", "a-part", "a@x", "Alice"));
        var bob = users.save(new User("kc-b-part", "b-part", "b@x", "Bob"));
        var carol = users.save(new User("kc-c-part", "c-part", "c@x", "Carol"));
        var bystander = users.save(new User("kc-d-part", "d-part", "d@x", "Dave"));
        var room = channels.create("Participants", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.join(room, carol);
        channels.join(room, bystander);

        var parent = messages.post(room, alice, "Ideas for the offsite?");
        messages.replyInThread(parent.getId(), bob, "Climbing.");
        messages.replyInThread(parent.getId(), bob, "Or bowling.");
        em.flush();

        // Carol replies: Alice (parent author) and Bob (replier, once - not twice) get told. Dave
        // is in the channel and not in the thread, so he does not.
        assertThat(messages.threadParticipants(parent, carol))
                .containsExactlyInAnyOrder("a-part", "b-part");
        // And the replier is never told about their own reply.
        assertThat(messages.threadParticipants(parent, bob)).containsExactly("a-part");
        assertThat(messages.threadParticipants(parent, alice)).containsExactly("b-part");
    }

    @Test
    void aParticipantWhoIsNoLongerAMemberIsNotNotified() {
        var alice = users.save(new User("kc-a-gone", "a-gone", "a@x", "Alice"));
        var bob = users.save(new User("kc-b-gone", "b-gone", "b@x", "Bob"));
        var carol = users.save(new User("kc-c-gone", "c-gone", "c@x", "Carol"));
        var room = channels.create("Departures", null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);
        channels.invite(room, carol, alice);

        var parent = messages.post(room, bob, "Rolling out on Thursday.");
        messages.replyInThread(parent.getId(), carol, "Ack.");
        em.flush();

        assertThat(messages.threadParticipants(parent, alice))
                .containsExactlyInAnyOrder("b-gone", "c-gone");

        // Bob's membership goes away. His message stays and he is still, historically, in the
        // thread - but the participant list is an audience, and he is not in it any more. This
        // matters because the list is broadcast to the channel topic: anything in it is a name a
        // client will act on.
        members.delete(members.findByChannelAndUser(room, bob).orElseThrow());
        em.flush();

        assertThat(messages.threadParticipants(parent, alice)).containsExactly("c-gone");
    }
}
