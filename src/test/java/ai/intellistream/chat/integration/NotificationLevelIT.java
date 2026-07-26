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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.NotificationPreferenceService;
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

import static ai.intellistream.chat.domain.NotificationLevel.ALL;
import static ai.intellistream.chat.domain.NotificationLevel.DEFAULT;
import static ai.intellistream.chat.domain.NotificationLevel.MENTIONS;
import static ai.intellistream.chat.domain.NotificationLevel.NONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-channel notification levels against the real schema.
 *
 * <p>The test this file exists for is {@link #changingTheAccountDefaultMovesOnlyInheritingChannels}.
 * The wrong implementation — copying the account default into the membership when the user joins —
 * passes every other test here and fails that one, because a copied MENTIONS is indistinguishable
 * from a deliberately-chosen MENTIONS and so never moves again.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class NotificationLevelIT {

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
    @Autowired ChannelMemberRepository members;
    @Autowired ChannelService channels;
    @Autowired NotificationPreferenceService notify;
    @Autowired SidebarService sidebar;
    @PersistenceContext EntityManager em;

    /** Unique handles per test method — the class-level rollback doesn't cover the sequence. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String name) {
        var n = name + "-" + SEQ.incrementAndGet();
        return users.save(new User("kc-" + n, n, n + "@example.com", n));
    }

    private Channel newChannel(String name, User owner) {
        return channels.create(name + "-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, owner);
    }

    // ------------------------------------------------------------------------------------------
    // The one that catches copy-at-join-time
    // ------------------------------------------------------------------------------------------

    @Test
    void changingTheAccountDefaultMovesOnlyInheritingChannels() {
        var alice = newUser("alice");
        var inherited = newChannel("inherited", alice);
        var overridden = newChannel("overridden", alice);

        // The override is set to MENTIONS *on purpose* — the same value the account default
        // currently holds. That is what makes this test load-bearing: a copy-at-join-time
        // implementation stores exactly this on both channels and can no longer tell them apart.
        notify.setLevelFor(overridden, alice, MENTIONS);
        em.flush();

        assertThat(notify.levelFor(inherited, alice)).isEqualTo(DEFAULT);
        assertThat(notify.levelFor(overridden, alice)).isEqualTo(MENTIONS);
        assertThat(notify.effectiveLevelFor(inherited, alice)).isEqualTo(MENTIONS);
        assertThat(notify.effectiveLevelFor(overridden, alice)).isEqualTo(MENTIONS);

        notify.setAccountDefault(alice, ALL);
        em.flush();
        em.clear();

        var reread = users.findById(alice.getId()).orElseThrow();
        assertThat(reread.getNotifyDefault()).isEqualTo(ALL);

        var freshInherited = channels.requireById(inherited.getId());
        var freshOverridden = channels.requireById(overridden.getId());

        // Moved: it was never carrying a value of its own.
        assertThat(notify.levelFor(freshInherited, reread)).isEqualTo(DEFAULT);
        assertThat(notify.effectiveLevelFor(freshInherited, reread)).isEqualTo(ALL);

        // Did not move: it was explicitly set, and MENTIONS is still what the user asked for.
        assertThat(notify.levelFor(freshOverridden, reread)).isEqualTo(MENTIONS);
        assertThat(notify.effectiveLevelFor(freshOverridden, reread)).isEqualTo(MENTIONS);
    }

    @Test
    void aMutedChannelStaysMutedWhenTheAccountDefaultChanges() {
        var alice = newUser("alice");
        var quiet = newChannel("quiet", alice);

        notify.setLevelFor(quiet, alice, NONE);
        em.flush();

        for (var level : new NotificationLevel[]{ALL, MENTIONS, NONE}) {
            notify.setAccountDefault(alice, level);
            em.flush();
            em.clear();

            var reread = users.findById(alice.getId()).orElseThrow();
            var fresh = channels.requireById(quiet.getId());
            assertThat(notify.levelFor(fresh, reread)).isEqualTo(NONE);
            assertThat(notify.effectiveLevelFor(fresh, reread)).isEqualTo(NONE);
        }
    }

    @Test
    void settingDefaultOnAChannelPutsItBackToFollowingTheAccount() {
        var alice = newUser("alice");
        var channel = newChannel("chatty", alice);

        notify.setLevelFor(channel, alice, NONE);
        notify.setAccountDefault(alice, ALL);
        em.flush();
        em.clear();

        var reread = users.findById(alice.getId()).orElseThrow();
        var fresh = channels.requireById(channel.getId());
        assertThat(notify.effectiveLevelFor(fresh, reread)).isEqualTo(NONE);

        notify.setLevelFor(fresh, reread, DEFAULT);
        em.flush();
        em.clear();

        var again = users.findById(alice.getId()).orElseThrow();
        var channelAgain = channels.requireById(channel.getId());
        assertThat(notify.levelFor(channelAgain, again)).isEqualTo(DEFAULT);
        assertThat(notify.effectiveLevelFor(channelAgain, again)).isEqualTo(ALL);
    }

    // ------------------------------------------------------------------------------------------
    // Migration compatibility
    // ------------------------------------------------------------------------------------------

    /**
     * A user row and a membership row written without ever naming the new columns — which is
     * exactly the shape every row had before V7 ran, and which {@code ADD COLUMN … NOT NULL
     * DEFAULT} backfilled. Both must read as today's behaviour: MENTIONS, inherited.
     */
    @Test
    void rowsThatPredateTheMigrationBehaveAsMentions() {
        var subject = "kc-legacy-" + SEQ.incrementAndGet();
        em.createNativeQuery("""
                        insert into users (subject, username, email, display_name)
                        values (:subject, :username, :email, :displayName)
                        """)
                .setParameter("subject", subject)
                .setParameter("username", subject)
                .setParameter("email", subject + "@example.com")
                .setParameter("displayName", subject)
                .executeUpdate();
        var legacy = users.findBySubject(subject).orElseThrow();
        assertThat(legacy.getNotifyDefault())
                .as("a users row written before V7 keeps today's behaviour")
                .isEqualTo(MENTIONS);

        var owner = newUser("owner");
        var channel = newChannel("legacy-home", owner);

        // Not memberRepository.save(...): a JPA insert names notify_level explicitly and would
        // prove nothing. This is the raw INSERT shape, the same one ChannelMemberRepository
        // .insertMemberIgnore uses on the join/invite path, so the column default is what fills in.
        em.createNativeQuery("""
                        insert into channel_members (channel_id, user_id, role)
                        values (:channelId, :userId, 'MEMBER')
                        """)
                .setParameter("channelId", channel.getId())
                .setParameter("userId", legacy.getId())
                .executeUpdate();
        em.clear();

        var freshChannel = channels.requireById(channel.getId());
        var freshLegacy = users.findBySubject(subject).orElseThrow();
        assertThat(notify.levelFor(freshChannel, freshLegacy)).isEqualTo(DEFAULT);
        assertThat(notify.effectiveLevelFor(freshChannel, freshLegacy)).isEqualTo(MENTIONS);
        assertThat(members.findByChannelAndUser(freshChannel, freshLegacy).orElseThrow()
                .followsAccountDefault()).isTrue();
    }

    /** The ordinary join path goes through the same native INSERT, so it must inherit too. */
    @Test
    void joiningAChannelInheritsRatherThanSnapshotting() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var channel = newChannel("open", alice);

        notify.setAccountDefault(bob, ALL);
        em.flush();

        channels.join(channel, bob);
        em.flush();
        em.clear();

        var freshBob = users.findById(bob.getId()).orElseThrow();
        var freshChannel = channels.requireById(channel.getId());
        assertThat(notify.levelFor(freshChannel, freshBob))
                .as("join must store the inheritance, not a copy of the current default")
                .isEqualTo(DEFAULT);
        assertThat(notify.effectiveLevelFor(freshChannel, freshBob)).isEqualTo(ALL);
    }

    // ------------------------------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------------------------------

    /**
     * A non-member can neither read nor write, even for a PUBLIC channel whose messages they are
     * allowed to read. The preference is a fact about the person, not about the channel.
     */
    @Test
    void nonMemberCanNeitherReadNorWriteAChannelLevel() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var channel = newChannel("public-room", alice);
        assertThat(channels.isMember(channel, bob)).isFalse();

        assertThatThrownBy(() -> notify.levelFor(channel, bob))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> notify.effectiveLevelFor(channel, bob))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> notify.setLevelFor(channel, bob, NONE))
                .isInstanceOf(AccessDeniedException.class);

        // And nothing was written on the way to being refused.
        assertThat(members.findByChannelAndUser(channel, bob)).isEmpty();
    }

    @Test
    void theAccountDefaultRefusesInherit() {
        var alice = newUser("alice");
        assertThatThrownBy(() -> notify.setAccountDefault(alice, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        em.clear();
        assertThat(users.findById(alice.getId()).orElseThrow().getNotifyDefault()).isEqualTo(MENTIONS);
    }

    // ------------------------------------------------------------------------------------------
    // Page payload
    // ------------------------------------------------------------------------------------------

    /** The channel page must not need one request per channel to render the picker. */
    @Test
    void theSidebarCarriesRawLevelsAndTheAccountDefault() {
        var alice = newUser("alice");
        var muted = newChannel("muted", alice);
        var inheriting = newChannel("inheriting", alice);

        notify.setLevelFor(muted, alice, NONE);
        notify.setAccountDefault(alice, ALL);
        em.flush();
        em.clear();

        var reread = users.findById(alice.getId()).orElseThrow();
        var view = sidebar.joinedFor(reread);

        assertThat(view.notifyDefault()).isEqualTo(ALL);

        var rows = view.channels();
        assertThat(rows)
                .filteredOn(r -> r.id().equals(muted.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.notifyLevel()).isEqualTo(NONE));
        assertThat(rows)
                .filteredOn(r -> r.id().equals(inheriting.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.notifyLevel())
                        .as("raw, not resolved — the picker needs to show \"Default\"")
                        .isEqualTo(DEFAULT));

        // The channel search results carry the same raw level.
        assertThat(sidebar.search(reread, muted.getName(), 10))
                .filteredOn(r -> r.id().equals(muted.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.notifyLevel()).isEqualTo(NONE));
    }
}
