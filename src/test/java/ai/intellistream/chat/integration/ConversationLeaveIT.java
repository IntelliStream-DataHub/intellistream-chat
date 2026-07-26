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

import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.NotificationPreferenceService;
import ai.intellistream.chat.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Leaving a group conversation, and the edges that decide what leaving means.
 *
 * <p>{@code ChannelLeaveIT} is the sibling. The differences are the interesting part: a
 * conversation has no roles to hand over, no PUBLIC tier to re-enter through, and — for a DIRECT
 * one — nothing to leave at all.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ConversationLeaveIT {

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
    @Autowired ConversationService conversations;
    @Autowired NotificationPreferenceService preferences;
    @Autowired SearchService search;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-lv-" + n + "-" + label, label + "-" + n,
                label + n + "@example.com", label + " " + n));
    }

    @Test
    void leavingRemovesTheMembershipAndTheSidebarRow() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Standup", alice, List.of(bob));

        conversations.leave(conv, bob);

        assertThat(conversations.isMember(conv, bob)).isFalse();
        assertThat(conversations.listForUser(bob)).extracting("id").doesNotContain(conv.getId());
        assertThat(conversations.members(conv)).hasSize(1);
    }

    @Test
    void afterLeavingYouCanNeitherReadNorWrite() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Standup", alice, List.of(bob));
        conversations.post(conv, alice, "before");

        conversations.leave(conv, bob);

        assertThatThrownBy(() -> conversations.recent(conv, bob, 50))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> conversations.post(conv, bob, "still here?"))
                .isInstanceOf(AccessDeniedException.class);
        // And the search ACL agrees — it is read from the membership table on every query, which is
        // the property that makes a removal take effect immediately rather than at the next restart.
        assertThatThrownBy(() -> search.searchConversation(conv, bob, "before", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void whatYouWroteStaysBehind() {
        // You are leaving, not deleting. The people still here were part of the conversation, and
        // their copy of it does not become less true because you left.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Standup", alice, List.of(bob));
        conversations.post(conv, bob, "here is the runbook");

        conversations.leave(conv, bob);

        assertThat(conversations.recent(conv, alice, 50))
                .extracting(m -> m.getBodyMarkdown()).contains("here is the runbook");
    }

    @Test
    void aThreadYouStartedSurvivesAndStopsNamingYou() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.createGroup("Release", alice, List.of(bob, carol));
        var parent = conversations.post(conv, bob, "shall we ship?");
        conversations.replyInThread(parent.getId(), carol, "after the migration");

        conversations.leave(conv, bob);

        // The thread is intact for the people still in it…
        assertThat(conversations.threadReplies(parent.getId(), alice)).hasSize(1);
        // …and bob is no longer among the people a further reply would notify. The narrowing is not
        // theoretical now that a conversation can be left: the participant list rides on a broadcast
        // the client acts on.
        assertThat(conversations.threadParticipants(parent.getId(), carol))
                .doesNotContain(bob.getUsername());
    }

    @Test
    void theNotificationLevelGoesWithTheMembership() {
        // It is a fact about a membership, not about the conversation. Re-added later you start
        // fresh, which is right: you were not there for what happened in between.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Noise", alice, List.of(bob));
        preferences.setLevelFor(conv, bob, NotificationLevel.NONE);

        conversations.leave(conv, bob);
        assertThatThrownBy(() -> preferences.levelFor(conv, bob))
                .isInstanceOf(AccessDeniedException.class);

        conversations.addToGroup(conv, bob, alice);
        assertThat(preferences.levelFor(conv, bob)).isEqualTo(NotificationLevel.DEFAULT);
    }

    @Test
    void theLastMemberMayLeaveAndTheMessagesRemain() {
        // Trapping the last person in a group everybody else has abandoned is the exact failure
        // this feature exists to fix. The row is left inert — nobody remains to add anyone back —
        // and it keeps its messages, because deleting them would destroy the history of everyone
        // who was ever in it the moment the last of them stopped reading.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Ghost town", alice, List.of(bob));
        conversations.post(conv, alice, "anyone?");

        conversations.leave(conv, bob);
        conversations.leave(conv, alice);

        assertThat(conversations.members(conv)).isEmpty();
        assertThat(conversations.requireById(conv.getId())).isNotNull();
        assertThatThrownBy(() -> conversations.recent(conv, alice, 50))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void leavingTwiceIsANotFoundRatherThanASecondDeparture() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.createGroup("Standup", alice, List.of(bob));

        conversations.leave(conv, bob);
        assertThatThrownBy(() -> conversations.leave(conv, bob))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aStrangerCannotLeaveAConversationTheyWereNeverIn() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.createGroup("Standup", alice, List.of(bob));

        assertThatThrownBy(() -> conversations.leave(conv, carol))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(conversations.members(conv)).hasSize(2);
    }

    // ---------- What cannot be left ----------

    @Test
    void aOneToOneCannotBeLeft() {
        // Slack draws the same line: you close a DM, you do not leave it. There is nothing to
        // leave — the conversation *is* the pair — and messaging that person again would resolve
        // the same dm_key and put you straight back, so "leave" would mean "hide until the next
        // message", which is a different feature wearing this one's name.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        assertThatThrownBy(() -> conversations.leave(conv, bob))
                .isInstanceOf(PublicBadRequestException.class);
        assertThat(conversations.isMember(conv, bob)).isTrue();
    }

    @Test
    void aSelfConversationCannotBeLeftEither() {
        // Same rule, and one extra reason: leaving would strand every future /remind me with
        // nowhere to deliver.
        var solo = newUser("solo");
        var conv = conversations.directBetween(solo, solo);
        assertThat(conversations.members(conv)).hasSize(1);

        assertThatThrownBy(() -> conversations.leave(conv, solo))
                .isInstanceOf(PublicBadRequestException.class);
        assertThat(conversations.isMember(conv, solo)).isTrue();
    }
}
