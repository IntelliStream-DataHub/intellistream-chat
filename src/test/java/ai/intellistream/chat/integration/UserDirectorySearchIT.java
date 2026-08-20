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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MentionService;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.UserRestController;
import ai.intellistream.chat.web.dto.UserSearchResultDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@code GET /api/users/directory} — the "Find user" browser behind the
 * new-conversation form. Unlike its channel sibling ({@link ChannelInviteCandidatesIT}) there is
 * no membership to scope by: any authenticated user may start a conversation with anyone, so the
 * search spans the whole directory, including the caller (a conversation with yourself is real).
 * Filter semantics are shared with the channel browser via {@code UserService}'s pattern
 * helpers; the assertions here pin that the unscoped query path applies them the same way.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class UserDirectorySearchIT {

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
    @Autowired UserService userService;
    @Autowired ChannelService channelService;
    @Autowired ConversationService conversationService;
    @Autowired MentionService mentionService;

    private CurrentUser currentUser;
    private UserRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        controller = new UserRestController(userService, currentUser, new RateLimiter(),
                channelService, conversationService, mentionService);
    }

    private User newUser(String prefix) {
        return newUser(prefix, prefix + "@example.com");
    }

    private User newUser(String prefix, String email) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-dir-" + prefix + i, prefix + "-" + i, email, prefix + " " + i));
    }

    private void asUser(User me) {
        when(currentUser.resolve(any(Principal.class))).thenReturn(me);
    }

    @Test
    void includesEveryone_includingTheCaller() {
        var alice = newUser("alice");
        var stranger = newUser("stranger");

        asUser(alice);
        var found = controller.directory("", "", true, mock(Principal.class));

        // The caller too: a conversation with yourself is real (Conversation.isSelfDirect()).
        assertThat(found).extracting(UserSearchResultDto::username)
                .contains(alice.getUsername(), stranger.getUsername());
    }

    @Test
    void usernameWildcard_filtersByPattern() {
        var alice = newUser("alice");
        var match = newUser("dzorro");
        var nomatch = newUser("nope");

        asUser(alice);
        var found = controller.directory("d*rro*", "", true, mock(Principal.class));

        assertThat(found).extracting(UserSearchResultDto::username)
                .contains(match.getUsername())
                .doesNotContain(nomatch.getUsername());
    }

    @Test
    void emailDomain_matchesDomainPrefixOnly() {
        var alice = newUser("alice");
        var acme = newUser("worker", "worker" + SEQ.get() + "@diracme.io");
        var other = newUser("other", "other" + SEQ.get() + "@notdiracme.io");

        asUser(alice);
        var found = controller.directory("", "diracme", true, mock(Principal.class));

        // Anchored right after the '@': "diracme" matches diracme.io but not notdiracme.io.
        assertThat(found).extracting(UserSearchResultDto::username)
                .contains(acme.getUsername())
                .doesNotContain(other.getUsername());
    }

    @Test
    void recentFirst_ordersNewestCreatedFirst() {
        var alice = newUser("alice");
        var older = newUser("older");
        var newer = newUser("newer");

        asUser(alice);
        var found = controller.directory("", "", true, mock(Principal.class));

        var idxOlder = indexOf(found, older.getUsername());
        var idxNewer = indexOf(found, newer.getUsername());
        assertThat(idxNewer).isLessThan(idxOlder);
    }

    @Test
    void neverReturnsAnEmailAddress() {
        var alice = newUser("alice");
        newUser("someone", "someone" + SEQ.get() + "@secret-domain.example");

        asUser(alice);
        var found = controller.directory("", "secret-domain", true, mock(Principal.class));

        // The DTO has no email field at all; this pins that nobody adds one back without
        // noticing what the browse endpoints promised.
        assertThat(found).isNotEmpty();
        for (var field : UserSearchResultDto.class.getRecordComponents()) {
            assertThat(field.getName()).doesNotContainIgnoringCase("email");
        }
    }

    @Test
    void resultIsCappedAtOneHundred() {
        var alice = newUser("alice");
        var tag = "dircap" + SEQ.incrementAndGet();
        for (int i = 0; i < 105; i++) {
            newUser(tag + "-" + i);
        }

        asUser(alice);
        var found = controller.directory(tag, "", true, mock(Principal.class));

        assertThat(found).hasSize(100);
    }

    private static int indexOf(java.util.List<UserSearchResultDto> list, String username) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).username().equals(username)) return i;
        }
        throw new AssertionError("Not found: " + username);
    }
}
