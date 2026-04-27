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

package com.example.chat.integration;

import com.example.chat.attachments.AttachmentBytes;
import com.example.chat.domain.ConversationType;
import com.example.chat.domain.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.security.CurrentUser;
import com.example.chat.security.RateLimiter;
import com.example.chat.service.ConversationService;
import com.example.chat.service.MarkdownRenderer;
import com.example.chat.service.UserService;
import com.example.chat.web.ConversationRestController;
import com.example.chat.web.ConversationWebSocketController;
import com.example.chat.web.UserRestController;
import com.example.chat.web.dto.ConversationDto;
import com.example.chat.web.dto.ConversationMessageDto;
import com.example.chat.web.dto.SendMessageRequest;
import com.example.chat.web.dto.StartDirectRequest;
import com.example.chat.web.dto.UserProfileDto;
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
 * Coverage for the hovercard + DM feature: profile lookup endpoint, direct-conversation
 * lifecycle (start, list, post, broadcast), and access enforcement. Mirrors the pattern in
 * {@code AvatarBroadcastIT}: controllers are constructed manually with mocked
 * {@link CurrentUser} / {@link SimpMessagingTemplate}, real services hit Postgres.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class HovercardAndDmFlowIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
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
    @Autowired com.example.chat.service.ConversationAttachmentService convAttachments;
    @Autowired com.example.chat.service.ConversationReactionService convReactions;
    @Autowired MarkdownRenderer markdown;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private UserRestController userController;
    private ConversationRestController conversationController;
    private ConversationWebSocketController conversationWs;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        // Stub the resolve(...) calls each test makes; tests that assert behaviour for a
        // specific user override this with their own when(...).thenReturn(specificUser).
        // Profile-lookup tests don't care which viewer is logged in, only that one is.
        when(currentUser.resolve(any(Principal.class))).thenAnswer(inv -> {
            // Default: a throwaway viewer so rate-limit keys are stable per test.
            return users.findAll().stream().findFirst().orElseThrow();
        });
        userController = new UserRestController(userService, currentUser, new RateLimiter());
        conversationController = new ConversationRestController(
                conversations, userService, currentUser, markdown,
                convAttachments, convReactions, broker, new RateLimiter());
        conversationWs = new ConversationWebSocketController(conversations, markdown, currentUser, broker, new RateLimiter());
    }

    @Test
    void profileEndpointReturnsHovercardPayload() {
        var alice = newUser("alice", "Alice Anderson");

        var dto = userController.profile(alice.getUsername(), mock(Principal.class)).getBody();

        assertThat(dto).isNotNull();
        assertThat(dto.username()).isEqualTo(alice.getUsername());
        assertThat(dto.displayName()).isEqualTo("Alice Anderson");
        assertThat(dto.email()).isEqualTo(alice.getEmail());
        assertThat(dto.createdAt()).isNotNull();
        assertThat(dto.hasAvatar()).isFalse();
        assertThat(dto.avatarVersion()).isZero();
    }

    @Test
    void profileLookupIsCaseInsensitive() {
        var bob = newUser("bob", "Bob Builder");

        var dto = userController.profile(bob.getUsername().toUpperCase(), mock(Principal.class)).getBody();

        assertThat(dto).isNotNull();
        assertThat(dto.username()).isEqualTo(bob.getUsername());
    }

    @Test
    void profileLookupForUnknownUserThrows() {
        // Seed a viewer so the wire()-installed CurrentUser stub has someone to resolve to.
        newUser("viewer", "Viewer");
        assertThatThrownBy(() -> userController.profile("does-not-exist", mock(Principal.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startDirectCreatesConversationAndIsIdempotent() {
        var alice = newUser("alice", "Alice");
        var bob = newUser("bob", "Bob");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var first = conversationController.startDirect(new StartDirectRequest(bob.getUsername()), mock(Principal.class));
        var second = conversationController.startDirect(new StartDirectRequest(bob.getUsername()), mock(Principal.class));

        assertThat(first.id()).isNotNull();
        assertThat(first.type()).isEqualTo(ConversationType.DIRECT);
        assertThat(first.id()).isEqualTo(second.id());
        // The DTO from Alice's perspective surfaces Bob as the "other" party.
        assertThat(first.otherUsername()).isEqualTo(bob.getUsername());
        assertThat(first.title()).isEqualTo("Bob");
    }

    @Test
    void startDirectWithSelfThrows() {
        var alice = newUser("alice", "Alice");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        assertThatThrownBy(() -> conversationController.startDirect(
                new StartDirectRequest(alice.getUsername()), mock(Principal.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listShowsOnlyConversationsTheViewerBelongsTo() {
        var alice = newUser("alice", "Alice");
        var bob = newUser("bob", "Bob");
        var carol = newUser("carol", "Carol");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        // Alice <-> Bob: alice is a member
        conversations.directBetween(alice, bob);
        // Bob <-> Carol: alice is NOT a member
        conversations.directBetween(bob, carol);

        var list = conversationController.list(mock(Principal.class));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).otherUsername()).isEqualTo(bob.getUsername());
    }

    @Test
    void listSurfacesOtherPartyForEachDirectConversation() {
        var alice = newUser("alice", "Alice");
        var bob = newUser("bob", "Bob");
        var carol = newUser("carol", "Carol");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        conversations.directBetween(alice, bob);
        conversations.directBetween(alice, carol);

        var list = conversationController.list(mock(Principal.class));

        assertThat(list).hasSize(2);
        assertThat(list).extracting(ConversationDto::otherUsername)
                .containsExactlyInAnyOrder(bob.getUsername(), carol.getUsername());
    }

    @Test
    void messagesEndpointRendersMarkdownAndOrdersAscending() {
        var alice = newUser("alice", "Alice");
        var bob = newUser("bob", "Bob");
        var conv = conversations.directBetween(alice, bob);
        conversations.post(conv, alice, "first");
        conversations.post(conv, bob,   "second **bold**");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var msgs = conversationController.messages(conv.getId(), mock(Principal.class));

        assertThat(msgs).hasSize(2);
        assertThat(msgs).extracting(ConversationMessageDto::bodyMarkdown)
                .containsExactly("first", "second **bold**");
        assertThat(msgs.get(1).bodyHtml()).contains("<strong>bold</strong>");
    }

    @Test
    void messagesEndpointRefusesNonMember() {
        var alice = newUser("alice", "Alice");
        var bob = newUser("bob", "Bob");
        var carol = newUser("carol", "Carol");
        var conv = conversations.directBetween(alice, bob);
        when(currentUser.resolve(any(Principal.class))).thenReturn(carol);

        assertThatThrownBy(() -> conversationController.messages(conv.getId(), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void webSocketSendBroadcastsAndPersists() {
        var alice = newUser("alice", "Alice");
        var bob = newUser("bob", "Bob");
        var conv = conversations.directBetween(alice, bob);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        conversationWs.send(conv.getId(), new SendMessageRequest("Hello **world**"), mock(Principal.class));

        // Persisted: bob can read it back via the membership-checked path.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var seenByBob = conversationController.messages(conv.getId(), mock(Principal.class));
        assertThat(seenByBob).extracting(ConversationMessageDto::bodyMarkdown)
                .containsExactly("Hello **world**");

        // Broadcast: a single frame on /topic/conversations/{id} carrying the rendered DTO.
        var captor = ArgumentCaptor.forClass(ConversationMessageDto.class);
        verify(broker).convertAndSend(eq("/topic/conversations/" + conv.getId()), captor.capture());
        assertThat(captor.getValue().bodyHtml()).contains("<strong>world</strong>");
        assertThat(captor.getValue().authorUsername()).isEqualTo(alice.getUsername());
    }

    @Test
    void webSocketSendByNonMemberThrows() {
        var alice = newUser("alice", "Alice");
        var bob = newUser("bob", "Bob");
        var carol = newUser("carol", "Carol");
        var conv = conversations.directBetween(alice, bob);
        when(currentUser.resolve(any(Principal.class))).thenReturn(carol);

        assertThatThrownBy(() -> conversationWs.send(conv.getId(),
                new SendMessageRequest("intruding"), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Sanity: the static {@link UserProfileDto#from} mapper picks up avatar metadata too. */
    @Test
    void profileDtoMappingPreservesAvatarVersion() {
        var u = newUser("avi", "Avatar User");
        u.setAvatar("some-key", "image/png");
        users.save(u);

        var dto = UserProfileDto.from(u);

        assertThat(dto.hasAvatar()).isTrue();
        assertThat(dto.avatarVersion()).isPositive();
    }

    @Test
    void recipientSeesUnreadCountAfterPeerSends() {
        var alice = newUser("alice-unread", "Alice");
        var bob   = newUser("bob-unread",   "Bob");
        var conv  = conversations.directBetween(alice, bob);

        // Bob writes two messages; alice's read marker hasn't moved yet.
        conversations.post(conv, bob, "first ping");
        conversations.post(conv, bob, "second ping");

        var counts = conversations.unreadCounts(alice, java.util.List.of(conv.getId()));
        assertThat(counts).containsEntry(conv.getId(), 2L);
    }

    @Test
    void senderDoesNotCountTheirOwnMessagesAsUnread() {
        var alice = newUser("alice-self", "Alice");
        var bob   = newUser("bob-self",   "Bob");
        var conv  = conversations.directBetween(alice, bob);

        conversations.post(conv, alice, "alice typed this");

        var counts = conversations.unreadCounts(alice, java.util.List.of(conv.getId()));
        assertThat(counts).doesNotContainKey(conv.getId());
    }

    @Test
    void markReadClearsUnreadCount() {
        var alice = newUser("alice-mr", "Alice");
        var bob   = newUser("bob-mr",   "Bob");
        var conv  = conversations.directBetween(alice, bob);

        conversations.post(conv, bob, "ping");
        assertThat(conversations.unreadCounts(alice, java.util.List.of(conv.getId())))
                .containsEntry(conv.getId(), 1L);

        conversations.markRead(conv, alice);

        assertThat(conversations.unreadCounts(alice, java.util.List.of(conv.getId())))
                .doesNotContainKey(conv.getId());
    }

    @Test
    void unreadCountsScopedToTheViewer() {
        var alice = newUser("alice-scope", "Alice");
        var bob   = newUser("bob-scope",   "Bob");
        var conv  = conversations.directBetween(alice, bob);

        // Each posts one message. From each viewer's POV, only the *other* user's message counts.
        conversations.post(conv, alice, "hi");
        conversations.post(conv, bob,   "hello back");

        var aliceUnread = conversations.unreadCounts(alice, java.util.List.of(conv.getId()));
        var bobUnread   = conversations.unreadCounts(bob,   java.util.List.of(conv.getId()));

        assertThat(aliceUnread).containsEntry(conv.getId(), 1L);
        assertThat(bobUnread).containsEntry(conv.getId(), 1L);
    }

    // ---------- DM attachment access control ----------

    @Test
    void dmAttachmentDownloadAllowedForBothMembers() throws Exception {
        var alice = newUser("alice-attach-ok", "Alice");
        var bob   = newUser("bob-attach-ok",   "Bob");
        var conv  = conversations.directBetween(alice, bob);
        var att = convAttachments.upload(conv, alice,
                "hi.txt", "text/plain", -1L, AttachmentBytes.UNLIMITED, "ping",
                new java.io.ByteArrayInputStream("hi".getBytes()));

        when(currentUser.resolve((Principal) any())).thenReturn(alice);
        var aliceDownload = conversationController.downloadAttachment(
                conv.getId(), att.getId(), null, mock(Principal.class));
        assertThat(aliceDownload.getStatusCode().value()).isEqualTo(200);

        when(currentUser.resolve((Principal) any())).thenReturn(bob);
        var bobDownload = conversationController.downloadAttachment(
                conv.getId(), att.getId(), null, mock(Principal.class));
        assertThat(bobDownload.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void dmAttachmentDownload404ForNonMember() throws Exception {
        var alice = newUser("alice-attach-404", "Alice");
        var bob   = newUser("bob-attach-404",   "Bob");
        var carol = newUser("carol-attach-404", "Carol");
        var conv  = conversations.directBetween(alice, bob);
        var att = convAttachments.upload(conv, alice,
                "secret.txt", "text/plain", -1L, AttachmentBytes.UNLIMITED, "private",
                new java.io.ByteArrayInputStream("classified".getBytes()));

        // Carol is not a participant — must get 404, NOT 403, so existence isn't leaked.
        when(currentUser.resolve((Principal) any())).thenReturn(carol);
        var carolDownload = conversationController.downloadAttachment(
                conv.getId(), att.getId(), null, mock(Principal.class));

        assertThat(carolDownload.getStatusCode().value()).isEqualTo(404);
        assertThat(carolDownload.getBody()).isNull();
    }

    @Test
    void dmAttachmentDownload404ForUnknownAttachmentId() throws Exception {
        var alice = newUser("alice-attach-unk", "Alice");
        var bob   = newUser("bob-attach-unk",   "Bob");
        var conv  = conversations.directBetween(alice, bob);

        when(currentUser.resolve((Principal) any())).thenReturn(alice);
        var resp = conversationController.downloadAttachment(
                conv.getId(), java.util.UUID.randomUUID(), null, mock(Principal.class));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void dmAttachmentDownload404WhenAttachmentBelongsToDifferentConversation() throws Exception {
        var alice = newUser("alice-mismatch", "Alice");
        var bob   = newUser("bob-mismatch",   "Bob");
        var carol = newUser("carol-mismatch", "Carol");

        var convAB = conversations.directBetween(alice, bob);
        var convAC = conversations.directBetween(alice, carol);
        // Attachment lives in conv A↔B; carol belongs to A↔C but not A↔B.
        var att = convAttachments.upload(convAB, alice,
                "ab.txt", "text/plain", -1L, AttachmentBytes.UNLIMITED, "",
                new java.io.ByteArrayInputStream("ab-only".getBytes()));

        when(currentUser.resolve((Principal) any())).thenReturn(carol);
        // Carol asks for the attachment as though it lived in HER conversation.
        var resp = conversationController.downloadAttachment(
                convAC.getId(), att.getId(), null, mock(Principal.class));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    private User newUser(String label, String displayName) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-hc-" + n + "-" + label,
                label + "-" + n,
                label + n + "@example.com",
                displayName));
    }
}
