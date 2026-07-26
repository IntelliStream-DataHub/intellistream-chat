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
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MessageService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive flow with three users:
 *   Alice (admin)  — creates/destroys channels, pins messages, invites to private channels.
 *   Bob   (member) — joins public channels, can be invited to private ones.
 *   Clark (member) — same as Bob; together with Alice posts messages Bob can read.
 *
 * Also exercises private 1-to-1 messages and named group DMs across the three users.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class AdminAndConversationFlowIT {

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
    @Autowired ConversationService conversations;

    private record Cast(User alice, User bob, User clark) {}

    private Cast cast(String suffix) {
        var alice = users.save(new User("kc-alice-" + suffix, "alice-" + suffix, "alice-" + suffix + "@x", "Alice"));
        var bob   = users.save(new User("kc-bob-"   + suffix, "bob-"   + suffix, "bob-"   + suffix + "@x", "Bob"));
        var clark = users.save(new User("kc-clark-" + suffix, "clark-" + suffix, "clark-" + suffix + "@x", "Clark"));
        return new Cast(alice, bob, clark);
    }

    @Test
    void aliceAdminPowers_createPinDestroy_andPublicChannelMessageFlow() {
        var c = cast("admin");

        // Alice creates a public channel and is automatically the admin.
        var general = channels.create("General", "Welcome", ChannelType.PUBLIC, c.alice());
        assertThat(channels.isAdmin(general, c.alice())).isTrue();
        assertThat(channels.isAdmin(general, c.bob())).isFalse();
        assertThat(channels.isAdmin(general, c.clark())).isFalse();

        // Bob and Clark join the public channel — no invite needed.
        channels.join(general, c.bob());
        channels.join(general, c.clark());
        assertThat(channels.isMember(general, c.bob())).isTrue();
        assertThat(channels.isMember(general, c.clark())).isTrue();

        // Alice and Clark each send a message; Bob reads them.
        var aliceMsg = messages.post(general, c.alice(), "Hi team — welcome to **#general**.");
        var clarkMsg = messages.post(general, c.clark(), "Hey everyone, Clark here.");

        var bobsView = messages.recent(general, c.bob(), 50);
        assertThat(bobsView).extracting(m -> m.getAuthor().getUsername())
                .containsExactly(c.alice().getUsername(), c.clark().getUsername());
        assertThat(bobsView).extracting(m -> m.getBodyMarkdown())
                .anyMatch(s -> s.contains("welcome to **#general**"))
                .anyMatch(s -> s.contains("Clark here"));

        // Non-admins cannot pin.
        assertThatThrownBy(() -> messages.pin(aliceMsg.getId(), c.bob()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> messages.pin(clarkMsg.getId(), c.clark()))
                .isInstanceOf(AccessDeniedException.class);

        // Alice (admin) pins one of Clark's messages.
        var pinned = messages.pin(clarkMsg.getId(), c.alice());
        assertThat(pinned.isPinned()).isTrue();
        assertThat(pinned.getPinnedBy().getUsername()).isEqualTo(c.alice().getUsername());

        var pinnedList = messages.pinned(general, c.bob());
        assertThat(pinnedList).hasSize(1);
        assertThat(pinnedList.get(0).getId()).isEqualTo(clarkMsg.getId());

        // Alice can unpin and the list goes back to empty.
        messages.unpin(clarkMsg.getId(), c.alice());
        assertThat(messages.pinned(general, c.bob())).isEmpty();

        // Deleting a channel is a workspace-admin action now, not a channel-admin one. This
        // assertion was inverted rather than dropped: it used to say alice, the channel's own
        // admin, could destroy it, and that is precisely the behaviour that has been removed.
        // Destroying a channel wipes other people's messages and other people's files with no undo,
        // and a channel admin is whoever happened to create the room; Slack draws the line in the
        // same place and points everyone else at archiving, which is now available and reversible.
        assertThatThrownBy(() -> channels.destroy(general, c.bob()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> channels.destroy(general, c.alice()))
                .isInstanceOf(AccessDeniedException.class);

        // With ROLE_ADMIN on the request it goes; messages cascade away.
        AsWorkspaceAdmin.run(() -> channels.destroy(general, c.alice()));
        assertThatThrownBy(() -> channels.requireById(general.getId()))
                .isInstanceOf(ai.intellistream.chat.security.ResourceNotFoundException.class);
    }

    @Test
    void privateChannel_aliceInvitesBobAndClark_messagesReadable() {
        var c = cast("private");

        var room = channels.create("Project X", "secret stuff", ChannelType.PRIVATE, c.alice());

        // Without an invite Bob cannot self-join nor post.
        assertThatThrownBy(() -> channels.join(room, c.bob()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> messages.post(room, c.bob(), "smuggled in"))
                .isInstanceOf(AccessDeniedException.class);

        // Alice invites both Bob and Clark.
        channels.invite(room, c.bob(),   c.alice());
        channels.invite(room, c.clark(), c.alice());
        assertThat(channels.isMember(room, c.bob())).isTrue();
        assertThat(channels.isMember(room, c.clark())).isTrue();

        // Slack/Mattermost default: any channel member can invite. Bob (plain member) invites Dave.
        var dave = users.save(new User("kc-dave-private", "dave-private", "dave@x", "Dave"));
        channels.invite(room, dave, c.bob());
        assertThat(channels.isMember(room, dave)).isTrue();

        // Alice and Clark post; Bob reads both.
        messages.post(room, c.alice(), "Reminder: ship by Friday.");
        messages.post(room, c.clark(), "Acked. Pushing the last patch tonight.");

        var fromBobsPerspective = messages.recent(room, c.bob(), 50);
        assertThat(fromBobsPerspective).hasSize(2);
        assertThat(fromBobsPerspective).extracting(m -> m.getAuthor().getUsername())
                .containsExactly(c.alice().getUsername(), c.clark().getUsername());
        assertThat(fromBobsPerspective).extracting(m -> m.getBodyMarkdown())
                .containsExactly("Reminder: ship by Friday.",
                                 "Acked. Pushing the last patch tonight.");
    }

    @Test
    void directMessages_betweenTwoUsers_areIsolatedAndDeduplicated() {
        var c = cast("dm");

        var aliceBob = conversations.directBetween(c.alice(), c.bob());
        // Re-requesting returns the same conversation row (no duplicates).
        var aliceBobAgain = conversations.directBetween(c.bob(), c.alice());
        assertThat(aliceBobAgain.getId()).isEqualTo(aliceBob.getId());

        // Both participants can post and read.
        conversations.post(aliceBob, c.alice(), "Hey Bob, got a sec?");
        conversations.post(aliceBob, c.bob(),   "Sure, what's up?");
        var thread = conversations.recent(aliceBob, c.bob(), 50);
        assertThat(thread).hasSize(2);
        assertThat(thread).extracting(m -> m.getAuthor().getUsername())
                .containsExactly(c.alice().getUsername(), c.bob().getUsername());

        // Clark is not part of this DM and cannot read or post.
        assertThatThrownBy(() -> conversations.recent(aliceBob, c.clark(), 50))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> conversations.post(aliceBob, c.clark(), "eavesdropping"))
                .isInstanceOf(AccessDeniedException.class);

        // A separate Alice<->Clark DM is a distinct conversation and Bob cannot read it.
        var aliceClark = conversations.directBetween(c.alice(), c.clark());
        assertThat(aliceClark.getId()).isNotEqualTo(aliceBob.getId());
        conversations.post(aliceClark, c.alice(), "private to Clark");
        assertThatThrownBy(() -> conversations.recent(aliceClark, c.bob(), 50))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void groupConversation_threeUsersCanMessageEachOther() {
        var c = cast("group");

        var group = conversations.createGroup("Trio Planning", c.alice(), List.of(c.bob(), c.clark()));
        assertThat(conversations.isMember(group, c.alice())).isTrue();
        assertThat(conversations.isMember(group, c.bob())).isTrue();
        assertThat(conversations.isMember(group, c.clark())).isTrue();

        conversations.post(group, c.alice(), "Standup at 9?");
        conversations.post(group, c.bob(),   "Works for me.");
        conversations.post(group, c.clark(), "+1");

        var asBob = conversations.recent(group, c.bob(), 50);
        assertThat(asBob).extracting(m -> m.getBodyMarkdown())
                .containsExactly("Standup at 9?", "Works for me.", "+1");

        // A non-member cannot see the group.
        var dave = users.save(new User("kc-dave-group", "dave-group", "dave@x", "Dave"));
        assertThatThrownBy(() -> conversations.recent(group, dave, 50))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> conversations.post(group, dave, "hi?"))
                .isInstanceOf(AccessDeniedException.class);

        // A member can add another user; non-members cannot.
        assertThatThrownBy(() -> conversations.addToGroup(group, dave, dave))
                .isInstanceOf(AccessDeniedException.class);
        conversations.addToGroup(group, dave, c.alice());
        assertThat(conversations.isMember(group, dave)).isTrue();

        // Cannot add members to a DIRECT conversation.
        var dm = conversations.directBetween(c.alice(), c.bob());
        assertThatThrownBy(() -> conversations.addToGroup(dm, c.clark(), c.alice()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
