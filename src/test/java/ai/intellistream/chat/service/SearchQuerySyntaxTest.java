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
 * The search query grammar, at the one place that decides what a token means.
 *
 * <p>{@link SearchService#parsed} is pure — no database, no index — so every modifier, and every
 * near-miss that must <em>not</em> be read as a modifier, is cheap to pin down here. The
 * integration tests then check that the parts it produces reach Lucene and select the right
 * messages; this file checks that the user's typing is read the way the help text promises.
 */
class SearchQuerySyntaxTest {

    // ---------- @handle now means "mentions", not "wrote" ----------

    @Test
    void aBareHandleIsAMentionFilterAndNotAnAuthorFilter() {
        // The behaviour change this grammar exists for. `@bob` used to select messages Bob wrote,
        // which is the opposite of what the same token does in Slack, so someone looking for where
        // they had pinged Bob got Bob's own messages and no hint that the query had been inverted.
        var p = SearchService.parsed("@bob deploy");

        assertThat(p.mentions()).containsExactly("bob");
        assertThat(p.authors()).isEmpty();
        assertThat(p.body()).isEqualTo("deploy");
    }

    @Test
    void fromSelectsTheAuthorWithOrWithoutTheAtSign() {
        assertThat(SearchService.parsed("from:bob deploy").authors()).containsExactly("bob");
        assertThat(SearchService.parsed("from:@bob deploy").authors()).containsExactly("bob");
        assertThat(SearchService.parsed("from:@bob deploy").mentions()).isEmpty();
    }

    @Test
    void fromAndAtHandleAreDifferentQuestionsAndCanBeAskedTogether() {
        var p = SearchService.parsed("from:alice @bob release");

        assertThat(p.authors()).containsExactly("alice");
        assertThat(p.mentions()).containsExactly("bob");
        assertThat(p.body()).isEqualTo("release");
    }

    @Test
    void modifiersAreCaseInsensitiveAndTheirValuesAreLowercased() {
        var p = SearchService.parsed("FROM:Alice @Bob IN:#General");

        assertThat(p.authors()).containsExactly("alice");
        assertThat(p.mentions()).containsExactly("bob");
        assertThat(p.inChannel()).isEqualTo("General"); // resolved case-insensitively later
    }

    @Test
    void severalValuesOfTheSameModifierAccumulate() {
        var p = SearchService.parsed("from:alice from:carol @bob @dave");

        assertThat(p.authors()).containsExactly("alice", "carol");
        assertThat(p.mentions()).containsExactly("bob", "dave");
    }

    // ---------- in: ----------

    @Test
    void inAcceptsTheChannelWithOrWithoutItsHash() {
        assertThat(SearchService.parsed("in:#general standup").inChannel()).isEqualTo("general");
        assertThat(SearchService.parsed("in:general standup").inChannel()).isEqualTo("general");
        assertThat(SearchService.parsed("in:#general standup").body()).isEqualTo("standup");
    }

    @Test
    void aScopeWithNothingToSearchForIsNotAQuery() {
        // in: narrows; it does not select. On its own it would mean "everything in this channel",
        // which is a channel, not a search — and the caller can't tell it apart from a typo.
        assertThat(SearchService.parsed("in:#general")).isNull();
    }

    // ---------- unknown prefixes are text ----------

    @Test
    void anUnknownPrefixIsSearchedForLiterallyWithItsColonEscaped() {
        // Lucene's parser reads `foo:bar` as "field foo contains bar". Left alone, a typo'd or
        // imagined modifier becomes a query against a field that does not exist and matches
        // nothing at all — a wrong answer that looks like a correct empty one.
        var p = SearchService.parsed("befor:friday standup");

        assertThat(p.body()).isEqualTo("befor\\:friday standup");
        assertThat(p.authors()).isEmpty();
        assertThat(p.mentions()).isEmpty();
        assertThat(p.inChannel()).isNull();
    }

    @Test
    void theOutOfScopeModifiersAreTextToday() {
        // before:/after:/has: are not implemented. They must behave like any other unknown prefix
        // — literal text — rather than parse and silently do nothing.
        assertThat(SearchService.parsed("before:friday x").body()).isEqualTo("before\\:friday x");
        assertThat(SearchService.parsed("after:friday x").body()).isEqualTo("after\\:friday x");
        assertThat(SearchService.parsed("has:link x").body()).isEqualTo("has\\:link x");
    }

    @Test
    void aTimeOrUrlInTheQueryIsNotMistakenForAFieldQualifier() {
        assertThat(SearchService.parsed("standup at 09:30").body()).isEqualTo("standup at 09\\:30");
        assertThat(SearchService.parsed("https://example.com/x").body())
                .isEqualTo("https\\://example.com/x");
    }

    @Test
    void aModifierWithNoValueIsJustText() {
        assertThat(SearchService.parsed("from: alice").authors()).isEmpty();
        assertThat(SearchService.parsed("from: alice").body()).isEqualTo("from\\: alice");
        assertThat(SearchService.parsed("in: general").inChannel()).isNull();
    }

    // ---------- what must NOT become a modifier ----------

    @Test
    void aQuotedPhraseIsNeverReadAsAModifier() {
        var p = SearchService.parsed("\"from: the top\"");

        assertThat(p.authors()).isEmpty();
        assertThat(p.body()).isEqualTo("\"from: the top\"");
    }

    @Test
    void anEmailAddressIsNotAMention() {
        var p = SearchService.parsed("mail bob@example.com");

        assertThat(p.mentions()).isEmpty();
        assertThat(p.body()).isEqualTo("mail bob@example.com");
    }

    @Test
    void aLoneAtSignIsText() {
        assertThat(SearchService.parsed("@ sign").mentions()).isEmpty();
        assertThat(SearchService.parsed("@ sign").body()).isEqualTo("@ sign");
    }

    @Test
    void luceneSyntaxInTheBodySurvivesUntouched() {
        // Phrases, negation and boolean operators are part of the documented syntax and are the
        // Lucene parser's job, not this one's.
        assertThat(SearchService.parsed("\"quick brown\" -draft").body())
                .isEqualTo("\"quick brown\" -draft");
        assertThat(SearchService.parsed("release AND notes").body()).isEqualTo("release AND notes");
    }

    // ---------- emptiness ----------

    @Test
    void nothingSearchableReturnsNull() {
        assertThat(SearchService.parsed(null)).isNull();
        assertThat(SearchService.parsed("")).isNull();
        assertThat(SearchService.parsed("   ")).isNull();
        assertThat(SearchService.parsed("a")).isNull(); // below the two-character floor
    }

    @Test
    void aFilterAloneIsEnoughOfAQuery() {
        assertThat(SearchService.parsed("from:alice")).isNotNull();
        assertThat(SearchService.parsed("from:alice").body()).isEmpty();
        assertThat(SearchService.parsed("@bob")).isNotNull();
        assertThat(SearchService.parsed("@bob").body()).isEmpty();
    }

    // ---------- tokenizer ----------

    @Test
    void quotedRunsSurviveTokenizationAsOneToken() {
        assertThat(SearchService.tokenize("a \"b c\" d")).containsExactly("a", "\"b c\"", "d");
        assertThat(SearchService.tokenize("  spaced   out  ")).containsExactly("spaced", "out");
        // An unbalanced quote must not lose the rest of the query.
        assertThat(SearchService.tokenize("a \"b c")).containsExactly("a", "\"b c");
    }
}
