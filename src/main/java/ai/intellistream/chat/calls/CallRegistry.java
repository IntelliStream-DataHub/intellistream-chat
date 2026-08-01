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

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory state for calls that are ringing or in progress. No messaging, no Spring beyond the
 * stereotype, no database — which is what lets the whole ring state machine be unit tested without
 * a context, and it is worth testing, because every interesting failure in a calling feature is a
 * state machine bug rather than a media bug.
 *
 * <p><b>Nothing is persisted.</b> A call is a live thing; when the process stops there are no calls,
 * and a row saying otherwise would be a lie that outlived its subject. What survives a restart is
 * the line written into the conversation when the call ended, which is the part anybody will ever
 * read again. This also means calls do not survive a rolling restart — the peers' media connections
 * would in fact keep working, since the server is not in the media path, but neither side could hang
 * up through a server that has forgotten the call, so {@code CallService} tears them down on the way
 * out rather than leaving two people connected to a call the server denies exists.
 *
 * <p><b>Every transition holds the monitor.</b> Two maps have to move together — a call's state and
 * the "who is busy" index — and a call that ended while leaving its participants marked busy is a
 * user who can never be called again until the process restarts. Call volume is a handful a minute
 * even in a large workspace, so the coarse lock costs nothing and rules out the whole class of
 * interleaving bug.
 *
 * <p><b>Single process.</b> Like {@code RateLimiter}, this is per-node state: a second instance
 * would not know a call was ringing on the first. Deliberate while the deployment is one JVM, and
 * listed with the rest of horizontal scaling.
 */
@Component
public class CallRegistry {

    /** Live calls only, by id. An ended call is removed — see {@link #end}. */
    private final Map<String, Call> calls = new HashMap<>();

    /**
     * username → id of the call they are in. The busy index, and the reason a second invite to
     * somebody already ringing is refused rather than queued: two calls arriving at one person is a
     * UI with no good answer, and "they are on another call" is one.
     */
    private final Map<String, String> engaged = new HashMap<>();

    private final Clock clock;

    public CallRegistry() {
        this(Clock.systemUTC());
    }

    /** Test seam — a fixed or advanceable clock makes ring timeouts assertable. */
    public CallRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Start ringing. The call exists from this moment, so a hangup arriving before the callee's
     * devices have even rendered the invite still finds something to cancel.
     *
     * @throws CallBusyException if either party is already in a call. The caller being busy is
     *         normally a stale tab rather than a real second call, but refusing is still right:
     *         whatever the client thinks, this account already has a media session the server knows
     *         about, and starting another would leave the first unreachable.
     * @throws IllegalArgumentException if someone calls themselves. The UI cannot produce this — a
     *         self-DM has no call button — so it means a hand-made frame.
     */
    public synchronized Call invite(Long conversationId, String caller, String callee,
                                    CallMedia media, String callerSession) {
        if (caller.equals(callee)) {
            throw new IllegalArgumentException("Cannot call yourself");
        }
        requireFree(caller);
        requireFree(callee);

        var call = Call.ringing(UUID.randomUUID().toString(), conversationId, caller, callee,
                media, callerSession, clock.instant());
        calls.put(call.id(), call);
        engaged.put(caller, call.id());
        engaged.put(callee, call.id());
        return call;
    }

    /**
     * Answer a ringing call.
     *
     * <p>{@code calleeSession} is the STOMP session of the tab that picked up, and recording it is
     * the whole of the multi-device story: the invite went to every session the callee had open, so
     * every one of them is ringing, and the answer has to stop the rest. Racing answers from two
     * tabs resolve here rather than in the client, because only one of them can hold this monitor —
     * the second sees a call that is no longer RINGING and gets nothing back.
     *
     * <p>The losing tabs are told to stop by the {@code call-ended}-shaped event {@code CallService}
     * publishes to the callee's user queue, which reaches every session including the winner's. No
     * device comparison is needed to sort that out: the tab that answered knows it answered, from
     * its own state, and ignores it.
     *
     * @return the now-ACTIVE call, or empty if it is not ringing any more (already answered,
     *         declined, cancelled, or timed out while the callee was reaching for the button)
     */
    public synchronized Optional<Call> accept(String callId, String callee, String calleeSession) {
        var call = calls.get(callId);
        if (call == null || call.state() != CallState.RINGING) return Optional.empty();
        // Only the callee answers. The caller sending an accept for their own call is a hand-made
        // frame; ignoring it is enough, since it changes nothing.
        if (!call.callee().equals(callee)) return Optional.empty();

        var answered = call.answered(calleeSession, clock.instant());
        calls.put(callId, answered);
        return Optional.of(answered);
    }

