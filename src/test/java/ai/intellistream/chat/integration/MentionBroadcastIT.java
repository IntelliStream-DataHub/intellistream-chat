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
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.web.ChatWebSocketController;
import ai.intellistream.chat.web.dto.MessageEvent;
import ai.intellistream.chat.web.dto.SendMessageRequest;
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
    @Autowired ai.intellistream.chat.slash.SlashCommandService slashCommands;
    @Autowired ai.intellistream.chat.service.PollService pollService;
    @Autowired ai.intellistream.chat.service.PresenceTracker presence;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private ChatWebSocketController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @org.junit.jupiter.api.AfterEach
    void clearPresence() {
        // Process-wide in-memory state shared by every test in this context.
        presence.resetForTests();
    }

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        controller = new ChatWebSocketController(channels, messages, markdown, currentUser,
                broker, new RateLimiter(), mentionRepository, slashCommands, pollService,
                new ai.intellistream.chat.metrics.WritePathMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), linkPreviews());
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

    /**
     * The {@code mentions} array on the broadcast frame is a live-notify hint — it drives a toast
     * and a chime in a browser that is receiving this frame — so an @channel puts the *connected*
     * members in it, not the whole membership. The absent members still get their mention row, which
     * is what the bell inbox and the channel badge read; sending a thousand names to a thousand
     * subscribers for the sake of people who are not there would be a kilobyte per message.
     */
    @Test
    void channelBroadcastAnnouncesOnlyConnectedMembers() {
        var alice = newUser("alice");
        var online = newUser("bob");
        var offline = newUser("carol");
        var room = channels.create("Broadcast room " + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        channels.join(room, online);
        channels.join(room, offline);
        presence.connect(online.getUsername(), "stomp-session-1");
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(), new SendMessageRequest("@channel standup now"),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        org.mockito.Mockito.verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        assertThat(captor.getValue().message().mentions()).containsExactly(online.getUsername());
        // The row exists for the disconnected member all the same.
        assertThat(mentionRepository.usernamesByMessage(
                messages.requireById(captor.getValue().message().id())))
                .containsExactlyInAnyOrder(online.getUsername(), offline.getUsername());
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-mb-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }

    /** Real decoration against this context's LinkPreviewService; the broker is the test's mock. */
    private ai.intellistream.chat.web.LinkPreviews linkPreviews() {
        return new ai.intellistream.chat.web.LinkPreviews(linkPreviewService,
                org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
    }
    @org.springframework.beans.factory.annotation.Autowired
    ai.intellistream.chat.linkpreview.LinkPreviewService linkPreviewService;
}
