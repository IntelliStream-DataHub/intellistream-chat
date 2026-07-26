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
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.service.SearchService.SearchHit;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end proof that search over direct and group conversations cannot return content from a
 * conversation the searcher is not a member of.
 *
 * <p>The assertions are deliberately about <b>content</b>, not counts. A count assertion passes
 * for the wrong reasons all the time — the term never got indexed, the fixture didn't commit, an
 * unrelated document filled the result window — and it would also pass against a post-filtering
 * implementation that leaks through hit totals and pagination. So every leak test here reads:
 *
 * <ol>
 *   <li>the outsider's results contain no message body from the private conversation,</li>
 *   <li>a member running the identical query <em>does</em> get that body back.</li>
 * </ol>
 *
 * Step 2 is the guard against a green test that proves nothing.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ConversationSearchAccessIT {

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
    @Autowired ConversationService conversations;
    @Autowired SearchService search;
    @Autowired MessageIndexService messageIndex;
    @Autowired ai.intellistream.chat.search.LuceneBootstrap luceneBootstrap;
    @Autowired ai.intellistream.chat.service.UserService userService;

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

    /** Every body the viewer's global search returned, whatever store it came from. */
    private List<String> bodiesVisibleTo(User viewer, String query) {
        return search.searchAccessible(viewer, query, 50).stream()
                .map(hit -> switch (hit) {
                    case SearchHit.ChannelHit c -> c.message().getBodyMarkdown();
                    case SearchHit.ConversationHit c -> c.message().getBodyMarkdown();
                })
                .toList();
    }

    /**
     * Give the searcher a normal, non-empty footprint: a public channel they joined and a DM of
     * their own. Without it a passing test could just be "this user can't search at all", which
     * is not the property under test.
     */
    private void giveTheSearcherSomethingOfTheirOwn(User searcher, User friend, String marker) {
        var lobby = newPublic("lobby", searcher);
        channels.join(lobby, friend);
        messages.post(lobby, searcher, marker + " in a channel the searcher is in");
        var ownDm = conversations.directBetween(searcher, friend);
        conversations.post(ownDm, searcher, marker + " in the searcher's own dm");
    }

    // ---------- The security property ----------

    @Test
    void aDirectMessageBetweenTwoOtherPeopleNeverMatches() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var snooper = newUser("snoop");
        var friend = newUser("friend");

        var marker = "sealevel" + SEQ.incrementAndGet();
        var secret = marker + " the office alarm code is 4417";

        var privateDm = conversations.directBetween(alice, bob);
        conversations.post(privateDm, alice, secret);
        giveTheSearcherSomethingOfTheirOwn(snooper, friend, marker);

        var visible = bodiesVisibleTo(snooper, marker);

        // The point of the test: the content is absent, not merely under-counted.
        assertThat(visible).doesNotContain(secret);
        assertThat(visible).noneMatch(body -> body.contains("4417"));
        // …and the snooper's own results are otherwise intact, so this isn't "search is broken".
        assertThat(visible).anyMatch(body -> body.contains("the searcher's own dm"));

        // Control: the same query, run by a participant, does return the body. Without this the
        // assertions above would also pass if the message had never been indexed at all.
        assertThat(bodiesVisibleTo(alice, marker)).contains(secret);
        assertThat(bodiesVisibleTo(bob, marker)).contains(secret);
    }

    @Test
    void aGroupConversationTheSearcherIsNotInNeverMatches() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dave = newUser("dave");
        var snooper = newUser("snoop");
        var friend = newUser("friend");

        var marker = "tradewind" + SEQ.incrementAndGet();
        var secret = marker + " severance terms for the layoffs";

        var group = conversations.createGroup("Leads " + SEQ.incrementAndGet(), alice, List.of(bob, dave));
        conversations.post(group, bob, secret);
        giveTheSearcherSomethingOfTheirOwn(snooper, friend, marker);

        var visible = bodiesVisibleTo(snooper, marker);

        assertThat(visible).doesNotContain(secret);
        assertThat(visible).noneMatch(body -> body.contains("severance"));
        assertThat(visible).anyMatch(body -> body.contains("the searcher's own dm"));

        // Control: all three members can find it.
        assertThat(bodiesVisibleTo(alice, marker)).contains(secret);
        assertThat(bodiesVisibleTo(bob, marker)).contains(secret);
        assertThat(bodiesVisibleTo(dave, marker)).contains(secret);
    }

    @Test
    void beingAddedToAGroupChangesWhatIsSearchableImmediately() {
        // The ACL is read from the database per query, so a membership change needs no reindex.
        // The flip side is the one that matters: it also can't go stale in the leak direction.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var latecomer = newUser("late");
        var friend = newUser("friend");

        var marker = "driftwood" + SEQ.incrementAndGet();
        var secret = marker + " the retro notes";
        var group = conversations.createGroup("Squad " + SEQ.incrementAndGet(), alice, List.of(bob));
        conversations.post(group, alice, secret);
        giveTheSearcherSomethingOfTheirOwn(latecomer, friend, marker);

        assertThat(bodiesVisibleTo(latecomer, marker)).doesNotContain(secret);

        conversations.addToGroup(group, latecomer, alice);

        assertThat(bodiesVisibleTo(latecomer, marker)).contains(secret);
    }

    @Test
    void scopedConversationSearchRejectsANonMember() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var snooper = newUser("snoop");

        var marker = "quiethour" + SEQ.incrementAndGet();
        var privateDm = conversations.directBetween(alice, bob);
        conversations.post(privateDm, alice, marker + " confidential");

        // ?conversationId= must not become a way to aim the search at somebody else's DM.
        assertThatThrownBy(() -> search.searchConversation(privateDm, snooper, marker, 10))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(search.searchConversation(privateDm, alice, marker, 10))
                .extracting(m -> m.getBodyMarkdown())
                .anyMatch(body -> body.contains("confidential"));
    }

    @Test
    void theAdminWideSearchDoesNotReachConversations() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var admin = newUser("admin");

        var marker = "auditword" + SEQ.incrementAndGet();
        var channelBody = marker + " posted in a channel";
        var dmBody = marker + " posted in a private dm";

        var room = newPublic("boardroom", alice);
        messages.post(room, alice, channelBody);
        var privateDm = conversations.directBetween(alice, bob);
        conversations.post(privateDm, alice, dmBody);

        SecurityContextHolder.getContext().setAuthentication(
                authenticated(admin.getUsername(), "ROLE_ADMIN"));
        var adminResults = search.searchEverywhere(admin, marker, 50).stream()
                .map(m -> m.getBodyMarkdown())
                .toList();

        assertThat(adminResults).contains(channelBody);
        assertThat(adminResults).doesNotContain(dmBody);
        // Control: the DM body is indexed and findable — by its participants.
        assertThat(bodiesVisibleTo(alice, marker)).contains(dmBody);
    }

    // ---------- Ordinary behaviour ----------

    @Test
    void conversationHitsAppearAlongsideChannelHits() {
        var alice = newUser("alice");
        var bob = newUser("bob");

        var marker = "standup" + SEQ.incrementAndGet();
        var room = newPublic("general", alice);
        channels.join(room, bob);
        var channelBody = marker + " notes in the channel";
        messages.post(room, alice, channelBody);

        var dm = conversations.directBetween(alice, bob);
        var dmBody = marker + " notes in the dm";
        conversations.post(dm, bob, dmBody);

        var hits = search.searchAccessible(alice, marker, 50);

        assertThat(hits.stream().map(h -> switch (h) {
            case SearchHit.ChannelHit c -> c.message().getBodyMarkdown();
            case SearchHit.ConversationHit c -> c.message().getBodyMarkdown();
        })).contains(channelBody, dmBody);
        // Both stores are represented, and each hit carries the identity the UI needs to link it.
        assertThat(hits).anySatisfy(h -> assertThat(h).isInstanceOf(SearchHit.ChannelHit.class));
        assertThat(hits).anySatisfy(h -> assertThat(h)
                .isInstanceOfSatisfying(SearchHit.ConversationHit.class,
                        c -> assertThat(c.message().getConversation().getId()).isEqualTo(dm.getId())));
    }

    @Test
    void anEditedConversationMessageIsReindexed() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var marker = "revision" + SEQ.incrementAndGet();
        var posted = conversations.post(dm, alice, marker + " original phrasing");

        assertThat(bodiesVisibleTo(alice, marker + " original")).anyMatch(b -> b.contains("original"));

        conversations.editMessage(posted.getId(), alice, marker + " replacement phrasing");

        assertThat(bodiesVisibleTo(alice, marker + " original")).isEmpty();
        assertThat(bodiesVisibleTo(alice, marker + " replacement"))
                .anyMatch(b -> b.contains("replacement phrasing"));
    }

    @Test
    void aDeletedConversationMessageLeavesTheIndex() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var marker = "ephemeral" + SEQ.incrementAndGet();
        var posted = conversations.post(dm, alice, marker + " say it once");

        assertThat(bodiesVisibleTo(alice, marker)).anyMatch(b -> b.contains("say it once"));

        conversations.deleteMessage(posted.getId(), alice);

        assertThat(bodiesVisibleTo(alice, marker)).isEmpty();
    }

    @Test
    void renamingAnAuthorReindexesTheirConversationMessages() {
        // Spelled `from:` since the syntax change — a bare `@handle` now asks who a message is
        // about, not who wrote it. The behaviour under test (the doc caches the username at write
        // time, so a rename has to rewrite it) is unchanged.
        var subject = "kc-dmrename-" + SEQ.incrementAndGet();
        var oldName = "dmold" + SEQ.incrementAndGet();
        var alice = userService.upsert(subject, oldName, "a@e.com", "Alice", false);
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var marker = "handleswap" + SEQ.incrementAndGet();
        conversations.post(dm, alice, marker + " sent under the old handle");

        var newName = "dmnew" + SEQ.incrementAndGet();
        var renamed = userService.upsert(subject, newName, "a@e.com", "Alice", false);

        assertThat(bodiesVisibleTo(renamed, "from:" + newName + " " + marker))
                .anyMatch(b -> b.contains("sent under the old handle"));
    }

    // ---------- Reconcile ----------

    @Test
    void theStartupReconcileRestoresLostConversationDocuments() {
        // Async commits mean a crash can lose index documents that are durable in the DB. The
        // startup sweep has to heal DMs too, or a restart silently drops them out of search
        // until the message is next edited.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var marker = "reconcileme" + SEQ.incrementAndGet();
        var posted = conversations.post(dm, alice, marker + " must survive a restart");

        // Simulate the lost tail: drop the document, keep the row.
        messageIndex.deleteConversationMessage(posted.getId());
        assertThat(bodiesVisibleTo(alice, marker)).isEmpty();

        luceneBootstrap.rebuildOrReconcile();

        assertThat(bodiesVisibleTo(alice, marker)).anyMatch(b -> b.contains("must survive a restart"));
    }

    @Test
    void theStartupReconcileDropsConversationDocumentsWhoseRowIsGone() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var marker = "orphandoc" + SEQ.incrementAndGet();

        // A document for a conversation message id that no row has — the shape a lost post-commit
        // delete leaves behind.
        long ghostId = 900_000_000L + SEQ.incrementAndGet();
        messageIndex.indexConversationMessage(ghostId, dm.getId(), alice.getUsername(),
                marker + " should not survive", List.of());
        assertThat(messageIndex.allIndexedConversationIds()).contains(ghostId);

        luceneBootstrap.rebuildOrReconcile();

        assertThat(messageIndex.allIndexedConversationIds()).doesNotContain(ghostId);
        assertThat(bodiesVisibleTo(alice, marker)).isEmpty();
    }

    private static TestingAuthenticationToken authenticated(String username, String... roles) {
        var auth = new TestingAuthenticationToken(username, "n/a", roles);
        auth.setAuthenticated(true);
        return auth;
    }
}
