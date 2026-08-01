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

package ai.intellistream.chat.calls;

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMember;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.dto.CallEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules around a call: who may place one, where, and what it leaves behind.
 *
 * <p>The media is not here and cannot be — a peer connection needs a browser. What is testable, and
 * is where the security actually lives, is the set of refusals: calls only in a 1:1, only between
 * members, and a signal relayed only to somebody who is in the call.
 */
class CallServiceTest {

    private CallProperties properties;
    private CallRegistry registry;
    private ConversationService conversations;
    private UserService users;
    private SimpMessagingTemplate broker;
    private CallService service;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        properties = new CallProperties();
        properties.setTurnUrls(List.of("turn:example.com:3478"));
        properties.setTurnSecret("secret");

        registry = new CallRegistry();
        conversations = mock(ConversationService.class);
        users = mock(UserService.class);
        broker = mock(SimpMessagingTemplate.class);
        var markdown = mock(MarkdownRenderer.class);

        service = new CallService(registry, properties, conversations, users, markdown, broker);

        alice = user(1L, "alice", "Alice");
        bob = user(2L, "bob", "Bob");
    }

    private static User user(Long id, String username, String displayName) {
        var u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getUsername()).thenReturn(username);
        when(u.getDisplayName()).thenReturn(displayName);
        return u;
    }

    private Conversation conversation(Long id, ConversationType type, User... members) {
        // The member mocks are built and stubbed BEFORE the when() that returns them. Creating a
        // mock inside the argument expression of an outer when() leaves Mockito mid-stubbing and
        // fails with UnfinishedStubbingException, which reports the line of the outer call and
        // says nothing about the nested one.
        var memberMocks = new java.util.ArrayList<ConversationMember>(members.length);
        for (var u : members) {
            var m = mock(ConversationMember.class);
            when(m.getUser()).thenReturn(u);
            memberMocks.add(m);
        }
        var c = mock(Conversation.class);
        when(c.getId()).thenReturn(id);
        when(c.getType()).thenReturn(type);
        when(conversations.requireById(id)).thenReturn(c);
        when(conversations.members(c)).thenReturn(List.copyOf(memberMocks));
        return c;
    }

    private List<CallEvent> eventsTo(String username) {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(broker, org.mockito.Mockito.atLeast(0))
                .convertAndSendToUser(eq(username), eq("/queue/calls"), captor.capture());
        return captor.getAllValues().stream().map(CallEvent.class::cast).toList();
    }

    @Test
    @DisplayName("a 1:1 rings the callee and confirms to the caller")
    void placesACall() {
        conversation(7L, ConversationType.DIRECT, alice, bob);

        service.invite(7L, alice, "session-alice", CallMedia.AUDIO);

        assertThat(eventsTo("bob")).singleElement()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo("invite");
                    assertThat(e.peer()).isEqualTo("alice");
                    assertThat(e.peerDisplayName()).isEqualTo("Alice");
                    // The callee is always the polite peer in perfect negotiation. Assigned by
                    // role, never negotiated, or both sides can end up polite and deadlock.
                    assertThat(e.polite()).isTrue();
                });
        assertThat(eventsTo("alice")).singleElement()
                .satisfies(e -> assertThat(e.type()).isEqualTo("ringing"));
    }

    @Test
    @DisplayName("a group conversation cannot host a call")
    void refusesGroups() {
        // Not a policy choice — the transport is one peer connection and a third participant has
        // nowhere to go. The button is not rendered either; this is the frame-level refusal.
        conversation(8L, ConversationType.GROUP, alice, bob);

        assertThatThrownBy(() -> service.invite(8L, alice, "s", CallMedia.AUDIO))
                .isInstanceOf(CallService.CallsUnavailableException.class);
    }

    @Test
    @DisplayName("a note-to-self has nobody to call")
    void refusesSelfDirect() {
        conversation(9L, ConversationType.DIRECT, alice);

        assertThatThrownBy(() -> service.invite(9L, alice, "s", CallMedia.AUDIO))
                .isInstanceOf(CallService.CallsUnavailableException.class);
    }

    @Test
    @DisplayName("calls are refused outright when no TURN server is configured")
    void refusesWhenUnconfigured() {
        properties.setTurnUrls(List.of());
        conversation(7L, ConversationType.DIRECT, alice, bob);

        assertThatThrownBy(() -> service.invite(7L, alice, "s", CallMedia.AUDIO))
                .isInstanceOf(CallService.CallsUnavailableException.class);
        verify(broker, never()).convertAndSendToUser(eq("bob"), any(), any());
    }

    @Test
    @DisplayName("calling somebody already on a call tells the caller, and starts nothing")
    void reportsBusy() {
        conversation(7L, ConversationType.DIRECT, alice, bob);
        var carol = user(3L, "carol", "Carol");
        conversation(11L, ConversationType.DIRECT, carol, bob);
        service.invite(7L, alice, "session-alice", CallMedia.AUDIO);

        service.invite(11L, carol, "session-carol", CallMedia.AUDIO);

        assertThat(eventsTo("carol")).singleElement()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo("busy");
                    assertThat(e.peer()).isEqualTo("bob");
                });
        // bob heard about alice's call and nothing else — a second invite must not reach him.
        assertThat(eventsTo("bob")).hasSize(1);
    }

    @Test
    @DisplayName("a signal reaches the other participant and nobody else")
    void relaysSignals() {
        conversation(7L, ConversationType.DIRECT, alice, bob);
        service.invite(7L, alice, "session-alice", CallMedia.AUDIO);
        var callId = registry.current("alice").orElseThrow().id();

        service.signal(callId, alice, "offer", JsonNodeFactory.instance.objectNode().put("sdp", "v=0"));

        assertThat(eventsTo("bob")).last()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo("signal");
                    assertThat(e.signalKind()).isEqualTo("offer");
                    assertThat(e.peer()).isEqualTo("alice");
                });
    }

    @Test
    @DisplayName("somebody who is not in the call cannot signal into it")
    void refusesSignalsFromOutsiders() {
        conversation(7L, ConversationType.DIRECT, alice, bob);
        service.invite(7L, alice, "session-alice", CallMedia.AUDIO);
        var callId = registry.current("alice").orElseThrow().id();
        var mallory = user(4L, "mallory", "Mallory");

        assertThatThrownBy(() -> service.signal(callId, mallory, "offer",
                JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an oversized payload is refused rather than relayed")
    void refusesOversizedSignals() {
        conversation(7L, ConversationType.DIRECT, alice, bob);
        service.invite(7L, alice, "session-alice", CallMedia.AUDIO);
        var callId = registry.current("alice").orElseThrow().id();
        var huge = JsonNodeFactory.instance.objectNode().put("sdp", "x".repeat(20_000));

        // Otherwise two authenticated accounts have an unbounded private message bus.
        assertThatThrownBy(() -> service.signal(callId, alice, "offer", huge))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("both parties are told when a call ends")
    void endsTellBothSides() {
        conversation(7L, ConversationType.DIRECT, alice, bob);
        when(users.requireByUsername("alice")).thenReturn(alice);
        service.invite(7L, alice, "session-alice", CallMedia.AUDIO);
        var callId = registry.current("alice").orElseThrow().id();

        service.hangUp(callId, bob, CallEndReason.DECLINED);

        assertThat(eventsTo("alice")).last()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo("ended");
                    assertThat(e.reason()).isEqualTo("DECLINED");
                });
        assertThat(eventsTo("bob")).last()
                .satisfies(e -> assertThat(e.type()).isEqualTo("ended"));
    }

    @Test
    @DisplayName("an unanswered call is archived as a missed call, never as a decline")
    void archivesAMissedCall() {
        // Recording that somebody pressed decline would put a social judgement in permanent
        // writing on their behalf. Every unanswered call reads the same way.
        conversation(7L, ConversationType.DIRECT, alice, bob);
        when(users.requireByUsername("alice")).thenReturn(alice);
        service.invite(7L, alice, "session-alice", CallMedia.AUDIO);
        var callId = registry.current("alice").orElseThrow().id();

        service.hangUp(callId, bob, CallEndReason.DECLINED);

        var body = ArgumentCaptor.forClass(String.class);
        verify(conversations).post(any(), eq(alice), body.capture());
        assertThat(body.getValue()).isEqualTo("_Missed call_");
    }

    @Test
    @DisplayName("an answered call is archived with how long it lasted")
    void archivesTalkTime() {
        conversation(7L, ConversationType.DIRECT, alice, bob);
        when(users.requireByUsername("alice")).thenReturn(alice);
        service.invite(7L, alice, "session-alice", CallMedia.VIDEO);
        var callId = registry.current("alice").orElseThrow().id();
        service.accept(callId, bob, "session-bob");

        service.hangUp(callId, alice, CallEndReason.HANGUP);

        var body = ArgumentCaptor.forClass(String.class);
        verify(conversations).post(any(), eq(alice), body.capture());
        assertThat(body.getValue()).startsWith("_Video call · ");
    }

    @Test
    void humanDurationReadsTheWayPeopleSayIt() {
        assertThat(CallService.humanDuration(Duration.ofSeconds(42))).isEqualTo("42 sec");
        assertThat(CallService.humanDuration(Duration.ofSeconds(59))).isEqualTo("59 sec");
        assertThat(CallService.humanDuration(Duration.ofSeconds(60))).isEqualTo("1 min");
        assertThat(CallService.humanDuration(Duration.ofMinutes(4).plusSeconds(30)))
                .isEqualTo("4 min");
        assertThat(CallService.humanDuration(Duration.ofMinutes(65))).isEqualTo("1 h 05 min");
        assertThat(CallService.humanDuration(Duration.ZERO)).isEqualTo("0 sec");
    }
}
