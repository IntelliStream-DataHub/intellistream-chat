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
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.web.ConversationWebSocketController;
import ai.intellistream.chat.web.dto.TypingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Typing pings in a conversation: who may send one, where it lands, and what happens above the
 * budget. The channel equivalent lives in {@code ChatWebSocketController} and has the same three
 * rules; this asserts the DM half now that there is one.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ConversationTypingIT {

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
    @Autowired MarkdownRenderer markdown;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private ConversationWebSocketController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        controller = new ConversationWebSocketController(conversations, markdown, currentUser,
                broker, new RateLimiter(),
                mock(ai.intellistream.chat.web.ConversationAlertPublisher.class));
    }

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-typ-" + n + "-" + label, label + "-" + n,
                label + n + "@example.com", label + " " + n));
    }

    @Test
    void aMembersPingLandsOnTheConversationTypingTopic() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.typing(conv.getId(), mock(Principal.class));

        var event = new TypingEvent(alice.getUsername(), alice.getDisplayName());
        verify(broker).convertAndSend(eq("/topic/conversations/" + conv.getId() + "/typing"),
                eq(event));
    }

    @Test
    void aNonMemberCannotInjectTypingPings() {
        // Broadcasting into a conversation is a write, and a DM has no PUBLIC tier for the check to
        // short-circuit on. Without this a connected stranger could make somebody's DM claim they
        // were typing.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var conv = conversations.directBetween(alice, bob);
        when(currentUser.resolve(any(Principal.class))).thenReturn(carol);

        assertThatThrownBy(() -> controller.typing(conv.getId(), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
        verify(broker, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void pingsAboveTheBudgetAreDroppedRatherThanRefused() {
        // 60 a minute, and the 61st goes nowhere — quietly. An ERROR frame would tear down the
        // socket carrying the message the user is in the middle of typing, which is a strange price
        // to pay for an indicator.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        for (int i = 0; i < 60; i++) {
            controller.typing(conv.getId(), mock(Principal.class));
        }
        controller.typing(conv.getId(), mock(Principal.class)); // no throw

        verify(broker, org.mockito.Mockito.times(60))
                .convertAndSend(eq("/topic/conversations/" + conv.getId() + "/typing"),
                        any(Object.class));
    }

    @Test
    void aSelfConversationStillBroadcasts() {
        // One member, who is also the only subscriber. The server does not special-case it: the
        // client drops its own pings, which is the same filter it needs for a group anyway, and a
        // server-side "is this the only member" test would be a second place for that rule to live.
        var solo = newUser("solo");
        var conv = conversations.directBetween(solo, solo);
        assertThat(conversations.members(conv)).hasSize(1);
        when(currentUser.resolve(any(Principal.class))).thenReturn(solo);

        controller.typing(conv.getId(), mock(Principal.class));

        verify(broker).convertAndSend(eq("/topic/conversations/" + conv.getId() + "/typing"),
                eq(new TypingEvent(solo.getUsername(), solo.getDisplayName())));
    }
}
