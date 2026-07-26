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

    // ------------------------------------------------------------------------------- helpers

    private User newUser(String name) {
        var unique = name + "-" + System.nanoTime();
        return users.save(new User("kc-" + unique, unique, unique + "@example.test", name));
    }
}
