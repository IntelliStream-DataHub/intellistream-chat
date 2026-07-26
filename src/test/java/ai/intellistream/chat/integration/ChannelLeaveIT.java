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

import ai.intellistream.chat.domain.ChannelRole;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelAccessCache;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.SidebarService;
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
 * Leaving a channel, and the edge rules that decide whether the channel survives it.
 *
 * <p>Membership used to be add-only, which two other things quietly depended on:
 * {@code ChannelAccessCache} (only positive decisions are cached, so a cached "yes" could never
 * become wrong) and the last-admin invariant ({@code demote} guards it, and leaving did not exist to
 * be guarded). Both are asserted here.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class ChannelLeaveIT {

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

    private static final AtomicInteger SEQ = new AtomicInteger();

    @PersistenceContext EntityManager em;
    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired MessageRepository messageRepo;
    @Autowired ChannelMemberRepository members;
    @Autowired SidebarService sidebar;
    @Autowired ChannelAccessCache accessCache;

    private User newUser(String name) {
        var unique = name + "-" + SEQ.incrementAndGet();
        return users.save(new User("kc-" + unique, unique, unique + "@example.com", name));
    }

    @Test
    void leavingRemovesMembershipAndTheSidebarRow() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("leave-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        em.flush();

        channels.leave(room, bob);
        em.flush();
        em.clear();

        assertThat(members.findByChannelAndUser(room, users.findById(bob.getId()).orElseThrow()))
                .isEmpty();
        assertThat(channels.isMember(room, bob)).isFalse();
        assertThat(sidebar.joinedFor(users.findById(bob.getId()).orElseThrow()).channels())
                .extracting("id").doesNotContain(room.getId());
        // And with the membership gone, writing is refused again.
        assertThatThrownBy(() -> messages.post(room, bob, "still here?"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void yourMessagesStayBehind() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("stay-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var posted = messages.post(room, bob, "here is the runbook");
        em.flush();

        channels.leave(room, bob);
        em.flush();

        // You are leaving, not deleting. A departure that took the channel's history with it would
        // make leaving unusable for anyone who had ever been useful in it.
        assertThat(messageRepo.findById(posted.getId())).isPresent();
        assertThat(messages.recent(room, alice, 10)).extracting("id").contains(posted.getId());
    }

    @Test
    void anAdminCanRemoveAnotherMember() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("kick-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);
        em.flush();

        channels.removeMember(room, bob, alice);
        em.flush();

        assertThat(channels.isMember(room, bob)).isFalse();
        assertThat(channels.isMember(room, alice)).isTrue();
    }

    @Test
    void aPlainMemberCannotRemoveAnyoneElse() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("nokick-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.join(room, carol);
        em.flush();

        assertThatThrownBy(() -> channels.removeMember(room, carol, bob))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(channels.isMember(room, carol)).isTrue();
    }

    @Test
    void removingYourselfThroughTheAdminPathIsJustLeaving() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("selfkick-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        em.flush();

        // Bob is not an admin. DELETE /members/{bob} plainly means "take me out", so it must not
        // demand the admin role bob would never have.
        channels.removeMember(room, bob, bob);
        em.flush();

        assertThat(channels.isMember(room, bob)).isFalse();
    }

    @Test
    void leavingAChannelYouAreNotInIsNotFound() {
        var alice = newUser("alice");
        var stranger = newUser("stranger");
        var room = channels.create("absent-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        em.flush();

        assertThatThrownBy(() -> channels.leave(room, stranger))
                .isInstanceOf(ai.intellistream.chat.security.ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------------------------------
    // The last admin. Refusing traps the one person who took responsibility for the channel;
    // allowing it bare leaves a channel nobody can ever manage again.
    // ------------------------------------------------------------------------------------------

    @Test
    void theLastAdminLeavingHandsOverToTheLongestStandingMember() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("succ-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);
        em.flush();
        channels.invite(room, carol, alice);
        em.flush();

        channels.leave(room, alice);
        em.flush();
        em.clear();

        var reread = users.findById(bob.getId()).orElseThrow();
        assertThat(members.findByChannelAndUser(room, reread).orElseThrow().getRole())
                .describedAs("bob was invited first")
                .isEqualTo(ChannelRole.ADMIN);
        assertThat(members.findByChannelAndUser(room, users.findById(carol.getId()).orElseThrow())
                .orElseThrow().getRole()).isEqualTo(ChannelRole.MEMBER);
        // The channel is manageable again by somebody, which is the whole point.
        assertThat(channels.isAdmin(room, reread)).isTrue();
    }

    @Test
    void anAdminLeavingWhileAnotherRemainsPromotesNobody() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("twoadm-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.join(room, carol);
        channels.promote(room, bob, alice);
        em.flush();

        channels.leave(room, alice);
        em.flush();
        em.clear();

        assertThat(members.findByChannelAndUser(room, users.findById(carol.getId()).orElseThrow())
                .orElseThrow().getRole())
                .describedAs("bob is still an admin, so nothing needs handing over")
                .isEqualTo(ChannelRole.MEMBER);
    }

    @Test
    void theLastMemberLeavingEmptiesTheChannelWithoutDestroyingIt() {
        var alice = newUser("alice");
        var room = channels.create("empty-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        var posted = messages.post(room, alice, "closing this out");
        em.flush();

        channels.leave(room, alice);
        em.flush();
        em.clear();

        assertThat(members.countByChannel(room)).isZero();
        assertThat(messageRepo.findById(posted.getId()))
                .describedAs("an empty channel still holds its history")
                .isPresent();
    }

    @Test
    void theFirstPersonBackIntoAnEmptyChannelBecomesItsAdmin() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("revive-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        channels.leave(room, alice);
        em.flush();
        em.clear();

        var membership = channels.join(room, users.findById(bob.getId()).orElseThrow());
        em.flush();

        // An empty channel has no admin and nothing that could promote one, so it would be
        // permanently unmanageable — no invites, no deletion, no way back. The first person in takes
        // it on, exactly as the creator did.
        assertThat(membership.getRole()).isEqualTo(ChannelRole.ADMIN);
    }

    @Test
    void joiningAChannelThatAlreadyHasAnAdminDoesNotPromoteYou() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("normal-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        em.flush();

        assertThat(channels.join(room, bob).getRole()).isEqualTo(ChannelRole.MEMBER);
    }

    // ------------------------------------------------------------------------------------------
    // ChannelAccessCache. "Membership is add-only" was one of the two invariants it rested on, and
    // this feature is what breaks it.
    // ------------------------------------------------------------------------------------------

    @Test
    void leavingEvictsTheCachedWriteAccessDecision() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("cache-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);
        em.flush();

        // Warm it the way the message send path does: a verified positive, then served from memory.
        channels.requireWriteAccessCached(room, bob);
        assertThat(accessCache.hasWriteAccess(room.getId(), bob.getId())).isTrue();

        channels.leave(room, bob);
        // The eviction is registered as an afterCommit hook, so it needs a real commit to run —
        // the class-level @Transactional otherwise rolls this test back without ever firing it.
        Tx.commit();

        assertThat(accessCache.hasWriteAccess(room.getId(), bob.getId()))
                .describedAs("a cached yes must not outlive the membership it was about")
                .isFalse();
        // And the uncached check now refuses, which is what the cache was standing in front of.
        assertThatThrownBy(() -> channels.requireWriteAccessCached(room, bob))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void removingAMemberEvictsTheirCachedDecisionToo() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("kickcache-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);
        em.flush();
        channels.requireWriteAccessCached(room, bob);

        channels.removeMember(room, bob, alice);
        Tx.commit();

        assertThat(accessCache.hasWriteAccess(room.getId(), bob.getId())).isFalse();
    }

    @Test
    void oneMemberLeavingDoesNotEvictAnother() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("keepcache-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);
        em.flush();
        channels.requireWriteAccessCached(room, alice);
        channels.requireWriteAccessCached(room, bob);

        channels.removeMember(room, bob, alice);
        Tx.commit();

        assertThat(accessCache.hasWriteAccess(room.getId(), alice.getId()))
                .describedAs("evictMember is per member, not per channel")
                .isTrue();
    }
}
