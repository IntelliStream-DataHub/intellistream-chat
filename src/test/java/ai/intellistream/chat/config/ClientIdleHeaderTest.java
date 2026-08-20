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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code idle-ms} CONNECT header is client-supplied, so every malformed shape has to land on
 * the pre-header behaviour ("active now") rather than on an exception in the CONNECT interceptor —
 * a throw there refuses the socket for a header nobody asked the client to get right.
 */
class ClientIdleHeaderTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void aReportedIdleTimeBackdatesTheStamp() {
        assertThat(ClientIdleHeader.lastInputAt("90000", NOW)).isEqualTo(NOW.minusSeconds(90));
        assertThat(ClientIdleHeader.lastInputAt(" 1500 ", NOW)).isEqualTo(NOW.minusMillis(1500));
    }

    @Test
    void missingBlankOrGarbageMeansNow() {
        assertThat(ClientIdleHeader.lastInputAt(null, NOW)).isEqualTo(NOW);
        assertThat(ClientIdleHeader.lastInputAt("", NOW)).isEqualTo(NOW);
        assertThat(ClientIdleHeader.lastInputAt("   ", NOW)).isEqualTo(NOW);
        assertThat(ClientIdleHeader.lastInputAt("soon", NOW)).isEqualTo(NOW);
        assertThat(ClientIdleHeader.lastInputAt("1e3", NOW)).isEqualTo(NOW);
        assertThat(ClientIdleHeader.lastInputAt("99999999999999999999", NOW)).isEqualTo(NOW);
    }

    @Test
    void zeroAndNegativeMeanNow() {
        // A client cannot have been idle for a negative time, and "0" is literally now.
        assertThat(ClientIdleHeader.lastInputAt("0", NOW)).isEqualTo(NOW);
        assertThat(ClientIdleHeader.lastInputAt("-5000", NOW)).isEqualTo(NOW);
    }

    @Test
    void absurdClaimsAreClampedNotRefused() {
        var tenYears = Duration.ofDays(3650).toMillis();
        assertThat(ClientIdleHeader.lastInputAt(Long.toString(tenYears), NOW))
                .isEqualTo(NOW.minus(ClientIdleHeader.MAX_IDLE));
        assertThat(ClientIdleHeader.lastInputAt(Long.toString(Long.MAX_VALUE), NOW))
                .isEqualTo(NOW.minus(ClientIdleHeader.MAX_IDLE));
    }
}