    /**
     * End a call, whoever is ending it and for whatever reason.
     *
     * <p>Idempotent: a hangup for a call that already ended returns empty rather than throwing, and
     * that matters because both peers hang up in a race routinely — one presses the button, the
     * other's client sees the connection drop and sends its own. Exactly one of them gets the call
     * back and writes the archive line.
     *
     * @param actor who is ending it, or null for the server itself (timeout sweep, disconnect).
     *        A non-null actor must be in the call; anyone else asking is refused with empty.
     */
    public synchronized Optional<Call> end(String callId, String actor, CallEndReason reason) {
        var call = calls.get(callId);
        if (call == null) return Optional.empty();
        if (actor != null && !call.involves(actor)) return Optional.empty();

        calls.remove(callId);
        release(call.caller(), callId);
        release(call.callee(), callId);
        return Optional.of(call.ended(reason, clock.instant()));
    }

    /** A live call by id, for validating that a signal frame belongs to something real. */
    public synchronized Optional<Call> find(String callId) {
        return Optional.ofNullable(calls.get(callId));
    }

    /** The call this user is in, if any. */
    public synchronized Optional<Call> current(String username) {
        var id = engaged.get(username);
        return id == null ? Optional.empty() : Optional.ofNullable(calls.get(id));
    }

    /**
     * End every call that has been ringing longer than {@code timeout}.
     *
     * <p>This is also the backstop for a caller who vanished without a hangup in a way the
     * disconnect hook missed — a laptop lid closed hard enough that no close frame ever arrived.
     * Without it the callee's phone rings forever and both accounts stay marked busy, which is the
     * one failure mode of this feature that a user cannot clear themselves.
     *
     * @return the ended calls, for the caller to publish and archive
     */
    public synchronized List<Call> expireRinging(Duration timeout) {
        var cutoff = clock.instant().minus(timeout);
        var expired = new ArrayList<Call>();
        for (var call : List.copyOf(calls.values())) {
            if (call.state() == CallState.RINGING && call.ringingSince().isBefore(cutoff)) {
                end(call.id(), null, CallEndReason.TIMEOUT).ifPresent(expired::add);
            }
        }
        return expired;
    }

    /**
     * End every call that belonged to a STOMP session that has gone away.
     *
     * <p>Scoped to the session rather than the account because that is what a call belongs to. A
     * user with the workspace open on a laptop and a phone has two sessions; closing the phone must
     * not hang up the call running on the laptop, and an account-wide sweep would. A ringing call
     * matches only on the caller's session, so the callee closing one of five tabs while the other
     * four ring changes nothing — which is the behaviour you want and the one an account-scoped
     * version silently gets wrong.
     */
    public synchronized List<Call> endForSession(String sessionId, CallEndReason reason) {
        if (sessionId == null) return List.of();
        var ended = new ArrayList<Call>(1);
        for (var call : List.copyOf(calls.values())) {
            if (call.usesSession(sessionId)) {
                end(call.id(), null, reason).ifPresent(ended::add);
            }
        }
        return ended;
    }

    /**
     * End every call this user is in, whichever session it belongs to. For account-level events —
     * a suspension, a ban — where the point is that this person stops talking everywhere at once.
     */
    public synchronized List<Call> endAllFor(String username, CallEndReason reason) {
        var ended = new ArrayList<Call>(1);
        for (var call : List.copyOf(calls.values())) {
            if (call.involves(username)) {
                end(call.id(), null, reason).ifPresent(ended::add);
            }
        }
        return ended;
    }

    /** Every live call, for the shutdown teardown. */
    public synchronized List<Call> live() {
        return List.copyOf(calls.values());
    }

    private void requireFree(String username) {
        var existing = engaged.get(username);
        if (existing != null && calls.containsKey(existing)) {
            throw new CallBusyException(username);
        }
        // Stale index entry for a call that is already gone — clear it rather than refusing, or a
        // bug anywhere in the teardown path becomes an account that can never call again.
        engaged.remove(username);
    }

    private void release(String username, String callId) {
        engaged.remove(username, callId);
    }
}
