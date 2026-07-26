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

package ai.intellistream.chat.config;

import ai.intellistream.chat.moderation.SuspendedSessionEvictor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The half of leaving a group conversation that a page-reload test cannot see.
 *
 * <p>STOMP SUBSCRIBE is authorised once, when the frame arrives, and the broker never re-checks. So
 * somebody who leaves a group DM with an open socket keeps receiving every message sent to it until
 * they happen to reconnect — and reloading the page is precisely the thing that hides that. Asserted
 * here at the frame level, exactly as the channel side is.
 *
 * <p>A conversation has no PUBLIC tier, so unlike a channel there is no reading of the rules under
 * which the leaked messages would have been visible anyway. Every one of them is private.
 */
class StompConversationSubscriptionRevokerTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    private final SimpUserRegistry registry = mock(SimpUserRegistry.class);
    private final MessageChannel inbound = mock(MessageChannel.class);
    private final SuspendedSessionEvictor sessions = new SuspendedSessionEvictor();
    private final StompConversationSubscriptionRevoker revoker =
            new StompConversationSubscriptionRevoker(registry, inbound, sessions);

    /** Register a live socket with the evictor the way the real CONNECT path does. */
    private void openSession(String sessionId, long userId) {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        try {
            sessions.decorate(mock(WebSocketHandler.class)).afterConnectionEstablished(socket);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        sessions.bind(sessionId, userId);
    }

    private static SimpSubscription subscription(String id, String destination) {
        var sub = mock(SimpSubscription.class);
        when(sub.getId()).thenReturn(id);
        when(sub.getDestination()).thenReturn(destination);
        return sub;
    }

    private void registryHolds(String sessionId, SimpSubscription... subscriptions) {
        var session = mock(SimpSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getSubscriptions()).thenReturn(Set.of(subscriptions));
        var user = mock(SimpUser.class);
        when(user.getSessions()).thenReturn(Set.of(session));
        when(registry.getUsers()).thenReturn(Set.of(user));
    }

    private List<Message<?>> sentFrames() {
        var captor = ArgumentCaptor.<Message<?>>captor();
        verify(inbound, org.mockito.Mockito.atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void revokesTheConversationTopicAndItsTypingSibling() {
        openSession("s1", ALICE);
        registryHolds("s1",
                subscription("sub-1", "/topic/conversations/42"),
                subscription("sub-2", "/topic/conversations/42/typing"));

        revoker.revoke(42L, ALICE);

        var frames = sentFrames();
        assertThat(frames).hasSize(2);
        assertThat(frames).allSatisfy(frame -> {
            var accessor = StompHeaderAccessor.wrap(frame);
            assertThat(accessor.getCommand()).isEqualTo(StompCommand.UNSUBSCRIBE);
            assertThat(accessor.getSessionId()).isEqualTo("s1");
        });
        assertThat(frames).extracting(f -> SimpMessageHeaderAccessor.getSubscriptionId(f.getHeaders()))
                .containsExactlyInAnyOrder("sub-1", "sub-2");
    }

    @Test
    void leavesEveryOtherSubscriptionAlone() {
        openSession("s1", ALICE);
        registryHolds("s1",
                subscription("keep-1", "/topic/conversations/7"),
                subscription("keep-2", "/topic/channels/42"),
                subscription("keep-3", "/topic/presence"),
                subscription("kill-1", "/topic/conversations/42"));

        revoker.revoke(42L, ALICE);

        // The point of unsubscribing rather than closing the socket: leaving one group DM must not
        // cost the user their channels, their other DMs or their presence feed.
        assertThat(sentFrames())
                .extracting(f -> SimpMessageHeaderAccessor.getSubscriptionId(f.getHeaders()))
                .containsExactly("kill-1");
    }

    @Test
    void conversation42DoesNotTakeConversation420WithIt() {
        // Equality-or-slash, not a bare startsWith. The prefix test is the whole reason the sweep is
        // shared code rather than written twice.
        openSession("s1", ALICE);
        registryHolds("s1",
                subscription("keep-1", "/topic/conversations/420"),
                subscription("kill-1", "/topic/conversations/42"));

        revoker.revoke(42L, ALICE);

        assertThat(sentFrames())
                .extracting(f -> SimpMessageHeaderAccessor.getSubscriptionId(f.getHeaders()))
                .containsExactly("kill-1");
    }

    @Test
    void doesNotTouchAnotherMembersSubscriptionToTheSameConversation() {
        openSession("s-bob", BOB);
        registryHolds("s-bob", subscription("bobs-sub", "/topic/conversations/42"));

        // Alice left; Bob is still in the group. The registry alone cannot tell them apart —
        // SimpUser.getName() is the security principal's name, which is not the domain username for
        // email-shaped accounts — so the session-id map is what makes this safe.
        revoker.revoke(42L, ALICE);

        verify(inbound, never()).send(any());
    }

    @Test
    void aUserWithNothingOpenIsANoOp() {
        revoker.revoke(42L, ALICE);

        verify(registry, never()).getUsers();
        verify(inbound, never()).send(any());
    }
}
