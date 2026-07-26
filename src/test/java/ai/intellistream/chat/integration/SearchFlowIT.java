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

package ai.intellistream.chat.integration;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.PublicBadRequestException;
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
import static org.assertj.core.api.Assertions.catchThrowable;

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
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-search-flow");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired SearchService search;
    @Autowired ai.intellistream.chat.service.UserService userService;
    @Autowired ai.intellistream.chat.service.AttachmentService attachments;
    @Autowired ai.intellistream.chat.service.ConversationService conversations;
    @Autowired ai.intellistream.chat.service.ConversationAttachmentService conversationAttachments;
    @Autowired ai.intellistream.chat.repository.AttachmentRepository attachmentRepo;

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

    /** Unwrap the channel-message side of a mixed result list. */
    private static java.util.List<ai.intellistream.chat.domain.Message> channelMessages(
            java.util.List<SearchService.SearchHit> hits) {
        return hits.stream()
                .filter(h -> h instanceof SearchService.SearchHit.ChannelHit)
                .map(h -> ((SearchService.SearchHit.ChannelHit) h).message())
                .toList();
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

        var hits = channelMessages(search.searchAccessible(bob, "shared-token", 10));

        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(m -> m.getChannel().getId())
                .containsExactlyInAnyOrder(roomA.getId(), roomB.getId());
    }

    @Test
    void theDefaultScopeReachesAPublicChannelTheViewerNeverJoined() {
        // requireMember short-circuits for PUBLIC channels, so Bob may open this room, read every
        // message in it and download its files. Search used to be the one surface that pretended
        // it wasn't there — you could read #incidents cover to cover and be told the workspace
        // contains no message with the word "outage" in it.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var lobby = newPublic("lobby", alice);
        var joinedRoom = newPublic("joined", alice);
        channels.join(joinedRoom, bob); // so bob has a non-empty footprint of his own
        var marker = "neverjoined-" + SEQ.incrementAndGet();
        messages.post(lobby, alice, marker + " posted where bob is not a member");

        var hits = channelMessages(search.searchAccessible(bob, marker, 10));

        assertThat(hits).singleElement()
                .satisfies(m -> assertThat(m.getChannel().getId()).isEqualTo(lobby.getId()));
    }

    @Test
    void aResultFromAChannelTheViewerHasNotJoinedIsMarkedAsSuch() {
        // The flag the UI hangs a "not joined" tag on. Without it the result opens a page with no
        // composer and no explanation, which reads as a broken channel rather than a joinable one.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var lobby = newPublic("lobby", alice);
        var joinedRoom = newPublic("joined", alice);
        channels.join(joinedRoom, bob);
        var marker = "joinflag-" + SEQ.incrementAndGet();
        messages.post(lobby, alice, marker + " over here");
        messages.post(joinedRoom, alice, marker + " and over here");

        var hits = search.searchAccessible(bob, marker, 10).stream()
                .map(h -> (SearchService.SearchHit.ChannelHit) h)
                .toList();

        assertThat(hits).hasSize(2);
        assertThat(hits).filteredOn(h -> h.message().getChannel().getId().equals(lobby.getId()))
                .singleElement().satisfies(h -> assertThat(h.joined()).isFalse());
        assertThat(hits).filteredOn(h -> h.message().getChannel().getId().equals(joinedRoom.getId()))
                .singleElement().satisfies(h -> assertThat(h.joined()).isTrue());
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

        var hits = channelMessages(search.searchAccessible(bob, marker, 10));

        // The line the widening must not cross: public in, private-and-not-joined out.
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getChannel().getId()).isEqualTo(publicRoom.getId());
    }

    @Test
    void aPrivateChannelStaysInvisibleEvenWithNothingElseToSearch() {
        // The degenerate case the widening could break: a viewer who belongs to nothing at all now
        // has a non-empty channel filter (every public channel), so "no accessible container" no
        // longer short-circuits before the query runs. The private room must be excluded by the
        // filter itself rather than by there being no query at all.
        var alice = newUser("alice");
        var loner = newUser("loner"); // joins nothing, is in no conversation
        var secret = newPrivate("secret", alice);
        var marker = "lonertest-" + SEQ.incrementAndGet();
        messages.post(secret, alice, marker + " behind a closed door");

        assertThat(search.searchAccessible(loner, marker, 10)).isEmpty();

        // Control: the message is indexed and matchable — a member finds it.
        assertThat(channelMessages(search.searchAccessible(alice, marker, 10))).hasSize(1);
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
        assertThat(search.searchAccessible(alice, "  ", 10)).isEmpty();
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

    // ---------- from: (author) vs @handle (mention) ----------

    @Test
    void fromAndAtHandleSelectGenuinelyDifferentMessages() {
        // The heart of the syntax change. Both queries name Bob; one asks who wrote the message
        // and the other asks who it is about, and they must not return the same row. Asserting
        // both directions is what makes this a test rather than a coincidence: a build that
        // ignored the mention field entirely would return Bob's own message for both.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newPublic("general", alice);
        channels.join(room, bob);
        var marker = "standup-" + SEQ.incrementAndGet();
        messages.post(room, bob, marker + " notes, written by bob");
        messages.post(room, alice, "@" + bob.getUsername() + " " + marker + " is yours today");

        var written = search.searchChannel(room, alice, "from:" + bob.getUsername() + " " + marker, 10);
        assertThat(written).singleElement()
                .satisfies(m -> assertThat(m.getBodyMarkdown()).contains("written by bob"));

        var mentioning = search.searchChannel(room, alice, "@" + bob.getUsername() + " " + marker, 10);
        assertThat(mentioning).singleElement()
                .satisfies(m -> assertThat(m.getBodyMarkdown()).contains("is yours today"));
    }

    @Test
    void fromAcceptsTheHandleWithOrWithoutItsAtSign() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        var marker = "fromsigil-" + SEQ.incrementAndGet();
        messages.post(room, alice, marker + " from alice");

        assertThat(search.searchChannel(room, alice, "from:" + alice.getUsername() + " " + marker, 10))
                .hasSize(1);
        assertThat(search.searchChannel(room, alice, "from:@" + alice.getUsername() + " " + marker, 10))
                .hasSize(1);
    }

    @Test
    void aMentionInsideAnEditedBodyFollowsTheEdit() {
        // The mention field is derived from the body at index time, so an edit has to rewrite it.
        // Left stale, @bob would keep finding a message that no longer names him.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newPublic("general", alice);
        channels.join(room, bob);
        var marker = "editmention-" + SEQ.incrementAndGet();
        var msg = messages.post(room, alice, "@" + bob.getUsername() + " " + marker);

        assertThat(search.searchChannel(room, alice, "@" + bob.getUsername() + " " + marker, 10))
                .hasSize(1);

        messages.edit(msg.getId(), alice, marker + " never mind");

        assertThat(search.searchChannel(room, alice, "@" + bob.getUsername() + " " + marker, 10))
                .isEmpty();
        assertThat(search.searchChannel(room, alice, marker, 10)).hasSize(1);
    }

    // ---------- in:#channel ----------

    @Test
    void inChannelNarrowsAGlobalSearchToOneChannel() {
        var alice = newUser("alice");
        var roomA = newPublic("alpha", alice);
        var roomB = newPublic("beta", alice);
        var marker = "inscope-" + SEQ.incrementAndGet();
        messages.post(roomA, alice, marker + " in the first room");
        messages.post(roomB, alice, marker + " in the second room");

        assertThat(channelMessages(search.searchAccessible(alice, marker, 10))).hasSize(2);

        var scoped = channelMessages(
                search.searchAccessible(alice, "in:#" + roomA.getSlug() + " " + marker, 10));
        assertThat(scoped).singleElement()
                .satisfies(m -> assertThat(m.getChannel().getId()).isEqualTo(roomA.getId()));
    }

    @Test
    void inChannelResolvesTheDisplayNameAsWellAsTheSlug() {
        var alice = newUser("alice");
        var room = newPublic("alpha", alice);
        var marker = "byname-" + SEQ.incrementAndGet();
        messages.post(room, alice, marker + " findable by either identifier");

        assertThat(channelMessages(search.searchAccessible(alice, "in:#" + room.getName() + " " + marker, 10)))
                .hasSize(1);
        assertThat(channelMessages(search.searchAccessible(alice, "in:#" + room.getSlug() + " " + marker, 10)))
                .hasSize(1);
    }

    @Test
    void inChannelFailsVisiblyForAChannelTheViewerCannotRead() {
        // The failure that matters. A silently-ignored in: would widen the search back to
        // everything the viewer can read and hand them results from a different channel, which
        // reads as if the modifier had worked.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var secret = newPrivate("secret", alice);
        messages.post(secret, alice, "closed-door planning notes");

        assertThatThrownBy(() -> search.searchAccessible(bob, "in:#" + secret.getSlug() + " planning", 10))
                .isInstanceOf(PublicBadRequestException.class);
    }

    @Test
    void inChannelFailsTheSameWayForAChannelThatDoesNotExist() {
        // Deliberately indistinguishable from the unreadable case: a different message for
        // "exists but not for you" turns the search box into a private-channel name oracle.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var secret = newPrivate("secret", alice);

        var unreadable = catchThrowable(
                () -> search.searchAccessible(bob, "in:#" + secret.getSlug() + " anything", 10));
        var missing = catchThrowable(
                () -> search.searchAccessible(bob, "in:#no-such-channel-here anything", 10));

        assertThat(unreadable).isInstanceOf(PublicBadRequestException.class);
        assertThat(missing).isInstanceOf(PublicBadRequestException.class);
        // The message is a pure function of what the user typed — it echoes their own input and
        // says nothing else. Both cases produce the same sentence, so no reply distinguishes
        // "that channel exists and is not for you" from "there is no such channel".
        assertThat(unreadable.getMessage())
                .isEqualTo("No channel called #" + secret.getSlug() + " that you can read.");
        assertThat(missing.getMessage())
                .isEqualTo("No channel called #no-such-channel-here that you can read.");
    }

    @Test
    void inChannelReachesAPublicChannelTheViewerNeverJoined() {
        var alice = newUser("alice");
        var bob = newUser("bob"); // never joins
        var lobby = newPublic("lobby", alice);
        var marker = "openroom-" + SEQ.incrementAndGet();
        messages.post(lobby, alice, marker + " anyone can read this");

        assertThat(channelMessages(search.searchAccessible(bob, "in:#" + lobby.getSlug() + " " + marker, 10)))
                .hasSize(1);
    }

    // ---------- unknown modifiers ----------

    @Test
    void anUnknownModifierIsSearchedForAsText() {
        // Lucene would otherwise read `note:xyz` as a query on a field called "note", which does
        // not exist, and answer with a confident zero.
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        var marker = "unknownmod" + SEQ.incrementAndGet();
        messages.post(room, alice, "note:" + marker + " written with a colon in it");
        messages.post(room, alice, "unrelated line");

        assertThat(search.searchChannel(room, alice, "note:" + marker, 10)).hasSize(1);
    }

    @Test
    void theUnimplementedDateModifiersFindNothingRatherThanEverything() {
        // before:/after:/has: are not supported. As text they simply don't match, which is a
        // visible "no results" instead of a modifier that quietly widened the search.
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        var marker = "datemod-" + SEQ.incrementAndGet();
        messages.post(room, alice, marker + " a message with no date modifier in its body");

        assertThat(search.searchChannel(room, alice, "before:friday " + marker, 10)).isEmpty();
        assertThat(search.searchChannel(room, alice, marker, 10)).hasSize(1);
    }

    // ---------- from: (author) filter ----------
    //
    // These were written when a bare `@name` was the author filter. The token now means "mentions
    // name", so every one of them moved to `from:` — the same assertions about the same behaviour,
    // spelled the way the syntax spells it now. `fromAndAtHandleSelectGenuinelyDifferentMessages`
    // above is the test that the two spellings really do ask different questions.

    @Test
    void renamingAnAuthorReindexesTheirMessagesForFromSearch() {
        // N23: the Lucene doc caches the author's username at write time; after a rename,
        // from:newname search must still find the renamed user's older messages.
        var subject = "kc-rename-" + SEQ.incrementAndGet();
        var oldName = "oldhandle" + SEQ.incrementAndGet();
        var alice = userService.upsert(subject, oldName, "a@e.com", "Alice", false);
        var room = newPublic("rename-room", alice);
        messages.post(room, alice, "reindex me after the rename");

        var newName = "newhandle" + SEQ.incrementAndGet();
        var renamed = userService.upsert(subject, newName, "a@e.com", "Alice", false);
        assertThat(renamed.getId()).isEqualTo(alice.getId());
        assertThat(renamed.getUsername()).isEqualTo(newName);

        assertThat(search.searchChannel(room, renamed, "from:" + newName, 10))
                .extracting(m -> m.getBodyMarkdown()).contains("reindex me after the rename");
    }

    @Test
    void fromAloneReturnsAllMessagesByThatAuthor() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newPublic("general", alice);
        channels.join(room, bob);
        messages.post(room, alice, "alpha message from alice");
        messages.post(room, alice, "second alice message");
        messages.post(room, bob,   "bob says hi");

        var hits = search.searchChannel(room, alice, "from:" + alice.getUsername(), 10);

        assertThat(hits).hasSize(2);
        assertThat(hits).allMatch(m -> m.getAuthor().getId().equals(alice.getId()));
    }

    @Test
    void fromCombinedWithKeywordIntersects() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newPublic("general", alice);
        channels.join(room, bob);
        messages.post(room, alice, "release notes for ship");
        messages.post(room, alice, "lunch plans for friday");
        messages.post(room, bob,   "release notes draft from bob");

        var hits = search.searchChannel(room, alice, "from:" + alice.getUsername() + " release", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getAuthor().getId()).isEqualTo(alice.getId());
        assertThat(hits.get(0).getBodyMarkdown()).contains("release notes for ship");
    }

    @Test
    void multipleFromValuesOrTogether() {
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
                "from:" + alice.getUsername() + " from:" + bob.getUsername(), 10);

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

        var hits = search.searchChannel(room, alice, "from:" + alice.getUsername() + " after", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getBodyMarkdown()).isEqualTo("after edit");
    }

    @Test
    void anUnknownFromHandleReturnsEmpty() {
        var alice = newUser("alice");
        var room = newPublic("general", alice);
        messages.post(room, alice, "anything");

        assertThat(search.searchChannel(room, alice, "from:nobody-here-123", 10)).isEmpty();
        // And the mention spelling of a handle nobody has named is equally empty.
        assertThat(search.searchChannel(room, alice, "@nobody-here-123", 10)).isEmpty();
    }

    // ---------- Paging and totals ----------

    @Test
    void aPagedSearchReportsTheWholeSetAndWalksItWithoutRepeatsOrGaps() {
        // The count is what the results page shows and the pager is what it draws, so both are
        // asserted against a set the test knows the size of. Collecting the ids across pages and
        // comparing to the ids of one big page is the assertion that matters: a paging bug shows up
        // as a duplicate or a missing row, not as a wrong total.
        var alice = newUser("alice");
        var room = newPublic("paging", alice);
        var marker = "pagemarker" + SEQ.incrementAndGet();
        for (int i = 0; i < 25; i++) {
            messages.post(room, alice, marker + " message number " + i);
        }

        var first = search.searchPage(alice, marker, SearchService.ScopeKind.CHANNEL, room, null, 0, 10);
        assertThat(first.total()).isEqualTo(25);
        assertThat(first.totalIsLowerBound()).isFalse();
        assertThat(first.hits()).hasSize(10);
        assertThat(first.hasPrevious()).isFalse();
        assertThat(first.hasNext()).isTrue();
        assertThat(first.firstResultNumber()).isEqualTo(1);
        assertThat(first.lastResultNumber()).isEqualTo(10);

        var last = search.searchPage(alice, marker, SearchService.ScopeKind.CHANNEL, room, null, 2, 10);
        assertThat(last.hits()).hasSize(5);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.firstResultNumber()).isEqualTo(21);
        assertThat(last.lastResultNumber()).isEqualTo(25);

        var walked = new java.util.ArrayList<Long>();
        for (int p = 0; p < 3; p++) {
            search.searchPage(alice, marker, SearchService.ScopeKind.CHANNEL, room, null, p, 10)
                    .hits().stream()
                    .map(h -> ((SearchService.SearchHit.ChannelHit) h).message().getId())
                    .forEach(walked::add);
        }
        var inOneGo = search.searchChannel(room, alice, marker, 25).stream()
                .map(ai.intellistream.chat.domain.Message::getId)
                .toList();
        assertThat(walked).containsExactlyElementsOf(inOneGo);
        assertThat(walked).doesNotHaveDuplicates();
    }

    @Test
    void pagingPastTheEndIsEmptyRatherThanTheLastPageAgain() {
        var alice = newUser("alice");
        var room = newPublic("paging-end", alice);
        var marker = "pastend" + SEQ.incrementAndGet();
        messages.post(room, alice, marker + " the only one");

        var beyond = search.searchPage(alice, marker, SearchService.ScopeKind.CHANNEL, room, null, 5, 10);
        assertThat(beyond.hits()).isEmpty();
    }

    @Test
    void pagingCannotBeUsedToAskForUnboundedWork() {
        // offset is attacker-controlled and Lucene collects offset+size documents to serve a page,
        // so an unbounded offset is a way to ask the server for arbitrary memory and CPU. Past the
        // window the answer is empty, not expensive.
        var alice = newUser("alice");
        var room = newPublic("paging-window", alice);
        var marker = "windowcap" + SEQ.incrementAndGet();
        messages.post(room, alice, marker + " one message");

        var absurd = search.searchPage(alice, marker, SearchService.ScopeKind.CHANNEL, room, null,
                1_000_000, 100);
        assertThat(absurd.hits()).isEmpty();
    }

    @Test
    void theAclHoldsOnEveryPageAndNotJustTheFirst() {
        // Pagination is where post-filtering fails worst: a page drawn from an unrestricted window
        // arrives short, and the shortfall itself tells the viewer something exists. Every page of
        // a paged search has to be filtered by the same query clause as the first.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var open = newPublic("open", alice);
        var secret = newPrivate("closed", alice);
        channels.join(open, bob);
        var marker = "pagedacl" + SEQ.incrementAndGet();
        for (int i = 0; i < 12; i++) {
            messages.post(open, alice, marker + " public number " + i);
            messages.post(secret, alice, marker + " private number " + i);
        }

        var bodies = new java.util.ArrayList<String>();
        long total = -1;
        for (int p = 0; p < 3; p++) {
            var pageOfResults = search.searchPage(bob, marker,
                    SearchService.ScopeKind.ACCESSIBLE, null, null, p, 5);
            total = pageOfResults.total();
            pageOfResults.hits().stream()
                    .map(h -> ((SearchService.SearchHit.ChannelHit) h).message().getBodyMarkdown())
                    .forEach(bodies::add);
        }

        assertThat(total).isEqualTo(12);              // the count leaks nothing either
        assertThat(bodies).hasSize(12);
        assertThat(bodies).allMatch(b -> b.contains("public number"));
        assertThat(bodies).noneMatch(b -> b.contains("private number"));
    }

    // ---------- Attachment filenames ----------
    //
    // These go through the real upload path rather than the index, because the interesting part is
    // the ordering: AttachmentService.upload is handed an ALREADY-INDEXED message and creates the
    // attachment row afterwards, so the filename can only reach the index by a second write. Every
    // test below would still pass against an index-level fake and tell you nothing.

    @Test
    void aSharedFilesNameFindsTheMessageThatCarriesIt() throws java.io.IOException {
        var alice = newUser("alice");
        var room = newPublic("files", alice);
        var name = "quarterly-report-" + SEQ.incrementAndGet() + ".pdf";
        upload(room, alice, name, "");

        // The whole name, and a word out of the middle of it — someone looking for a file rarely
        // remembers the exact spelling of all three parts.
        assertThat(search.searchChannel(room, alice, name, 10)).hasSize(1);
        assertThat(search.searchChannel(room, alice, "quarterly", 10)).hasSize(1);
    }

    @Test
    void aFileWithNoCaptionIsStillFindableByItsName() throws java.io.IOException {
        // The common case, and the one that had no index document at all before: a caption-less
        // upload is saved straight through the repository and never goes near MessageService.post,
        // so nothing indexed it until a reconcile sweep noticed the id was missing.
        var alice = newUser("alice");
        var room = newPublic("files", alice);
        upload(room, alice, "silent-upload-" + SEQ.incrementAndGet() + ".bin", "");

        assertThat(search.searchChannel(room, alice, "silent", 10)).hasSize(1);
    }

    @Test
    void editingTheCaptionDoesNotStopTheFileBeingFound() throws java.io.IOException {
        // The regression this feature invites. An index document is rewritten whole, so an edit
        // that only carries the new body erases the filenames — silently, and only noticeable to
        // someone searching for a file they shared months ago.
        var alice = newUser("alice");
        var room = newPublic("files", alice);
        var name = "budget-" + SEQ.incrementAndGet() + ".xlsx";
        var attachment = upload(room, alice, name, "first wording of the caption");
        assertThat(search.searchChannel(room, alice, "budget", 10)).hasSize(1);

        messages.edit(attachment.getMessage().getId(), alice, "second wording of the caption");

        assertThat(search.searchChannel(room, alice, "second wording", 10)).hasSize(1);
        assertThat(search.searchChannel(room, alice, "budget", 10)).hasSize(1);
    }

    @Test
    void aMessageCarryingTwoFilesIsOneResultFoundByEitherName() throws java.io.IOException {
        var alice = newUser("alice");
        var room = newPublic("files", alice);
        var seq = SEQ.incrementAndGet();
        var first = upload(room, alice, "runbook-" + seq + ".md", "the release pack");
        // A second attachment onto the SAME message — the shape the message-per-upload path does
        // not produce on its own, and the one where a document-per-attachment design would return
        // this message twice.
        attachOnto(first.getMessage(), alice, "screenshots-" + seq + ".zip");

        assertThat(search.searchChannel(room, alice, "runbook", 10)).hasSize(1);
        assertThat(search.searchChannel(room, alice, "screenshots", 10)).hasSize(1);
    }

    @Test
    void deletingTheMessageTakesItsFilenameOutOfSearch() throws java.io.IOException {
        var alice = newUser("alice");
        var room = newPublic("files", alice);
        var name = "doomed-" + SEQ.incrementAndGet() + ".pdf";
        var attachment = upload(room, alice, name, "");
        assertThat(search.searchChannel(room, alice, "doomed", 10)).hasSize(1);

        messages.delete(attachment.getMessage().getId(), alice);

        assertThat(search.searchChannel(room, alice, "doomed", 10)).isEmpty();
    }

    @Test
    void aFileSharedInADirectMessageIsFoundByName() throws java.io.IOException {
        // The conversation half runs down its own write path (ConversationAttachmentService →
        // ConversationService), so the channel tests above prove nothing about it.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var name = "holiday-photos-" + SEQ.incrementAndGet() + ".zip";
        conversationAttachments.upload(dm, alice, name, "application/octet-stream", 4,
                ai.intellistream.chat.attachments.AttachmentBytes.DEFAULT_MAX_BYTES, "",
                new java.io.ByteArrayInputStream(new byte[4]));

        assertThat(search.searchConversation(dm, bob, "holiday", 10)).hasSize(1);
    }

    @Test
    void editingADirectMessageCaptionDoesNotStopItsFileBeingFound() throws java.io.IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var name = "dmbudget-" + SEQ.incrementAndGet() + ".xlsx";
        var attachment = conversationAttachments.upload(dm, alice, name,
                "application/octet-stream", 4,
                ai.intellistream.chat.attachments.AttachmentBytes.DEFAULT_MAX_BYTES,
                "first dm wording", new java.io.ByteArrayInputStream(new byte[4]));

        conversations.editMessage(attachment.getMessage().getId(), alice, "second dm wording");

        assertThat(search.searchConversation(dm, bob, "second dm wording", 10)).hasSize(1);
        assertThat(search.searchConversation(dm, bob, "dmbudget", 10)).hasSize(1);
    }

    private ai.intellistream.chat.domain.Attachment upload(Channel room, User uploader,
                                                           String filename, String caption)
            throws java.io.IOException {
        return attachments.upload(room, uploader, filename, "application/octet-stream", 4,
                ai.intellistream.chat.attachments.AttachmentBytes.DEFAULT_MAX_BYTES, caption,
                new java.io.ByteArrayInputStream(new byte[4]));
    }

    /**
     * A second file onto an existing message. {@code AttachmentService.upload} always creates its
     * own message, so the row is written directly here and the index told the same way the upload
     * path tells it — which is exactly what a future multi-file upload would do.
     */
    private void attachOnto(ai.intellistream.chat.domain.Message message, User uploader,
                            String filename) throws java.io.IOException {
        var key = java.util.UUID.randomUUID().toString();
        java.nio.file.Files.write(attachments.storageRoot().resolve(key), new byte[4]);
        attachmentRepo.save(new ai.intellistream.chat.domain.Attachment(
                message, filename, "application/octet-stream", 4, key));
        messages.reindexAfterAttachmentChange(message);
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
