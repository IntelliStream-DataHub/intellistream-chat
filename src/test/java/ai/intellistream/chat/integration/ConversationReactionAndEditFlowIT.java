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
import ai.intellistream.chat.web.dto.ConversationEvent;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
import ai.intellistream.chat.web.dto.EditMessageRequest;
import ai.intellistream.chat.web.dto.ReactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reactions, edit, and delete on direct/group conversation messages — covers the new
 * endpoints added on {@link ConversationRestController}: react add/remove (via author
 * peer), edit (author-only), delete (author-or-admin), and the {@link ConversationEvent}
 * envelopes emitted on {@code /topic/conversations/{id}}.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ConversationReactionAndEditFlowIT {

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
    @Autowired ai.intellistream.chat.moderation.StorageQuotaService quotas;
    @Autowired ConversationReactionService convReactions;
    @Autowired MarkdownRenderer markdown;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private ConversationRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        controller = new ConversationRestController(conversations, userService, currentUser,
                markdown, convAttachments, convReactions, broker, new RateLimiter(), quotas,
                mock(ai.intellistream.chat.web.ConversationAlertPublisher.class), linkPreviews());
    }

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-cre-" + n + "-" + label,
                label + "-" + n,
                label + n + "@example.com",
                label + " " + n));
    }

    private User newAdmin(String label) {
        var u = newUser(label);
        u.setAdmin(true);
        return users.save(u);
    }

    // ---------- Reactions ----------

    @Test
    void peerCanReactAndUnreact_brodcastingUpdatedDto() {
        var alice = newUser("alice-react");
        var bob   = newUser("bob-react");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "hello");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);

        var afterAdd = controller.addReaction(msg.getId(), new ReactionRequest("👍"), mock(Principal.class));
        assertThat(afterAdd.reactions()).hasSize(1);
        assertThat(afterAdd.reactions().get(0).emoji()).isEqualTo("👍");
        assertThat(afterAdd.reactions().get(0).count()).isEqualTo(1);

        controller.removeReaction(msg.getId(), "👍", mock(Principal.class));
        var afterRemoveList = controller.messages(conv.getId(), null, mock(Principal.class));
        assertThat(afterRemoveList).hasSize(1);
        assertThat(afterRemoveList.get(0).reactions()).isEmpty();

        // Both add and remove broadcast a message-updated envelope.
        var captor = ArgumentCaptor.forClass(ConversationEvent.class);
        verify(broker, org.mockito.Mockito.atLeast(2))
                .convertAndSend(eq("/topic/conversations/" + conv.getId()), captor.capture());
        assertThat(captor.getAllValues()).extracting(ConversationEvent::type)
                .contains("message-updated");
    }

    /**
     * Inverted along with its channel sibling in {@code ReactionFlowIT}. The rule it used to assert
     * — author may not react to their own message — was never Slack's or Mattermost's, and it read
     * worst of all in a DM, where half the messages are yours and the other person may not be
     * looking.
     */
    @Test
    void authorCanReactToOwnMessage() {
        var alice = newUser("alice-self-react");
        var bob   = newUser("bob-self-react");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "hi");

        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var after = controller.addReaction(msg.getId(), new ReactionRequest("✅"), mock(Principal.class));
        assertThat(after.reactions()).hasSize(1);
        assertThat(after.reactions().get(0).emoji()).isEqualTo("✅");
        assertThat(after.reactions().get(0).count()).isEqualTo(1);
        assertThat(after.reactions().get(0).mine()).isTrue();
    }

    @Test
    void nonMemberCannotReact() {
        var alice = newUser("alice-mem-react");
        var bob   = newUser("bob-mem-react");
        var carol = newUser("carol-mem-react");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "hi");

        when(currentUser.resolve(any(Principal.class))).thenReturn(carol);
        assertThatThrownBy(() -> controller.addReaction(msg.getId(),
                new ReactionRequest("👍"), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reactionsListIncludedOnMessagesEndpoint() {
        var alice = newUser("alice-list-react");
        var bob   = newUser("bob-list-react");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "vote");

        // Bob reacts twice with different emoji.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        controller.addReaction(msg.getId(), new ReactionRequest("👍"), mock(Principal.class));
        controller.addReaction(msg.getId(), new ReactionRequest("🎉"), mock(Principal.class));

        // Alice (the author) reads the list — she sees two distinct reactions, neither marked mine.
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var msgs = controller.messages(conv.getId(), null, mock(Principal.class));
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).reactions()).extracting(r -> r.emoji())
                .containsExactlyInAnyOrder("👍", "🎉");
        assertThat(msgs.get(0).reactions()).allMatch(r -> !r.mine());

        // From bob's POV both are his.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var bobView = controller.messages(conv.getId(), null, mock(Principal.class));
        assertThat(bobView.get(0).reactions()).allMatch(r -> r.mine());
    }

    // ---------- Reconnect backfill (?after=) ----------

    @Test
    void afterParamReturnsOnlyLaterMessagesOldestFirst() {
        // Backs the DM reconnect catch-up (N4/BUG-3): GET .../messages?after=<ts> returns the
        // messages missed during an outage, oldest-first.
        var alice = newUser("alice-after");
        var bob = newUser("bob-after");
        var conv = conversations.directBetween(alice, bob);
        var m1 = conversations.post(conv, alice, "one");
        var m2 = conversations.post(conv, alice, "two");
        var m3 = conversations.post(conv, bob, "three");

        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        // Epoch floor → the whole history, oldest-first (id tie-break keeps it deterministic).
        assertThat(controller.messages(conv.getId(), java.time.Instant.EPOCH, mock(Principal.class)))
                .extracting(d -> d.id()).containsExactly(m1.getId(), m2.getId(), m3.getId());
        // Nothing is strictly after the newest message.
        assertThat(controller.messages(conv.getId(), m3.getCreatedAt(), mock(Principal.class))).isEmpty();
        // after m1 excludes the anchor itself (strict >).
        assertThat(controller.messages(conv.getId(), m1.getCreatedAt(), mock(Principal.class)))
                .extracting(d -> d.id()).doesNotContain(m1.getId());
    }

    // ---------- Edit ----------

    @Test
    void authorCanEditOwnMessage() {
        var alice = newUser("alice-edit");
        var bob   = newUser("bob-edit");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "first draft");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var dto = controller.editMessage(msg.getId(), new EditMessageRequest("polished **draft**"), mock(Principal.class));

        assertThat(dto.bodyMarkdown()).isEqualTo("polished **draft**");
        assertThat(dto.bodyHtml()).contains("<strong>draft</strong>");
        assertThat(dto.editedAt()).isNotNull();

        var captor = ArgumentCaptor.forClass(ConversationEvent.class);
        verify(broker).convertAndSend(eq("/topic/conversations/" + conv.getId()), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("message-updated");
        assertThat(captor.getValue().message().bodyMarkdown()).isEqualTo("polished **draft**");
    }

    @Test
    void nonAuthorCannotEditEvenIfAdmin() {
        var alice = newUser("alice-edit-2");
        var bob   = newAdmin("bob-edit-2-admin");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "alice wrote this");

        // Bob is admin but admins do NOT get to edit other people's DM bodies.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        assertThatThrownBy(() -> controller.editMessage(msg.getId(),
                new EditMessageRequest("ghost-edit"), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void editEmptyBodyRejected() {
        var alice = newUser("alice-edit-empty");
        var bob   = newUser("bob-edit-empty");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "before");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        assertThatThrownBy(() -> controller.editMessage(msg.getId(),
                new EditMessageRequest("   "), mock(Principal.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Delete ----------

    @Test
    void authorCanDeleteOwnMessage() {
        var alice = newUser("alice-del");
        var bob   = newUser("bob-del");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "to-be-removed");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var resp = controller.deleteMessage(msg.getId(), mock(Principal.class));
        assertThat(resp.getStatusCode().value()).isEqualTo(204);

        var msgs = controller.messages(conv.getId(), null, mock(Principal.class));
        assertThat(msgs).isEmpty();

        var captor = ArgumentCaptor.forClass(ConversationEvent.class);
        verify(broker).convertAndSend(eq("/topic/conversations/" + conv.getId()), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("message-deleted");
        assertThat(captor.getValue().messageId()).isEqualTo(msg.getId());
    }

    @Test
    void adminCanDeletePeerMessageInGroup() {
        var alice = newUser("alice-del-admin");
        var bob   = newAdmin("bob-del-admin");
        var carol = newUser("carol-del-admin");
        var conv  = conversations.createGroup("Cabal", alice, java.util.List.of(bob, carol));
        var msg   = conversations.post(conv, alice, "ill-fitting message");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var resp = controller.deleteMessage(msg.getId(), mock(Principal.class));
        assertThat(resp.getStatusCode().value()).isEqualTo(204);

        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        assertThat(controller.messages(conv.getId(), null, mock(Principal.class))).isEmpty();
    }

    @Test
    void peerWithoutAdminCannotDeleteOtherUsersMessage() {
        var alice = newUser("alice-del-peer");
        var bob   = newUser("bob-del-peer");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "alice wrote this");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        assertThatThrownBy(() -> controller.deleteMessage(msg.getId(), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteByNonMemberRefused() {
        var alice = newUser("alice-del-out");
        var bob   = newUser("bob-del-out");
        var carol = newUser("carol-del-out");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "private to alice & bob");

        when(currentUser.resolve(any(Principal.class))).thenReturn(carol);
        assertThatThrownBy(() -> controller.deleteMessage(msg.getId(), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Edited messages also surface their reactions on the next read. */
    @Test
    void editingPreservesExistingReactions() {
        var alice = newUser("alice-edit-keep");
        var bob   = newUser("bob-edit-keep");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "hello");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        controller.addReaction(msg.getId(), new ReactionRequest("👍"), mock(Principal.class));

        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        controller.editMessage(msg.getId(), new EditMessageRequest("hello, world"), mock(Principal.class));

        var msgs = controller.messages(conv.getId(), null, mock(Principal.class));
        assertThat(msgs.get(0).bodyMarkdown()).isEqualTo("hello, world");
        assertThat(msgs.get(0).reactions()).extracting(r -> r.emoji()).containsExactly("👍");
    }

    @Test
    void deletingMessageCascadesReactions() {
        var alice = newUser("alice-del-cascade");
        var bob   = newUser("bob-del-cascade");
        var conv  = conversations.directBetween(alice, bob);
        var msg   = conversations.post(conv, alice, "soon-to-vanish");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        controller.addReaction(msg.getId(), new ReactionRequest("🎉"), mock(Principal.class));

        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        controller.deleteMessage(msg.getId(), mock(Principal.class));

        // No row left to grouping over; messages list is empty, so no reaction tray to render.
        assertThat(controller.messages(conv.getId(), null, mock(Principal.class))).isEmpty();
    }

    /** Real decoration against this context's LinkPreviewService; the broker is the test's mock. */
    private ai.intellistream.chat.web.LinkPreviews linkPreviews() {
        return new ai.intellistream.chat.web.LinkPreviews(linkPreviewService,
                org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
    }
    @org.springframework.beans.factory.annotation.Autowired
    ai.intellistream.chat.linkpreview.LinkPreviewService linkPreviewService;
}
