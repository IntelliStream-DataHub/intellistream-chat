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

package ai.intellistream.chat.search;

import ai.intellistream.chat.search.MessageIndexService.Hit;
import ai.intellistream.chat.search.MessageIndexService.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The membership filter, tested at the layer that enforces it: the Lucene query.
 *
 * <p>Every test here is written the same way — index content, run the search as somebody who
 * should not see it, assert the content is absent, then run the <em>same</em> search as somebody
 * who should see it and assert it is found. The second half is what makes the first half mean
 * something: without it, an assertion that a non-member found nothing also passes when the term
 * was never indexed, when the analyzer dropped it, or when the query failed to parse.
 *
 * <p>These operate on the raw index rather than through {@code SearchService} so the filter itself
 * is under test with nothing else in the way. {@code ConversationSearchAccessIT} covers the same
 * boundary end-to-end, through real membership rows in Postgres.
 */
class MessageIndexAclTest {

    private Path dir;
    private MessageIndexService index;

    /** Conversation ids. 100 is a DM between two other people; 200 is a group; 300 is the searcher's own. */
    private static final long DM_OF_OTHERS = 100L;
    private static final long GROUP_OF_OTHERS = 200L;
    private static final long SEARCHERS_OWN_DM = 300L;

    @BeforeEach
    void open() throws IOException {
        dir = Files.createTempDirectory("ichat-acl-index-");
        // async=false: every write is visible and committed immediately, so the assertions
        // don't race the batched refresh.
        index = new MessageIndexService(dir.toString(), false);
    }

    @AfterEach
    void close() {
        index.close();
    }

    @Test
    void aDirectMessageBetweenTwoOtherPeopleIsUnreachable() {
        index.indexConversationMessage(1L, DM_OF_OTHERS, "alice",
                "the deploy passphrase is correcthorsebattery");
        index.indexConversationMessage(2L, SEARCHERS_OWN_DM, "carol",
                "unrelated chatter about passphrase policy documents");

        // Carol is in her own DM and nothing else.
        var asOutsider = index.searchAccessible(
                List.of(), List.of(SEARCHERS_OWN_DM), "correcthorsebattery", Set.of(), 50);
        assertThat(asOutsider).doesNotContain(new Hit(Scope.CONVERSATION, 1L));
        assertThat(asOutsider).isEmpty();

        // Control: the term really is indexed and really is matchable — a participant finds it.
        assertThat(index.searchAccessible(
                List.of(), List.of(DM_OF_OTHERS), "correcthorsebattery", Set.of(), 50))
                .containsExactly(new Hit(Scope.CONVERSATION, 1L));
    }

    @Test
    void aGroupConversationTheSearcherIsNotInIsUnreachable() {
        index.indexConversationMessage(3L, GROUP_OF_OTHERS, "bob",
                "acquisition codename thunderclap signs monday");
        index.indexConversationMessage(4L, SEARCHERS_OWN_DM, "carol", "lunch at noon");

        var asOutsider = index.searchAccessible(
                List.of(), List.of(SEARCHERS_OWN_DM), "thunderclap", Set.of(), 50);
        assertThat(asOutsider).doesNotContain(new Hit(Scope.CONVERSATION, 3L));
        assertThat(asOutsider).isEmpty();

        assertThat(index.searchAccessible(
                List.of(), List.of(GROUP_OF_OTHERS), "thunderclap", Set.of(), 50))
                .containsExactly(new Hit(Scope.CONVERSATION, 3L));
    }

    @Test
    void anAuthorFilterDoesNotBypassTheMembershipFilter() {
        // `@alice` with no keyword takes a different branch in mainQuery (MatchAllDocs + author
        // filter). The ACL is applied to the composed query, not to one branch of it, but a
        // regression here would be invisible to the keyword tests above.
        index.indexConversationMessage(5L, DM_OF_OTHERS, "alice", "private words");
        index.indexConversationMessage(6L, SEARCHERS_OWN_DM, "alice", "public-ish words");

        var asOutsider = index.searchAccessible(
                List.of(), List.of(SEARCHERS_OWN_DM), "", Set.of("alice"), 50);
        assertThat(asOutsider).containsExactly(new Hit(Scope.CONVERSATION, 6L));
        assertThat(asOutsider).doesNotContain(new Hit(Scope.CONVERSATION, 5L));
    }

    @Test
    void noAccessibleContainerMatchesNothingRatherThanEverything() {
        // The degenerate case an ACL filter has to get right: "the viewer belongs to nothing"
        // must mean no results, never "no restriction".
        index.index(7L, 42L, "alice", "channel content about widgets");
        index.indexConversationMessage(8L, DM_OF_OTHERS, "alice", "dm content about widgets");

        assertThat(index.searchAccessible(List.of(), List.of(), "widgets", Set.of(), 50)).isEmpty();
    }

    @Test
    void channelIdsAndConversationIdsDoNotCollide() {
        // The two tables have independent id sequences, so channel 5 and conversation 5 both
        // exist. Membership of one must never grant the other.
        index.index(9L, 5L, "alice", "shared-marker in a channel");
        index.indexConversationMessage(10L, 5L, "alice", "shared-marker in a private conversation");

        assertThat(index.searchAccessible(List.of(5L), List.of(), "shared-marker", Set.of(), 50))
                .containsExactly(new Hit(Scope.CHANNEL, 9L));
        assertThat(index.searchAccessible(List.of(), List.of(5L), "shared-marker", Set.of(), 50))
                .containsExactly(new Hit(Scope.CONVERSATION, 10L));
    }

