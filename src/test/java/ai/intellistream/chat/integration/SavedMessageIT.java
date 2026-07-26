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
import ai.intellistream.chat.repository.MessageSaveRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.SavedMessageService;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Saved messages: the private, cross-room reading queue.
 *
 * <p>The cases worth writing down are the ones where a save outlives the thing it points at or the
 * access that created it — leaving the channel, the channel being archived, the message being
 * deleted, the channel being destroyed. All four have to end somewhere other than a 500.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class SavedMessageIT {

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
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-saved");
        TestLuceneDirs.register(registry);
    }

    @PersistenceContext EntityManager em;
    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired ConversationService conversations;
    @Autowired SavedMessageService saved;
    @Autowired MessageSaveRepository saveRepo;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-sv-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }

    @Test
    void savingIsPerUserPrivateAndIdempotent() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var msg = messages.post(room, alice, "worth keeping");
        em.flush();

        saved.saveChannelMessage(msg.getId(), bob);
        saved.saveChannelMessage(msg.getId(), bob);
        em.flush();

        assertThat(saved.countFor(bob)).isEqualTo(1);
        // Nobody else's list changed — not even the author's.
        assertThat(saved.countFor(alice)).isZero();
        assertThat(saved.hasSavedChannelMessage(bob, msg.getId())).isTrue();
        assertThat(saved.hasSavedChannelMessage(alice, msg.getId())).isFalse();

        saved.unsaveChannelMessage(msg.getId(), bob);
        em.flush();
        assertThat(saved.countFor(bob)).isZero();
    }

    /**
     * Saving is a read, not a write: a non-member can read a public channel, so a non-member can
     * save from it. Requiring membership would mean joining a channel in order to bookmark
     * something in it.
     */
    @Test
    void aNonMemberCanSaveFromAPublicChannelButNotAPrivateOne() {
        var owner = newUser("owner");
        var outsider = newUser("outsider");
        var open = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, owner);
        var shut = channels.create("s-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);
        var readable = messages.post(open, owner, "public");
        var secret = messages.post(shut, owner, "private");
        em.flush();

        saved.saveChannelMessage(readable.getId(), outsider);
        em.flush();
        assertThat(saved.countFor(outsider)).isEqualTo(1);

        assertThatThrownBy(() -> saved.saveChannelMessage(secret.getId(), outsider))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(saved.countFor(outsider)).isEqualTo(1);
    }

    /** An archived channel is still readable, so its messages are still savable. */
    @Test
    void anArchivedChannelsMessagesCanStillBeSaved() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var msg = messages.post(room, alice, "before the freeze");
        em.flush();
        channels.archive(room, alice);
        em.flush();
        em.clear();

        saved.saveChannelMessage(msg.getId(), alice);
        em.flush();

        var list = saved.listFor(alice, 0, 20);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).readable()).isTrue();
        assertThat(list.get(0).channelArchived()).isTrue();
        assertThat(list.get(0).bodyHtml()).contains("before the freeze");
    }

    /**
     * The headline degradation case. Save from a private channel, then leave it: the row stays so
     * the owner can clear it, the body does not, and nothing throws.
     */
    @Test
    void aSaveSurvivesLeavingTheChannelButStopsShowingItsContents() {
        var owner = newUser("owner");
        var bob = newUser("bob");
        var secret = channels.create("s-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);
        channels.invite(secret, bob, owner);
        var msg = messages.post(secret, owner, "the secret plan");
        em.flush();

        saved.saveChannelMessage(msg.getId(), bob);
        em.flush();
        assertThat(saved.listFor(bob, 0, 20).get(0).readable()).isTrue();

        channels.leave(secret, bob);
        em.flush();
        em.clear();

        var list = saved.listFor(bob, 0, 20);
        assertThat(list).hasSize(1);
        var row = list.get(0);
        assertThat(row.readable()).isFalse();
        assertThat(row.bodyHtml()).isNull();
        assertThat(row.authorUsername()).isNull();
        assertThat(row.url()).isNull();
        // …and the owner can still tidy their own list, which is the whole reason the row stayed.
        saved.unsaveChannelMessage(msg.getId(), bob);
        em.flush();
        assertThat(saved.countFor(bob)).isZero();
    }

    /** Deleting the message takes the save with it, in the database, with no service involved. */
    @Test
    void deletingAMessageDeletesEveryoneSaveOfIt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var msg = messages.post(room, alice, "doomed");
        var kept = messages.post(room, alice, "kept");
        em.flush();
        saved.saveChannelMessage(msg.getId(), bob);
        saved.saveChannelMessage(msg.getId(), carol);
        saved.saveChannelMessage(kept.getId(), bob);
        em.flush();
        assertThat(saveRepo.count()).isEqualTo(3);

        messages.delete(msg.getId(), alice);
        em.flush();
        em.clear();

        assertThat(saved.countFor(bob)).isEqualTo(1);
        assertThat(saved.countFor(carol)).isZero();
        assertThat(saved.hasSavedChannelMessage(bob, kept.getId())).isTrue();
    }

    /** And so does destroying the whole channel. */
    @Test
    void destroyingAChannelTakesItsSavesWithIt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var msg = messages.post(room, alice, "gone soon");
        em.flush();
        saved.saveChannelMessage(msg.getId(), bob);
        em.flush();
        em.clear();
        assertThat(saved.countFor(bob)).isEqualTo(1);

        var doomed = channels.requireById(room.getId());
        Tx.commit();
        AsWorkspaceAdmin.run(() -> channels.destroy(doomed, alice));
        Tx.commit();

        assertThat(saved.countFor(bob)).isZero();
        // And the list renders without a row pointing at nothing.
        assertThat(saved.listFor(bob, 0, 20)).isEmpty();
    }

    /** DMs are savable too — the queue spans rooms, and a DM is the most personal kind of room. */
    @Test
    void aDirectMessageCanBeSavedAndReadsBackWithTheCounterpartsName() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var msg = conversations.post(conv, alice, "the address is 12 Elm Street");
        em.flush();

        saved.saveConversationMessage(msg.getId(), bob);
        em.flush();
        em.clear();

        var list = saved.listFor(bob, 0, 20);
        assertThat(list).hasSize(1);
        var row = list.get(0);
        assertThat(row.kind()).isEqualTo("conversation");
        assertThat(row.readable()).isTrue();
        assertThat(row.conversationId()).isEqualTo(conv.getId());
        assertThat(row.conversationTitle()).isEqualTo(alice.getDisplayName());
        assertThat(row.bodyHtml()).contains("12 Elm Street");
        assertThat(row.url()).isEqualTo("/conversations/" + conv.getId() + "#m=" + msg.getId());
    }

    @Test
    void aNonParticipantCannotSaveSomebodyElsesDirectMessage() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var snoop = newUser("snoop");
        var conv = conversations.directBetween(alice, bob);
        var msg = conversations.post(conv, alice, "between us");
        em.flush();

        assertThatThrownBy(() -> saved.saveConversationMessage(msg.getId(), snoop))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Newest save first, across both kinds, as one list. */
    @Test
    void theListIsOneOrderingAcrossChannelsAndDirectMessages() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var conv = conversations.directBetween(alice, bob);
        var inChannel = messages.post(room, alice, "channel one");
        var inDm = conversations.post(conv, alice, "dm one");
        em.flush();

        saved.saveChannelMessage(inChannel.getId(), bob);
        em.flush();
        saved.saveConversationMessage(inDm.getId(), bob);
        em.flush();
        em.clear();

        var list = saved.listFor(bob, 0, 20);
        assertThat(list).hasSize(2);
        assertThat(list).extracting(r -> r.kind()).containsExactly("conversation", "channel");
    }

    /** The channel page's one lookup: which of this room's messages has the viewer saved. */
    @Test
    void savedIdsInChannelIsScopedToThatChannel() {
        var alice = newUser("alice");
        var here = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var elsewhere = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var mine = messages.post(here, alice, "here");
        var other = messages.post(elsewhere, alice, "elsewhere");
        em.flush();
        saved.saveChannelMessage(mine.getId(), alice);
        saved.saveChannelMessage(other.getId(), alice);
        em.flush();

        assertThat(saved.savedIdsInChannel(alice, here.getId())).containsExactly(mine.getId());
        assertThat(saved.savedIdsAmong(alice, java.util.List.of(mine.getId(), other.getId())))
                .containsExactlyInAnyOrder(mine.getId(), other.getId());
    }

    /**
     * The DM page's counterpart. Saving a DM was enforced and covered from the day saved messages
     * landed; what was missing was the lookup the action row needs on first paint to know which
     * bookmark to draw filled, and therefore the button itself.
     */
    @Test
    void savedIdsInConversationIsScopedToThatConversation() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var here = conversations.directBetween(alice, bob);
        var elsewhere = conversations.createGroup("Elsewhere", alice, java.util.List.of(bob));
        var mine = conversations.post(here, bob, "worth keeping");
        var other = conversations.post(elsewhere, bob, "also worth keeping");
        em.flush();
        saved.saveConversationMessage(mine.getId(), alice);
        saved.saveConversationMessage(other.getId(), alice);
        em.flush();

        assertThat(saved.savedIdsInConversation(alice, here.getId())).containsExactly(mine.getId());
        // Channel and conversation message ids come from independent sequences, so the two lookups
        // must not see each other's rows — a saved DM must never mark a channel message.
        assertThat(saved.savedIdsInChannel(alice, here.getId())).isEmpty();
        // And it is per reader, like every other saved lookup.
        assertThat(saved.savedIdsInConversation(bob, here.getId())).isEmpty();
    }

    /** A thread reply in a DM is a conversation message, so it can be saved like any other. */
    @Test
    void aThreadReplyInAConversationCanBeSaved() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        var parent = conversations.post(conv, bob, "question");
        var reply = conversations.replyInThread(parent.getId(), bob, "answer");
        em.flush();

        saved.saveConversationMessage(reply.getId(), alice);
        em.flush();

        assertThat(saved.savedIdsInConversation(alice, conv.getId())).containsExactly(reply.getId());
    }

    /** A DM with yourself is a conversation, and its notes are savable like any other. */
    @Test
    void aSelfConversationsMessagesCanBeSaved() {
        var solo = newUser("solo");
        var conv = conversations.directBetween(solo, solo);
        var note = conversations.post(conv, solo, "look into the flaky test");
        em.flush();

        saved.saveConversationMessage(note.getId(), solo);
        em.flush();

        assertThat(saved.savedIdsInConversation(solo, conv.getId())).containsExactly(note.getId());
        assertThat(saved.hasSavedConversationMessage(solo, note.getId())).isTrue();
    }
}
