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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic coverage for the LIKE-pattern builders behind
 * {@link UserService#searchInviteCandidates}, the channel settings "Find user" browser. No
 * Spring context, no database — {@link UserRepository} takes it from here on trust that the
 * pattern this builds does what {@code escape '!'} says it does.
 */
class UserServiceSearchPatternTest {

    @Test
    void usernamePattern_blank_matchesEverything() {
        assertThat(UserService.usernamePattern(null)).isEqualTo("%");
        assertThat(UserService.usernamePattern("")).isEqualTo("%");
        assertThat(UserService.usernamePattern("   ")).isEqualTo("%");
    }

    @Test
    void usernamePattern_noWildcard_isSubstringMatch() {
        assertThat(UserService.usernamePattern("ali")).isEqualTo("%ali%");
    }

    @Test
    void usernamePattern_starAndQuestionMark_becomeSqlWildcards() {
        assertThat(UserService.usernamePattern("ali*")).isEqualTo("ali%");
        assertThat(UserService.usernamePattern("ali?e")).isEqualTo("ali_e");
    }

    @Test
    void usernamePattern_literalWildcardCharsInInput_areEscaped() {
        // A username genuinely containing % or _ must search for itself, not act as a wildcard —
        // same contract UserFileService.likePattern gives filenames.
        assertThat(UserService.usernamePattern("100%_done")).isEqualTo("%100!%!_done%");
    }

    @Test
    void usernamePattern_literalBangIsEscapedToo() {
        // '!' is the escape character itself, so a literal one must double up or it would escape
        // whatever follows it instead of matching itself.
        assertThat(UserService.usernamePattern("wow!")).isEqualTo("%wow!!%");
    }

    @Test
    void emailDomainPattern_blank_disablesTheFilter() {
        assertThat(UserService.emailDomainPattern(null)).isEmpty();
        assertThat(UserService.emailDomainPattern("")).isEmpty();
        assertThat(UserService.emailDomainPattern("   ")).isEmpty();
        assertThat(UserService.emailDomainPattern("@")).isEmpty();
    }

    @Test
    void emailDomainPattern_prefixMatchesDomainStart() {
        assertThat(UserService.emailDomainPattern("example.com")).isEqualTo("%@example.com%");
    }

    @Test
    void emailDomainPattern_leadingAtSignIsIgnored() {
        assertThat(UserService.emailDomainPattern("@example.com"))
                .isEqualTo(UserService.emailDomainPattern("example.com"));
    }

    @Test
    void emailDomainPattern_escapesLiteralWildcardChars() {
        assertThat(UserService.emailDomainPattern("my_co%")).isEqualTo("%@my!_co!%%");
    }
}
