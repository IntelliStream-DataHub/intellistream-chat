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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DM read marker: what counts as unread, what moves the marker, and what a one-member
 * conversation does differently.
 *
 * <p>{@code ReadStateAndMentionsIT} is the channel sibling. The semantics asserted here are
 * deliberately the same ones — a message from somebody else, newer than your marker, including a
 * thread reply — rather than a set invented for DMs.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ConversationReadStateIT {

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
    @Autowired ConversationService conversations;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-read-" + n + "-" + label, label + "-" + n,
                label + n + "@example.com", label + " " + n));
    }

    private long unread(User viewer, Long convId) {
        return conversations.unreadCounts(viewer, List.of(convId)).getOrDefault(convId, 0L);
    }

    @Test
    void aPeersMessagesAreUnreadUntilTheMarkerMoves() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        conversations.post(conv, alice, "one");
        conversations.post(conv, alice, "two");
        assertThat(unread(bob, conv.getId())).isEqualTo(2);
        // Your own are never unread to you in a conversation with somebody else in it.
        assertThat(unread(alice, conv.getId())).isZero();

        conversations.markRead(conv, bob);
        assertThat(unread(bob, conv.getId())).isZero();

        conversations.post(conv, alice, "three");
        assertThat(unread(bob, conv.getId())).isEqualTo(1);
    }

    @Test
    void threadRepliesCountTowardUnread() {
        // The channel side started counting replies this session; a reply is a message in the room,
        // and filing it under another one does not make it something you have read. The DM query
        // counts every conversation_messages row, so it agrees for free — this pins that it does,
        // because the obvious "fix" of excluding replies from the feed query would be one edit away
        // from excluding them here too.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "question");
        conversations.markRead(conv, bob);
        assertThat(unread(bob, conv.getId())).isZero();

        conversations.replyInThread(parent.getId(), alice, "and an answer");
        assertThat(unread(bob, conv.getId())).isEqualTo(1);
    }

    @Test
    void theMarkerStartsUnsetAndIsReadableBeforeItMoves() {
        // The page-render path reads this *before* stamping it, because it is what the "new
        // messages" divider is drawn from and the stamp destroys it.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        assertThat(conversations.lastReadAt(conv, bob)).isNull();

        conversations.post(conv, alice, "hello");
        conversations.markRead(conv, bob);
        var marker = conversations.lastReadAt(conv, bob);
        assertThat(marker).isNotNull();

        // And it is the reader's own, not the conversation's.
        assertThat(conversations.lastReadAt(conv, alice)).isNull();
    }

    @Test
    void markReadIsANoOpForANonMember() {
        // It fires on live traffic and on every refocus, so a viewer who was removed from a group
        // while their tab was open must get silence rather than an error on a page that is about to
        // reload anyway.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.directBetween(alice, bob);

        conversations.markRead(conv, carol);
        assertThat(conversations.lastReadAt(conv, carol)).isNull();
    }

    @Test
    void inAConversationWithOneMemberYourOwnMessagesCount() {
        // A DM with yourself: every message is your own, so the ordinary "authored by somebody
        // else" rule would count nothing and the badge could never light — which is right for notes
        // you typed and wrong for the one thing that writes there without you, a fired /remind me.
        var solo = newUser("solo");
        var conv = conversations.directBetween(solo, solo);
        assertThat(conversations.members(conv)).hasSize(1);

        conversations.post(conv, solo, "look into the flaky test");
        assertThat(unread(solo, conv.getId())).isEqualTo(1);

        conversations.markRead(conv, conv.getCreatedBy());
        assertThat(unread(solo, conv.getId())).isZero();

        // Including a reply in a thread of your own notes.
        var note = conversations.post(conv, solo, "another");
        conversations.markRead(conv, solo);
        conversations.replyInThread(note.getId(), solo, "follow-up");
        assertThat(unread(solo, conv.getId())).isEqualTo(1);
    }

    @Test
    void aGroupCountsOnlyWhatOtherPeopleWrote() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.createGroup("Standup", alice, List.of(bob, carol));

        conversations.post(conv, alice, "morning");
        conversations.post(conv, bob, "morning");
        conversations.post(conv, carol, "morning");

        // Three members, three messages, and each of them wrote one of them.
        assertThat(unread(alice, conv.getId())).isEqualTo(2);
        assertThat(unread(bob, conv.getId())).isEqualTo(2);
        assertThat(unread(carol, conv.getId())).isEqualTo(2);
    }
}
