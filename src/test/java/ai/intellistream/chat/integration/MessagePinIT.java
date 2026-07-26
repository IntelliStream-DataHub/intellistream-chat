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
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MessageService;
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
 * Pinning a message to its channel: who may do it, who may see it, and what happens to a pin when
 * the thing it points at goes away.
 *
 * <p>The pin itself is a column pair on {@code messages} rather than a table of its own, which is
 * the whole reason the "dangling pin" question has a boring answer — but boring answers stop being
 * true when somebody adds a table later, so the cascade cases are asserted here rather than
 * assumed.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class MessagePinIT {

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
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-message-pin");
        TestLuceneDirs.register(registry);
    }

    @PersistenceContext EntityManager em;
    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired MessageRepository messageRepo;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-pin-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }

    /**
     * The headline decision: any member, not only a channel admin. This is a widening of what
     * {@code MessageService.pin} used to require, so the assertion is deliberately made by a plain
     * member of somebody else's channel.
     */
    @Test
    void anyMemberCanPinAndUnpin() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        assertThat(channels.isAdmin(room, bob)).isFalse();
        var msg = messages.post(room, alice, "read this first");
        em.flush();

        messages.pin(msg.getId(), bob);
        em.flush();
        em.clear();

        var pins = messages.pinned(channels.requireById(room.getId()), bob);
        assertThat(pins).extracting(m -> m.getId()).containsExactly(msg.getId());
        assertThat(pins.get(0).getPinnedBy().getUsername()).isEqualTo(bob.getUsername());

        // And whoever may pin may unpin, including somebody else's pin.
        messages.unpin(msg.getId(), alice);
        em.flush();
        em.clear();
        assertThat(messages.pinned(channels.requireById(room.getId()), alice)).isEmpty();
    }

    /**
     * Pinning is a write, so a non-member is refused even in a PUBLIC channel they can read. Same
     * rule as posting: read the room, then join it before you change what it says.
     */
    @Test
    void aNonMemberCannotPinEvenInAPublicChannel() {
        var alice = newUser("alice");
        var outsider = newUser("outsider");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var msg = messages.post(room, alice, "hello");
        em.flush();

        assertThatThrownBy(() -> messages.pin(msg.getId(), outsider))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Reading the pins is the read check, so a public channel's pins are visible before joining. */
    @Test
    void pinsAreVisibleToAnyoneWhoCanReadTheChannel() {
        var alice = newUser("alice");
        var outsider = newUser("outsider");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var msg = messages.post(room, alice, "start here");
        messages.pin(msg.getId(), alice);
        em.flush();

        assertThat(messages.pinned(room, outsider)).extracting(m -> m.getId())
                .containsExactly(msg.getId());
        assertThat(messages.pinnedCount(room, outsider)).isEqualTo(1);
    }

    /** …and a private channel's are not. */
    @Test
    void aPrivateChannelsPinsAreNotReadableByOutsiders() {
        var owner = newUser("owner");
        var snoop = newUser("snoop");
        var secret = channels.create("s-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);
        var msg = messages.post(secret, owner, "internal");
        messages.pin(msg.getId(), owner);
        em.flush();

        assertThatThrownBy(() -> messages.pinned(secret, snoop))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** An archived channel is a record. Re-curating a record needs it taken out of the archive. */
    @Test
    void anArchivedChannelRefusesPinningAndUnpinning() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var pinned = messages.post(room, alice, "already pinned");
        var loose = messages.post(room, alice, "not pinned");
        messages.pin(pinned.getId(), alice);
        em.flush();

        channels.archive(room, alice);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> messages.pin(loose.getId(), alice))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> messages.unpin(pinned.getId(), alice))
                .isInstanceOf(AccessDeniedException.class);
        // Reading is untouched — an archived channel keeps its pins and keeps showing them.
        assertThat(messages.pinned(channels.requireById(room.getId()), alice)).hasSize(1);
    }

    /** Editing the message it points at does not disturb the pin. */
    @Test
    void aPinSurvivesAnEditOfTheMessage() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var msg = messages.post(room, alice, "first draft");
        messages.pin(msg.getId(), alice);
        em.flush();

        messages.edit(msg.getId(), alice, "second draft");
        em.flush();
        em.clear();

        var pins = messages.pinned(channels.requireById(room.getId()), alice);
        assertThat(pins).hasSize(1);
        assertThat(pins.get(0).getBodyMarkdown()).isEqualTo("second draft");
        assertThat(pins.get(0).getPinnedAt()).isNotNull();
    }

    /** Deleting it does. There is no pin left pointing at a message that no longer exists. */
    @Test
    void deletingAPinnedMessageLeavesNoDanglingPin() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var kept = messages.post(room, alice, "kept");
        var doomed = messages.post(room, alice, "doomed");
        messages.pin(kept.getId(), alice);
        messages.pin(doomed.getId(), alice);
        em.flush();
        assertThat(messages.pinnedCount(room, alice)).isEqualTo(2);

        messages.delete(doomed.getId(), alice);
        em.flush();
        em.clear();

        var live = channels.requireById(room.getId());
        assertThat(messages.pinned(live, alice)).extracting(m -> m.getId()).containsExactly(kept.getId());
        assertThat(messages.pinnedCount(live, alice)).isEqualTo(1);
        assertThat(messageRepo.findById(doomed.getId())).isEmpty();
    }

    /**
     * A moderator's soft delete hides the message from every read path, and the pin list is a read
     * path. A pin pointing at a message nobody can see is a pin nobody can act on.
     */
    @Test
    void aSoftDeletedMessageDropsOutOfThePinList() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var msg = messages.post(room, alice, "pinned then removed");
        messages.pin(msg.getId(), alice);
        em.flush();

        messageRepo.softDeleteByIds(java.util.List.of(msg.getId()), java.time.Instant.now(), alice);
        em.flush();
        em.clear();

        var live = channels.requireById(room.getId());
        assertThat(messages.pinned(live, alice)).isEmpty();
        assertThat(messages.pinnedCount(live, alice)).isZero();
    }

    /** Destroying the channel takes the messages, and the pins are on the messages. */
    @Test
    void destroyingAChannelTakesItsPinsWithIt() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var msg = messages.post(room, alice, "pinned");
        messages.pin(msg.getId(), alice);
        em.flush();
        em.clear();
        var messageId = msg.getId();
        var channelId = room.getId();

        // Tx.commit around destroy, as ChannelLifecycleIT does: the delete cascades in the database
        // and the post-commit hooks (index purge, file reap, cache eviction) only fire on a real
        // commit, so asserting inside the rolled-back test transaction would prove nothing.
        var doomed = channels.requireById(channelId);
        Tx.commit();
        AsWorkspaceAdmin.run(() -> channels.destroy(doomed, alice));
        Tx.commit();

        assertThat(messageRepo.findById(messageId)).isEmpty();
    }

    /**
     * A thread reply cannot be pinned. The pins panel jumps into the channel feed and a reply is
     * not in it, so a pinned reply would be a list entry whose only action does not work.
     */
    @Test
    void aThreadReplyCannotBePinned() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var parent = messages.post(room, alice, "the question");
        var reply = messages.replyInThread(parent.getId(), alice, "the answer");
        em.flush();

        assertThatThrownBy(() -> messages.pin(reply.getId(), alice))
                .isInstanceOf(PublicBadRequestException.class);
        assertThat(messages.pinnedCount(room, alice)).isZero();
    }

    /** Pinning twice is not two pins, and the list stays newest-pinned-first. */
    @Test
    void pinsAreOrderedNewestFirstAndPinningIsIdempotentEnough() {
        var alice = newUser("alice");
        var room = channels.create("r-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var first = messages.post(room, alice, "one");
        var second = messages.post(room, alice, "two");
        em.flush();

        messages.pin(first.getId(), alice);
        em.flush();
        messages.pin(second.getId(), alice);
        messages.pin(second.getId(), alice);
        em.flush();
        em.clear();

        var live = channels.requireById(room.getId());
        assertThat(messages.pinnedCount(live, alice)).isEqualTo(2);
        assertThat(messages.pinned(live, alice)).extracting(m -> m.getId())
                .containsExactly(second.getId(), first.getId());
    }
}
