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

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MentionService;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.UserRestController;
import ai.intellistream.chat.web.dto.MentionCandidateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Principal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The @-mention typeahead endpoint: what it matches, how it ranks, and — the part that matters —
 * how far it is allowed to see. A composer autocomplete that answers prefix queries over the whole
 * user table is a workspace directory, so the scoping rules are asserted here rather than trusted:
 * members first, non-members only for a {@code PUBLIC} channel, participants only for a DM, and
 * write access (real membership) required before any of it.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MentionCandidatesIT {

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
    @Autowired UserService userService;
    @Autowired ChannelService channels;
    @Autowired ConversationService conversations;
    @Autowired MentionService mentionService;
    @Autowired RateLimiter rateLimiter;

    private CurrentUser currentUser;
    private UserRestController controller;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void wire() {
        currentUser = mock(CurrentUser.class);
        controller = new UserRestController(userService, currentUser, rateLimiter,
                channels, conversations, mentionService);
    }

    private User user(String username, String displayName) {
        return users.save(new User("kc-mc-" + username, username, username + "@example.com", displayName));
    }

    private List<MentionCandidateDto> inChannel(User viewer, Long channelId, String q) {
        when(currentUser.resolve(any(Principal.class))).thenReturn(viewer);
        return controller.mentionCandidates(channelId, null, q, 8, mock(Principal.class));
    }

    private static List<String> handles(List<MentionCandidateDto> rows) {
        return rows.stream().map(MentionCandidateDto::username).toList();
    }

    /**
     * The case the seeded data demonstrates: two people whose display names both begin "Alice",
     * where only the handle tells them apart. Both must be findable by either field, and the
     * username prefix must come first — that is the row a user typing "@ali" is aiming at.
     */
    @Test
    void matchesUsernameAndDisplayNamePrefixFirst() {
        var n = SEQ.incrementAndGet();
        var aardvark = user("alice" + n, "Alice Aardvark");
        var anderson = user("aanderson" + n, "Alice Anderson");
        var bob = user("bob" + n, "Bob Brown");
        // PRIVATE so the assertion sees the ranking and nothing else: a public channel also pads
        // with non-members, and every other test in this class leaves an "Alice" in the database.
        var room = channels.create("cand-" + n, null, ChannelType.PRIVATE, aardvark);
        channels.invite(room, anderson, aardvark);
        channels.invite(room, bob, aardvark);

        // "ali": alice<n> matches on the username prefix (rank 0), Alice Anderson on the
        // display-name prefix (rank 1). Bob matches neither field and is absent entirely.
        var rows = inChannel(aardvark, room.getId(), "ali");
        assertThat(handles(rows)).containsExactly(aardvark.getUsername(), anderson.getUsername());
        assertThat(handles(rows)).doesNotContain(bob.getUsername());

        // A later word of the display name is still a match — "@and" finds "Alice Anderson" —
        // and the query is case-insensitive, because nobody types their colleague's handle case.
        assertThat(handles(inChannel(aardvark, room.getId(), "ANDers")))
                .containsExactly(anderson.getUsername());
    }

    /** Typing a bare "@" opens the list: no query means "the first few members", not "no rows". */
    @Test
    void emptyQueryListsMembers() {
        var n = SEQ.incrementAndGet();
        var alice = user("empty-a" + n, "Alice Empty");
        var bob = user("empty-b" + n, "Bob Empty");
        var room = channels.create("cand-empty-" + n, null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);

        var people = inChannel(alice, room.getId(), "").stream()
                .filter(r -> "user".equals(r.kind()))
                .toList();
        assertThat(handles(people))
                .containsExactlyInAnyOrder(alice.getUsername(), bob.getUsername());
    }

    /**
     * A private channel's composer must not answer questions about people who aren't in the room.
     * Padding it with directory hits would leak the existence of users to anyone who can type.
     */
    @Test
    void privateChannelReturnsMembersOnly() {
        var n = SEQ.incrementAndGet();
        var alice = user("priv-a" + n, "Priv Alice");
        var outsider = user("priv-out" + n, "Priv Outsider");
        var room = channels.create("cand-priv-" + n, null, ChannelType.PRIVATE, alice);

        var rows = inChannel(alice, room.getId(), "priv");
        assertThat(handles(rows)).containsExactly(alice.getUsername());
        assertThat(handles(rows)).doesNotContain(outsider.getUsername());
    }

    /**
     * A public channel does pad with non-members, flagged as such: a mention there really does
     * reach them (see the N2 filter in MentionService), so hiding them would hide something that
     * works. The flag is what lets the UI say "not in channel" instead of implying they're in it.
     */
    @Test
    void publicChannelPadsWithNonMembersAndFlagsThem() {
        var n = SEQ.incrementAndGet();
        var alice = user("pub-a" + n, "Pub Alice");
        var outsider = user("pub-out" + n, "Pub Outsider");
        var room = channels.create("cand-pub-" + n, null, ChannelType.PUBLIC, alice);

        var rows = inChannel(alice, room.getId(), "pub-");
        assertThat(handles(rows)).containsExactly(alice.getUsername(), outsider.getUsername());
        assertThat(rows.get(0).member()).isTrue();
        assertThat(rows.get(1).member()).isFalse();
    }

    /**
     * Write access, not read access. A lurker in a public channel can read it but cannot post to
     * it, so there is nothing for them to autocomplete — and the read check would have handed them
     * the member list of every public channel, one prefix at a time.
     */
    @Test
    void nonMemberOfPublicChannelIsDenied() {
        var n = SEQ.incrementAndGet();
        var alice = user("lurk-a" + n, "Lurk Alice");
        var lurker = user("lurk-b" + n, "Lurk Bob");
        var room = channels.create("cand-lurk-" + n, null, ChannelType.PUBLIC, alice);

        assertThatThrownBy(() -> inChannel(lurker, room.getId(), "lurk"))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** A conversation's typeahead is its participants — there is no public tier for a DM. */
    @Test
    void conversationScopeIsLimitedToItsParticipants() {
        var n = SEQ.incrementAndGet();
        var alice = user("conv-a" + n, "Conv Alice");
        var bob = user("conv-b" + n, "Conv Bob");
        var stranger = user("conv-c" + n, "Conv Stranger");
        var dm = conversations.directBetween(alice, bob);

        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);
        var rows = controller.mentionCandidates(null, dm.getId(), "conv-", 8, mock(Principal.class));
        assertThat(handles(rows)).containsExactlyInAnyOrder(alice.getUsername(), bob.getUsername());
        assertThat(handles(rows)).doesNotContain(stranger.getUsername());

        when(currentUser.resolve(any(Principal.class))).thenReturn(stranger);
        assertThatThrownBy(() ->
                controller.mentionCandidates(null, dm.getId(), "conv-", 8, mock(Principal.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The broadcast handles are offered from the same list, with the size of the audience attached.
     * That number is the notice: it is in front of the user while they are choosing the handle,
     * which is earlier and harder to reflex-dismiss than a confirmation dialog after the fact.
     */
    @Test
    void broadcastHandlesAreOfferedWithTheirAudienceSize() {
        var n = SEQ.incrementAndGet();
        var alice = user("bcast-a" + n, "Bcast Alice");
        var bob = user("bcast-b" + n, "Bcast Bob");
        var room = channels.create("cand-bcast-" + n, null, ChannelType.PRIVATE, alice);
        channels.invite(room, bob, alice);

        // A real prefix of the handle: the broadcast leads, because "ch" is not a person's name.
        var ch = inChannel(alice, room.getId(), "ch");
        assertThat(ch).isNotEmpty();
        assertThat(ch.get(0).kind()).isEqualTo("broadcast");
        assertThat(ch.get(0).username()).isEqualTo("channel");
        assertThat(ch.get(0).notifyCount()).isEqualTo(2);   // both members, however few are online

        // @here reports no count on purpose: its audience is whoever is connected at send time.
        var here = inChannel(alice, room.getId(), "her");
        assertThat(handles(here)).containsExactly("here");
        assertThat(here.get(0).notifyCount()).isZero();

        // @everyone is offered too — it is a synonym here, not an error.
        assertThat(handles(inChannel(alice, room.getId(), "every"))).containsExactly("everyone");
    }

    /**
     * A bare "@" leads with people. The broadcast rows are still there, at the end: a megaphone
     * under the first Enter of an empty query is the wrong default.
     */
    @Test
    void emptyQueryPutsBroadcastsLast() {
        var n = SEQ.incrementAndGet();
        var alice = user("tail-a" + n, "Tail Alice");
        var room = channels.create("cand-tail-" + n, null, ChannelType.PRIVATE, alice);

        var rows = inChannel(alice, room.getId(), "");
        assertThat(rows.get(0).kind()).isEqualTo("user");
        assertThat(handles(rows)).containsSubsequence(alice.getUsername(), "channel", "here", "everyone");
    }

    /** A conversation has no channel to notify, so it is offered no broadcast handles. */
    @Test
    void conversationsAreOfferedNoBroadcastHandles() {
        var n = SEQ.incrementAndGet();
        var alice = user("nobc-a" + n, "Nobc Alice");
        var bob = user("nobc-b" + n, "Nobc Bob");
        var dm = conversations.directBetween(alice, bob);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        var rows = controller.mentionCandidates(null, dm.getId(), "ch", 8, mock(Principal.class));
        assertThat(rows).noneMatch(r -> "broadcast".equals(r.kind()));
    }

    /** Neither scope, or both, is a client bug — answer 400 rather than guessing. */
    @Test
    void exactlyOneScopeIsRequired() {
        var n = SEQ.incrementAndGet();
        var alice = user("scope-a" + n, "Scope Alice");
        var room = channels.create("cand-scope-" + n, null, ChannelType.PUBLIC, alice);
        when(currentUser.resolve(any(Principal.class))).thenReturn(alice);

        assertThatThrownBy(() -> controller.mentionCandidates(null, null, "a", 8, mock(Principal.class)))
                .isInstanceOf(PublicBadRequestException.class);
        assertThatThrownBy(() ->
                controller.mentionCandidates(room.getId(), 1L, "a", 8, mock(Principal.class)))
                .isInstanceOf(PublicBadRequestException.class);
    }
}
