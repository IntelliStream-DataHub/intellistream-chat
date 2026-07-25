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

package ai.intellistream.chat.moderation;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Eviction is the half of a ban that people actually notice, so these pin the lifecycle: a socket
 * is tracked from the moment it is accepted, tied to an account at CONNECT, closed on suspension,
 * and forgotten when it goes away.
 */
class SuspendedSessionEvictorTest {

    private final SuspendedSessionEvictor evictor = new SuspendedSessionEvictor();
    private final WebSocketHandler decorated = evictor.decorate(mock(WebSocketHandler.class));

    private WebSocketSession open(String id) throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        decorated.afterConnectionEstablished(session);
        return session;
    }

    @Test
    void closesOnlyTheSuspendedAccountsSockets() throws Exception {
        var bobTab1 = open("s1");
        var bobTab2 = open("s2");
        var alice = open("s3");
        evictor.bind("s1", 42L);
        evictor.bind("s2", 42L);
        evictor.bind("s3", 7L);

        var closed = evictor.closeAllFor(42L);

        assertThat(closed).isEqualTo(2);
        verify(bobTab1).close(any(CloseStatus.class));
        verify(bobTab2).close(any(CloseStatus.class));
        verify(alice, never()).close(any(CloseStatus.class));
    }

    @Test
    void closesWithPolicyViolationSoTheReasonIsOnTheWire() throws Exception {
        var session = open("s1");
        evictor.bind("s1", 42L);

        evictor.closeAllFor(42L);

        verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("Account suspended"));
    }

    @Test
    void anUnboundSocketIsNeverClosedByAccident() throws Exception {
        // A connection that has not completed STOMP CONNECT belongs to nobody yet — and cannot
        // subscribe or send, because that CONNECT re-reads the account from the database.
        var session = open("s1");

        assertThat(evictor.closeAllFor(42L)).isZero();
        assertThat(evictor.closeAllFor(null)).isZero();
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void aClosedSocketIsForgotten() throws Exception {
        var session = open("s1");
        evictor.bind("s1", 42L);

        decorated.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(evictor.liveSessionCount()).isZero();
        assertThat(evictor.closeAllFor(42L)).isZero();
    }

    @Test
    void bindingAnUnknownSessionDoesNotResurrectIt() {
        evictor.bind("gone", 42L);

        assertThat(evictor.liveSessionCount()).isZero();
    }

    @Test
    void oneUncloseableSocketDoesNotStopTheRest() throws Exception {
        var stuck = open("s1");
        var fine = open("s2");
        evictor.bind("s1", 42L);
        evictor.bind("s2", 42L);
        doThrow(new IOException("peer gone")).when(stuck).close(any(CloseStatus.class));

        var closed = evictor.closeAllFor(42L);

        // The suspension must not fail because a half-dead socket refused to hang up.
        assertThat(closed).isEqualTo(1);
        verify(fine).close(any(CloseStatus.class));
    }
}
