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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Hangs up the call a closed tab was on.
 *
 * <p>The ring timeout would eventually catch this, but "eventually" is up to forty-five seconds of
 * a phone ringing for somebody who has already gone — and if the call was answered rather than
 * ringing, the timeout does not apply at all and the other side sits watching a call timer count
 * up against nobody. A disconnect is the earliest honest signal that one end is gone.
 *
 * <p>Keyed on the STOMP session, not the account, which is why this is a separate listener from
 * {@code PresenceEventListener} rather than a branch inside it. Presence asks "is this person still
 * here anywhere", and answering it means consulting the tracker after it has processed the same
 * event — an ordering dependency between two listeners. A call belongs to one tab, the event names
 * that tab, and this needs to know nothing else.
 */
@Component
public class CallSessionListener {

    private static final Logger log = LoggerFactory.getLogger(CallSessionListener.class);

    private final CallService calls;

    public CallSessionListener(CallService calls) {
        this.calls = calls;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        var sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (sessionId == null) return;
        try {
            calls.endForSession(sessionId);
        } catch (RuntimeException e) {
            // A disconnect is not something to fail. Losing this leaves the ring timeout as the
            // backstop, which is the behaviour that predates this listener.
            log.warn("Could not end calls for disconnected session {}", sessionId, e);
        }
    }
}
