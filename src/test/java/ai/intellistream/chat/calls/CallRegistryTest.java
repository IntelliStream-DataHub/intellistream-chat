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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ring state machine. Every interesting failure of a calling feature lives here rather than in
 * the media — "it kept ringing after they hung up", "I can't call anyone any more" — so this is
 * where the tests are.
 */
class CallRegistryTest {

    /** A clock the test can push forward, for ring timeouts and talk time. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-01T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration by) { now = now.plus(by); }
    }

    private TestClock clock;
    private CallRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        registry = new CallRegistry(clock);
    }

    private Call ring() {
        return registry.invite(7L, "alice", "bob", CallMedia.AUDIO, "session-alice");
    }

    @Nested
    @DisplayName("placing a call")
    class Placing {

        @Test
        void startsRingingWithBothPartiesEngaged() {
            var call = ring();

            assertThat(call.state()).isEqualTo(CallState.RINGING);
            assertThat(call.caller()).isEqualTo("alice");
            assertThat(call.callee()).isEqualTo("bob");
            assertThat(call.callerSession()).isEqualTo("session-alice");
            assertThat(call.calleeSession()).isNull();
            assertThat(registry.current("alice")).contains(call);
            assertThat(registry.current("bob")).contains(call);
        }

        @Test
        void refusesASecondCallToSomeoneAlreadyRinging() {
            ring();

            assertThatThrownBy(() ->
                    registry.invite(9L, "carol", "bob", CallMedia.AUDIO, "session-carol"))
                    .isInstanceOf(CallBusyException.class)
                    .extracting(e -> ((CallBusyException) e).getUsername())
                    .isEqualTo("bob");
        }

        @Test
        void refusesCallingYourself() {
            // The UI cannot produce this — a note-to-self has no call button — so reaching it means
            // a hand-made frame, and it must not create a call whose two ends are one person.
            assertThatThrownBy(() ->
                    registry.invite(7L, "alice", "alice", CallMedia.AUDIO, "s"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void allowsANewCallOnceTheLastOneEnded() {
            var first = ring();
            registry.end(first.id(), "alice", CallEndReason.HANGUP);

            var second = registry.invite(7L, "alice", "bob", CallMedia.VIDEO, "session-alice-2");

            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(second.state()).isEqualTo(CallState.RINGING);
        }
    }

    @Nested
    @DisplayName("answering")
    class Answering {

        @Test
        void recordsTheAnsweringSession() {
            var call = ring();

            var answered = registry.accept(call.id(), "bob", "session-bob").orElseThrow();

            assertThat(answered.state()).isEqualTo(CallState.ACTIVE);
            assertThat(answered.calleeSession()).isEqualTo("session-bob");
            assertThat(answered.answeredAt()).isNotNull();
        }

        @Test
        void onlyOneOfTwoRacingTabsWins() {
            // Both of bob's tabs are ringing and both press answer. The second must get nothing,
            // or two peer connections negotiate against one caller.
            var call = ring();

            var first = registry.accept(call.id(), "bob", "session-bob-laptop");
            var second = registry.accept(call.id(), "bob", "session-bob-phone");

            assertThat(first).isPresent();
            assertThat(second).isEmpty();
            assertThat(registry.find(call.id()).orElseThrow().calleeSession())
                    .isEqualTo("session-bob-laptop");
        }

        @Test
        void theCallerCannotAnswerTheirOwnCall() {
            var call = ring();

            assertThat(registry.accept(call.id(), "alice", "session-alice")).isEmpty();
        }

        @Test
        void answeringAnEndedCallDoesNothing() {
            var call = ring();
            registry.end(call.id(), "alice", CallEndReason.CANCELLED);

            assertThat(registry.accept(call.id(), "bob", "session-bob")).isEmpty();
        }
    }

    @Nested
    @DisplayName("ending")
    class Ending {

        @Test
        void freesBothPartiesToCallAgain() {
            var call = ring();

            registry.end(call.id(), "bob", CallEndReason.DECLINED);

            assertThat(registry.current("alice")).isEmpty();
            assertThat(registry.current("bob")).isEmpty();
            assertThat(registry.find(call.id())).isEmpty();
        }

        @Test
        void isIdempotentSoBothPeersMayHangUpAtOnce() {
            // Routine: one presses the button, the other's client sees the connection drop and
            // sends its own hangup. Exactly one may come back with the call, or the archive gets
            // two lines for one conversation.
            var call = ring();

            var first = registry.end(call.id(), "alice", CallEndReason.HANGUP);
            var second = registry.end(call.id(), "bob", CallEndReason.HANGUP);

            assertThat(first).isPresent();
            assertThat(second).isEmpty();
        }

        @Test
        void refusesAnOutsider() {
            var call = ring();

            assertThat(registry.end(call.id(), "mallory", CallEndReason.HANGUP)).isEmpty();
            assertThat(registry.find(call.id())).isPresent();
        }

        @Test
        void carriesTheReasonAndTheTalkTime() {
            var call = ring();
            registry.accept(call.id(), "bob", "session-bob");
            clock.advance(Duration.ofMinutes(4));

            var ended = registry.end(call.id(), "alice", CallEndReason.HANGUP).orElseThrow();

            assertThat(ended.endReason()).isEqualTo(CallEndReason.HANGUP);
            // Measured from the answer, not the invite — the ringing is not part of the call.
            assertThat(ended.talkTime()).isEqualTo(Duration.ofMinutes(4));
        }

        @Test
        void anUnansweredCallHasNoTalkTime() {
            var call = ring();
            clock.advance(Duration.ofSeconds(30));

            var ended = registry.end(call.id(), "alice", CallEndReason.CANCELLED).orElseThrow();

            assertThat(ended.talkTime()).isZero();
        }
    }

    @Nested
    @DisplayName("ring timeout")
    class Timeout {

        @Test
        void endsACallNobodyAnswered() {
            var call = ring();
            clock.advance(Duration.ofSeconds(46));

            var expired = registry.expireRinging(Duration.ofSeconds(45));

            assertThat(expired).singleElement()
                    .satisfies(c -> {
                        assertThat(c.id()).isEqualTo(call.id());
                        assertThat(c.endReason()).isEqualTo(CallEndReason.TIMEOUT);
                    });
            assertThat(registry.current("bob")).isEmpty();
        }

        @Test
        void leavesACallThatWasAnswered() {
            // The timeout is for ringing only. An hour-long call is not a stuck one.
            var call = ring();
            registry.accept(call.id(), "bob", "session-bob");
            clock.advance(Duration.ofHours(1));

            assertThat(registry.expireRinging(Duration.ofSeconds(45))).isEmpty();
            assertThat(registry.find(call.id())).isPresent();
        }

        @Test
        void leavesACallStillWithinItsWindow() {
            ring();
            clock.advance(Duration.ofSeconds(44));

            assertThat(registry.expireRinging(Duration.ofSeconds(45))).isEmpty();
        }
    }

    @Nested
    @DisplayName("disconnects")
    class Disconnects {

        @Test
        void endTheCallTheClosedTabWasOn() {
            var call = ring();
            registry.accept(call.id(), "bob", "session-bob");

            var ended = registry.endForSession("session-bob", CallEndReason.DISCONNECTED);

            assertThat(ended).singleElement()
                    .satisfies(c -> assertThat(c.endReason()).isEqualTo(CallEndReason.DISCONNECTED));
            assertThat(registry.current("alice")).isEmpty();
        }

        @Test
        void ignoreAnUnrelatedTabOfSomeoneOnACall() {
            // bob has the workspace open on a laptop and a phone; the call is on the laptop.
            // Closing the phone must not hang up the call — the single most annoying way for a
            // session-blind implementation to fail.
            var call = ring();
            registry.accept(call.id(), "bob", "session-bob-laptop");

            var ended = registry.endForSession("session-bob-phone", CallEndReason.DISCONNECTED);

            assertThat(ended).isEmpty();
            assertThat(registry.find(call.id())).isPresent();
        }

        @Test
        void endARingingCallWhenTheCallerVanishes() {
            var call = ring();

            var ended = registry.endForSession("session-alice", CallEndReason.DISCONNECTED);

            assertThat(ended).hasSize(1);
            assertThat(registry.find(call.id())).isEmpty();
        }

        @Test
        void doNotEndARingingCallWhenOneOfTheCalleesTabsCloses() {
            // Nobody has answered, so no tab owns the call yet. Closing one of five ringing tabs
            // leaves the other four ringing.
            var call = ring();

            assertThat(registry.endForSession("session-bob-phone", CallEndReason.DISCONNECTED))
                    .isEmpty();
            assertThat(registry.find(call.id())).isPresent();
        }
    }

    @Test
    void aStaleBusyEntryDoesNotLockAnAccountOut() {
        // Defence in depth for the one failure this feature cannot recover from on its own: an
        // account marked busy for a call that no longer exists can never place or receive another
        // until the process restarts.
        var call = ring();
        registry.end(call.id(), "alice", CallEndReason.HANGUP);

        assertThat(registry.current("alice")).isEmpty();
        assertThat(registry.invite(7L, "alice", "bob", CallMedia.AUDIO, "s2")).isNotNull();
    }

    @Test
    void peerOfNamesTheOtherSideAndNobodyElse() {
        var call = ring();

        assertThat(call.peerOf("alice")).isEqualTo("bob");
        assertThat(call.peerOf("bob")).isEqualTo("alice");
        // The authorisation for relaying a signal is exactly this returning null.
        assertThat(call.peerOf("mallory")).isNull();
    }
}
