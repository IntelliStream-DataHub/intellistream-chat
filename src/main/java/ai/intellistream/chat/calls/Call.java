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

import java.time.Duration;
import java.time.Instant;

/**
 * One 1:1 call, as the server knows it.
 *
 * <p>Immutable, and replaced wholesale in {@link CallRegistry} on every transition. The registry
 * hands these out to publishers and schedulers that will read them on other threads after the state
 * has moved on again; a mutable object would have those readers describing a call that no longer
 * looks like that, which is precisely the bug that makes a stuck "ringing" UI unreproducible.
 *
 * <p>The server deliberately knows nothing about the media itself — no SDP, no candidates, no track
 * state. It knows who is calling whom, whether they answered and when it ended, because that is what
 * ringing, busy-checking and the archive line need. Everything else belongs to the two peers.
 *
 * <p><b>The two session ids are the device identity, and they come from STOMP rather than from the
 * client.</b> A call belongs to the tab that placed or answered it, not to the account: somebody
 * with the workspace open on a laptop and a phone has two sessions, only one of them is on the call,
 * and closing the other must not hang it up. Using the session id the broker already assigned means
 * there is no client-minted device id to forge, and no second identity scheme to keep in step with
 * the first.
 */
public record Call(String id,
                   Long conversationId,
                   String caller,
                   String callee,
                   CallMedia media,
                   CallState state,
                   String callerSession,
                   String calleeSession,
                   Instant ringingSince,
                   Instant answeredAt,
                   Instant endedAt,
                   CallEndReason endReason) {

    static Call ringing(String id, Long conversationId, String caller, String callee,
                        CallMedia media, String callerSession, Instant now) {
        return new Call(id, conversationId, caller, callee, media,
                CallState.RINGING, callerSession, null, now, null, null, null);
    }

    Call answered(String calleeSession, Instant now) {
        return new Call(id, conversationId, caller, callee, media,
                CallState.ACTIVE, callerSession, calleeSession, ringingSince, now, null, null);
    }

    Call ended(CallEndReason reason, Instant now) {
        return new Call(id, conversationId, caller, callee, media,
                CallState.ENDED, callerSession, calleeSession, ringingSince, answeredAt, now, reason);
    }

    /** The other party's username, or null if {@code username} is not in this call. */
    public String peerOf(String username) {
        if (caller.equals(username)) return callee;
        if (callee.equals(username)) return caller;
        return null;
    }

    public boolean involves(String username) {
        return caller.equals(username) || callee.equals(username);
    }

    /**
     * Whether this call is tied to the given STOMP session — the tab that placed it, or the one
     * that answered. A ringing call has no callee session yet, which is correct: until somebody
     * picks up, every one of the callee's tabs is a candidate and none of them owns the call.
     */
    public boolean usesSession(String sessionId) {
        return sessionId != null
                && (sessionId.equals(callerSession) || sessionId.equals(calleeSession));
    }

    /**
     * How long the two were connected, or {@link Duration#ZERO} for a call that never was.
     *
     * <p>Measured from the answer rather than from the invite on purpose: the archive line says
     * "Call ended · 4 min", and counting the ringing into that would tell two people they spoke for
     * longer than they did.
     */
    public Duration talkTime() {
        if (answeredAt == null || endedAt == null) return Duration.ZERO;
        return Duration.between(answeredAt, endedAt);
    }
}
