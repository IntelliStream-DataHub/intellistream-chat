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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.service.SearchService.ScopeKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * How a request's scope is read. Small, and worth pinning: the JSON endpoint and the results page
 * both go through it, so a disagreement here means the "see all results" link lands on a different
 * result set than the dropdown it was offered from — the kind of thing that is obvious in use and
 * invisible in review.
 */
class SearchScopesTest {

    private static final Channel CHANNEL = mock(Channel.class);
    private static final Conversation CONVERSATION = mock(Conversation.class);

    @Test
    void withNoScopeTheViewersCurrentRoomWins() {
        // Searching while reading #general almost always means "in here". Having to say so, when
        // being on the page already said it, is the friction that stops people using search.
        assertThat(SearchScopes.resolve(null, CHANNEL, null)).isEqualTo(ScopeKind.CHANNEL);
        assertThat(SearchScopes.resolve(null, null, CONVERSATION)).isEqualTo(ScopeKind.CONVERSATION);
        assertThat(SearchScopes.resolve("", CHANNEL, null)).isEqualTo(ScopeKind.CHANNEL);
    }

    @Test
    void withNoScopeAndNoRoomItIsEverythingTheViewerCanRead() {
        assertThat(SearchScopes.resolve(null, null, null)).isEqualTo(ScopeKind.ACCESSIBLE);
    }

    @Test
    void anExplicitScopeAlwaysBeatsTheDefault() {
        // Widening has to be one click and never a guess, so the pre-selection must never be sticky.
        assertThat(SearchScopes.resolve("accessible", CHANNEL, null)).isEqualTo(ScopeKind.ACCESSIBLE);
        assertThat(SearchScopes.resolve("all", CHANNEL, null)).isEqualTo(ScopeKind.EVERYWHERE);
        assertThat(SearchScopes.resolve("ALL", CHANNEL, null)).isEqualTo(ScopeKind.EVERYWHERE);
    }

    @Test
    void aScopeWithNothingToScopeToFallsBackRatherThanFailing() {
        // ?scope=channel with no channelId is a hand-edited or stale URL. Searching everything the
        // viewer can read is a defensible answer; a null-pointer inside the service is not.
        assertThat(SearchScopes.resolve("channel", null, null)).isEqualTo(ScopeKind.ACCESSIBLE);
        assertThat(SearchScopes.resolve("conversation", null, null)).isEqualTo(ScopeKind.ACCESSIBLE);
    }

    @Test
    void anUnrecognisedScopeIsTheDefaultAndNotAnError() {
        assertThat(SearchScopes.resolve("everywhere", null, null)).isEqualTo(ScopeKind.ACCESSIBLE);
        assertThat(SearchScopes.resolve("¯\\_(ツ)_/¯", null, null)).isEqualTo(ScopeKind.ACCESSIBLE);
    }

    @Test
    void everyScopeRoundTripsThroughItsWireName() {
        // The page echoes the resolved scope back into its own form, so a name that doesn't
        // round-trip would silently reset the control on every submit.
        for (var kind : ScopeKind.values()) {
            assertThat(SearchScopes.resolve(SearchScopes.wireName(kind), CHANNEL, CONVERSATION))
                    .as("round trip for %s", kind)
                    .isEqualTo(kind);
        }
    }
}
