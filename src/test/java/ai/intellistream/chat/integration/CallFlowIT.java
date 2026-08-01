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

import ai.intellistream.chat.calls.CallEndReason;
import ai.intellistream.chat.calls.CallMedia;
import ai.intellistream.chat.calls.CallProperties;
import ai.intellistream.chat.calls.CallRegistry;
import ai.intellistream.chat.calls.CallService;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.dto.CallEvent;
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
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Calls against a real database.
 *
 * <p>{@code CallRegistryTest} and {@code CallServiceTest} cover the state machine and the refusals
 * with mocks; what those cannot reach is the half of this feature that outlives the call. The line
 * a call leaves in the conversation is an ordinary {@code conversation_messages} row, written
 * through the same {@code ConversationService.post} as anything anybody types, and the assertions
 * that matter here are that it is durable, that it lands in the feed both participants read, and
 * that it says the right thing.
 *
 * <p>{@code CallService} is constructed by hand rather than injected, the way
 * {@code ConversationThreadIT} builds its controller: it needs a {@code SimpMessagingTemplate} that
 * the test can interrogate, and the {@code calls} package stays out of
 * {@code IntegrationTestApplication}'s scan so the scan rule needs no exception.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class CallFlowIT {

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

    /** A clock the test can push forward, so talk time and ring timeouts are assertable. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-01T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration by) { now = now.plus(by); }
    }

    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired ConversationService conversations;
    @Autowired MarkdownRenderer markdown;

    private TestClock clock;
    private CallRegistry registry;
    private CallProperties properties;
    private SimpMessagingTemplate broker;
    private CallService calls;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        clock = new TestClock();
        registry = new CallRegistry(clock);
        properties = new CallProperties();
        properties.setTurnUrls(List.of("turn:example.com:3478"));
        properties.setTurnSecret("secret");
        broker = mock(SimpMessagingTemplate.class);
        calls = new CallService(registry, properties, conversations, userService, markdown, broker);
    }

    private User newUser(String label) {
        var n = SEQ.incrementAndGet();
        return users.save(new User("kc-call-" + n + "-" + label, label + "-" + n,
                label + n + "@example.com", label + " " + n));
    }

    private List<CallEvent> eventsTo(User user) {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broker, org.mockito.Mockito.atLeast(0))
                .convertAndSendToUser(eq(user.getUsername()), eq("/queue/calls"), captor.capture());
        return captor.getAllValues().stream().map(CallEvent.class::cast).toList();
    }

    private String currentCallId(User user) {
        return registry.current(user.getUsername()).orElseThrow().id();
    }

    // ---------- what a call leaves behind ----------

    @Test
    void anAnsweredCallLeavesADurableLineInTheConversation() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        calls.invite(conv.getId(), alice, "session-alice", CallMedia.AUDIO);
        var callId = currentCallId(alice);
        calls.accept(callId, bob, "session-bob");
        clock.advance(Duration.ofMinutes(4));
        calls.hangUp(callId, alice, CallEndReason.HANGUP);

        // Read it back the way the page does, not from the object the writer returned — the point
        // is that it survived the transaction and is in the feed both of them scroll.
        var feed = conversations.recent(conv, bob, 50);
        assertThat(feed).extracting(m -> m.getBodyMarkdown()).contains("_Call · 4 min_");
        assertThat(feed).filteredOn(m -> "_Call · 4 min_".equals(m.getBodyMarkdown()))
                .singleElement()
                // Authored by whoever caused the event. A synthetic system account would need a
                // real users row, a name nobody chose, and a profile page that means nothing.
                .satisfies(m -> assertThat(m.getAuthor().getId()).isEqualTo(alice.getId()));
    }

    @Test
    void aVideoCallSaysSoInTheArchive() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        calls.invite(conv.getId(), alice, "s", CallMedia.VIDEO);
        var callId = currentCallId(alice);
        calls.accept(callId, bob, "s2");
        clock.advance(Duration.ofSeconds(30));
        calls.hangUp(callId, bob, CallEndReason.HANGUP);

        assertThat(conversations.recent(conv, alice, 50))
                .extracting(m -> m.getBodyMarkdown())
                .contains("_Video call · 30 sec_");
    }

    @Test
    void aDeclinedCallIsArchivedAsAMissedCallAndNothingMore() {
        // The live UI tells the caller they were declined. The permanent record does not: writing
        // "declined" into somebody's message history puts a verdict on their behalf, and the fact
        // of the call is the part worth keeping.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        calls.invite(conv.getId(), alice, "s", CallMedia.AUDIO);
        calls.hangUp(currentCallId(alice), bob, CallEndReason.DECLINED);

        var bodies = conversations.recent(conv, alice, 50).stream()
                .map(m -> m.getBodyMarkdown()).toList();
        assertThat(bodies).contains("_Missed call_");
        assertThat(bodies).noneSatisfy(b -> assertThat(b).containsIgnoringCase("declin"));
    }

    @Test
    void aCallThatRangOutIsArchivedTheSameWayAsOneThatWasDeclined() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        calls.invite(conv.getId(), alice, "s", CallMedia.AUDIO);
        clock.advance(properties.getRingTimeout().plusSeconds(1));
        calls.sweepTimeouts();

        assertThat(conversations.recent(conv, alice, 50))
                .extracting(m -> m.getBodyMarkdown())
                .contains("_Missed call_");
        // And both accounts are free to call again — a timeout that leaked the busy index would
        // leave two people unable to call anyone until the process restarted.
        assertThat(registry.current(alice.getUsername())).isEmpty();
        assertThat(registry.current(bob.getUsername())).isEmpty();
    }

    @Test
    void oneCallLeavesExactlyOneLineEvenWhenBothPeersHangUp() {
        // Routine: one presses hang up, the other's client sees the connection drop and sends its
        // own. Two archive lines for one call is what an idempotency bug looks like from the feed.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        calls.invite(conv.getId(), alice, "s", CallMedia.AUDIO);
        var callId = currentCallId(alice);
        calls.accept(callId, bob, "s2");
        clock.advance(Duration.ofSeconds(10));
        calls.hangUp(callId, alice, CallEndReason.HANGUP);
        calls.hangUp(callId, bob, CallEndReason.HANGUP);

        assertThat(conversations.recent(conv, alice, 50))
                .filteredOn(m -> m.getBodyMarkdown().startsWith("_Call ·"))
                .hasSize(1);
    }

    // ---------- who may call ----------

    @Test
    void aGroupConversationCannotHostACall() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var group = conversations.createGroup("release", alice, List.of(bob, carol));

        assertThatThrownBy(() -> calls.invite(group.getId(), alice, "s", CallMedia.AUDIO))
                .isInstanceOf(CallService.CallsUnavailableException.class);
        assertThat(conversations.recent(group, alice, 50)).isEmpty();
    }

    @Test
    void aNoteToSelfHasNobodyToCall() {
        var alice = newUser("alice");
        var solo = conversations.directBetween(alice, alice);

        assertThatThrownBy(() -> calls.invite(solo.getId(), alice, "s", CallMedia.AUDIO))
                .isInstanceOf(CallService.CallsUnavailableException.class);
    }

    @Test
    void somebodyOutsideTheConversationCannotStartACallInIt() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var mallory = newUser("mallory");
        var conv = conversations.directBetween(alice, bob);

        // Same bar as posting into it — requireMember, not a weaker check invented for calls.
        assertThatThrownBy(() -> calls.invite(conv.getId(), mallory, "s", CallMedia.AUDIO))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void callingSomebodyAlreadyOnACallTellsTheCallerAndStartsNothing() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var aliceBob = conversations.directBetween(alice, bob);
        var carolBob = conversations.directBetween(carol, bob);

        calls.invite(aliceBob.getId(), alice, "s", CallMedia.AUDIO);
        calls.invite(carolBob.getId(), carol, "s", CallMedia.AUDIO);

        assertThat(eventsTo(carol)).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("busy");
            assertThat(e.peer()).isEqualTo(bob.getUsername());
        });
        // bob heard about alice's call and nothing else.
        assertThat(eventsTo(bob)).singleElement()
                .satisfies(e -> assertThat(e.type()).isEqualTo("invite"));
        assertThat(conversations.recent(carolBob, carol, 50)).isEmpty();
    }

    // ---------- signalling ----------

    @Test
    void aSignalReachesTheOtherParticipantOnly() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var mallory = newUser("mallory");
        var conv = conversations.directBetween(alice, bob);
        calls.invite(conv.getId(), alice, "s", CallMedia.AUDIO);
        var callId = currentCallId(alice);

        calls.signal(callId, alice, "offer",
                JsonNodeFactory.instance.objectNode().put("sdp", "v=0"));

        assertThat(eventsTo(bob)).last().satisfies(e -> {
            assertThat(e.type()).isEqualTo("signal");
            assertThat(e.signalKind()).isEqualTo("offer");
            // The payload is relayed untouched — the server has no business parsing an SDP.
            assertThat(e.payload().get("sdp").asString()).isEqualTo("v=0");
        });
        assertThatThrownBy(() -> calls.signal(callId, mallory, "offer",
                JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void answeringPromotesTheCallAndTellsBothSides() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);
        calls.invite(conv.getId(), alice, "session-alice", CallMedia.AUDIO);
        var callId = currentCallId(alice);

        calls.accept(callId, bob, "session-bob");

        // The caller needs it to start negotiating; bob's OTHER tabs need it to stop ringing.
        assertThat(eventsTo(alice)).last()
                .satisfies(e -> assertThat(e.type()).isEqualTo("accepted"));
        assertThat(eventsTo(bob)).last()
                .satisfies(e -> assertThat(e.type()).isEqualTo("accepted"));
        assertThat(registry.find(callId).orElseThrow().calleeSession()).isEqualTo("session-bob");
    }

    @Test
    void aCallIsNotStartedAtAllWhenNoTurnServerIsConfigured() {
        properties.setTurnUrls(List.of());
        var alice = newUser("alice");
        var bob = newUser("bob");
        var conv = conversations.directBetween(alice, bob);

        assertThatThrownBy(() -> calls.invite(conv.getId(), alice, "s", CallMedia.AUDIO))
                .isInstanceOf(CallService.CallsUnavailableException.class);
        assertThat(registry.current(alice.getUsername())).isEmpty();
    }
}
