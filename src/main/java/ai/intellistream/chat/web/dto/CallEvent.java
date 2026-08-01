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

package ai.intellistream.chat.web.dto;

import ai.intellistream.chat.calls.Call;
import ai.intellistream.chat.calls.CallEndReason;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * Everything that arrives on {@code /user/queue/calls}, as one shape with a {@code type} tag.
 *
 * <p>One record rather than five destinations because a call is a state machine and its client is
 * one too: the events have to be processed in order against a single local state, and separate
 * destinations would let "ended" overtake "accepted" on different broker paths and leave a tab
 * showing a call that is over. A single queue gives ordering for free.
 *
 * @param type one of {@code invite}, {@code ringing}, {@code accepted}, {@code ended},
 *        {@code busy}, {@code signal}
 * @param signalKind for {@code signal}: {@code offer}, {@code answer} or {@code candidate}. The
 *        server does not read the payload, but it does route on this, and a client that sends an
 *        unknown kind gets it delivered verbatim to a peer that will ignore it.
 * @param payload opaque SDP or ICE candidate. Deliberately a {@link JsonNode}: the server has no
 *        business parsing session descriptions, and anything it parsed it would eventually be
 *        tempted to validate, which is how a signalling relay grows a WebRTC implementation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CallEvent(String type,
                        String callId,
                        Long conversationId,
                        String peer,
                        String peerDisplayName,
                        String media,
                        Boolean polite,
                        String reason,
                        String signalKind,
                        JsonNode payload) {

    /**
     * Somebody is calling you. Goes to every session the callee has open.
     *
     * @param polite the perfect-negotiation role. The callee is always the polite peer: it is the
     *        side that yields when both ends offer at once, and assigning it by role rather than by
     *        comparing ids keeps the two sides from ever agreeing to both be polite — which
     *        deadlocks negotiation just as surely as both being impolite breaks it.
     */
    public static CallEvent invite(Call call, String callerDisplayName) {
        return new CallEvent("invite", call.id(), call.conversationId(),
                call.caller(), callerDisplayName, call.media().name(),
                true, null, null, null);
    }

    /** Your call is ringing at the other end. Confirms the invite was accepted for delivery. */
    public static CallEvent ringing(Call call, String calleeDisplayName) {
        return new CallEvent("ringing", call.id(), call.conversationId(),
                call.callee(), calleeDisplayName, call.media().name(),
                false, null, null, null);
    }

    /**
     * Answered. Sent to the caller, who starts negotiation, and to every one of the callee's
     * sessions — the tab that answered ignores it, the rest stop ringing.
     */
    public static CallEvent accepted(Call call) {
        return new CallEvent("accepted", call.id(), call.conversationId(),
                null, null, call.media().name(), null, null, null, null);
    }

    /** Over. Both sides tear down; the reason is what the closing line in the UI says. */
    public static CallEvent ended(Call call, CallEndReason reason) {
        return new CallEvent("ended", call.id(), call.conversationId(),
                null, null, null, null, reason.name(), null, null);
    }

    /** They are already on another call. Never becomes a call; the caller's UI says so and stops. */
    public static CallEvent busy(String peer, String peerDisplayName) {
        return new CallEvent("busy", null, null, peer, peerDisplayName,
                null, null, null, null, null);
    }

    /** An SDP or candidate, relayed to the peer untouched. */
    public static CallEvent signal(String callId, String from, String kind, JsonNode payload) {
        return new CallEvent("signal", callId, null, from, null, null, null, null, kind, payload);
    }

    /**
     * The call could not be started at all — calling is not configured here, or the conversation
     * cannot host one.
     *
     * <p>Distinct from {@code ended} because nothing began: there is no call id to tear down and
     * nothing to write into the archive. The client's own optimistic "calling…" state is the only
     * thing that needs retiring, and it needs an event that says so, or it sits there ringing at a
     * call the server never created.
     */
    public static CallEvent failed(String reason) {
        return new CallEvent("failed", null, null, null, null, null, null, reason, null, null);
    }
}
