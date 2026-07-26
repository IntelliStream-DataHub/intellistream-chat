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
import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MentionService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.NotificationPreferenceService;
import ai.intellistream.chat.service.PresenceTracker;
import ai.intellistream.chat.service.ReadStateService;
import ai.intellistream.chat.service.SidebarService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the per-channel read marker, mention extraction, and the unread / mention
 * counts that ride into the sidebar DTOs.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class ReadStateAndMentionsIT {

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
    @Autowired SidebarService sidebar;
    @Autowired MentionService mentionService;
    @Autowired MarkdownRenderer markdown;
    @Autowired MessageMentionRepository mentions;
    @Autowired NotificationPreferenceService notifications;
    @Autowired PresenceTracker presence;
    @PersistenceContext EntityManager em;

    @AfterEach
    void clearPresence() {
        // The tracker is process-wide in-memory state shared by every test in this context.
        presence.resetForTests();
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }

    // ---------- Mention extraction ----------

    @Test
    void extractsHandlesFromBody() {
        assertThat(mentionService.extractHandles("hi @alice and @bob, also email foo@bar.com"))
                .containsExactly("alice", "bob");
    }

    @Test
    void doesNotExtractFromMidWord() {
        // an email shouldn't trigger; "@bob" embedded after a comma should.
        assertThat(mentionService.extractHandles("user@example.com is not a mention, but @bob is"))
                .containsExactly("bob");
    }

    @Test
    void mentionRowsAreSavedForResolvedUsers() {
        var alice = users.save(new User("kc-axx", "axx", "a@e", "A"));
        var bob = users.save(new User("kc-bxx", "bxx", "b@e", "B"));
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);

        var msg = messages.post(room, alice, "ping @bxx and @ghost (nobody)");
        em.flush();

        // bxx exists -> a row; ghost doesn't -> nothing.
        var sidebars = sidebar.joinedFor(bob).channels();
        // Auto-join is not a thing — bob isn't a member yet, so the channel isn't in his sidebar
        // at all, and no mention badge can be.
        assertThat(sidebars).noneMatch(d -> d.id().equals(room.getId()));

        // Make bob a member, then re-query.
        channels.join(room, bob);
        em.flush();

        var withBob = sidebar.joinedFor(bob).channels();
        var entry = withBob.stream().filter(d -> d.id().equals(room.getId())).findFirst().orElseThrow();
        assertThat(entry.joined()).isTrue();
        assertThat(entry.mentionCount()).isEqualTo(1);
    }

    // ---------- Broadcast mentions (@channel / @here / @everyone) ----------

    /**
     * @channel writes a row per member, which is what makes the bell inbox and the per-channel
     * badge work for people who are not connected when it lands. The author is excluded — nobody
     * needs their own bell to ring for their own announcement.
     */
    @Test
    void channelBroadcastReachesEveryMemberButTheAuthor() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("bc-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.join(room, carol);

        var msg = messages.post(room, alice, "standup in five, @channel");
        em.flush();

        assertThat(mentions.usernamesByMessage(msg))
                .containsExactlyInAnyOrder(bob.getUsername(), carol.getUsername());
    }

    /**
     * The audience is the membership, so the N2 rule holds by construction: a broadcast in a private
     * channel cannot put a row — and therefore a body snippet in the bell inbox — in front of
     * somebody who cannot read the channel.
     */
    @Test
    void privateChannelBroadcastDoesNotReachNonMembers() {
        var alice = newUser("alice");
        var member = newUser("bob");
        var outsider = newUser("carol");
        var room = channels.create("bc-priv-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        channels.invite(room, member, alice);

        var msg = messages.post(room, alice, "@channel please review");
        em.flush();

        assertThat(mentions.usernamesByMessage(msg)).containsExactly(member.getUsername());
        assertThat(mentions.usernamesByMessage(msg)).doesNotContain(outsider.getUsername());
    }

    /** @here is the connected subset — that is the entire difference from @channel. */
    @Test
    void hereReachesOnlyConnectedMembers() {
        var alice = newUser("alice");
        var online = newUser("bob");
        var offline = newUser("carol");
        var room = channels.create("bc-here-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, online);
        channels.join(room, offline);
        presence.connect(online.getUsername(), "stomp-session-1");

        var msg = messages.post(room, alice, "@here quick question");
        em.flush();

        assertThat(mentions.usernamesByMessage(msg)).containsExactly(online.getUsername());
    }

    /**
     * A mute is a mute. NONE means "nothing from this channel", and the existing client-side rule is
     * explicit that a mute with exceptions is not a mute — so a broadcast does not even get a row,
     * and cannot leave a badge on a channel the user silenced.
     */
    @Test
    void mutedMemberIsNotReachedByABroadcast() {
        var alice = newUser("alice");
        var muted = newUser("bob");
        var listening = newUser("carol");
        var room = channels.create("bc-mute-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, muted);
        channels.join(room, listening);
        notifications.setLevelFor(room, muted, NotificationLevel.NONE);
        em.flush();

        var msg = messages.post(room, alice, "@channel heads up");
        em.flush();

        assertThat(mentions.usernamesByMessage(msg)).containsExactly(listening.getUsername());
    }

    /** @everyone is a synonym for @channel here, not a no-op and not an error. */
    @Test
    void everyoneBehavesLikeChannel() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("bc-every-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        var msg = messages.post(room, alice, "@everyone welcome");
        em.flush();

        assertThat(mentions.usernamesByMessage(msg)).containsExactly(bob.getUsername());
    }

    /**
     * A broadcast and a personal mention of the same person in one body must not collide on
     * uk_message_mentions — the fan-out is idempotent, and one row is what the badge counts.
     */
    @Test
    void personalAndBroadcastMentionOfTheSamePersonProducesOneRow() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("bc-both-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        var msg = messages.post(room, alice, "@" + bob.getUsername() + " and @channel");
        em.flush();

        assertThat(mentions.usernamesByMessage(msg)).containsExactly(bob.getUsername());
    }

    // ---------- Markdown highlight ----------

    @Test
    void mentionsBecomeSpansInRenderedHtml() {
        users.save(new User("kc-aaa", "aaa", "a@e", "A"));
        var html = markdown.render("hello @aaa and @nobody");
        assertThat(html).contains("<span class=\"mention\" data-username=\"aaa\">@aaa</span>");
        // Unknown handles stay as plain text.
        assertThat(html).contains("@nobody");
        assertThat(html).doesNotContain("data-username=\"nobody\"");
    }

    @Test
    void mentionsInsideCodeBlocksAreNotDecorated() {
        users.save(new User("kc-aaa2", "aaa2", "a@e", "A"));
        var html = markdown.render("```\nuse @aaa2 here\n```\nand @aaa2 here");
        // Inside <pre><code> the mention text remains untouched.
        var codeStart = html.indexOf("<code");
        var codeEnd = html.indexOf("</code>");
        var insideCode = html.substring(codeStart, codeEnd);
        assertThat(insideCode).contains("@aaa2").doesNotContain("class=\"mention\"");
        // Outside the code block the second occurrence IS wrapped.
        assertThat(html).contains("<span class=\"mention\" data-username=\"aaa2\">@aaa2</span>");
    }

    // ---------- Read state / unread counts ----------

    @Test
    void unreadCountsRespectLastReadMarker() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        // Two messages from alice while bob hasn't visited — both unread for bob.
        messages.post(room, alice, "first");
        messages.post(room, alice, "second");
        em.flush();

        var pre = reads.unreadCounts(bob, List.of(room.getId()));
        assertThat(pre).containsEntry(room.getId(), 2L);

        reads.markRead(room, bob);
        em.flush();

        var post = reads.unreadCounts(bob, List.of(room.getId()));
        assertThat(post).doesNotContainKey(room.getId());

        // A new message after marking read should reappear in the count.
        messages.post(room, alice, "third");
        em.flush();
        assertThat(reads.unreadCounts(bob, List.of(room.getId()))).containsEntry(room.getId(), 1L);
    }

    @Test
    void unreadDoesNotCountViewersOwnMessages() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        // Alice (the only member) posts two herself.
        messages.post(room, alice, "self 1");
        messages.post(room, alice, "self 2");
        em.flush();

        assertThat(reads.unreadCounts(alice, List.of(room.getId()))).doesNotContainKey(room.getId());
    }

    /**
     * Inverted deliberately. This asserted that replies did NOT count, on the reasoning that they
     * live in the thread panel rather than the timeline — and the consequence was that a reply
     * produced no signal anywhere: no badge, no bold name, and with no @mention in it no bell entry
     * either. A thread could run for a hundred messages while the sidebar said the channel was
     * quiet. A reply is a message in the channel; the channel is unread.
     */
    @Test
    void unreadCountsThreadReplies() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        var parent = messages.post(room, alice, "top-level");
        messages.replyInThread(parent.getId(), alice, "reply 1");
        messages.replyInThread(parent.getId(), alice, "reply 2");
        em.flush();

        assertThat(reads.unreadCounts(bob, List.of(room.getId())))
                .containsEntry(room.getId(), 3L);
    }

    @Test
    void mentionCountsRespectLastReadMarker() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        messages.post(room, alice, "hey @" + bob.getUsername() + " look at this");
        messages.post(room, alice, "ping again @" + bob.getUsername());
        messages.post(room, alice, "no ping here");
        em.flush();

        assertThat(reads.mentionCounts(bob, List.of(room.getId())))
                .containsEntry(room.getId(), 2L);

        reads.markRead(room, bob);
        em.flush();
        assertThat(reads.mentionCounts(bob, List.of(room.getId()))).doesNotContainKey(room.getId());
    }

    // ---------- Sidebar wiring ----------

    @Test
    void sidebarSurfacesUnreadAndMentionCounts() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        messages.post(room, alice, "hey @" + bob.getUsername());
        messages.post(room, alice, "more chatter");
        em.flush();

        var entry = sidebar.joinedFor(bob).channels().stream()
                .filter(d -> d.id().equals(room.getId()))
                .findFirst().orElseThrow();
        assertThat(entry.unreadCount()).isEqualTo(2);
        assertThat(entry.mentionCount()).isEqualTo(1);
    }

    @Test
    void editingAMessageUpdatesItsMentionRows() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.join(room, carol);

        var msg = messages.post(room, alice, "ping @" + bob.getUsername());
        em.flush();
        assertThat(reads.mentionCounts(bob, List.of(room.getId()))).containsEntry(room.getId(), 1L);
        assertThat(reads.mentionCounts(carol, List.of(room.getId()))).doesNotContainKey(room.getId());

        // Edit removes the mention of bob, adds carol — old row gone, new row in.
        messages.edit(msg.getId(), alice, "actually pinging @" + carol.getUsername());
        em.flush();

        assertThat(reads.mentionCounts(bob, List.of(room.getId()))).doesNotContainKey(room.getId());
        assertThat(reads.mentionCounts(carol, List.of(room.getId()))).containsEntry(room.getId(), 1L);
    }
}
