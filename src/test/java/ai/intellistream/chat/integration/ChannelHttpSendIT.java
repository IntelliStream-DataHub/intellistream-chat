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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.PollService;
import ai.intellistream.chat.service.ReactionService;
import ai.intellistream.chat.service.ReadStateService;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.web.ChannelRestController;
import ai.intellistream.chat.web.dto.InviteRequest;
import ai.intellistream.chat.web.dto.MessageEvent;
import ai.intellistream.chat.web.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the channel HTTP write endpoints: the send endpoint must broadcast over STOMP (N6), and
 * invite must not leak username existence to a non-member (N8).
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ChannelHttpSendIT {

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
    @Autowired AttachmentService attachments;
    @Autowired ReactionService reactions;
    @Autowired ReadStateService reads;
    @Autowired UserService userService;
    @Autowired PollService pollService;
    @Autowired MarkdownRenderer markdown;
    @Autowired MessageMentionRepository mentionRepo;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private ChannelRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired ai.intellistream.chat.service.SidebarService sidebarService;

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        controller = new ChannelRestController(channels, messages, attachments, reactions,
                reads, userService, pollService, markdown, currentUser, new RateLimiter(),
                broker, mentionRepo, sidebarService);
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-hs-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }

    @Test
    void httpPostBroadcastsCreatedEventToChannelTopic() {
        var alice = newUser("alice");
        var room = channels.create("Http-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var dto = controller.post(room.getId(), new SendMessageRequest("hello over http"),
                mock(Principal.class));

        assertThat(dto.bodyHtml()).contains("hello over http");
        // N6: the message is broadcast live, not just persisted.
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(MessageEvent.class);
        assertThat(((MessageEvent) payload.getValue()).type()).isEqualTo("created");
    }

    @Test
    void inviteByNonMemberIsForbiddenRegardlessOfUsernameExistence() {
        var owner = newUser("owner");
        var outsider = newUser("outsider");
        var known = newUser("known");
        var room = channels.create("Inv-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, owner);
        when(currentUser.resolve(any(Principal.class))).thenReturn(outsider);

        // N8: an existing username and an unknown one must both yield the SAME forbidden outcome —
        // authorization runs before the username lookup, so there is no 403-vs-400 existence oracle.
        assertThatThrownBy(() -> controller.invite(room.getId(),
                new InviteRequest(known.getUsername()), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.invite(room.getId(),
                new InviteRequest("ghost-does-not-exist"), mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
