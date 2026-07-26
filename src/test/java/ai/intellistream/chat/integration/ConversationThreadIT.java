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
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ConversationAttachmentService;
import ai.intellistream.chat.service.ConversationReactionService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.ConversationRestController;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
import ai.intellistream.chat.web.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Threads in direct and group conversations — the DM half of what {@code ThreadFlowIT} asserts for
 * channels, plus the cases a channel does not have: a conversation with one member, and a reply
 * reached by naming a message id from a conversation you are not in.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ConversationThreadIT {

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
    @Autowired UserService userService;
    @Autowired ConversationService conversations;
    @Autowired ConversationAttachmentService convAttachments;
    @Autowired ConversationReactionService convReactions;
    @Autowired ai.intellistream.chat.moderation.StorageQuotaService quotas;
    @Autowired MarkdownRenderer markdown;

    private CurrentUser currentUser;
    private ConversationRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        controller = new ConversationRestController(conversations, userService, currentUser,
                markdown, convAttachments, convReactions, mock(SimpMessagingTemplate.class),
                new RateLimiter(), quotas,
                mock(ai.intellistream.chat.web.ConversationAlertPublisher.class));
    }

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-thr-" + n + "-" + label, label + "-" + n,
                label + n + "@example.com", label + " " + n));
    }

    private void asUser(User u) {
        when(currentUser.resolve(any(Principal.class))).thenReturn(u);
    }

    // ---------- The feed and the thread are different lists ----------

    @Test
    void aReplyStaysOutOfTheFeedAndCountsOnItsParent() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "shall we ship it?");

        asUser(bob);
        var reply = controller.reply(parent.getId(), new SendMessageRequest("after the migration", null),
                mock(Principal.class));

        assertThat(reply.parentId()).isEqualTo(parent.getId());
        // The whole point: the reply is not in the conversation feed. A thread that pushed the room
        // it belongs to off its own page would be worse than no thread at all.
        var feed = controller.messages(conv.getId(), null, mock(Principal.class));
        assertThat(feed).extracting(ConversationMessageDto::id).containsExactly(parent.getId());
        assertThat(feed.get(0).replyCount()).isEqualTo(1);
    }

    @Test
    void reconnectBackfillDoesNotReplayReplies() {
        // ?after= drives the DM reconnect catch-up. It pages the feed, so a reply must not appear
        // there either — otherwise every reconnect would drop thread replies into the room.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "anchor");
        asUser(bob);
        controller.reply(parent.getId(), new SendMessageRequest("in the thread", null), mock(Principal.class));
        var later = conversations.post(conv, bob, "in the room");

        var caught = controller.messages(conv.getId(), java.time.Instant.EPOCH, mock(Principal.class));
        assertThat(caught).extracting(ConversationMessageDto::id)
                .containsExactly(parent.getId(), later.getId());
    }

    @Test
    void threadEndpointReturnsParentThenRepliesOldestFirst() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "one question");
        asUser(bob);
        var first = controller.reply(parent.getId(), new SendMessageRequest("one", null), mock(Principal.class));
        var second = controller.reply(parent.getId(), new SendMessageRequest("two", null), mock(Principal.class));

        asUser(alice);
        var thread = controller.thread(parent.getId(), mock(Principal.class));
        assertThat(thread.parent().id()).isEqualTo(parent.getId());
        assertThat(thread.parent().replyCount()).isEqualTo(2);
        assertThat(thread.replies()).extracting(ConversationMessageDto::id)
                .containsExactly(first.id(), second.id());
    }

    // ---------- One level deep ----------

    @Test
    void aReplyCannotBeRepliedTo() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "root");
        asUser(bob);
        var reply = controller.reply(parent.getId(), new SendMessageRequest("branch", null), mock(Principal.class));

        assertThatThrownBy(() -> controller.reply(reply.id(),
                new SendMessageRequest("twig", null), mock(Principal.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Access ----------

    @Test
    void nonMemberCanNeitherReadNorReplyToAThread() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "private");
        asUser(bob);
        controller.reply(parent.getId(), new SendMessageRequest("also private", null), mock(Principal.class));

        asUser(carol);
        assertThatThrownBy(() -> controller.thread(parent.getId(), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
        // The membership check is against the *parent's* conversation, not one the caller names —
        // otherwise a member of any conversation could reply into any other by quoting its ids.
        assertThatThrownBy(() -> controller.reply(parent.getId(),
                new SendMessageRequest("gatecrash", null), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void emptyReplyRefused() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "root");
        asUser(bob);
        assertThatThrownBy(() -> controller.reply(parent.getId(),
                new SendMessageRequest("   ", null), mock(Principal.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Deleting ----------

    @Test
    void deletingTheParentTakesItsThreadWithIt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "doomed");
        asUser(bob);
        var reply = controller.reply(parent.getId(), new SendMessageRequest("also doomed", null),
                mock(Principal.class));

        asUser(alice);
        controller.deleteMessage(parent.getId(), mock(Principal.class));

        assertThat(controller.messages(conv.getId(), null, mock(Principal.class))).isEmpty();
        // The reply is gone too — not orphaned into a thread nothing can open.
        assertThatThrownBy(() -> controller.thread(reply.id(), mock(Principal.class)))
                .isInstanceOf(ai.intellistream.chat.security.ResourceNotFoundException.class);
    }

    @Test
    void deletingOneReplyLeavesTheRestOfTheThread() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, alice, "root");
        asUser(bob);
        var doomed = controller.reply(parent.getId(), new SendMessageRequest("mistake", null), mock(Principal.class));
        var kept = controller.reply(parent.getId(), new SendMessageRequest("the real one", null), mock(Principal.class));

        controller.deleteMessage(doomed.id(), mock(Principal.class));

        var thread = controller.thread(parent.getId(), mock(Principal.class));
        assertThat(thread.replies()).extracting(ConversationMessageDto::id).containsExactly(kept.id());
        assertThat(thread.parent().replyCount()).isEqualTo(1);
    }

    // ---------- Group ----------

    @Test
    void everyGroupMemberSeesTheThreadAndItsParticipants() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.createGroup("Release", alice, List.of(bob, carol));
        var parent = conversations.post(conv, alice, "ship it?");

        asUser(bob);
        controller.reply(parent.getId(), new SendMessageRequest("yes", null), mock(Principal.class));

        // Participants are derived from who has written in the thread, minus the person replying —
        // telling somebody about their own message is the one guaranteed-useless notification.
        asUser(carol);
        var mine = controller.reply(parent.getId(), new SendMessageRequest("agreed", null), mock(Principal.class));
        assertThat(mine.threadParticipants())
                .containsExactlyInAnyOrder(alice.getUsername(), bob.getUsername());

        var thread = controller.thread(parent.getId(), mock(Principal.class));
        assertThat(thread.replies()).hasSize(2);
    }

    // ---------- A conversation with one member ----------

    @Test
    void aSelfConversationHasThreadsToo() {
        // A DM with yourself is a real conversation — /remind me delivers into it — and everything
        // added here has to work in one. The single member is both author and audience, which is
        // exactly the shape that breaks code assuming a DIRECT conversation has two people.
        var solo = newUser("solo");
        var conv = conversations.directBetween(solo, solo);
        assertThat(conversations.members(conv)).hasSize(1);
        var note = conversations.post(conv, solo, "look into the flaky test");

        asUser(solo);
        var reply = controller.reply(note.getId(), new SendMessageRequest("it is the clock", null),
                mock(Principal.class));

        assertThat(reply.parentId()).isEqualTo(note.getId());
        // No participants: the only person who has written in the thread is the one replying.
        assertThat(reply.threadParticipants()).isEmpty();

        var feed = controller.messages(conv.getId(), null, mock(Principal.class));
        assertThat(feed).extracting(ConversationMessageDto::id).containsExactly(note.getId());
        assertThat(feed.get(0).replyCount()).isEqualTo(1);

        var thread = controller.thread(note.getId(), mock(Principal.class));
        assertThat(thread.replies()).extracting(ConversationMessageDto::id).containsExactly(reply.id());
    }
}
