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

package ai.intellistream.chat.secretshare;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser summary an anonymous opener is recorded under. Real User-Agent strings, because the
 * ordering traps are in the real strings: Edge and Opera say "Chrome", Chrome says "Safari", and an
 * iPhone says "like Mac OS X".
 */
class ClientSummaryTest {

    @Test
    void desktopBrowsers() {
        assertThat(ClientSummary.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36 Edg/139.0.0.0"))
                .isEqualTo("Edge on Windows");
        assertThat(ClientSummary.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"))
                .isEqualTo("Chrome on Windows");
        assertThat(ClientSummary.of("Mozilla/5.0 (X11; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0"))
                .isEqualTo("Firefox on Linux");
        assertThat(ClientSummary.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15"))
                .isEqualTo("Safari on macOS");
        assertThat(ClientSummary.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36 OPR/121.0.0.0"))
                .isEqualTo("Opera on macOS");
        assertThat(ClientSummary.of("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"))
                .isEqualTo("Chrome on ChromeOS");
    }

    @Test
    void mobileBrowsers() {
        assertThat(ClientSummary.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"))
                .isEqualTo("Safari on iOS");
        assertThat(ClientSummary.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/139.0.0.0 Mobile/15E148 Safari/604.1"))
                .isEqualTo("Chrome on iOS");
        assertThat(ClientSummary.of("Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"))
                .isEqualTo("Chrome on Android");
    }

    @Test
    void missingOrHostileHeadersGetTheFixedFallbackNeverTheirOwnText() {
        assertThat(ClientSummary.of(null)).isEqualTo("Unknown browser on unknown system");
        assertThat(ClientSummary.of("")).isEqualTo("Unknown browser on unknown system");
        assertThat(ClientSummary.of("curl/8.9.1")).isEqualTo("Unknown browser on unknown system");
        var hostile = ClientSummary.of("<img src=x onerror=alert(1)> [click](https://evil.example) **Firefox/1 Windows**");
        assertThat(hostile).isEqualTo("Firefox on Windows");
        assertThat(hostile).doesNotContain("<", "[", "*", "evil");
        // A huge header is only scanned, never echoed.
        assertThat(ClientSummary.of("A".repeat(100_000) + " Firefox/1")).isEqualTo("Unknown browser on unknown system");
    }

    @Test
    void theSummaryAlwaysFitsItsColumn() {
        assertThat(ClientSummary.of("Mozilla/5.0 (Windows NT 10.0) Edg/1").length()).isLessThanOrEqualTo(64);
        assertThat(ClientSummary.of(null).length()).isLessThanOrEqualTo(64);
    }
}
