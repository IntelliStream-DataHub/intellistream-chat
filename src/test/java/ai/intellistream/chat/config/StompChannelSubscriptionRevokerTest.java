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
 * The failure mode a page-reload test cannot see.
 *
 * <p>STOMP SUBSCRIBE is authorised once, when the frame arrives, and the broker never re-checks. So
 * a user with an open socket who is removed from a private channel keeps receiving everything
 * broadcast to it — evicting the access cache stops them subscribing <em>again</em> and does nothing
 * about the subscription they already hold. Reloading the page is exactly the thing that hides this,
 * which is why it is asserted here at the frame level.
 */
class StompChannelSubscriptionRevokerTest {

    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    private final SimpUserRegistry registry = mock(SimpUserRegistry.class);
    private final MessageChannel inbound = mock(MessageChannel.class);
    private final SuspendedSessionEvictor sessions = new SuspendedSessionEvictor();
    private final StompChannelSubscriptionRevoker revoker =
            new StompChannelSubscriptionRevoker(registry, inbound, sessions);

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
    void revokesTheChannelTopicAndItsTypingSibling() {
        openSession("s1", ALICE);
        registryHolds("s1",
                subscription("sub-1", "/topic/channels/42"),
                subscription("sub-2", "/topic/channels/42/typing"));

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
                subscription("keep-1", "/topic/channels/7"),
                subscription("keep-2", "/topic/conversations/42"),
                subscription("keep-3", "/topic/presence"),
                subscription("kill-1", "/topic/channels/42"));

        revoker.revoke(42L, ALICE);

        // The point of unsubscribing rather than closing the socket: the user keeps their DMs, their
        // other channels and their presence feed.
        assertThat(sentFrames())
                .extracting(f -> SimpMessageHeaderAccessor.getSubscriptionId(f.getHeaders()))
                .containsExactly("kill-1");
    }

    @Test
    void doesNotTouchAnotherUsersSubscriptionToTheSameChannel() {
        openSession("s-bob", BOB);
        registryHolds("s-bob", subscription("bobs-sub", "/topic/channels/42"));

        // Alice left; Bob is still in the channel and his session id is not one of hers. The
        // registry alone cannot tell them apart — SimpUser.getName() is the security principal's
        // name, which is not the domain username — so the session-id map is what makes this safe.
        revoker.revoke(42L, ALICE);

        verify(inbound, never()).send(any());
    }

    @Test
    void aUserWithNothingOpenIsANoOp() {
        revoker.revoke(42L, ALICE);

        verify(registry, never()).getUsers();
        verify(inbound, never()).send(any());
    }

    @Test
    void channelIdPrefixesDoNotCollide() {
        openSession("s1", ALICE);
        registryHolds("s1",
                subscription("other", "/topic/channels/420"),
                subscription("mine", "/topic/channels/42"));

        // "/topic/channels/42" must not match channel 420. A bare startsWith on the topic would.
        revoker.revoke(42L, ALICE);

        assertThat(sentFrames())
                .extracting(f -> SimpMessageHeaderAccessor.getSubscriptionId(f.getHeaders()))
                .containsExactly("mine");
    }
}