    @Test
    void channelAndConversationHitsComeBackInOneRankedList() {
        index.index(11L, 5L, "alice", "quarterly-review notes from the channel");
        index.indexConversationMessage(12L, SEARCHERS_OWN_DM, "alice",
                "quarterly-review quarterly-review quarterly-review in the dm");

        var hits = index.searchAccessible(List.of(5L), List.of(SEARCHERS_OWN_DM),
                "quarterly-review", Set.of(), 50);

        assertThat(hits).containsExactlyInAnyOrder(
                new Hit(Scope.CHANNEL, 11L), new Hit(Scope.CONVERSATION, 12L));
        // Denser match ranks first, across the two stores — one list, one ordering.
        assertThat(hits.get(0)).isEqualTo(new Hit(Scope.CONVERSATION, 12L));
    }

    /**
     * The scaling claim in {@code searchAccessible}'s javadoc, as an executable assertion.
     *
     * <p>5,000 ids is well past Lucene's default 1,024-clause BooleanQuery limit, so the naive
     * "one SHOULD TermQuery per id" encoding would throw {@code TooManyClauses} here. It must
     * neither throw nor quietly drop ids — a truncated ACL is a search that silently stops
     * finding things the user is entitled to.
     */
    @Test
    void aVeryLargeMembershipSetStaysCorrect() {
        long targetConversation = 4_321L;
        index.indexConversationMessage(13L, targetConversation, "alice",
                "needle-in-a-haystack marker");

        var manyIds = new ArrayList<Long>(5_000);
        for (long i = 1; i <= 5_000; i++) {
            manyIds.add(i);
        }
        assertThat(manyIds).contains(targetConversation);

        assertThat(index.searchAccessible(List.of(), manyIds, "needle-in-a-haystack", Set.of(), 50))
                .containsExactly(new Hit(Scope.CONVERSATION, 13L));

        // Same size, but the one id that matters is missing: still no leak.
        var manyIdsWithout = new ArrayList<>(manyIds);
        manyIdsWithout.remove(Long.valueOf(targetConversation));
        manyIdsWithout.add(999_999L);
        assertThat(index.searchAccessible(List.of(), manyIdsWithout, "needle-in-a-haystack", Set.of(), 50))
                .isEmpty();
    }

    @Test
    void theAdminWideSearchNeverReachesConversations() {
        index.index(14L, 5L, "alice", "budget-forecast in a channel nobody joined");
        index.indexConversationMessage(15L, DM_OF_OTHERS, "alice", "budget-forecast in a private dm");

        // searchEverywhere is the unrestricted, admin-only query. It sees the private channel…
        assertThat(index.searchEverywhere("budget-forecast", Set.of(), 50)).containsExactly(14L);
        // …and it must not see the DM, which is proven present by a member's own search.
        assertThat(index.searchAccessible(List.of(), List.of(DM_OF_OTHERS), "budget-forecast", Set.of(), 50))
                .containsExactly(new Hit(Scope.CONVERSATION, 15L));
    }

    @Test
    void aDeletedConversationMessageStopsMatching() {
        index.indexConversationMessage(16L, SEARCHERS_OWN_DM, "carol", "retract this sentence");
        assertThat(index.searchAccessible(List.of(), List.of(SEARCHERS_OWN_DM), "retract", Set.of(), 50))
                .containsExactly(new Hit(Scope.CONVERSATION, 16L));

        index.deleteConversationMessage(16L);

        assertThat(index.searchAccessible(List.of(), List.of(SEARCHERS_OWN_DM), "retract", Set.of(), 50))
                .isEmpty();
    }

    @Test
    void deletingAChannelMessageIdCannotDeleteTheConversationMessageWithTheSameId() {
        // Namespaced document keys, verified rather than assumed: the channel delete path builds
        // its term from the bare numeric id, and both tables have a row 17.
        index.index(17L, 5L, "alice", "channel-side content");
        index.indexConversationMessage(17L, SEARCHERS_OWN_DM, "carol", "conversation-side content");

        index.delete(17L);

        assertThat(index.searchAccessible(List.of(5L), List.of(), "channel-side", Set.of(), 50)).isEmpty();
        assertThat(index.searchAccessible(List.of(), List.of(SEARCHERS_OWN_DM), "conversation-side", Set.of(), 50))
                .containsExactly(new Hit(Scope.CONVERSATION, 17L));
    }

    @Test
    void theReconcileSweepsSeeTheirOwnDocumentsOnly() {
        index.index(18L, 5L, "alice", "channel row");
        index.indexConversationMessage(19L, SEARCHERS_OWN_DM, "carol", "conversation row");

        // Each sweep diffs its own table; crossing the id spaces would make every document of one
        // kind look "stale" to the other sweep and get it deleted.
        assertThat(index.allIndexedIds()).containsExactly(18L);
        assertThat(index.allIndexedConversationIds()).containsExactly(19L);
    }
}
