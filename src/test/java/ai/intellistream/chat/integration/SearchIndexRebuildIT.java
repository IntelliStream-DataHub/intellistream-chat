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

import ai.intellistream.chat.cleanup.CleanupProperties;
import ai.intellistream.chat.cleanup.CleanupTasks;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.ConversationAttachmentRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.search.LuceneBootstrap;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.AvatarService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.service.SearchService.SearchHit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two paths that write an index document without going through the message write path:
 * {@link LuceneBootstrap}'s rebuild from Postgres, and {@link CleanupTasks}'s periodic reconcile.
 *
 * <p>They exist here because {@code mentions} is a <em>derived</em> field — computed from the body
 * when a document is written — and a derived field is exactly the kind that gets added to the hot
 * write path and forgotten everywhere else. The failure that produces is quiet and slow: search
 * works for everyone testing it on a fresh deployment, and then some months later a restart or a
 * reconcile sweep rewrites older documents and {@code @someone} starts returning a different set
 * than it did yesterday. Every one of these tests therefore asserts on a document the rebuild
 * wrote, never on one the write path wrote.
 */
@Testcontainers
@SpringBootTest(
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SearchIndexRebuildIT {

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
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-index-rebuild");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired ConversationService conversations;
    @Autowired SearchService search;
    @Autowired MessageIndexService messageIndex;
    @Autowired LuceneBootstrap luceneBootstrap;
    @Autowired AttachmentService attachmentService;
    @Autowired ai.intellistream.chat.service.ConversationAttachmentService conversationAttachmentService;
    @Autowired AvatarService avatarService;
    @Autowired ai.intellistream.chat.linkpreview.LinkPreviewService linkPreviews;
    @Autowired AttachmentRepository attachmentRepo;
    @Autowired ConversationAttachmentRepository convAttachmentRepo;
    @Autowired ai.intellistream.chat.repository.MessageRepository messageRepo;
    @Autowired ai.intellistream.chat.repository.ConversationMessageRepository conversationMessageRepo;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User newUser(String prefix) {
        var id = SEQ.incrementAndGet();
        return users.save(new User("kc-" + prefix + id, prefix + id,
                prefix + id + "@example.com", prefix + " " + id));
    }

    private Channel newPublic(String name, User creator) {
        return channels.create(name + "-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, creator);
    }

    private List<String> bodiesFor(User viewer, String query) {
        return search.searchAccessible(viewer, query, 50).stream()
                .map(hit -> switch (hit) {
                    case SearchHit.ChannelHit c -> c.message().getBodyMarkdown();
                    case SearchHit.ConversationHit c -> c.message().getBodyMarkdown();
                })
                .toList();
    }

    /** Empty the index the way a wiped data directory would, leaving Postgres untouched. */
    private void wipeIndex() {
        messageIndex.deleteAll(messageIndex.allIndexedIds());
        messageIndex.deleteAllConversationMessages(messageIndex.allIndexedConversationIds());
        assertThat(messageIndex.isEmpty()).isTrue();
    }

    private CleanupTasks armedCleanup() {
        var props = new CleanupProperties();
        props.setEnabled(true);
        props.setDryRun(false); // the sweeps ship inert; this test is about what they do when armed
        return new CleanupTasks(props, attachmentService, avatarService, attachmentRepo,
                convAttachmentRepo, users, messageRepo, conversationMessageRepo, messageIndex, linkPreviews);
    }

    @Test
    void theStartupRebuildPopulatesTheMentionFieldForChannelMessages() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newPublic("rebuild-channel", alice);
        channels.join(room, bob);
        var marker = "rebuildmarker" + SEQ.incrementAndGet();
        messages.post(room, alice, "hey @" + bob.getUsername() + " " + marker + " please look");

        wipeIndex();
        luceneBootstrap.rebuildOrReconcile();

        // The mention filter is what's under test; the marker keeps the assertion tied to this row.
        assertThat(bodiesFor(bob, "@" + bob.getUsername() + " " + marker))
                .singleElement().asString().contains(marker);
    }

    @Test
    void theStartupRebuildPopulatesTheMentionFieldForConversationMessages() {
        // The conversation half runs down a different code path (reconcileConversationTail →
        // reindexConversations), so "the channel rebuild is fine" proves nothing about it.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var marker = "dmrebuild" + SEQ.incrementAndGet();
        conversations.post(dm, alice, "@" + bob.getUsername() + " " + marker + " about the dm");

        wipeIndex();
        luceneBootstrap.rebuildOrReconcile();

        assertThat(bodiesFor(bob, "@" + bob.getUsername() + " " + marker))
                .singleElement().asString().contains(marker);
    }

    @Test
    void theStartupRebuildLeavesTheIndexStampedAsCurrent() {
        // The stamp is what stops the rebuild running again on the next boot. If it were never
        // written, every restart would reindex the whole corpus from Postgres.
        wipeIndex();
        luceneBootstrap.rebuildOrReconcile();

        assertThat(messageIndex.schemaOutdated()).isFalse();
    }

    @Test
    void theCleanupReconcileRewritesAMissingChannelDocumentWithItsMentions() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newPublic("reconcile-channel", alice);
        channels.join(room, bob);
        var marker = "reconcilemarker" + SEQ.incrementAndGet();
        var posted = messages.post(room, alice, "@" + bob.getUsername() + " " + marker + " ping");

        // Drop just this document — the crash-window state the sweep exists to heal.
        messageIndex.delete(posted.getId());
        assertThat(bodiesFor(bob, marker)).isEmpty();

        armedCleanup().reconcileSearchIndex();

        assertThat(bodiesFor(bob, "@" + bob.getUsername() + " " + marker))
                .singleElement().asString().contains(marker);
    }

    @Test
    void theCleanupReconcileRewritesAMissingConversationDocumentWithItsMentions() {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var marker = "dmreconcile" + SEQ.incrementAndGet();
        var posted = conversations.post(dm, alice, "@" + bob.getUsername() + " " + marker + " ping");

        messageIndex.deleteConversationMessage(posted.getId());
        assertThat(bodiesFor(bob, marker)).isEmpty();

        armedCleanup().reconcileConversationSearchIndex();

        assertThat(bodiesFor(bob, "@" + bob.getUsername() + " " + marker))
                .singleElement().asString().contains(marker);
    }

    // ---------- attachment filenames ----------
    //
    // Filenames are the second derived field on these documents and they have a worse failure mode
    // than mentions: a body still has its own text to fall back on, whereas a file posted with no
    // caption is findable by its name or not at all. Every test below asserts on a document one of
    // the bulk paths wrote, because the write path having it proves nothing about them.

    /** How many results a channel-scoped search returns for {@code viewer}. */
    private int channelHits(Channel room, User viewer, String query) {
        return search.searchChannel(room, viewer, query, 50).size();
    }

    @Test
    void theStartupRebuildCarriesTheAttachmentFilenames() throws java.io.IOException {
        var alice = newUser("alice");
        var room = newPublic("rebuild-files", alice);
        var name = "rebuildfile" + SEQ.incrementAndGet() + ".pdf";
        upload(room, alice, name);

        wipeIndex();
        luceneBootstrap.rebuildOrReconcile();

        assertThat(channelHits(room, alice, name)).isEqualTo(1);
    }

    @Test
    void theStartupRebuildCarriesConversationAttachmentFilenames() throws java.io.IOException {
        // Different code path (reconcileConversationTail → reindexConversations), same trap.
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var name = "dmrebuildfile" + SEQ.incrementAndGet() + ".zip";
        dmUpload(dm, alice, name);

        wipeIndex();
        luceneBootstrap.rebuildOrReconcile();

        assertThat(search.searchConversation(dm, bob, name, 50)).hasSize(1);
    }

    @Test
    void theCleanupReconcileRewritesAMissingChannelDocumentWithItsFilenames() {
        var alice = newUser("alice");
        var room = newPublic("reconcile-files", alice);
        var name = "reconcilefile" + SEQ.incrementAndGet() + ".pdf";
        var attachment = uploadUnchecked(room, alice, name);

        messageIndex.delete(attachment.getMessage().getId());
        assertThat(channelHits(room, alice, name)).isZero();

        armedCleanup().reconcileSearchIndex();

        assertThat(channelHits(room, alice, name)).isEqualTo(1);
    }

    @Test
    void theCleanupReconcileRewritesAMissingConversationDocumentWithItsFilenames()
            throws java.io.IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        var name = "dmreconcilefile" + SEQ.incrementAndGet() + ".zip";
        var attachment = dmUpload(dm, alice, name);

        messageIndex.deleteConversationMessage(attachment.getMessage().getId());
        assertThat(search.searchConversation(dm, bob, name, 50)).isEmpty();

        armedCleanup().reconcileConversationSearchIndex();

        assertThat(search.searchConversation(dm, bob, name, 50)).hasSize(1);
    }

    @Test
    void aReconcileSweepLeavesAnUpToDateDocumentWithFilesAlone() throws java.io.IOException {
        // The failure AGENTS.md warns about, in its quietest form: a sweep that decided a document
        // it did not recognise was stale, or that rewrote a healthy one from a projection missing
        // half its fields. Either way the file is gone from search an hour after it was uploaded,
        // and nothing anywhere says so.
        var alice = newUser("alice");
        var room = newPublic("untouched-files", alice);
        var name = "untouchedfile" + SEQ.incrementAndGet() + ".pdf";
        upload(room, alice, name);
        assertThat(channelHits(room, alice, name)).isEqualTo(1);

        var cleanup = armedCleanup();
        cleanup.reconcileSearchIndex();
        cleanup.reconcileConversationSearchIndex();

        assertThat(channelHits(room, alice, name)).isEqualTo(1);
    }

    private ai.intellistream.chat.domain.Attachment upload(Channel room, User uploader, String filename)
            throws java.io.IOException {
        return attachmentService.upload(room, uploader, filename, "application/octet-stream", 4,
                ai.intellistream.chat.attachments.AttachmentBytes.DEFAULT_MAX_BYTES, "",
                new java.io.ByteArrayInputStream(new byte[4]));
    }

    /** {@link #upload} where the checked exception would only add noise to the test body. */
    private ai.intellistream.chat.domain.Attachment uploadUnchecked(Channel room, User uploader,
                                                                    String filename) {
        try {
            return upload(room, uploader, filename);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private ai.intellistream.chat.domain.ConversationAttachment dmUpload(
            ai.intellistream.chat.domain.Conversation dm, User uploader, String filename)
            throws java.io.IOException {
        return conversationAttachmentService.upload(dm, uploader, filename,
                "application/octet-stream", 4,
                ai.intellistream.chat.attachments.AttachmentBytes.DEFAULT_MAX_BYTES, "",
                new java.io.ByteArrayInputStream(new byte[4]));
    }
}
