/*
 * Copyright 2026 Olav Gjerde
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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage for {@link SearchService}: exercises the embedded Lucene index
 * (in {@code data/lucene}, isolated per-context via {@link IntegrationTestApplication})
 * against a real Postgres database. If anything here fails, the search experience
 * in the UI is broken at the data/index layer.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SearchFlowIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("chat")
            .withUsername("chat")
            .withPassword("chat");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired SearchService search;
    @Autowired ai.intellistream.chat.service.UserService userService;

    /** Each test gets a fresh user/channel name to avoid colliding with other tests sharing the container. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private User newUser(String prefix) {
        var id = SEQ.incrementAndGet();
        return users.save(new User("kc-" + prefix + id, prefix + id,
                prefix + id + "@example.com", prefix + " " + id));
    }

    private Channel newPublic(String name, User creator) {
        return channels.create(name + "-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, creator);
    }

    private Channel newPrivate(String name, User creator) {
        return channels.create(name + "-" + SEQ.incrementAndGet(), null, ChannelType.PRIVATE, creator);
    }

    private static void authenticateAs(String username, String... roles) {
        var auth = new TestingAuthenticationToken(username, "n/a", roles);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ---------- Basic semantics ----------

    @Test
    void findsMatchingMessage() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "Hello world this is a search test");

        var hits = search.searchChannel(room, alice, "search", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getBodyMarkdown()).contains("search test");
    }

    @Test
    void searchIsCaseInsensitive() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "PostgreSQL is great");

        assertThat(search.searchChannel(room, alice, "postgresql", 10)).hasSize(1);
        assertThat(search.searchChannel(room, alice, "POSTGRESQL", 10)).hasSize(1);
        assertThat(search.searchChannel(room, alice, "PostgreSQL", 10)).hasSize(1);
    }

    @Test
    void multiWordQueryRequiresAllTerms() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "spring boot is fun");
        messages.post(room, alice, "spring framework is also good");
        messages.post(room, alice, "boot menu loaded");

        // Lucene QueryParser default operator is AND — same semantic as websearch_to_tsquery.
        var hits = search.searchChannel(room, alice, "spring boot", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getBodyMarkdown()).contains("spring boot is fun");
    }

    @Test
    void exactPhraseQuoting() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "the quick brown fox jumps");
        messages.post(room, alice, "brown is a quick color");

        var hits = search.searchChannel(room, alice, "\"quick brown\"", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getBodyMarkdown()).contains("quick brown fox");
    }

    @Test
    void negationExcludesTerm() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "release notes for version 4");
        messages.post(room, alice, "release notes draft");

        var hits = search.searchChannel(room, alice, "release -draft", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getBodyMarkdown()).contains("version 4");
    }

    // ---------- Channel-scoping ----------

    @Test
    void channelScopedSearchDoesNotLeakAcrossChannels() {
        var alice = newUser("alice");
        var roomA = newPublic("alpha", alice);
        var roomB = newPublic("beta", alice);

        messages.post(roomA, alice, "leakcheck-keyword in alpha room");
        messages.post(roomB, alice, "leakcheck-keyword in beta room");

        var hitsA = search.searchChannel(roomA, alice, "alpha", 10);
        assertThat(hitsA).hasSize(1);
        assertThat(hitsA.get(0).getChannel().getId()).isEqualTo(roomA.getId());

        var hitsB = search.searchChannel(roomB, alice, "beta", 10);
        assertThat(hitsB).hasSize(1);
        assertThat(hitsB.get(0).getChannel().getId()).isEqualTo(roomB.getId());

        // Term unique to the other channel must not bleed across.
        assertThat(search.searchChannel(roomA, alice, "leakcheck-keyword", 10))
                .extracting(m -> m.getChannel().getId())
                .containsOnly(roomA.getId());
    }

    @Test
    void allJoinedSearchSpansEveryJoinedChannel() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var roomA = newPublic("alpha", alice);
        var roomB = newPublic("beta", alice);
        channels.join(roomA, bob);
        channels.join(roomB, bob);

        messages.post(roomA, alice, "shared-token here in alpha");
        messages.post(roomB, alice, "shared-token here in beta");

        var hits = search.searchAllJoined(bob, "shared-token", 10);

        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(m -> m.getChannel().getId())
                .containsExactlyInAnyOrder(roomA.getId(), roomB.getId());
    }

    @Test
    void allJoinedSearchExcludesPrivateChannelsViewerHasntJoined() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var publicRoom = newPublic("publik", alice);
        var privateRoom = newPrivate("secret", alice);
        channels.join(publicRoom, bob);
        // Bob is NOT a member of the private room.

        var marker = "cookie-" + SEQ.incrementAndGet();
        messages.post(publicRoom, alice, marker + " recipes for everyone");
        messages.post(privateRoom, alice, marker + " recipes top secret");

        var hits = search.searchAllJoined(bob, marker, 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getChannel().getId()).isEqualTo(publicRoom.getId());
    }

    // ---------- Authorisation ----------

    @Test
    void channelSearchOnPrivateChannelRequiresMembership() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var secret = newPrivate("secret", alice);
        messages.post(secret, alice, "hidden treasure map");

        assertThatThrownBy(() -> search.searchChannel(secret, bob, "treasure", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void channelSearchOnPublicChannelDoesNotRequireMembership() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var publicRoom = newPublic("lobby", alice);
        messages.post(publicRoom, alice, "open invitation to the launch");

        // Bob hasn't joined, but PUBLIC channels are readable.
        var hits = search.searchChannel(publicRoom, bob, "launch", 10);
        assertThat(hits).hasSize(1);
    }

    // ---------- Global (cross-channel) search: admin-only ----------

    @Test
    void globalSearchByAdminSeesPrivateChannelsTheyHaventJoined() {
        var alice = newUser("alice");
        var bob = newUser("bob"); // bob is the admin — never joined the private room
        var publicRoom = newPublic("lobby", alice);
        var privateRoom = newPrivate("secret", alice);

        var marker = "globalmarker-" + SEQ.incrementAndGet();
        messages.post(publicRoom, alice, marker + " in the lobby");
        messages.post(privateRoom, alice, marker + " behind closed doors");

        authenticateAs("bob", "ROLE_ADMIN");
        var hits = search.searchEverywhere(bob, marker, 10);

        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(m -> m.getChannel().getId())
                .containsExactlyInAnyOrder(publicRoom.getId(), privateRoom.getId());
    }

    @Test
    void globalSearchByNonAdminIsRejected() {
        var bob = newUser("bob");

        authenticateAs("bob", "ROLE_USER");

        assertThatThrownBy(() -> search.searchEverywhere(bob, "anything", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void globalSearchWithoutAuthenticationIsRejected() {
        var bob = newUser("bob");

        // No authentication on the security context at all.
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> search.searchEverywhere(bob, "anything", 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- Input validation ----------

    @Test
    void blankAndShortQueriesReturnEmpty() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "anything here");

        assertThat(search.searchChannel(room, alice, null, 10)).isEmpty();
        assertThat(search.searchChannel(room, alice, "", 10)).isEmpty();
        assertThat(search.searchChannel(room, alice, "   ", 10)).isEmpty();
        assertThat(search.searchChannel(room, alice, "a", 10)).isEmpty(); // <2 chars
        assertThat(search.searchAllJoined(alice, "  ", 10)).isEmpty();
    }

    // ---------- Ranking & limits ----------

    @Test
    void resultsAreRankedByRelevance() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        var marker = "needle-" + SEQ.incrementAndGet();
        // Three occurrences of the term — should rank higher than the single occurrence.
        messages.post(room, alice, marker + " filler filler filler");
        messages.post(room, alice, marker + " " + marker + " " + marker + " stronger match");

        var hits = search.searchChannel(room, alice, marker, 10);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getBodyMarkdown()).contains("stronger match");
    }

    @Test
    void limitIsApplied() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        var marker = "limittest-" + SEQ.incrementAndGet();
        for (int i = 0; i < 5; i++) {
            messages.post(room, alice, marker + " message number " + i);
        }

        var hits = search.searchChannel(room, alice, marker, 3);
        assertThat(hits).hasSize(3);
    }

    @Test
    void editedBodyBecomesSearchable() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        var message = messages.post(room, alice, "initial wording goes here");

        assertThat(search.searchChannel(room, alice, "wording", 10)).hasSize(1);
        assertThat(search.searchChannel(room, alice, "rewritten", 10)).isEmpty();

        // Edit through the service — the index update fires after the transaction commits.
        messages.edit(message.getId(), alice, "rewritten content stored fresh");

        assertThat(search.searchChannel(room, alice, "wording", 10)).isEmpty();
        assertThat(search.searchChannel(room, alice, "rewritten", 10)).hasSize(1);
    }

    @Test
    void fuzzyMatchesNearMisses() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "PostgreSQL is great for full-text search");

        // 1-edit typo (transposition)
        assertThat(search.searchChannel(room, alice, "PostrgeSQL", 10)).hasSize(1);
        // 1-edit typo (deletion)
        assertThat(search.searchChannel(room, alice, "PostgreSQ", 10)).hasSize(1);
        // 1-edit typo (substitution) on a separate term
        assertThat(search.searchChannel(room, alice, "saerch", 10)).hasSize(1);
        // Wildly off (>2 edits) still shouldn't match.
        assertThat(search.searchChannel(room, alice, "elephant", 10)).isEmpty();
    }

    @Test
    void fuzzyDoesNotBreakQuotedPhrases() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "the quick brown fox jumps");
        messages.post(room, alice, "brown is a quick color");

        // Quoted phrase still requires the exact ordering even with fuzziness on.
        var hits = search.searchChannel(room, alice, "\"quick brown\"", 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getBodyMarkdown()).contains("quick brown fox");
    }

    @Test
    void deletedMessageDisappearsFromIndex() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        var marker = "deleteme-" + SEQ.incrementAndGet();
        var msg = messages.post(room, alice, marker + " this should be gone soon");

        assertThat(search.searchChannel(room, alice, marker, 10)).hasSize(1);

        messages.delete(msg.getId(), alice);

        assertThat(search.searchChannel(room, alice, marker, 10)).isEmpty();
    }

    // ---------- @author filter ----------

    @Test
    void renamingAnAuthorReindexesTheirMessagesForAtUserSearch() {
        // N23: the Lucene doc caches the author's username at write time; after a rename,
        // @newname search must still find the renamed user's older messages.
        var subject = "kc-rename-" + SEQ.incrementAndGet();
        var oldName = "oldhandle" + SEQ.incrementAndGet();
        var alice = userService.upsert(subject, oldName, "a@e.com", "Alice", false);
        var room = newPublic("rename-room", alice);
        messages.post(room, alice, "reindex me after the rename");

        var newName = "newhandle" + SEQ.incrementAndGet();
        var renamed = userService.upsert(subject, newName, "a@e.com", "Alice", false);
        assertThat(renamed.getId()).isEqualTo(alice.getId());
        assertThat(renamed.getUsername()).isEqualTo(newName);

        assertThat(search.searchChannel(room, renamed, "@" + newName, 10))
                .extracting(m -> m.getBodyMarkdown()).contains("reindex me after the rename");
    }

    @Test
    void atUserOnlyReturnsAllMessagesByThatAuthor() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newPublic("general", alice);
        channels.join(room, bob);
        messages.post(room, alice, "alpha message from alice");
        messages.post(room, alice, "second alice message");
        messages.post(room, bob,   "bob says hi");

        var hits = search.searchChannel(room, alice, "@" + alice.getUsername(), 10);

        assertThat(hits).hasSize(2);
        assertThat(hits).allMatch(m -> m.getAuthor().getId().equals(alice.getId()));
    }

    @Test
    void atUserCombinedWithKeywordIntersects() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newPublic("general", alice);
        channels.join(room, bob);
        messages.post(room, alice, "release notes for ship");
        messages.post(room, alice, "lunch plans for friday");
        messages.post(room, bob,   "release notes draft from bob");

        var hits = search.searchChannel(room, alice, "@" + alice.getUsername() + " release", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getAuthor().getId()).isEqualTo(alice.getId());
        assertThat(hits.get(0).getBodyMarkdown()).contains("release notes for ship");
    }

    @Test
    void multipleAtUsersOrTogether() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var carol = newUser("carol");
        var room = newPublic("general", alice);
        channels.join(room, bob);
        channels.join(room, carol);
        messages.post(room, alice, "alice line");
        messages.post(room, bob,   "bob line");
        messages.post(room, carol, "carol line");

        var hits = search.searchChannel(
                room, alice,
                "@" + alice.getUsername() + " @" + bob.getUsername(), 10);

        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(m -> m.getAuthor().getId())
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    @Test
    void editedMessageStaysFindableByItsAuthor() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        var msg = messages.post(room, alice, "before");
        messages.edit(msg.getId(), alice, "after edit");

        var hits = search.searchChannel(room, alice, "@" + alice.getUsername() + " after", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getBodyMarkdown()).isEqualTo("after edit");
    }

    @Test
    void unknownAtUserReturnsEmpty() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "anything");

        assertThat(search.searchChannel(room, alice, "@nobody-here-123", 10)).isEmpty();
    }

    // ---------- Snippet highlighting ----------

    @Autowired ai.intellistream.chat.search.MessageIndexService messageIndex;

    @Test
    void highlightWrapsMatchedTermInMark() {
        // The search dropdown shows a snippet of each result with the matched term wrapped
        // in <mark>. Highlighter is part of the index service so the same Analyzer that
        // indexed the message is used to find matching offsets.
        var snippet = messageIndex.highlight("postgres", "running postgres in production", 200);
        assertThat(snippet).contains("<mark>postgres</mark>");
    }

    @Test
    void highlightHtmlEscapesMatchSurroundings() {
        // Untrusted body content (e.g. literal <script>) must come back HTML-escaped so the
        // search dropdown's innerHTML render can't be a script-injection vector.
        var snippet = messageIndex.highlight("hello", "hello <script>alert(1)</script>", 200);
        assertThat(snippet).contains("<mark>hello</mark>");
        assertThat(snippet).contains("&lt;script&gt;");
        assertThat(snippet).doesNotContain("<script>");
    }

    @Test
    void highlightReturnsNullForMissingMatch() {
        // No match → null; the JS falls back to bodyHtml.
        assertThat(messageIndex.highlight("zzzzzzzz", "hello world", 200)).isNull();
    }

    @Test
    void highlightReturnsNullForBlankBody() {
        assertThat(messageIndex.highlight("anything", "", 200)).isNull();
        assertThat(messageIndex.highlight("anything", null, 200)).isNull();
    }

    @Test
    void highlightReturnsNullForUnparseableQuery() {
        // Two-character minimum on the parser; an empty string yields no Query and no snippet.
        assertThat(messageIndex.highlight("", "hello world", 200)).isNull();
    }
}
