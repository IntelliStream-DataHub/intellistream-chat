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

package ai.intellistream.chat.web;

import ai.intellistream.chat.calls.CallEndReason;
import ai.intellistream.chat.calls.CallService;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.web.dto.CallEvent;
import ai.intellistream.chat.web.dto.CallSignalRequest;
import ai.intellistream.chat.web.dto.StartCallRequest;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Duration;

/**
 * Call signalling over the socket that already exists.
 *
 * <p>Everything here is pairwise and goes to {@code /user/queue/calls} — never to a topic. An SDP
 * offer is addressed to exactly one peer, and putting one on a conversation topic would hand a
 * media session's negotiation to every subscriber including the ones who are not on the call.
 * Spring resolves the user destination per session, so an invite reaches every tab the callee has
 * open, which is what makes the phone ring in the right places.
 *
 * <p><b>Authorisation is not repeated here.</b> Starting a call goes through
 * {@code CallService.requireCallable}, which applies the same membership rule as posting into the
 * conversation; everything afterwards is authorised by the call itself, since the registry knows
 * who its two participants are. That is deliberate — re-deriving membership per signal frame would
 * put a database query on the chattiest path in the feature.
 */
@Controller
public class CallWebSocketController {

    private final CallService calls;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;
    private final SimpMessagingTemplate broker;

    public CallWebSocketController(CallService calls,
                                   CurrentUser currentUser,
                                   RateLimiter rateLimiter,
                                   SimpMessagingTemplate broker) {
        this.calls = calls;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
        this.broker = broker;
    }

    @MessageMapping("/calls/invite")
    public void invite(@Valid StartCallRequest payload,
                       Principal principal,
                       SimpMessageHeaderAccessor headers) {
        var user = currentUser.resolve(principal);
        // Low, because placing a call is a deliberate human act and ten a minute is already someone
        // hammering a button. It is also the one frame here that rings somebody else's devices, so
        // it is the one worth rate-limiting as harassment rather than as load.
        if (!rateLimiter.tryAcquire(user.getUsername(), "call-invite", 10, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("call rate exceeded");
        }
        try {
            calls.invite(payload.conversationId(), user, headers.getSessionId(), payload.media());
        } catch (CallService.CallsUnavailableException e) {
            // Answer the caller rather than letting this reach the generic notice handler: their
            // client is sitting in an optimistic "calling…" state that only a call event retires.
            broker.convertAndSendToUser(user.getUsername(), "/queue/calls",
                    CallEvent.failed("UNAVAILABLE"));
        }
    }

    @MessageMapping("/calls/{callId}/accept")
    public void accept(@DestinationVariable String callId,
                       Principal principal,
                       SimpMessageHeaderAccessor headers) {
        var user = currentUser.resolve(principal);
        guardControlRate(user.getUsername());
        calls.accept(callId, user, headers.getSessionId());
    }

    /**
     * Decline and hang up are the same transition with different words on it, and they are separate
     * destinations so the reason travels without the client having to be trusted to name it. A
     * decline can only come from a ringing call, a hangup from a live one, and neither client gets
     * to decide which of those the archive and the other party are told.
     */
    @MessageMapping("/calls/{callId}/decline")
    public void decline(@DestinationVariable String callId, Principal principal) {
        var user = currentUser.resolve(principal);
        guardControlRate(user.getUsername());
        calls.hangUp(callId, user, CallEndReason.DECLINED);
    }

    @MessageMapping("/calls/{callId}/hangup")
    public void hangup(@DestinationVariable String callId, Principal principal) {
        var user = currentUser.resolve(principal);
        guardControlRate(user.getUsername());
        // The caller abandoning a call nobody answered yet is a cancellation rather than a hangup,
        // but the registry knows whether it was ever answered and this does not — so both arrive as
        // HANGUP and the distinction is made where the state actually lives.
        calls.hangUp(callId, user, CallEndReason.HANGUP);
    }

    /**
     * Relay one SDP or candidate.
     *
     * <p><b>The budget here is the one that matters.</b> Trickle ICE emits a candidate per network
     * interface per protocol as they are discovered — dozens in the first seconds of a call, and
     * again on every ICE restart — so a limit sized like the typing indicator's sixty a minute would
     * shred call setup. Worse, it would shred it silently and asymmetrically: the frames that got
     * through would establish a connection over the candidates that survived, so the symptom is not
     * "calls fail" but "calls sometimes connect with one-way audio", which is close to undebuggable
     * from a bug report. Six hundred a minute is far above any real negotiation and still bounds a
     * client trying to use the relay as a chat channel.
     */
    @MessageMapping("/calls/{callId}/signal")
    public void signal(@DestinationVariable String callId,
                       @Valid CallSignalRequest payload,
                       Principal principal) {
        var user = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(user.getUsername(), "call-signal", 600, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("call signalling rate exceeded");
        }
        calls.signal(callId, user, payload.kind(), payload.payload());
    }

    /**
     * Shared budget for accept/decline/hangup. Generous because a client legitimately sends a
     * hangup on teardown, on page unload and on a connection-state failure, and any of those can
     * arrive together; low enough that these cannot be used to hammer the registry's monitor.
     */
    private void guardControlRate(String username) {
        if (!rateLimiter.tryAcquire(username, "call-control", 120, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("call control rate exceeded");
        }
    }
}
