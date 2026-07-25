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
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MentionService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.ReadStateService;
import ai.intellistream.chat.web.MentionRestController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the topbar mention-inbox contract: GET /api/mentions returns recent unread mentions
 * across all channels, GET /api/mentions/count matches the list, and marking a channel read
 * removes that channel's older mentions from the inbox (mirrors the per-channel badge logic).
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MentionInboxIT {

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
    @Autowired ReadStateService reads;
    @Autowired MentionService mentionService;

    private CurrentUser currentUser;
    private MentionRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        controller = new MentionRestController(mentionService, reads, currentUser);
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-mi-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }

    @Test
    void inboxReturnsMentionsAcrossMultipleChannels() {
        var bob = newUser("bob");
        var alice = newUser("alice");
        var carol = newUser("carol");
        var room1 = channels.create("Room1-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var room2 = channels.create("Room2-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, carol);

        messages.post(room1, alice, "hi @" + bob.getUsername() + " from room1");
        messages.post(room2, carol, "hey @" + bob.getUsername() + " from room2");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);

        var inbox = controller.inbox(20, mock(Principal.class));
        assertThat(inbox).hasSize(2);
        assertThat(inbox).extracting("channelId")
                .containsExactlyInAnyOrder(room1.getId(), room2.getId());
        assertThat(inbox).allSatisfy(item -> {
            assertThat(item.snippet()).contains("@" + bob.getUsername());
            assertThat(item.authorUsername()).isNotBlank();
        });

        var count = controller.count(mock(Principal.class));
        assertThat(count.unread()).isEqualTo(2);
    }

    @Test
    void markingChannelReadDropsItsMentionsFromInbox() {
        var bob = newUser("bob");
        var alice = newUser("alice");
        var carol = newUser("carol");
        var room1 = channels.create("R1-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var room2 = channels.create("R2-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, carol);

        messages.post(room1, alice, "@" + bob.getUsername() + " ping1");
        messages.post(room2, carol, "@" + bob.getUsername() + " ping2");
        // Bob reads room1 — that mention should disappear from his inbox.
        reads.markRead(room1, bob);

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var inbox = controller.inbox(20, mock(Principal.class));

        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).channelId()).isEqualTo(room2.getId());
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(1);
    }

    @Test
    void inboxIsEmptyWhenNoUnresolvedMentions() {
        var bob = newUser("bob");
        var alice = newUser("alice");
        var room = channels.create("Empty-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        // Mention is for someone else.
        messages.post(room, alice, "hi @ghost-handle nobody");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        assertThat(controller.inbox(20, mock(Principal.class))).isEmpty();
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(0);
    }

    @Test
    void privateChannelMentionDoesNotLeakToNonMember() {
        // N2: mentioning a non-member in a PRIVATE channel must not surface the message body or
        // channel name in their inbox, and must not bump their bell count.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var secret = channels.create("Secret-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        // bob is deliberately NOT a member of the private channel.
        messages.post(secret, alice, "@" + bob.getUsername() + " the deal closes at $5M");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        assertThat(controller.inbox(20, mock(Principal.class))).isEmpty();
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(0);
    }

    @Test
    void privateChannelMentionStillReachesAMember() {
        // Control for N2: a mention of an actual member of the private channel works as before.
        var alice = newUser("alice");
        var carol = newUser("carol");
        var secret = channels.create("Team-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        channels.invite(secret, carol, alice);
        messages.post(secret, alice, "@" + carol.getUsername() + " standup in 5");

        when(currentUser.resolve(any(Principal.class))).thenReturn(carol);
        var inbox = controller.inbox(20, mock(Principal.class));
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).channelId()).isEqualTo(secret.getId());
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(1);
    }

    @Test
    void inboxOrdersNewestFirstAndRespectsLimit() {
        var bob = newUser("bob");
        var alice = newUser("alice");
        var room = channels.create("Order-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        messages.post(room, alice, "@" + bob.getUsername() + " first");
        messages.post(room, alice, "@" + bob.getUsername() + " second");
        messages.post(room, alice, "@" + bob.getUsername() + " third");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var inbox = controller.inbox(2, mock(Principal.class));
        assertThat(inbox).hasSize(2);
        assertThat(inbox.get(0).snippet()).contains("third");
        assertThat(inbox.get(1).snippet()).contains("second");
    }

    @Test
    void snippetCollapsesWhitespaceAndTruncates() {
        var bob = newUser("bob");
        var alice = newUser("alice");
        var room = channels.create("Snip-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var longBody = "@" + bob.getUsername() + "  has   lots\nof\twhitespace " + "x".repeat(500);
        messages.post(room, alice, longBody);

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        var inbox = controller.inbox(20, mock(Principal.class));
        assertThat(inbox).hasSize(1);
        var snippet = inbox.get(0).snippet();
        // Whitespace collapsed to single spaces.
        assertThat(snippet).doesNotContain("  ").doesNotContain("\n").doesNotContain("\t");
        // Truncated with the ellipsis marker.
        assertThat(snippet.length()).isLessThanOrEqualTo(241); // 240 + "…"
        assertThat(snippet).endsWith("…");
    }

    @Test
    void inboxIncludesEditedMessageWithUpdatedMentions() {
        var bob = newUser("bob");
        var alice = newUser("alice");
        var room = channels.create("Edit-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        // Original message doesn't mention bob.
        var msg = messages.post(room, alice, "no mention here");
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        assertThat(controller.inbox(20, mock(Principal.class))).isEmpty();

        // Edit to add the mention — the mention sync should pick this up.
        messages.edit(msg.getId(), alice, "actually @" + bob.getUsername() + " ping");

        var inbox = controller.inbox(20, mock(Principal.class));
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).snippet()).contains("@" + bob.getUsername());
    }

    // ---------- Mark all as read ----------

    @Test
    void markAllReadEmptiesInboxAcrossEveryChannel() {
        var bob = newUser("bob");
        var alice = newUser("alice");
        var carol = newUser("carol");
        var room1 = channels.create("All1-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var room2 = channels.create("All2-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, carol);
        var room3 = channels.create("All3-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        messages.post(room1, alice, "@" + bob.getUsername() + " ping1");
        messages.post(room2, carol, "@" + bob.getUsername() + " ping2");
        messages.post(room3, alice, "@" + bob.getUsername() + " ping3");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(3);

        var result = controller.markAllRead(mock(Principal.class));
        assertThat(result).containsEntry("channelsMarkedRead", 3);

        // Inbox is empty post-mark; count is zero.
        assertThat(controller.inbox(20, mock(Principal.class))).isEmpty();
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(0);
    }

    @Test
    void markAllReadIsIdempotentWhenInboxAlreadyEmpty() {
        var bob = newUser("bob");
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);

        var result = controller.markAllRead(mock(Principal.class));
        assertThat(result).containsEntry("channelsMarkedRead", 0);
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(0);
    }

    @Test
    void markAllReadDoesNotAffectOtherUsersUnreadCounts() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("Iso-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        // Alice mentions both bob and carol in the same message.
        messages.post(room, alice, "@" + bob.getUsername() + " and @" + carol.getUsername() + " hi");

        // Bob clears his inbox.
        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        controller.markAllRead(mock(Principal.class));
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(0);

        // Carol's inbox still shows the mention.
        when(currentUser.resolve(any(Principal.class))).thenReturn(carol);
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(1);
    }

    @Test
    void markAllReadDoesNotTouchMentionsInChannelsAlreadyAcknowledged() {
        var bob = newUser("bob");
        var alice = newUser("alice");
        var room1 = channels.create("Ack1-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var room2 = channels.create("Ack2-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        messages.post(room1, alice, "@" + bob.getUsername() + " first");
        // Bob already read room1 manually — that mention shouldn't even be in his inbox.
        reads.markRead(room1, bob);
        messages.post(room2, alice, "@" + bob.getUsername() + " second");

        when(currentUser.resolve(any(Principal.class))).thenReturn(bob);
        // Inbox has only room2's mention.
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(1);
        // markAllRead should only touch the still-unread channel (room2), not room1.
        var result = controller.markAllRead(mock(Principal.class));
        assertThat(result).containsEntry("channelsMarkedRead", 1);
        assertThat(controller.count(mock(Principal.class)).unread()).isEqualTo(0);
    }
}
