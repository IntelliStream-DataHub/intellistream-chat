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

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.ReactionService;
import ai.intellistream.chat.service.ReadStateService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Realistic multi-user thread scenario: alice (creator/admin) plus four other members
 * have a back-and-forth on a single parent message. Exercises everything that a busy
 * thread typically touches at once — reply ordering, mentions, reactions, edits,
 * author-vs-admin delete rules, and read/unread counts for someone who hasn't seen it yet.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class FivePersonThreadIT {

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
        registry.add("intellistream.search.lucene-dir", () -> "build/test-lucene/FivePersonThreadIT");
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired MessageRepository messageRepo;
    @Autowired ReactionService reactions;
    @Autowired ReadStateService reads;
    @PersistenceContext EntityManager em;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }

    @Test
    void fivePersonThreadEndToEnd() {
        // --- Cast: alice owns the channel; bob/carol/dave/eve join.
        var alice = newUser("alice");
        var bob   = newUser("bob");
        var carol = newUser("carol");
        var dave  = newUser("dave");
        var eve   = newUser("eve");
        var absent = newUser("absent"); // never opens the channel — used to assert unread counts

        var room = channels.create("planning-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        for (var u : List.of(bob, carol, dave, eve, absent)) channels.join(room, u);
        em.flush();

        // --- Parent message + a five-way thread.
        var parent = messages.post(room, alice, "Sprint planning — please weigh in below");
        var rBob   = messages.replyInThread(parent.getId(), bob,   "+1 to the new auth scope, @" + alice.getUsername());
        var rCarol = messages.replyInThread(parent.getId(), carol, "I'll take the migration ticket");
        var rDave  = messages.replyInThread(parent.getId(), dave,  "Concerned about the timeline; @" + carol.getUsername() + " thoughts?");
        var rEve   = messages.replyInThread(parent.getId(), eve,   "Fine by me. We can split if it slips.");
        var rAlice = messages.replyInThread(parent.getId(), alice, "Thanks all — let's lock it in.");
        em.flush();

        // --- Reply view returns the 5 replies in chronological order; parent stays out.
        var thread = messages.threadReplies(parent.getId(), eve);
        assertThat(thread).extracting(m -> m.getId())
                .containsExactly(rBob.getId(), rCarol.getId(), rDave.getId(), rEve.getId(), rAlice.getId());
        assertThat(thread).extracting(m -> m.getAuthor().getUsername())
                .containsExactly(bob.getUsername(), carol.getUsername(), dave.getUsername(),
                        eve.getUsername(), alice.getUsername());
        // Every reply carries the parent reference — never the channel directly.
        assertThat(thread).allSatisfy(reply -> {
            assertThat(reply.getParent().getId()).isEqualTo(parent.getId());
            assertThat(reply.getChannel().getId()).isEqualTo(room.getId());
        });
        assertThat(messages.threadReplyCount(parent)).isEqualTo(5);

        // --- The main channel feed shows ONLY the parent, even after 5 replies.
        var top = messages.recent(room, eve, 50);
        assertThat(top).extracting(m -> m.getId()).containsExactly(parent.getId());

        // --- Mentions are persisted: bob mentioned alice; dave mentioned carol.
        assertThat(reads.mentionCounts(alice, List.of(room.getId())))
                .containsEntry(room.getId(), 1L);
        assertThat(reads.mentionCounts(carol, List.of(room.getId())))
                .containsEntry(room.getId(), 1L);
        assertThat(reads.mentionCounts(eve, List.of(room.getId()))).doesNotContainKey(room.getId());

        // --- Reactions: ❤️ from carol/dave/eve on bob's reply; 👍 from alice on dave's.
        reactions.addReaction(rBob.getId(), carol, "❤️");
        reactions.addReaction(rBob.getId(), dave,  "❤️");
        reactions.addReaction(rBob.getId(), eve,   "❤️");
        reactions.addReaction(rDave.getId(), alice, "👍");
        em.flush();

        var bobReplyReactions = reactions.groupingsFor(rBob, dave); // dave is the viewer here
        assertThat(bobReplyReactions).hasSize(1);
        assertThat(bobReplyReactions.get(0).emoji()).isEqualTo("❤️");
        assertThat(bobReplyReactions.get(0).count()).isEqualTo(3);
        assertThat(bobReplyReactions.get(0).mine()).isTrue(); // dave reacted
        assertThat(bobReplyReactions.get(0).usernames())
                .containsExactlyInAnyOrder(carol.getUsername(), dave.getUsername(), eve.getUsername());

        var daveReplyReactions = reactions.groupingsFor(rDave, alice);
        assertThat(daveReplyReactions).hasSize(1);
        assertThat(daveReplyReactions.get(0).emoji()).isEqualTo("👍");
        assertThat(daveReplyReactions.get(0).mine()).isTrue();

        // --- Author edits + edited timestamp shows up.
        var editedParent = messages.edit(parent.getId(), alice,
                "Sprint planning — *please weigh in below* (closing Friday)");
        em.flush();
        assertThat(editedParent.getEditedAt()).isNotNull();
        assertThat(editedParent.getBodyMarkdown()).contains("closing Friday");

        // --- Author can delete their own reply.
        messages.delete(rBob.getId(), bob);
        em.flush();
        assertThat(messageRepo.findById(rBob.getId())).isEmpty();

        // --- Channel admin (alice) can delete someone else's reply (carol's).
        messages.delete(rCarol.getId(), alice);
        em.flush();
        assertThat(messageRepo.findById(rCarol.getId())).isEmpty();

        // --- Non-author non-admin can't delete dave's reply (eve tries).
        assertThatThrownBy(() -> messages.delete(rDave.getId(), eve))
                .isInstanceOf(AccessDeniedException.class);

        // --- After two deletes the thread shows the remaining 3 replies in original order.
        var afterDeletes = messages.threadReplies(parent.getId(), eve);
        assertThat(afterDeletes).extracting(m -> m.getId())
                .containsExactly(rDave.getId(), rEve.getId(), rAlice.getId());
        assertThat(messages.threadReplyCount(parent)).isEqualTo(3);

        // --- Unread for the user who never visited: only the parent counts (replies live in the
        // thread panel and are intentionally excluded from the main timeline unread count).
        assertThat(reads.unreadCounts(absent, List.of(room.getId())))
                .containsEntry(room.getId(), 1L);

        // --- Marking the channel read clears unread but leaves mentions and reactions intact.
        reads.markRead(room, alice);
        em.flush();
        assertThat(reads.unreadCounts(alice, List.of(room.getId()))).doesNotContainKey(room.getId());
        assertThat(reads.mentionCounts(alice, List.of(room.getId()))).doesNotContainKey(room.getId());
        // Reactions on dave's reply still reflect the alice 👍.
        assertThat(reactions.groupingsFor(rDave, alice).get(0).count()).isEqualTo(1);
    }

    @Test
    void parentDeleteCascadesEntireFivePersonThread() {
        var alice = newUser("alice");
        var bob   = newUser("bob");
        var carol = newUser("carol");
        var dave  = newUser("dave");
        var eve   = newUser("eve");
        var room  = channels.create("plan-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        for (var u : List.of(bob, carol, dave, eve)) channels.join(room, u);

        var parent = messages.post(room, alice, "kicking off");
        var replies = List.of(
                messages.replyInThread(parent.getId(), bob,   "1"),
                messages.replyInThread(parent.getId(), carol, "2"),
                messages.replyInThread(parent.getId(), dave,  "3"),
                messages.replyInThread(parent.getId(), eve,   "4"),
                messages.replyInThread(parent.getId(), alice, "5"));
        // Spice each reply with a reaction to make sure the cascade gets the dependents too.
        // Authors can't react to their own messages, so swap in bob when alice authored.
        for (var r : replies) {
            var reactor = r.getAuthor().getId().equals(alice.getId()) ? bob : alice;
            reactions.addReaction(r.getId(), reactor, "👀");
        }
        em.flush();

        messages.delete(parent.getId(), alice);
        em.flush();

        assertThat(messageRepo.findById(parent.getId())).isEmpty();
        for (var r : replies) {
            assertThat(messageRepo.findById(r.getId())).isEmpty();
        }
    }
}
