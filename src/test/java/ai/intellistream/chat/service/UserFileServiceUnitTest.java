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

import ai.intellistream.chat.security.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two pure-logic branches of the file manager: how a search box turns into a {@code like}
 * pattern, and how a path segment turns into a scope. Both take raw user input, and both have a
 * failure mode that is invisible until it matters — a wildcard smuggled into a pattern silently
 * widens a search, and a scope parsed leniently would let {@code /api/files/CHANNEL/1} and
 * {@code /api/files/conversation/1} disagree about which table an id belongs to.
 */
class UserFileServiceUnitTest {

    @Test
    void aBlankQueryMatchesEverything() {
        assertThat(UserFileService.likePattern(null)).isEqualTo("%");
        assertThat(UserFileService.likePattern("")).isEqualTo("%");
        assertThat(UserFileService.likePattern("   ")).isEqualTo("%");
    }

    @Test
    void aPlainQueryBecomesACaseFoldedContainsPattern() {
        assertThat(UserFileService.likePattern("Invoice")).isEqualTo("%invoice%");
        assertThat(UserFileService.likePattern("  report  ")).isEqualTo("%report%");
    }

    @Test
    void likeWildcardsInTheQueryAreEscapedRatherThanHonoured() {
        // Unescaped, "report_2026" matches "reportX2026" too — the user typed a filename, not a
        // pattern language.
        assertThat(UserFileService.likePattern("report_2026")).isEqualTo("%report!_2026%");
        assertThat(UserFileService.likePattern("50%")).isEqualTo("%50!%%");
        // The escape character itself has to be escapable or a filename containing '!' would
        // consume the character after it.
        assertThat(UserFileService.likePattern("a!b")).isEqualTo("%a!!b%");
    }

    @Test
    void scopeParsingIsCaseInsensitiveAndRejectsAnythingElse() {
        assertThat(UserFileService.Scope.parse("channel")).isEqualTo(UserFileService.Scope.CHANNEL);
        assertThat(UserFileService.Scope.parse("CONVERSATION"))
                .isEqualTo(UserFileService.Scope.CONVERSATION);
        assertThat(UserFileService.Scope.CHANNEL.wire()).isEqualTo("channel");

        // 404, like every other "we don't know what you're asking for" on this endpoint — a
        // different status here would tell a prober that the scope namespace is worth mapping.
        assertThatThrownBy(() -> UserFileService.Scope.parse("avatars"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> UserFileService.Scope.parse(null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
