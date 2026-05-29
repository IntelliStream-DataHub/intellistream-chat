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

import ai.intellistream.radiance.domain.ChannelRole;
import ai.intellistream.radiance.domain.ChannelType;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.security.CurrentUser;
import ai.intellistream.radiance.security.RateLimiter;
import ai.intellistream.radiance.service.AttachmentService;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.MarkdownRenderer;
import ai.intellistream.radiance.service.MessageService;
import ai.intellistream.radiance.service.PollService;
import ai.intellistream.radiance.service.ReactionService;
import ai.intellistream.radiance.service.ReadStateService;
import ai.intellistream.radiance.service.UserService;
import ai.intellistream.radiance.web.ChannelRestController;
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
 * Coverage for {@code GET /api/channels/{id}/members} — the endpoint behind the
 * "Channel members" dropdown next to the channel-search box. Same access stance as
 * message reads: PUBLIC channels are visible to any authenticated user, PRIVATE
 * requires actual membership.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ChannelMembersListIT {

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

    private CurrentUser currentUser;
    private ChannelRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        controller = new ChannelRestController(channels, messages, attachments, reactions,
                reads, userService, pollService, markdown, currentUser, new RateLimiter());
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-cm-" + prefix + i, prefix + "-" + i,
                prefix + i + "@e", prefix + " " + i));
    }

    @Test
    void publicChannel_listsCreatorPlusJoiners_withRoles() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Room-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var listed = controller.members(room.getId(), mock(Principal.class));

        assertThat(listed).hasSize(2);
        assertThat(listed).extracting(m -> m.username())
                .containsExactlyInAnyOrder(alice.getUsername(), bob.getUsername());
        // Creator becomes ADMIN by default; the joined user is a regular MEMBER.
        var aliceRow = listed.stream().filter(m -> m.username().equals(alice.getUsername())).findFirst().orElseThrow();
        var bobRow = listed.stream().filter(m -> m.username().equals(bob.getUsername())).findFirst().orElseThrow();
        assertThat(aliceRow.role()).isEqualTo(ChannelRole.ADMIN);
        assertThat(bobRow.role()).isEqualTo(ChannelRole.MEMBER);
    }

    @Test
    void publicChannel_visibleToNonMember() {
        // PUBLIC channels are readable by any authenticated user — the members list
        // follows the same posture so the "👥 N" button works without joining first.
        var alice = newUser("alice");
        var snoop = newUser("snoop");
        var room = channels.create("Room-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);

        when(currentUser.resolve(any(Principal.class))).thenReturn(snoop);
        var listed = controller.members(room.getId(), mock(Principal.class));

        assertThat(listed).extracting(m -> m.username())
                .containsExactly(alice.getUsername());
    }

    @Test
    void privateChannel_visibleToMember() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var secret = channels.create("Secret-" + SEQ.incrementAndGet(),
                null, ChannelType.PRIVATE, alice);
        channels.invite(secret, bob, alice);

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var listed = controller.members(secret.getId(), mock(Principal.class));

        assertThat(listed).extracting(m -> m.username())
                .containsExactlyInAnyOrder(alice.getUsername(), bob.getUsername());
    }

    @Test
    void privateChannel_refusedToNonMember() {
        // PRIVATE channels are sealed — non-members should not be able to enumerate
        // who's inside, otherwise "is X in $secret_room" becomes a side-channel.
        var alice = newUser("alice");
        var snoop = newUser("snoop");
        var secret = channels.create("Secret-" + SEQ.incrementAndGet(),
                null, ChannelType.PRIVATE, alice);

        when(currentUser.resolve(any(Principal.class))).thenReturn(snoop);
        assertThatThrownBy(() -> controller.members(secret.getId(), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unknownChannel_throws() {
        var alice = newUser("alice");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        assertThatThrownBy(() -> controller.members(999_999_999L, mock(Principal.class)))
                .isInstanceOf(ai.intellistream.radiance.security.ResourceNotFoundException.class);
    }

    @Test
    void avatarMetadataAndDisplayNamePopulated() {
        // The dropdown renders an avatar + display name per row; the DTO must carry
        // both without a second /api/users/{u} round-trip per member.
        var alice = newUser("alice");
        var bob = newUser("bob");
        bob.setAvatar("some-key", "image/png");
        users.save(bob);
        var room = channels.create("Room-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var listed = controller.members(room.getId(), mock(Principal.class));

        var bobRow = listed.stream().filter(m -> m.username().equals(bob.getUsername())).findFirst().orElseThrow();
        assertThat(bobRow.hasAvatar()).isTrue();
        assertThat(bobRow.avatarVersion()).isPositive();
        assertThat(bobRow.displayName()).isNotBlank();
    }
}
