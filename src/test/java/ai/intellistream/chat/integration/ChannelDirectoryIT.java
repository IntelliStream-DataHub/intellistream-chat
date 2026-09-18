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
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.SidebarService;
import ai.intellistream.chat.web.dto.ChannelBrowseDto;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The channel directory and the first-login suggestions: a population ranking over live public
 * channels, computed by one query that groups over a left join. Against Postgres because the
 * whole thing is that query — the grouping, the ordering by an aggregate, and the two exclusions.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class ChannelDirectoryIT {

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
    @Autowired SidebarService sidebar;

    private User user(String name) {
        return users.save(new User("kc-dir-" + name, name, name + "@example.com", name));
    }

    /** A public channel created by {@code owner} with {@code extra} further members. */
    private Channel populated(String name, User owner, List<User> extra) {
        var c = channels.create(name, "About " + name, ChannelType.PUBLIC, owner);
        for (var u : extra) channels.join(c, u);
        return c;
    }

    @Test
    void aNewcomerIsOfferedTheFiveLargestLivePublicChannels() {
        var owner = user("owner");
        var crowd = List.of(user("p1"), user("p2"), user("p3"), user("p4"), user("p5"), user("p6"));

        // Seven public channels with distinct populations, so the order is unambiguous.
        var six   = populated("six",   owner, crowd.subList(0, 6));
        var five  = populated("five",  owner, crowd.subList(0, 5));
        var four  = populated("four",  owner, crowd.subList(0, 4));
        var three = populated("three", owner, crowd.subList(0, 3));
        var two   = populated("two",   owner, crowd.subList(0, 2));
        var one   = populated("one",   owner, crowd.subList(0, 1));
        populated("zero-extra", owner, List.of());
        // The biggest room of all is archived — it must not be offered, however populous.
        var archived = populated("archived", owner, crowd);
        channels.archive(archived, owner);
        // And a private one everybody is in, which a non-member never gets to see the name of.
        var secret = channels.create("secret", null, ChannelType.PRIVATE, owner);
        for (var u : crowd) channels.invite(secret, u, owner);

        var newcomer = user("newcomer");
        var view = sidebar.joinedFor(newcomer);

        assertThat(view.channels()).isEmpty();
        assertThat(view.suggestions())
                .extracting(ChannelBrowseDto::id)
                .containsExactly(six.getId(), five.getId(), four.getId(), three.getId(), two.getId());
        assertThat(view.suggestions()).extracting(ChannelBrowseDto::memberCount)
                .containsExactly(7L, 6L, 5L, 4L, 3L); // creator + extras
        assertThat(view.suggestions()).allMatch(s -> !s.joined());
        assertThat(view.suggestions()).extracting(ChannelBrowseDto::id)
                .doesNotContain(one.getId(), archived.getId(), secret.getId());
    }

    @Test
    void theFirstJoinRetiresTheSuggestions() {
        var owner = user("owner2");
        var general = populated("general", owner, List.of());
        populated("random", owner, List.of());

        var person = user("person");
        assertThat(sidebar.joinedFor(person).suggestions()).isNotEmpty();

        channels.join(general, person);

        var view = sidebar.joinedFor(person);
        assertThat(view.channels()).extracting(d -> d.id()).containsExactly(general.getId());
        // The sidebar is theirs now: alphabetical, nothing ranked, nothing offered.
        assertThat(view.suggestions()).isEmpty();
    }

    @Test
    void browseListsEveryLivePublicChannelLargestFirstAndMarksMembership() {
        var owner = user("owner3");
        var a = user("a");
        var b = user("b");
        var busy  = populated("busy",  owner, List.of(a, b));
        var quiet = populated("quiet", owner, List.of());
        var mine  = populated("mine",  owner, List.of(a));
        var gone  = populated("gone",  owner, List.of(a, b));
        channels.archive(gone, owner);
        var secret = channels.create("secret3", null, ChannelType.PRIVATE, owner);
        channels.invite(secret, a, owner);

        var rows = sidebar.browse(a);

        assertThat(rows).extracting(ChannelBrowseDto::id)
                .containsExactly(busy.getId(), mine.getId(), quiet.getId());
        assertThat(rows).extracting(ChannelBrowseDto::memberCount).containsExactly(3L, 2L, 1L);
        assertThat(rows).extracting(ChannelBrowseDto::joined).containsExactly(true, true, false);
        // The description rides along — the directory is where there is room to show it.
        assertThat(rows.get(0).description()).isEqualTo("About busy");
        // Archived and private never appear, even for a member of the private one.
        assertThat(rows).extracting(ChannelBrowseDto::id).doesNotContain(gone.getId(), secret.getId());
    }

    @Test
    void tiesBreakOnNameThenIdSoTheOrderIsTotal() {
        var owner = user("owner4");
        var bravo = populated("Bravo", owner, List.of());
        var alfa  = populated("alfa",  owner, List.of());
        var charlie = populated("charlie", owner, List.of());

        var rows = sidebar.browse(owner);

        // All three have one member; case-insensitive name decides.
        assertThat(rows).extracting(ChannelBrowseDto::id)
                .containsExactly(alfa.getId(), bravo.getId(), charlie.getId());
    }
}
