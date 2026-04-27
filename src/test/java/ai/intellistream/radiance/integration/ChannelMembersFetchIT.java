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

package ai.intellistream.radiance.integration;

import ai.intellistream.radiance.domain.ChannelType;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.service.ChannelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the bug where clicking a channel in the sidebar threw
 *   LazyInitializationException: Could not initialize proxy [User#...] - no session
 * during Thymeleaf rendering of the admin members panel.
 *
 * The HomeController loads {@link ChannelService#members(ai.intellistream.radiance.domain.Channel)},
 * the service transaction commits, then the template reads {@code m.user.displayName}.
 * With {@code spring.jpa.open-in-view=false} the lazy {@code user} association must be
 * fetched inside the service transaction or rendering blows up.
 *
 * This test runs WITHOUT a class-level {@code @Transactional} so the service call
 * commits and closes its session — exactly like the controller flow does.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ChannelMembersFetchIT {

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
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;

    @Test
    void clickingChannelInSidebar_rendersMemberListWithoutLazyInitException() {
        var alice = users.save(new User("kc-alice-fetch", "alice-fetch", "a@x", "Alice Anderson"));
        var bob   = users.save(new User("kc-bob-fetch",   "bob-fetch",   "b@x", "Bob Builder"));

        var room = channels.create("Members Fetch Room", null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        // Same call HomeController makes when an admin opens a channel page.
        var loaded = channels.members(room);

        // Reading the lazy `user` association AFTER the service transaction has
        // closed must not throw — this is what Thymeleaf does in the admin panel.
        assertThat(loaded).extracting(m -> m.getUser().getUsername())
                .containsExactlyInAnyOrder("alice-fetch", "bob-fetch");
        assertThat(loaded).extracting(m -> m.getUser().getDisplayName())
                .containsExactlyInAnyOrder("Alice Anderson", "Bob Builder");
        assertThat(loaded).allSatisfy(m -> {
            assertThat(m.getUser().getEmail()).isNotBlank();
            assertThat(m.getRole()).isNotNull();
        });
    }
}
