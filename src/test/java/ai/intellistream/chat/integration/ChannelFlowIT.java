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
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.SearchService;
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

@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class ChannelFlowIT {

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
    @Autowired MessageService messages;
    @Autowired SearchService search;
    @Autowired SidebarService sidebar;
    @Autowired MarkdownRenderer markdown;

    @Test
    void publicChannelHappyPath() {
        var alice = users.save(new User("kc-alice", "alice", "alice@example.com", "Alice"));
        var bob   = users.save(new User("kc-bob",   "bob",   "bob@example.com",   "Bob"));

        var general = channels.create("General Discussion", "Hello there", ChannelType.PUBLIC, alice);
        assertThat(general.getSlug()).isEqualTo("general-discussion");
        assertThat(channels.isAdmin(general, alice)).isTrue();
        assertThat(channels.isAdmin(general, bob)).isFalse();

        channels.join(general, bob);
        assertThat(channels.isMember(general, bob)).isTrue();

        messages.post(general, alice, "Hello **world**");
        messages.post(general, bob,   "Hi alice, this is a test message");

        var recent = messages.recent(general, alice, 50);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getAuthor().getUsername()).isEqualTo("alice");
        assertThat(recent.get(1).getAuthor().getUsername()).isEqualTo("bob");

        var rendered = markdown.render(recent.get(0).getBodyMarkdown());
        assertThat(rendered).contains("<strong>world</strong>");

        // Lucene index writes are deferred to afterCommit; commit the test tx so the
        // hooks fire before the search assertions run. Tx.commit() restarts a fresh
        // tx so the test still gets the @Transactional auto-rollback container around
        // any subsequent writes.
        Tx.commit();

        var hitsInChannel = search.searchChannel(general, alice, "hello", 10);
        assertThat(hitsInChannel).hasSize(1);

        var globalHits = search.searchAccessible(bob, "test", 10);
        assertThat(globalHits).hasSize(1);
        assertThat(globalHits.get(0))
                .isInstanceOfSatisfying(ai.intellistream.chat.service.SearchService.SearchHit.ChannelHit.class,
                        hit -> assertThat(hit.message().getBodyMarkdown()).contains("test message"));

        var alicesSidebar = sidebar.sidebarFor(alice);
        assertThat(alicesSidebar).extracting("name", "joined", "admin")
                .contains(org.assertj.core.groups.Tuple.tuple("General Discussion", true, true));
    }

    @Test
    void privateChannelGatesAccess() {
        var alice = users.save(new User("kc-alice2", "alice2", "alice2@example.com", "Alice"));
        var bob   = users.save(new User("kc-bob2",   "bob2",   "bob2@example.com",   "Bob"));

        var secret = channels.create("Secret room", null, ChannelType.PRIVATE, alice);

        assertThatThrownBy(() -> channels.join(secret, bob))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> messages.post(secret, bob, "should not work"))
                .isInstanceOf(AccessDeniedException.class);

        var membership = channels.invite(secret, bob, alice);
        assertThat(membership).isNotNull();
        assertThat(channels.isMember(secret, bob)).isTrue();

        messages.post(secret, bob, "thanks for the invite");
        assertThat(messages.recent(secret, bob, 10)).hasSize(1);
    }

    @Test
    void memberCanInviteAnotherUser() {
        // Slack/Mattermost default: any channel member (not just admins) can invite others.
        var alice = users.save(new User("kc-alice3a", "alice3a", "alice3a@example.com", "Alice"));
        var bob   = users.save(new User("kc-bob3a",   "bob3a",   "bob3a@example.com",   "Bob"));
        var carol = users.save(new User("kc-carol3a", "carol3a", "carol3a@example.com", "Carol"));

        var room = channels.create("Project X member-invite", null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);   // alice (admin) invites bob
        channels.invite(room, carol, bob);   // bob (plain member) invites carol — must succeed

        assertThat(channels.isMember(room, carol)).isTrue();
    }

    @Test
    void nonMemberCannotInvite() {
        var alice  = users.save(new User("kc-alice3b", "alice3b", "alice3b@example.com", "Alice"));
        var snoop  = users.save(new User("kc-snoop3b", "snoop3b", "snoop3b@example.com", "Snoop"));
        var carol  = users.save(new User("kc-carol3b", "carol3b", "carol3b@example.com", "Carol"));

        var room = channels.create("Project X non-member", null, ChannelType.PRIVATE, alice);
        // snoop is not a member of this private channel — must not be able to invite anyone in.
        assertThatThrownBy(() -> channels.invite(room, carol, snoop))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsBlankAndOversizedMessages() {
        var alice = users.save(new User("kc-alice4", "alice4", "alice4@example.com", "Alice"));
        var room = channels.create("Test 4", null, ChannelType.PUBLIC, alice);

        assertThatThrownBy(() -> messages.post(room, alice, ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> messages.post(room, alice, "x".repeat(8001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Promote / demote ----------

    @Test
    void adminCanPromoteAnotherMemberToAdmin() {
        var alice = users.save(new User("kc-cf-alice5", "cf-alice5", "alice5@example.com", "Alice"));
        var bob   = users.save(new User("kc-cf-bob5",   "cf-bob5",   "bob5@example.com",   "Bob"));
        var room = channels.create("Promo " + System.nanoTime(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        assertThat(channels.isAdmin(room, bob)).isFalse();

        channels.promote(room, bob, alice);

        assertThat(channels.isAdmin(room, bob)).isTrue();
    }

    @Test
    void demoteRemovesAdminRoleButRefusesLastAdmin() {
        // alice creates the channel (becomes the first/only admin) and promotes bob.
        // Now there are two admins; bob can be demoted. After that, alice is the last
        // admin again — demoting her must fail to keep the channel manageable.
        var alice = users.save(new User("kc-cf-alice6", "cf-alice6", "alice6@example.com", "Alice"));
        var bob   = users.save(new User("kc-cf-bob6",   "cf-bob6",   "bob6@example.com",   "Bob"));
        var room = channels.create("Demote " + System.nanoTime(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.promote(room, bob, alice);

        // bob → MEMBER (one admin remaining: alice)
        channels.demote(room, bob, alice);
        assertThat(channels.isAdmin(room, bob)).isFalse();
        assertThat(channels.isAdmin(room, alice)).isTrue();

        // demoting the last admin must throw, leaving alice still admin.
        assertThatThrownBy(() -> channels.demote(room, alice, alice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last admin");
        assertThat(channels.isAdmin(room, alice)).isTrue();
    }

    @Test
    void nonAdminCannotPromoteOrDemote() {
        var alice = users.save(new User("kc-cf-alice7", "cf-alice7", "alice7@example.com", "Alice"));
        var bob   = users.save(new User("kc-cf-bob7",   "cf-bob7",   "bob7@example.com",   "Bob"));
        var carol = users.save(new User("kc-cf-carol7", "cf-carol7", "carol7@example.com", "Carol"));
        var room = channels.create("Auth " + System.nanoTime(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);
        channels.join(room, carol);

        // bob is a plain member — neither promote nor demote should work for him.
        assertThatThrownBy(() -> channels.promote(room, carol, bob))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> channels.demote(room, alice, bob))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void demotingPlainMemberIsANoOp() {
        // Demoting someone who's already a plain member shouldn't throw — it's the
        // idempotent path the UI hits when an admin clicks "Demote" on a row that
        // happens to no longer be ADMIN by the time the request lands.
        var alice = users.save(new User("kc-cf-alice8", "cf-alice8", "alice8@example.com", "Alice"));
        var bob   = users.save(new User("kc-cf-bob8",   "cf-bob8",   "bob8@example.com",   "Bob"));
        var room = channels.create("Idem " + System.nanoTime(), null, ChannelType.PUBLIC, alice);
        channels.join(room, bob);

        // Should not throw.
        channels.demote(room, bob, alice);
        assertThat(channels.isAdmin(room, bob)).isFalse();
    }
}
