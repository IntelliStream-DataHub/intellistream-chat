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

package ai.intellistream.chat.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a connect does to the activity stamp. The session bookkeeping itself is covered by
 * {@code PresenceFlowIT}; this pins the one thing that used to be wrong: a connect was always
 * "active now", so a background tab whose throttled heartbeat got it disconnected came back green
 * on the redial.
 */
class PresenceTrackerTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(1);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    private final PresenceTracker tracker = new PresenceTracker();

    @Test
    void aPlainConnectIsActivityNow() {
        tracker.connect("bob", "tab-1");

        assertThat(tracker.isIdle("bob", THRESHOLD, Instant.now())).isFalse();
        assertThat(tracker.lastActivityAt("bob")).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void aReconnectReportingIdleTimeLandsOnAway() {
        // The tab has been untouched for three minutes; the socket is merely being redialled.
        tracker.connect("bob", "tab-1", NOW.minus(Duration.ofMinutes(3)));

        assertThat(tracker.isOnline("bob")).isTrue();
        assertThat(tracker.isIdle("bob", THRESHOLD, NOW)).isTrue();
    }

    @Test
    void aReconnectReportingRecentInputIsActive() {
        tracker.connect("bob", "tab-1", NOW.minusSeconds(5));

        assertThat(tracker.isIdle("bob", THRESHOLD, NOW)).isFalse();
    }

    @Test
    void theStampNeverMovesBackwards() {
        // A live, active second tab has the last word over a stale claim from a redialling first.
        tracker.connect("bob", "tab-live", NOW.minusSeconds(2));
        tracker.connect("bob", "tab-stale", NOW.minus(Duration.ofMinutes(10)));

        assertThat(tracker.lastActivityAt("bob")).isEqualTo(NOW.minusSeconds(2));
        assertThat(tracker.isIdle("bob", THRESHOLD, NOW)).isFalse();
    }

    @Test
    void aLaterClaimDoesMoveItForward() {
        tracker.connect("bob", "tab-1", NOW.minus(Duration.ofMinutes(10)));
        tracker.connect("bob", "tab-2", NOW.minusSeconds(1));

        assertThat(tracker.lastActivityAt("bob")).isEqualTo(NOW.minusSeconds(1));
    }

    @Test
    void aNullStampFallsBackToNow() {
        tracker.connect("bob", "tab-1", null);

        assertThat(tracker.isIdle("bob", THRESHOLD, Instant.now())).isFalse();
    }

    @Test
    void theStampLeavesWithTheLastSession() {
        tracker.connect("bob", "tab-1", NOW.minus(Duration.ofMinutes(3)));
        tracker.disconnect("bob", "tab-1");

        assertThat(tracker.lastActivityAt("bob")).isNull();
        // Offline is not idle: the two are different colours for a reason.
        assertThat(tracker.isIdle("bob", THRESHOLD, NOW)).isFalse();
    }
}
