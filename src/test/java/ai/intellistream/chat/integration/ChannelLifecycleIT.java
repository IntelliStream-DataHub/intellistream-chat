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
import ai.intellistream.chat.repository.ChannelRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelAccessCache;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.SidebarService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A channel after creation: renamed, re-described — and, in later commits on this branch, archived,
 * unarchived and destroyed.
 *
 * <p>Everything here exists because {@code Channel} has no setters and must not gain any. The
 * mutations go through bulk UPDATEs, which is the only reason {@code ChannelImmutabilityTest} can
 * still assert what it asserts, and a bulk UPDATE is exactly the kind of write that silently does
 * nothing if the query drifts — the persistence context happily keeps serving the old row. So these
 * assertions re-read through {@code EntityManager.clear()} or a fresh transaction rather than
 * trusting the instance the service handed back.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class ChannelLifecycleIT {

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

    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;
    @Autowired UserRepository users;
    @Autowired ChannelRepository channelRepository;
    @Autowired ChannelService channels;
    @Autowired ai.intellistream.chat.service.MessageService messages;
    @Autowired ai.intellistream.chat.service.ReactionService reactions;
    @Autowired ai.intellistream.chat.service.SearchService search;
    @Autowired SidebarService sidebar;
    @Autowired ChannelAccessCache accessCache;

    // ------------------------------------------------------------------ rename / re-describe

    @Test
    void renamingAChannelMovesItsSlug() {
        var alice = newUser("alice");
        var room = channels.create("Q3 Planning", "the quarter", ChannelType.PUBLIC, alice);
        assertThat(room.getSlug()).isEqualTo("q3-planning");

        channels.rename(room, "Project Sequoia", "the one after the quarter", alice);
        em.flush();
        em.clear();

        var reread = channels.requireById(room.getId());
        assertThat(reread.getName()).isEqualTo("Project Sequoia");
        assertThat(reread.getSlug()).isEqualTo("project-sequoia");
        assertThat(reread.getDescription()).isEqualTo("the one after the quarter");
        // The old slug stops resolving, which is the honest consequence of the slug tracking the
        // name. Nothing user-facing routes by slug — pages and API calls are all /channels/{id} —
        // so this breaks no link; the id route below is the one that has to keep working.
        assertThatThrownBy(() -> channels.requireBySlug("q3-planning"))
                .isInstanceOf(ai.intellistream.chat.security.ResourceNotFoundException.class);
        assertThat(channels.requireBySlug("project-sequoia").getId()).isEqualTo(room.getId());
        assertThat(channels.requireById(room.getId()).getId()).isEqualTo(room.getId());
    }

    @Test
    void aRenameEvictsTheCachedChannelSoTheHotPathStopsSeeingTheOldName() {
        var alice = newUser("alice");
        var room = channels.create("Before", null, ChannelType.PUBLIC, alice);
        Tx.commit();

        // Warm the cache the way the message send path does, then rename through the service.
        assertThat(channels.requireByIdForMessaging(room.getId()).getName()).isEqualTo("Before");
        channels.rename(room, "After", null, alice);
        Tx.commit();   // eviction is registered afterCommit, like every other cache/index hook here

        // Without evictChannel this still says "Before" for up to the 60s TTL. Cosmetic for a name
        // and an authorization bypass for a type flip — which is why the entity has no setters and
        // every mutation is required to evict.
        assertThat(channels.requireByIdForMessaging(room.getId()).getName()).isEqualTo("After");
        accessCache.clear();
    }

    @Test
    void aRenameCannotStealAnotherChannelsSlug() {
        var alice = newUser("alice");
        channels.create("Deploys", null, ChannelType.PUBLIC, alice);
        var other = channels.create("Incidents", null, ChannelType.PUBLIC, alice);

        assertThatThrownBy(() -> channels.rename(other, "deploys", null, alice))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearingTheDescriptionStoresNullRatherThanAnEmptyString() {
        var alice = newUser("alice");
        var room = channels.create("Purpose", "had one", ChannelType.PUBLIC, alice);

        channels.rename(room, "Purpose", "", alice);
        em.flush();
        em.clear();

        assertThat(channels.requireById(room.getId()).getDescription()).isNull();
    }

    @Test
    void onlyAChannelAdminCanRename() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Shared", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        assertThatThrownBy(() -> channels.rename(room, "Bobs Room", null, bob))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(channels.requireById(room.getId()).getName()).isEqualTo("Shared");
    }

    @Test
    void aRenamedChannelKeepsItsSidebarRowAndItsMembers() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Old Name", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        channels.rename(room, "New Name", null, alice);
        em.flush();
        em.clear();

        // Membership, favourites and read state all key on the channel id, so a rename touches none
        // of them — asserted because a rename implemented as delete-and-recreate would pass every
        // other test in this class and lose all three.
        var bobsSidebar = sidebar.joinedFor(users.findById(bob.getId()).orElseThrow());
        assertThat(bobsSidebar.channels()).extracting("id", "name")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(room.getId(), "New Name"));
    }

    // ------------------------------------------------------------------------------ archiving

    /**
     * The forgotten-call-site test. Every write an archived channel must refuse, exercised one at a
     * time, because "we put the check in {@code requireWriteAccess} and everything goes through
     * {@code requireWriteAccess}" is a claim about ten call sites in five classes and is exactly the
     * kind of claim that is true when written and false two features later.
     */
    @Test
    void anArchivedChannelRefusesEveryWrite() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var outsider = newUser("outsider");
        var room = channels.create("Freeze", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        var existing = messages.post(room, alice, "before the freeze");
        var parent = messages.post(room, alice, "a thread starter");
        em.flush();

        channels.archive(room, alice);
        em.flush();
        em.clear();
        var frozen = channels.requireById(room.getId());

        // Posting — both the transactional path and the buffered WebSocket one.
        assertThatThrownBy(() -> messages.post(frozen, alice, "after"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> messages.postWithMentions(frozen, alice, "after @bob"))
                .isInstanceOf(AccessDeniedException.class);
        // Replying in a thread.
        assertThatThrownBy(() -> messages.replyInThread(parent.getId(), alice, "reply"))
                .isInstanceOf(AccessDeniedException.class);
        // Editing an existing message.
        assertThatThrownBy(() -> messages.edit(existing.getId(), alice, "rewritten"))
                .isInstanceOf(AccessDeniedException.class);
        // Reacting.
        assertThatThrownBy(() -> reactions.addReaction(existing.getId(), alice, "\uD83D\uDC4D"))
                .isInstanceOf(AccessDeniedException.class);
        // Inviting — a member's write, which is why it goes through requireWriteAccess and not
        // requireAdmin.
        assertThatThrownBy(() -> channels.invite(frozen, outsider, alice))
                .isInstanceOf(AccessDeniedException.class);
        // Joining, which is the one write that cannot go through requireWriteAccess at all: the
        // whole point is that you are not a member yet, so ChannelService.join checks by hand.
        assertThatThrownBy(() -> channels.join(frozen, outsider))
                .isInstanceOf(AccessDeniedException.class);
        // Renaming. An archive is a record, and relabelling a record is not something it should let
        // you do without taking it out of the archive first.
        assertThatThrownBy(() -> channels.rename(frozen, "Thawed", null, alice))
                .isInstanceOf(AccessDeniedException.class);
        // The cached write check, which is the one that would silently keep working: it short-
        // circuits on a remembered positive, so the archived test has to run BEFORE that lookup.
        accessCache.rememberWriteAccess(room.getId(), alice.getId());
        assertThatThrownBy(() -> channels.requireWriteAccessCached(frozen, alice))
                .isInstanceOf(AccessDeniedException.class);
        accessCache.clear();
    }

    @Test
    void anArchivedChannelIsStillReadableAndStillIndexed() {
        var alice = newUser("alice");
        var outsider = newUser("outsider");
        var room = channels.create("Readable when done", null, ChannelType.PUBLIC, alice);
        messages.post(room, alice, "the decision was to ship on Friday");
        Tx.commit();

        channels.archive(room, alice);
        Tx.commit();

        var frozen = channels.requireById(room.getId());
        // Reading is untouched — requireMember never learns about archiving. This is the entire
        // difference between archiving and deleting, and it holds for a non-member too, because a
        // PUBLIC channel's history is public whether or not the channel is finished.
        assertThat(messages.recent(frozen, outsider, 50)).hasSize(1);
        // Still searchable: the index is not touched by archiving, so the history stays findable —
        // which is also the route back to an archived channel, since it is out of the sidebar and
        // out of channel search.
        assertThat(search.searchChannel(frozen, outsider, "friday", 10)).hasSize(1);
        assertThat(search.searchAccessible(alice, "friday", 10)).hasSize(1);
    }

    @Test
    void archivingTakesTheChannelOutOfTheSidebarAndOutOfDiscovery() {
        var alice = newUser("alice");
        var room = channels.create("Retiring Soon", null, ChannelType.PUBLIC, alice);
        var live = channels.create("Still Going", null, ChannelType.PUBLIC, alice);

        channels.archive(room, alice);
        em.flush();
        em.clear();
        var reread = users.findById(alice.getId()).orElseThrow();

        assertThat(sidebar.joinedFor(reread).channels()).extracting("name")
                .containsExactly("Still Going");
        assertThat(channels.listPublic()).extracting("name").doesNotContain("Retiring Soon");
        assertThat(sidebar.search(reread, "Retiring", 25)).isEmpty();
        // But the id route still resolves it, which is what the archived banner's Unarchive button
        // and every permalink into its history depend on.
        assertThat(channels.requireById(room.getId()).isArchived()).isTrue();
        assertThat(channels.listArchived()).extracting("id").contains(room.getId());
        // The membership row survives — the sidebar query hides it, nothing deletes it — so the
        // star, the notification level and the read marker are all still there to come back to.
        assertThat(channels.isMember(room, reread)).isTrue();
    }

    @Test
    void unarchivingRestoresEveryWritePathAndTheSidebarRow() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Back From The Dead", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.setFavourite(room, bob, true);
        channels.archive(room, alice);
        em.flush();
        em.clear();

        channels.unarchive(channels.requireById(room.getId()), alice);
        em.flush();
        em.clear();

        var thawed = channels.requireById(room.getId());
        assertThat(thawed.isArchived()).isFalse();
        // The tombstone is cleared completely, not half-cleared: a stale archived_by behind a null
        // archived_at is what makes a later "who archived this?" answer confidently wrong.
        assertThat(thawed.getArchivedAt()).isNull();
        assertThat(thawed.getArchivedByUsername()).isNull();

        messages.post(thawed, alice, "we are back");
        assertThat(messages.recent(thawed, alice, 10)).hasSize(1);

        // Bob's row comes back with his star on it. Nothing was restored because nothing was
        // deleted — the sidebar query was simply hiding it.
        var bobsSidebar = sidebar.joinedFor(users.findById(bob.getId()).orElseThrow());
        assertThat(bobsSidebar.favourites()).extracting("name").containsExactly("Back From The Dead");
    }

    @Test
    void archivingEvictsTheCachedChannelSoTheSendPathStopsAcceptingMessages() {
        var alice = newUser("alice");
        var room = channels.create("Hot Path", null, ChannelType.PUBLIC, alice);
        Tx.commit();

        // Warm both halves of the cache the way the message send path does: the channel instance and
        // the "alice may write here" decision.
        var cached = channels.requireByIdForMessaging(room.getId());
        channels.requireWriteAccessCached(cached, alice);

        channels.archive(channels.requireById(room.getId()), alice);
        Tx.commit();

        // Without evictChannel this passes for up to the 60s TTL and the channel keeps taking
        // messages after being archived — the same failure the cache's own docs describe for a
        // PUBLIC->PRIVATE flip, which is why Channel has no setters.
        assertThatThrownBy(() -> channels.requireWriteAccessCached(
                channels.requireByIdForMessaging(room.getId()), alice))
                .isInstanceOf(AccessDeniedException.class);
        accessCache.clear();
    }

    @Test
    void archivingIsIdempotentAndRecordsWhoDidIt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Twice", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.promote(room, bob, alice);

        channels.archive(room, alice);
        em.flush();
        em.clear();
        var first = channels.requireById(room.getId());
        var firstAt = first.getArchivedAt();
        assertThat(first.getArchivedByUsername()).isEqualTo(alice.getUsername());

        // A second admin clicking on a channel that is already archived gets a no-op, not an error
        // — and does not overwrite the record of who actually archived it.
        channels.archive(first, bob);
        em.flush();
        em.clear();
        var second = channels.requireById(room.getId());
        assertThat(second.getArchivedByUsername()).isEqualTo(alice.getUsername());
        assertThat(second.getArchivedAt()).isEqualTo(firstAt);

        // …and unarchiving something that is already live is a no-op too.
        channels.unarchive(channels.requireById(room.getId()), alice);
        channels.unarchive(channels.requireById(room.getId()), alice);
        assertThat(channels.requireById(room.getId()).isArchived()).isFalse();
    }

    @Test
    void onlyAChannelAdminCanArchiveOrUnarchive() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Not Yours", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        assertThatThrownBy(() -> channels.archive(room, bob))
                .isInstanceOf(AccessDeniedException.class);

        channels.archive(room, alice);
        em.flush();
        em.clear();
        var frozen = channels.requireById(room.getId());
        assertThatThrownBy(() -> channels.unarchive(frozen, bob))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(channels.requireById(room.getId()).isArchived()).isTrue();
    }

    // ------------------------------------------------------------------------------- helpers

    private User newUser(String name) {
        var unique = name + "-" + System.nanoTime();
        return users.save(new User("kc-" + unique, unique, unique + "@example.test", name));
    }
}
