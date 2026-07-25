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

import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ConversationAttachmentService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.ConversationRestController;
import ai.intellistream.chat.web.ConversationWebSocketController;
import ai.intellistream.chat.web.dto.AddGroupMemberRequest;
import ai.intellistream.chat.web.dto.ConversationEvent;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
import ai.intellistream.chat.web.dto.CreateGroupRequest;
import ai.intellistream.chat.web.dto.SendMessageRequest;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage for group direct messages: create, list members, add member,
 * access enforcement around the GROUP type, and the round-trip through
 * {@link ConversationWebSocketController#send} that mirrors the DIRECT path. Uses
 * the controller-IT pattern: real services + Postgres, mocked
 * {@link CurrentUser} and {@link SimpMessagingTemplate} so we can drive the
 * controller methods directly and verify both DB state and the broadcast envelope.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class GroupConversationFlowIT {

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
    @Autowired ai.intellistream.chat.service.ConversationReactionService convReactions;
    @Autowired MarkdownRenderer markdown;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private ConversationRestController controller;
    private ConversationWebSocketController ws;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        controller = new ConversationRestController(conversations, userService, currentUser,
                markdown, convAttachments, convReactions, broker, new RateLimiter());
        ws = new ConversationWebSocketController(conversations, markdown, currentUser,
                broker, new RateLimiter());
    }

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-grp-" + n + "-" + label,
                label + "-" + n,
                label + n + "@example.com",
                label + " " + n));
    }

    @Test
    void createGroup_seedsAllMembersAndCreator() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var dto = controller.createGroup(
                new CreateGroupRequest("Project X",
                        List.of(bob.getUsername(), carol.getUsername())),
                mock(Principal.class));

        assertThat(dto.id()).isNotNull();
        assertThat(dto.type()).isEqualTo(ConversationType.GROUP);
        assertThat(dto.title()).isEqualTo("Project X");
        // GROUP DTO has no "other" — title carries identity.
        assertThat(dto.otherUsername()).isNull();

        var members = controller.members(dto.id(), mock(Principal.class));
        assertThat(members).extracting(m -> m.username())
                .containsExactlyInAnyOrder(alice.getUsername(), bob.getUsername(), carol.getUsername());
    }

    @Test
    void createGroup_dropsCallerFromMembersListIfEchoed() {
        // The JS send the comma-separated input verbatim — the user might paste
        // their own username in by mistake. Server should de-dupe rather than fail.
        var alice = newUser("alice");
        var bob = newUser("bob");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var dto = controller.createGroup(
                new CreateGroupRequest("Echo group",
                        List.of(alice.getUsername(), bob.getUsername(), bob.getUsername())),
                mock(Principal.class));

        var members = controller.members(dto.id(), mock(Principal.class));
        assertThat(members).extracting(m -> m.username())
                .containsExactlyInAnyOrder(alice.getUsername(), bob.getUsername());
    }

    @Test
    void createGroup_unknownMemberFailsTheWholeCall() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        // The JS-typed username "ghost" doesn't exist; controller fails the whole
        // create rather than silently dropping invitees the user thought they invited.
        // Throws PublicBadRequestException so ApiExceptionHandler echoes the bad name
        // back to the client instead of the generic "Request rejected." envelope.
        assertThatThrownBy(() -> controller.createGroup(
                new CreateGroupRequest("Half group",
                        List.of(bob.getUsername(), "ghost-no-such-user")),
                mock(Principal.class)))
                .isInstanceOf(ai.intellistream.chat.security.PublicBadRequestException.class)
                // SEC-5: the error is generic — it must NOT echo the unknown username (enumeration oracle).
                .hasMessageContaining("could not be found")
                .hasMessageNotContaining("ghost-no-such-user");
    }

    @Test
    void createGroup_severalUnknownMembers_reportedTogether() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        // SEC-5: multiple unknowns still fail the whole call, but the message is generic — it must
        // not list which names were unknown (that would be a username-existence oracle).
        assertThatThrownBy(() -> controller.createGroup(
                new CreateGroupRequest("Triage",
                        List.of(bob.getUsername(), "ghost-1", "ghost-2")),
                mock(Principal.class)))
                .isInstanceOf(ai.intellistream.chat.security.PublicBadRequestException.class)
                .hasMessageContaining("could not be found")
                .hasMessageNotContaining("ghost-1")
                .hasMessageNotContaining("ghost-2");
    }

    @Test
    void createGroup_onlyCallerInMembersList_failsWithReadableMessage() {
        // The caller is auto-filtered from the seed list; if they typed only their own
        // name (or the de-dupe leaves nothing), the controller has to refuse with
        // a message that explains *why* — otherwise the user sees the redacted
        // "Request rejected." envelope and has no idea what to fix.
        var alice = newUser("alice");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        assertThatThrownBy(() -> controller.createGroup(
                new CreateGroupRequest("Solo",
                        List.of(alice.getUsername())),
                mock(Principal.class)))
                .isInstanceOf(ai.intellistream.chat.security.PublicBadRequestException.class)
                .hasMessageContaining("at least one other member");
    }

    @Test
    void createGroup_emptyMemberList_failsWithReadableMessage() {
        // The @Valid annotation already rejects an empty list at the DTO layer
        // (MethodArgumentNotValidException), but a list whose entries are all
        // blank strings slips past validation and reaches the controller.
        var alice = newUser("alice");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        assertThatThrownBy(() -> controller.createGroup(
                new CreateGroupRequest("Phantom",
                        List.of("   ", "")),
                mock(Principal.class)))
                .isInstanceOf(ai.intellistream.chat.security.PublicBadRequestException.class)
                .hasMessageContaining("at least one other member");
    }

    @Test
    void members_isRefusedToNonMember() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var snoop = newUser("snoop");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var dto = controller.createGroup(
                new CreateGroupRequest("Private", List.of(bob.getUsername())),
                mock(Principal.class));

        when(currentUser.resolve(any(Principal.class))).thenReturn(snoop);
        assertThatThrownBy(() -> controller.members(dto.id(), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addMember_byExistingMember_succeedsAndBroadcasts() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var dto = controller.createGroup(
                new CreateGroupRequest("Growing", List.of(bob.getUsername())),
                mock(Principal.class));

        // Bob (a member) invites Carol.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var added = controller.addMember(dto.id(),
                new AddGroupMemberRequest(carol.getUsername()),
                mock(Principal.class));

        assertThat(added.username()).isEqualTo(carol.getUsername());
        var members = controller.members(dto.id(), mock(Principal.class));
        assertThat(members).extracting(m -> m.username())
                .containsExactlyInAnyOrder(alice.getUsername(), bob.getUsername(), carol.getUsername());

        // Broadcast envelope on /topic/conversations/{id} with member-added discriminator.
        var captor = ArgumentCaptor.forClass(ConversationEvent.class);
        verify(broker).convertAndSend(eq("/topic/conversations/" + dto.id()), captor.capture());
        var event = captor.getValue();
        assertThat(event.type()).isEqualTo("member-added");
        assertThat(event.username()).isEqualTo(carol.getUsername());
        assertThat(event.conversationId()).isEqualTo(dto.id());
    }

    @Test
    void addMember_byNonMember_isRefused() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var snoop = newUser("snoop");
        var carol = newUser("carol");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var dto = controller.createGroup(
                new CreateGroupRequest("Closed", List.of(bob.getUsername())),
                mock(Principal.class));

        when(currentUser.resolve(any(Principal.class))).thenReturn(snoop);
        assertThatThrownBy(() -> controller.addMember(dto.id(),
                new AddGroupMemberRequest(carol.getUsername()),
                mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addMember_onDirectConversation_isRefused() {
        // DIRECT conversations are sealed at two participants — the underlying
        // ConversationService.addToGroup throws when the type isn't GROUP.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var direct = controller.startDirect(
                new ai.intellistream.chat.web.dto.StartDirectRequest(bob.getUsername()),
                mock(Principal.class));

        assertThatThrownBy(() -> controller.addMember(direct.id(),
                new AddGroupMemberRequest(carol.getUsername()),
                mock(Principal.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direct");
    }

    @Test
    void groupMessage_postedByMember_broadcastsAndPersists() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var dto = controller.createGroup(
                new CreateGroupRequest("Chat", List.of(bob.getUsername())),
                mock(Principal.class));

        ws.send(dto.id(), new SendMessageRequest("hello group"), mock(Principal.class));

        // Persistence: bob (a member) can read the message back through the membership-checked path.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var msgs = controller.messages(dto.id(), null, mock(Principal.class));
        assertThat(msgs).extracting(ConversationMessageDto::bodyMarkdown)
                .containsExactly("hello group");
    }

    @Test
    void groupMessage_postedByNonMember_isRefused() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var snoop = newUser("snoop");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var dto = controller.createGroup(
                new CreateGroupRequest("Closed", List.of(bob.getUsername())),
                mock(Principal.class));

        when(currentUser.resolve(any(Principal.class))).thenReturn(snoop);
        assertThatThrownBy(() -> ws.send(dto.id(),
                new SendMessageRequest("intruding"), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
