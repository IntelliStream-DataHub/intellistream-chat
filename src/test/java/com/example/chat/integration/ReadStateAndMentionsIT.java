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

import com.example.chat.domain.ChannelType;
import com.example.chat.domain.User;
import com.example.chat.repository.UserRepository;
import com.example.chat.service.ChannelService;
import com.example.chat.service.MarkdownRenderer;
import com.example.chat.service.MentionService;
import com.example.chat.service.MessageService;
import com.example.chat.service.ReadStateService;
import com.example.chat.service.SidebarService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired ReadStateService reads;
    @Autowired SidebarService sidebar;
    @Autowired MentionService mentionService;
    @Autowired MarkdownRenderer markdown;
    @PersistenceContext EntityManager em;

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
        var sidebars = sidebar.sidebarFor(bob);
        // Auto-join is not a thing — bob isn't a member yet, so mention badge won't show in his sidebar.
        assertThat(sidebars).noneMatch(d -> d.id().equals(room.getId()) && d.joined());

        // Make bob a member, then re-query.
        channels.join(room, bob);
        em.flush();

        var withBob = sidebar.sidebarFor(bob);
        var entry = withBob.stream().filter(d -> d.id().equals(room.getId())).findFirst().orElseThrow();
        assertThat(entry.joined()).isTrue();
        assertThat(entry.mentionCount()).isEqualTo(1);
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

    @Test
    void unreadDoesNotCountThreadReplies() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        var parent = messages.post(room, alice, "top-level");
        messages.replyInThread(parent.getId(), alice, "reply 1");
        messages.replyInThread(parent.getId(), alice, "reply 2");
        em.flush();

        // Only the top-level message counts — replies live in the thread panel, not the timeline.
        assertThat(reads.unreadCounts(bob, List.of(room.getId())))
                .containsEntry(room.getId(), 1L);
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

        var entry = sidebar.sidebarFor(bob).stream()
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
