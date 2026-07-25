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
import ai.intellistream.chat.repository.ReminderRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.slash.PollCommand;
import ai.intellistream.chat.slash.RemindCommand;
import ai.intellistream.chat.slash.ReminderScheduler;
import ai.intellistream.chat.slash.SlashCommandService;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * End-to-end coverage for the /poll and /remind slash commands. Goes through the same WS
 * controller used in production: {@link ChatWebSocketController#send} invokes the dispatcher,
 * the dispatcher routes to the command, the command persists/produces a message, and the
 * controller broadcasts. Reminder firing is exercised by calling {@link ReminderScheduler#runOnce}
 * with a synthetic "now" so we don't have to wait on the @Scheduled thread.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SlashCommandIT {

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
    @Autowired MessageMentionRepository mentionRepo;
    @Autowired SlashCommandService slashCommands;
    @Autowired ReminderRepository reminderRepo;
    @Autowired ReminderScheduler reminderScheduler;
    @Autowired PollCommand pollCommand;
    @Autowired ai.intellistream.chat.service.PollService pollService;
    @Autowired ai.intellistream.chat.service.UserService userService;
    /**
     * The bean-level broker that the scheduler is wired with. Different from the local
     * {@link #broker} mock the controller uses — the scheduler doesn't see the controller's
     * mock, so reminder-fire broadcasts land on this one.
     */
    @Autowired SimpMessagingTemplate beanBroker;

    private CurrentUser currentUser;
    private SimpMessagingTemplate broker;
    private ChatWebSocketController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        broker = mock(SimpMessagingTemplate.class);
        // Reset the autowired (singleton) mock between tests so verify() counts don't bleed.
        org.mockito.Mockito.reset(beanBroker);
        // Wipe the reminder queue too — earlier tests leave unfired rows that the scheduler's
        // runOnce(future-now) would otherwise pick up alongside the new test's queue.
        reminderRepo.deleteAll();
        controller = new ChatWebSocketController(channels, messages, markdown, currentUser,
                broker, new RateLimiter(), mentionRepo, slashCommands, pollService,
                new ai.intellistream.chat.metrics.WritePathMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-slash-" + prefix + i, prefix + "-" + i,
                prefix + i + "@example.com", prefix + " " + i));
    }

    // ============================================================
    // /poll
    // ============================================================

    @Test
    void pollFailsWithFewerThanTwoOptions() {
        // ChatWebSocketController.send catches the slash-command IllegalArgumentException
        // and delivers it as a per-user notice on /user/queue/notices instead of
        // throwing — quieter UX (only the sender sees the error, not the whole channel).
        var alice = newUser("alice");
        var room = channels.create("Poll-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(alice.getUsername()); // notice routes by principal name (N19)
        controller.send(room.getId(),
                new SendMessageRequest("/poll Just a question?"),
                principal);

        // No public broadcast — the malformed command never produces a channel message.
        verify(broker, never()).convertAndSend(eq("/topic/channels/" + room.getId()),
                any(Object.class));
        // Private notice carries the usage hint to the sender.
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSendToUser(eq(alice.getUsername()), eq("/queue/notices"),
                captor.capture());
        assertThat(captor.getValue().toString()).contains("at least 2 options");
    }

    @Test
    void pollRejectsTooManyOptions() {
        var alice = newUser("alice");
        var room = channels.create("Poll-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var manyOptions = "Q?";
        for (int i = 0; i < 11; i++) manyOptions += " | option" + i;
        var body = "/poll " + manyOptions;

        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(alice.getUsername());
        controller.send(room.getId(),
                new SendMessageRequest(body), principal);

        verify(broker, never()).convertAndSend(eq("/topic/channels/" + room.getId()),
                any(Object.class));
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSendToUser(eq(alice.getUsername()), eq("/queue/notices"),
                captor.capture());
        assertThat(captor.getValue().toString()).contains("Too many options");
    }

    @Test
    void pollIgnoresEmptyPipeSegments() {
        // "/poll Q? | A | | B" should still work — the empty middle segment is dropped.
        assertThat(PollCommand.parsePipeSeparated("Q? | A | | B"))
                .containsExactly("Q?", "A", "B");
    }

    // ============================================================
    // /remind
    // ============================================================

    @Test
    void remindMeQueuesReminderWithFireInstant() {
        var alice = newUser("alice");
        var room = channels.create("Remind-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var before = Instant.now();
        controller.send(room.getId(),
                new SendMessageRequest("/remind me in 5m to take a break"),
                mock(Principal.class));
        var after = Instant.now();

        var saved = reminderRepo.findAll().stream()
                .filter(r -> r.getChannel().getId().equals(room.getId()))
                .findFirst().orElseThrow();
        // fireAt is creator's "now + 5m"; bracket it between captured before/after.
        assertThat(saved.getFireAt()).isAfterOrEqualTo(before.plus(5, ChronoUnit.MINUTES).minusSeconds(2));
        assertThat(saved.getFireAt()).isBeforeOrEqualTo(after.plus(5, ChronoUnit.MINUTES).plusSeconds(2));
        assertThat(saved.getCreator().getId()).isEqualTo(alice.getId());
        assertThat(saved.getTarget()).isNull();         // "me" is stored as null target
        assertThat(saved.getBody()).isEqualTo("take a break");
        assertThat(saved.getFiredAt()).isNull();
    }

    @Test
    void remindOtherUserPrefixesBodyWithTheirHandle() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = channels.create("Remind-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("/remind @" + bob.getUsername() + " in 1h about the demo"),
                mock(Principal.class));

        var saved = reminderRepo.findAll().stream()
                .filter(r -> r.getChannel().getId().equals(room.getId()))
                .findFirst().orElseThrow();
        assertThat(saved.getTarget().getId()).isEqualTo(bob.getId());
        // Body prefixes the @-mention so the mention pipeline lights up when fired.
        assertThat(saved.getBody()).startsWith("@" + bob.getUsername()).contains("about the demo");
    }

    @Test
    void remindEmitsConfirmationMessageBackToCaller() {
        var alice = newUser("alice");
        var room = channels.create("Remind-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("/remind me in 30s to check the build"),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        var dto = captor.getValue().message();
        assertThat(dto.authorUsername()).isEqualTo(alice.getUsername());
        assertThat(dto.bodyMarkdown()).startsWith("⏰ Reminder set")
                .contains("check the build");
    }

    @Test
    void remindRejectsMissingTime() {
        // Same per-user notice path as the /poll error tests above — controller catches
        // and delivers privately rather than crashing the WebSocket frame.
        var alice = newUser("alice");
        var room = channels.create("Remind-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(alice.getUsername());
        controller.send(room.getId(),
                new SendMessageRequest("/remind me to do the thing"),
                principal);

        verify(broker, never()).convertAndSend(eq("/topic/channels/" + room.getId()),
                any(Object.class));
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSendToUser(eq(alice.getUsername()), eq("/queue/notices"),
                captor.capture());
        assertThat(captor.getValue().toString()).contains("Couldn't parse a time");
    }

    @Test
    void remindParsesAtTimeRollingForwardWhenAlreadyPast() {
        var alice = newUser("alice");
        // Frozen clock at midday so "at 8am" must roll to tomorrow.
        var fixed = ZoneId.systemDefault();
        var noonToday = Instant.now().truncatedTo(ChronoUnit.HOURS); // close enough; not used here
        var clock = Clock.fixed(noonToday.atZone(fixed).withHour(12).toInstant(), fixed);
        var cmd = new RemindCommand(messages, userService, reminderRepo, clock);
        var parsed = cmd.parse("at 8am to early standup", alice);
        var fireZdt = parsed.fireAt().atZone(fixed);
        assertThat(fireZdt.getHour()).isEqualTo(8);
        assertThat(fireZdt.toLocalDate()).isEqualTo(noonToday.atZone(fixed).toLocalDate().plusDays(1));
    }

    @Test
    void remindClampsHugeDurationsToAboutAYear() {
        var alice = newUser("alice");
        var cmd = new RemindCommand(messages, userService, reminderRepo, Clock.systemUTC());
        // N31: the clamp must bound the actual duration, not the raw amount — "in 3000000000d"
        // used to slip past the seconds-scaled ceiling and queue a reminder ~8.6M years out.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> cmd.parse("in 3000000000d to spam the future", alice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within about a year");
    }

    @Test
    void schedulerFiresDueReminderAndPostsToChannel() {
        var alice = newUser("alice");
        var room = channels.create("Sched-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("/remind me in 5m to drink water"),
                mock(Principal.class));

        // Pretend the queued reminder is now due. runOnce takes the synthetic "now"; the row's
        // fireAt is ~+5m from the real clock, so we pass +6m to ensure it qualifies.
        var fired = reminderScheduler.runOnce(Instant.now().plus(6, ChronoUnit.MINUTES));
        assertThat(fired).isEqualTo(1);

        // The controller's (local) broker saw the confirmation; the bean broker (used by the
        // scheduler) sees the reminder body.
        verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), any(Object.class));
        verify(beanBroker).convertAndSend(eq("/topic/channels/" + room.getId()), any(Object.class));

        var saved = reminderRepo.findAll().stream()
                .filter(r -> r.getChannel().getId().equals(room.getId()))
                .findFirst().orElseThrow();
        assertThat(saved.getFiredAt()).isNotNull();
    }

    @Test
    void schedulerSkipsRowsAlreadyFired() {
        var alice = newUser("alice");
        var room = channels.create("Skip-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("/remind me in 1m water"),
                mock(Principal.class));

        var first = reminderScheduler.runOnce(Instant.now().plus(2, ChronoUnit.MINUTES));
        var second = reminderScheduler.runOnce(Instant.now().plus(2, ChronoUnit.MINUTES));
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    // ============================================================
    // dispatcher / pass-through
    // ============================================================

    @Test
    void unknownSlashCommandFallsThroughAsRegularMessage() {
        var alice = newUser("alice");
        var room = channels.create("Pass-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("/typo just a normal message"),
                mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        // The whole literal text is preserved — the user didn't lose anything.
        assertThat(captor.getValue().message().bodyMarkdown())
                .isEqualTo("/typo just a normal message");
    }

    @Test
    void plainMessageStillFlowsThroughNormally() {
        var alice = newUser("alice");
        var room = channels.create("Plain-" + SEQ.incrementAndGet(),
                null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        controller.send(room.getId(),
                new SendMessageRequest("hello, world"), mock(Principal.class));

        var captor = ArgumentCaptor.forClass(MessageEvent.class);
        verify(broker).convertAndSend(eq("/topic/channels/" + room.getId()), captor.capture());
        assertThat(captor.getValue().message().bodyMarkdown()).isEqualTo("hello, world");
    }

    @Test
    void slashCommandServiceDoesNotMatchEmailAddresses() {
        // Defensive: leading slash with a non-letter shouldn't be treated as a command.
        assertThat(SlashCommandService.looksLikeCommand("/123 not a command")).isFalse();
        assertThat(SlashCommandService.looksLikeCommand("/poll real")).isTrue();
        assertThat(SlashCommandService.looksLikeCommand("regular message")).isFalse();
        assertThat(SlashCommandService.looksLikeCommand("")).isFalse();
        assertThat(SlashCommandService.looksLikeCommand(null)).isFalse();
    }

    // Suppress unused-field warning for pollCommand — wire-test sanity that the bean exists
    // and the ID isn't stale after refactors.
    @SuppressWarnings("unused")
    private void pollCommandIsAutowired() {
        assertThat(pollCommand).isNotNull();
    }
}
