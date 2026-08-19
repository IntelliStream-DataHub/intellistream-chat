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
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.PollService;
import ai.intellistream.chat.service.ReactionService;
import ai.intellistream.chat.service.ReadStateService;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.ChannelRestController;
import ai.intellistream.chat.web.dto.UserSearchResultDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@code GET /api/channels/{id}/invite-candidates} — the "Find user" browser behind
 * the channel settings panel's Find user button. Same authorization bar as
 * {@code POST /{id}/invite} (any channel member via {@code requireWriteAccess}), since browsing
 * for who to invite and typing their exact handle are the same action underneath.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ChannelInviteCandidatesIT {

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
    @Autowired AttachmentService attachments;
    @Autowired ReactionService reactions;
    @Autowired ReadStateService reads;
    @Autowired UserService userService;
    @Autowired PollService pollService;
    @Autowired MarkdownRenderer markdown;
    @Autowired ai.intellistream.chat.slash.SlashCommandService slashCommands;
    @Autowired ai.intellistream.chat.linkpreview.LinkPreviewService linkPreviewService;

    private CurrentUser currentUser;
    private ChannelRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        controller = new ChannelRestController(channels, messages, slashCommands, attachments, reactions,
                reads, userService, pollService, markdown, currentUser, new RateLimiter(),
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                mock(ai.intellistream.chat.repository.MessageMentionRepository.class),
                mock(ai.intellistream.chat.service.SidebarService.class),
                mock(ai.intellistream.chat.web.ChannelDestruction.class),
                new ai.intellistream.chat.web.LinkPreviews(linkPreviewService,
                        mock(org.springframework.messaging.simp.SimpMessagingTemplate.class)));
    }

    private User newUser(String prefix) {
        return newUser(prefix, prefix + "@example.com");
    }

    private User newUser(String prefix, String email) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-ic-" + prefix + i, prefix + "-" + i, email, prefix + " " + i));
    }

    private void asMember(User me) {
        when(currentUser.resolve(any(Principal.class))).thenReturn(me);
    }

    @Test
    void excludesExistingMembers_includesEveryoneElse() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var stranger = newUser("stranger");
        var room = channels.create("Room-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        asMember(alice);
        var found = controller.inviteCandidates(room.getId(), "", "", true, mock(Principal.class));

        assertThat(found).extracting(UserSearchResultDto::username)
                .contains(stranger.getUsername())
                .doesNotContain(alice.getUsername(), bob.getUsername());
    }

    @Test
    void usernameWildcard_filtersByPattern() {
        var alice = newUser("alice");
        var match = newUser("wzorro");
        var nomatch = newUser("nope");
        var room = channels.create("Room-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        asMember(alice);
        // "*" is a wildcard, so the pattern is fully anchored (LIKE 'w%rro%') — the trailing "*"
        // matters here because newUser() appends a "-<seq>" suffix after "rro".
        var found = controller.inviteCandidates(room.getId(), "w*rro*", "", true, mock(Principal.class));

        assertThat(found).extracting(UserSearchResultDto::username)
                .contains(match.getUsername())
                .doesNotContain(nomatch.getUsername());
    }

    @Test
    void usernameWithNoWildcard_isSubstringMatch() {
        var alice = newUser("alice");
        var match = newUser("carlyle");
        var nomatch = newUser("nope");
        var room = channels.create("Room-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        asMember(alice);
        var found = controller.inviteCandidates(room.getId(), "arly", "", true, mock(Principal.class));

        assertThat(found).extracting(UserSearchResultDto::username)
                .contains(match.getUsername())
                .doesNotContain(nomatch.getUsername());
    }

    @Test
    void emailDomain_matchesDomainPrefixOnly() {
        var alice = newUser("alice");
        var acme = newUser("worker", "worker" + SEQ.get() + "@acme.io");
        var other = newUser("other", "other" + SEQ.get() + "@notacme.io");
        var room = channels.create("Room-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        asMember(alice);
        var found = controller.inviteCandidates(room.getId(), "", "acme", true, mock(Principal.class));

        // "acme" must match the domain acme.io (prefix) but not notacme.io, even though the
        // latter contains "acme" as a substring — the match is anchored right after the '@'.
        assertThat(found).extracting(UserSearchResultDto::username)
                .contains(acme.getUsername())
                .doesNotContain(other.getUsername());
    }

    @Test
    void recentFirst_ordersNewestCreatedFirst() {
        var alice = newUser("alice");
        var older = newUser("older");
        var newer = newUser("newer");
        var room = channels.create("Room-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        asMember(alice);
        var found = controller.inviteCandidates(room.getId(), "", "", true, mock(Principal.class));

        var idxOlder = indexOf(found, older.getUsername());
        var idxNewer = indexOf(found, newer.getUsername());
        assertThat(idxNewer).isLessThan(idxOlder);
    }

    @Test
    void nonMember_refused() {
        var alice = newUser("alice");
        var snoop = newUser("snoop");
        var secret = channels.create("Secret-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);

        asMember(snoop);
        assertThatThrownBy(() -> controller.inviteCandidates(secret.getId(), "", "", true, mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resultIsCappedAtOneHundred() {
        var alice = newUser("alice");
        var room = channels.create("Room-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var tag = "cap" + SEQ.incrementAndGet();
        for (int i = 0; i < 105; i++) {
            newUser(tag + "-" + i);
        }

        asMember(alice);
        var found = controller.inviteCandidates(room.getId(), tag, "", true, mock(Principal.class));

        assertThat(found).hasSize(100);
    }

    private static int indexOf(java.util.List<UserSearchResultDto> list, String username) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).username().equals(username)) return i;
        }
        throw new AssertionError("Not found: " + username);
    }
}
