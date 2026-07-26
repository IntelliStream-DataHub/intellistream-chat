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

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.MessageModerationService;
import ai.intellistream.chat.moderation.RetentionPurgeScheduler;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.UserFileService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Storage accounting across the delete paths, against real Postgres.
 *
 * <p>The unit tests pin who calls what; this one pins the part they cannot see. {@code user_storage}
 * moves only through an atomic {@code addBytes} delta, so "did the number actually come back down"
 * is a question only the database can answer, and the queries that gather what to credit
 * ({@code AttachmentRepository.findByChannelWithAuthor} and {@code findByMessageIdsIncludingReplies})
 * are HQL that nothing else in the suite parses. The reply-expansion case in particular has no
 * cheaper test: a purged thread parent takes its replies' rows with it through an
 * {@code on delete cascade} that only the schema implements.
 *
 * <p>{@link Tx#commit()} appears throughout because every credit rides an {@code afterCommit} hook —
 * the class-level {@code @Transactional} would otherwise roll the test's transaction back before
 * those hooks ever fire.
 */
@Testcontainers
@SpringBootTest(
        // Listed explicitly rather than relied on as a nested @TestConfiguration: Spring Boot only
        // auto-detects those when it is left to find the primary configuration itself, and this
        // class names it.
        classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Transactional
class StorageAccountingIT {

    /**
     * {@code ChannelService} takes a {@link RateLimiter} (the channel-creation burst guard) and
     * {@code security} is outside this context's component scan, so without this the context cannot
     * be built at all. Supplied here rather than in {@link IntegrationTestApplication} to leave that
     * shared file alone. If a {@code RateLimiter} bean is ever added there, this nested class must
     * go — two definitions of the same name is a startup failure, and a loud one is the right way
     * to be told the workaround has outlived its purpose.
     */

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
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-storage-accounting");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired MessageRepository messageRepo;
    @Autowired AttachmentService attachments;
    @Autowired StorageQuotaService quotas;
    @Autowired MessageModerationService moderation;
    @Autowired RetentionPurgeScheduler purge;
    @Autowired UserFileService userFiles;
    @Autowired ai.intellistream.chat.service.SearchService search;
    @PersistenceContext EntityManager em;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void deletingAMessageGivesItsUploaderTheirBytesBack() throws IOException {
        var alice = newUser("alice");
        var room = newChannel(alice);
        var attachment = upload(room, alice, "report.bin", 512);
        Tx.commit();
        assertThat(usedBy(alice)).isEqualTo(512);

        messages.delete(attachment.getMessage().getId(), alice);
        Tx.commit();

        // Both halves of "the bytes are gone": off the disk, and off the account's ledger. A
        // decrement that never happens is invisible until the account cannot upload any more.
        assertThat(usedBy(alice)).isZero();
        assertThat(attachments.resolve(attachment)).doesNotExist();
    }

    /**
     * The double-credit case. Deleting your own file from the file manager tombstones the row and
     * credits the bytes; deleting the message it hung on then gathers that same row, because the
     * row still has to go and the file still has to be reaped. Crediting it twice hands the account
     * storage it never had, and {@code UserStorage} moves only through an atomic delta — nothing
     * downstream can notice the drift or repair it.
     *
     * <p>Two uploads, not one, so the assertion cannot be satisfied by a clamp at zero: the second
     * file's bytes are what a wrong credit would eat into.
     */
    @Test
    void aFileAlreadyDeletedFromTheFileManagerIsNotCreditedAgainWithItsMessage() throws IOException {
        var alice = newUser("alice");
        var room = newChannel(alice);
        var doomed = upload(room, alice, "draft.bin", 512);
        upload(room, alice, "keep.bin", 1000);
        Tx.commit();
        assertThat(usedBy(alice)).isEqualTo(1512);

        userFiles.delete(alice, UserFileService.Scope.CHANNEL, doomed.getId());
        Tx.commit();
        assertThat(usedBy(alice)).isEqualTo(1000);

        messages.delete(doomed.getMessage().getId(), alice);
        Tx.commit();

        assertThat(usedBy(alice))
                .as("the tombstoned file was credited when it was tombstoned, not again now")
                .isEqualTo(1000);
    }

    @Test
    void deletingAThreadCreditsEachReplyAuthorSeparately() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newChannel(alice);
        channels.join(room, bob);
        var parent = messages.post(room, alice, "who has the file?");
        em.flush();
        var reply = messages.replyInThread(parent.getId(), bob, "I do");
        em.flush();
        uploadOnto(reply, bob, 300);
        var aliceFile = upload(room, alice, "alice.bin", 100);
        Tx.commit();

        messages.delete(parent.getId(), alice);
        Tx.commit();

        // The reply is bob's, so its bytes are bob's — charging them to the thread starter would be
        // both wrong and untraceable, since the file on disk names nobody.
        assertThat(usedBy(bob)).isZero();
        // alice's own separate message is untouched; only the thread went.
        assertThat(usedBy(alice)).isEqualTo(100);
        assertThat(attachments.resolve(aliceFile)).exists();
    }

    @Test
    void destroyingAChannelCreditsEveryUploaderInIt() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newChannel(alice);
        channels.join(room, bob);
        upload(room, alice, "a.bin", 400);
        upload(room, bob, "b.bin", 250);
        Tx.commit();

        // Workspace admin, not channel admin — destroy narrowed to ROLE_ADMIN when the reversible
        // archive arrived to take its place for everyone else. The accounting this test is about is
        // unchanged; only who is allowed to trigger it moved.
        AsWorkspaceAdmin.run(() -> channels.destroy(room, alice));
        Tx.commit();

        assertThat(usedBy(alice)).isZero();
        assertThat(usedBy(bob)).isZero();
    }

    @Test
    void anAdminRemovalCreditsNothingUntilTheRetentionPurgeRuns() throws IOException {
        var alice = newUser("alice");
        var admin = newUser("root");
        var room = newChannel(alice);
        var attachment = upload(room, alice, "evidence.bin", 640);
        var messageId = attachment.getMessage().getId();
        Tx.commit();

        moderation.deleteOne(admin, messageId);
        Tx.commit();

        // Nothing has been freed: the row is flagged, the file is still on disk, and the removal is
        // still reversible. Crediting here would let the account delete-and-re-upload its way past
        // a quota that had released nothing.
        assertThat(usedBy(alice)).isEqualTo(640);
        assertThat(attachments.resolve(attachment)).exists();

        assertThat(purge.purgeDeletedBefore(Instant.now().plusSeconds(60))).isPositive();
        Tx.commit();

        // Now the bytes really have left the volume, so now they are credited back.
        assertThat(usedBy(alice)).isZero();
        assertThat(attachments.resolve(attachment)).doesNotExist();
        assertThat(messageRepo.findById(messageId)).isEmpty();
    }

    @Test
    void aPurgedThreadParentTakesItsRepliesFilesAndBytesWithIt() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var admin = newUser("root");
        var room = newChannel(alice);
        channels.join(room, bob);
        var parent = messages.post(room, alice, "thread starter");
        em.flush();
        var reply = messages.replyInThread(parent.getId(), bob, "with a file");
        em.flush();
        var replyFile = uploadOnto(reply, bob, 800);
        Tx.commit();

        // Both removed, so the purge's "no live replies underneath" guard lets the parent go and the
        // reply rides the parent's cascade — its id never appears in a purge batch. Its file and its
        // bytes have to be found through the parent or they are stranded forever.
        moderation.deleteOne(admin, reply.getId());
        moderation.deleteOne(admin, parent.getId());
        Tx.commit();

        assertThat(purge.purgeDeletedBefore(Instant.now().plusSeconds(60))).isPositive();
        Tx.commit();

        assertThat(usedBy(bob)).isZero();
        assertThat(attachments.resolve(replyFile)).doesNotExist();
    }

    @Test
    void aPurgedMessageTakesItsFilenameOutOfSearchForGood() throws IOException {
        // The last of the three deletion paths (the other two are the author's own delete and the
        // channel destroy, covered elsewhere). A filename is content like any other, so a purge
        // that hard-deleted the row and left the document behind would leave a file findable by
        // name for as long as the index lives — after the workspace decided to erase it.
        var alice = newUser("alice");
        var admin = newUser("root");
        var room = newChannel(alice);
        var name = "purged-ledger-" + SEQ.incrementAndGet() + ".csv";
        upload(room, alice, name, 128);
        Tx.commit();
        assertThat(search.searchChannel(room, alice, "ledger", 10)).hasSize(1);

        moderation.deleteAllByAuthor(admin, alice);
        Tx.commit();
        assertThat(purge.purgeDeletedBefore(Instant.now().plusSeconds(60))).isPositive();
        Tx.commit();

        assertThat(search.searchChannel(room, alice, "ledger", 10)).isEmpty();
    }

    @Test
    void aLiveAttachmentIsStillDownloadable() throws IOException {
        var alice = newUser("alice");
        var room = newChannel(alice);
        var attachment = upload(room, alice, "fine.bin", 64);
        Tx.commit();

        assertThat(attachments.requireForDownload(attachment.getId(), alice).getId())
                .isEqualTo(attachment.getId());
    }

    @Test
    void aRemovedMessagesAttachmentIsNoLongerDownloadable() throws IOException {
        var alice = newUser("alice");
        var admin = newUser("root");
        var room = newChannel(alice);
        var attachment = upload(room, alice, "regrettable.bin", 64);
        Tx.commit();

        moderation.deleteOne(admin, attachment.getMessage().getId());
        Tx.commit();

        // The message is only hidden, not gone — the row and the file both survive the retention
        // window. A gate that stopped at channel membership would keep serving this file to anyone
        // who kept the URL, which is the part of a removal a user actually cares about.
        //
        // Last statement on purpose: requireForDownload is @Transactional and joins the test's
        // transaction, so throwing marks it rollback-only and any later Tx.commit() would blow up.
        assertThatThrownBy(() -> attachments.requireForDownload(attachment.getId(), alice))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    private long usedBy(User user) {
        return quotas.usageFor(user).bytesUsed();
    }

    private ai.intellistream.chat.domain.Attachment upload(Channel room, User uploader,
                                                           String filename, int bytes)
            throws IOException {
        return attachments.upload(room, uploader, filename, "application/octet-stream",
                bytes, AttachmentBytes.DEFAULT_MAX_BYTES, "", new ByteArrayInputStream(new byte[bytes]));
    }

    /**
     * Attach a file to an existing message. {@code AttachmentService.upload} always creates its own
     * message, and these tests need one hanging off a thread reply.
     */
    private ai.intellistream.chat.domain.Attachment uploadOnto(ai.intellistream.chat.domain.Message message,
                                                               User uploader, int bytes) throws IOException {
        var key = java.util.UUID.randomUUID().toString();
        Files.write(attachments.storageRoot().resolve(key), new byte[bytes]);
        var saved = em.merge(new ai.intellistream.chat.domain.Attachment(
                message, "reply.bin", "application/octet-stream", bytes, key));
        quotas.recordUpload(uploader, bytes);
        em.flush();
        return saved;
    }

    private Channel newChannel(User creator) {
        return channels.create("room-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, creator);
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-sa-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }
}
