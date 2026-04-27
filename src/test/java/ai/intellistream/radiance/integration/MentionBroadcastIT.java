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
import ai.intellistream.radiance.repository.MessageMentionRepository;
import ai.intellistream.radiance.repository.UserRepository;
import ai.intellistream.radiance.security.CurrentUser;
import ai.intellistream.radiance.security.RateLimiter;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.MarkdownRenderer;
import ai.intellistream.radiance.service.MessageService;
import ai.intellistream.radiance.web.ChatWebSocketController;
import ai.intellistream.radiance.web.dto.MessageEvent;
import ai.intellistream.radiance.web.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the wire contract for live @mentions: messages broadcast on
 * {@code /topic/channels/{id}} must carry the resolved-username list so clients can
 * fire notifications without re-parsing the body.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MentionBroadcastIT {

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
    @Autowired MarkdownRenderer markdown;
    @Autowired MessageMentionRepository mentionRepository;
    @Autowired ai.intellistream.radiance.slash.SlashCommandService slashCommands;
    @Autowired ai.intellistream.radiance.service.PollService pollService;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private ChatWebSocketController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        controller = new ChatWebSocketController(channels, messages, markdown, currentUser,
                broker, new RateLimiter(), mentionRepository, slashCommands, pollService);
    }

    @Test
    void broadcastIncludesResolvedMentionedUsernames() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Mention room " + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("hey @" + bob.getUsername() + " ping"),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        org.mockito.Mockito.verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        var event = captor.getValue();
        assertThat(event.type()).isEqualTo("created");
        assertThat(event.message()).isNotNull();
        assertThat(event.message().mentions())
                .containsExactly(bob.getUsername());
    }

    @Test
    void broadcastEmitsEmptyMentionsWhenBodyHasNone() {
        var alice = newUser("alice");
        var room = channels.create("No-mention room " + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("plain message, no mentions"),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        org.mockito.Mockito.verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        assertThat(captor.getValue().message().mentions()).isEmpty();
    }

    @Test
    void unknownHandlesAreDroppedFromMentions() {
        var alice = newUser("alice");
        var room = channels.create("Ghost room " + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("hey @ghostperson are you there"),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        org.mockito.Mockito.verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        assertThat(captor.getValue().message().mentions()).isEmpty();
    }

    @Test
    void multipleMentionsAreAllSurfaced() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = channels.create("Crowded " + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("hey @" + bob.getUsername() + " and @" + carol.getUsername()),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        org.mockito.Mockito.verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        assertThat(captor.getValue().message().mentions())
                .containsExactlyInAnyOrder(bob.getUsername(), carol.getUsername());
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-mb-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }
}
