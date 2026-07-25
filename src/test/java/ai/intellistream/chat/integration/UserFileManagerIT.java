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
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.ConversationAttachmentRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationAttachmentService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.UserFileService;
import ai.intellistream.chat.web.dto.UserFileDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-user file manager against real Postgres: what it shows, what it refuses, and — the part
 * that has no cheaper test — whether the storage credit actually reaches the database.
 *
 * <p>{@code user_storage} moves only through an atomic {@code addBytes} delta, so "did the number
 * come back down" is a question only a committed transaction can answer. The credit is asserted
 * twice on purpose: once <em>before</em> {@link Tx#commit()}, which is what proves it was issued
 * inside the deleting transaction rather than deferred, and once after, which is what proves the
 * statement was actually committed. A credit registered in an {@code afterCommit} hook would pass
 * neither — the hook runs while the finished transaction's resources are still bound, so its UPDATE
 * joins a transaction that has already committed and is silently lost.
 */
@Testcontainers
@SpringBootTest(classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class UserFileManagerIT {

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
        registry.add("ichat.attachments.dir", () -> "build/test-attachments-file-manager");
        TestLuceneDirs.register(registry);
    }

    @Autowired UserRepository users;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired MessageRepository messageRepo;
    @Autowired ai.intellistream.chat.repository.ConversationMessageRepository conversationMessageRepo;
    @Autowired AttachmentService attachments;
    @Autowired AttachmentRepository attachmentRepo;
    @Autowired ConversationService conversations;
    @Autowired ConversationAttachmentService conversationAttachments;
    @Autowired ConversationAttachmentRepository conversationAttachmentRepo;
    @Autowired StorageQuotaService quotas;
    @Autowired MessageModerationService moderation;
    @Autowired UserFileService files;
    @PersistenceContext EntityManager em;

    private static final AtomicInteger SEQ = new AtomicInteger();

    // ------------------------------------------------------------------ listing + ownership

    @Test
    void listsBothChannelAndConversationUploadsWithTheirLocation() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newChannel(alice);
        upload(room, alice, "quarterly.pdf", 120);
        var dm = conversations.directBetween(alice, bob);
        em.flush();
        dmUpload(dm, alice, "holiday.png", 80);
        em.flush();

        var page = files.list(alice, null, 0);

        assertThat(page.files()).extracting(UserFileDto::filename)
                .containsExactlyInAnyOrder("quarterly.pdf", "holiday.png");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalBytes()).isEqualTo(200);
        // The "posted in" column has to survive a round trip to be useful — a channel is named and
        // anchored on the message, a DM is named after whoever is on the other end.
        var channelRow = page.files().stream()
                .filter(f -> f.filename().equals("quarterly.pdf")).findFirst().orElseThrow();
        assertThat(channelRow.locationLabel()).isEqualTo("#" + room.getName());
        assertThat(channelRow.locationUrl()).startsWith("/channels/" + room.getId() + "?m=");
        var dmRow = page.files().stream()
                .filter(f -> f.filename().equals("holiday.png")).findFirst().orElseThrow();
        assertThat(dmRow.locationKind()).isEqualTo("direct");
        assertThat(dmRow.locationLabel()).isEqualTo(bob.getDisplayName());
    }

    @Test
    void userAneverSeesUserBsFiles() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newChannel(alice);
        channels.join(room, bob);
        upload(room, alice, "mine.bin", 10);
        upload(room, bob, "bobs-secret.bin", 20);
        var dm = conversations.directBetween(alice, bob);
        em.flush();
        dmUpload(dm, bob, "bobs-dm-file.bin", 30);
        em.flush();

        // Same channel, same conversation, same page — the listing is scoped by uploader, not by
        // what the viewer can read.
        assertThat(files.list(alice, null, 0).files()).extracting(UserFileDto::filename)
                .containsExactly("mine.bin");
        assertThat(files.list(alice, null, 0).totalBytes()).isEqualTo(10);
        assertThat(files.list(bob, null, 0).files()).extracting(UserFileDto::filename)
                .containsExactlyInAnyOrder("bobs-secret.bin", "bobs-dm-file.bin");
    }

    @Test
    void searchMatchesFilenameSubstringsAndStaysScopedToTheOwner() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newChannel(alice);
        channels.join(room, bob);
        upload(room, alice, "2026-Invoice-final.pdf", 10);
        upload(room, alice, "cat.png", 20);
        // Same name, different owner: a search that leaked across accounts would return two rows.
        upload(room, bob, "2026-Invoice-final.pdf", 30);

        var hits = files.list(alice, "invoice", 0);

        assertThat(hits.files()).extracting(UserFileDto::filename).containsExactly("2026-Invoice-final.pdf");
        assertThat(hits.total()).isEqualTo(1);
        // Case-insensitive in both directions, and a miss really misses.
        assertThat(files.list(alice, "INVOICE", 0).files()).hasSize(1);
        assertThat(files.list(alice, "invoicing", 0).files()).isEmpty();
    }

    @Test
    void searchTreatsUnderscoreAndPercentAsCharactersNotWildcards() throws IOException {
        var alice = newUser("alice");
        var room = newChannel(alice);
        upload(room, alice, "report_2026.csv", 10);
        upload(room, alice, "reportX2026.csv", 20);

        // Unescaped, "report_2026" would be a single-character wildcard and match both.
        assertThat(files.list(alice, "report_2026", 0).files()).extracting(UserFileDto::filename)
                .containsExactly("report_2026.csv");
        // And a bare "%" must not turn into "match everything".
        assertThat(files.list(alice, "%", 0).files()).isEmpty();
    }

    // ------------------------------------------------------------------ delete: the quota credit

    @Test
    void deletingAChannelFileCreditsTheQuotaInTheSameTransactionAndCommitsIt() throws IOException {
        var alice = newUser("alice");
        var room = newChannel(alice);
        var attachment = upload(room, alice, "big.bin", 4096);
        var messageId = attachment.getMessage().getId();
        var file = attachments.resolve(attachment);
        Tx.commit();
        assertThat(usedBy(alice)).isEqualTo(4096);

        var deleted = files.delete(alice, UserFileService.Scope.CHANNEL, attachment.getId());

        assertThat(deleted.bytesFreed()).isEqualTo(4096);
        // Before the commit: the decrement is already visible to this transaction's own
        // connection, which is what "same transaction" means and what an afterCommit hook could
        // never manage. Read through the database, not the session — see usedByRereadingTheRow.
        assertThat(usedByRereadingTheRow(alice)).isZero();

        Tx.commit();

        // After the commit, read back through a fresh transaction: the statement was not merely
        // issued, it was committed. This is the assertion the afterCommit bug fails.
        assertThat(usedBy(alice)).isZero();
        // The message stays; the attachment becomes a tombstone. The quota assertions above are
        // the point of this test and are unchanged by that — what changed is that the row it
        // credits for survives, carrying who removed it and when.
        assertThat(messageRepo.findById(messageId)).isPresent();
        var tomb = attachmentRepo.findById(attachment.getId()).orElseThrow();
        assertThat(tomb.isDeleted()).isTrue();
        assertThat(tomb.getDeletedByUsername()).isEqualTo(alice.getUsername());
        // The bytes left the disk too — a credit for bytes that are still stored is just a leak
        // with better bookkeeping.
        assertThat(file).doesNotExist();
    }

    @Test
    void deletingAConversationFileCreditsTheQuotaInTheSameTransactionAndCommitsIt() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        em.flush();
        var attachment = dmUpload(dm, alice, "dm.bin", 2048);
        var messageId = attachment.getMessage().getId();
        var file = conversationAttachments.resolve(attachment);
        Tx.commit();
        assertThat(usedBy(alice)).isEqualTo(2048);

        files.delete(alice, UserFileService.Scope.CONVERSATION, attachment.getId());

        // ConversationService.deleteMessage has no credit of its own, so this half is the file
        // manager's own releaseAll — and it has to land in the same transaction as the delete.
        assertThat(usedByRereadingTheRow(alice)).isZero();

        Tx.commit();

        assertThat(usedBy(alice)).isZero();
        var tomb = conversationAttachmentRepo.findById(attachment.getId()).orElseThrow();
        assertThat(tomb.isDeleted()).isTrue();
        assertThat(tomb.getDeletedByUsername()).isEqualTo(alice.getUsername());
        assertThat(conversationMessageRepo.findById(messageId))
                .describedAs("the DM that carried it is untouched").isPresent();
        assertThat(file).doesNotExist();
    }

    // ------------------------------------------------------------------ delete: authorization

    @Test
    void userBCannotDeleteUserAsChannelFile() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newChannel(alice);
        channels.join(room, bob);
        var attachment = upload(room, alice, "alices.bin", 512);
        Tx.commit();

        // 404, not 403: whether that id belongs to somebody is not bob's to learn.
        assertThatThrownBy(() -> files.delete(bob, UserFileService.Scope.CHANNEL, attachment.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        // Nothing moved: the row, the file and the ledger are all where alice left them. Asserted
        // in a fresh transaction because the throw above marked the current one rollback-only.
        TestTx.freshRead(() -> {
            assertThat(attachmentRepo.findById(attachment.getId())).isPresent();
            assertThat(usedBy(alice)).isEqualTo(512);
            assertThat(attachments.resolve(attachment)).exists();
        });
    }

    @Test
    void userBCannotDeleteUserAsConversationFile() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var dm = conversations.directBetween(alice, bob);
        em.flush();
        var attachment = dmUpload(dm, alice, "alices-dm.bin", 256);
        Tx.commit();

        // bob is a member of this very conversation and can read the file — membership is not
        // ownership, and the file manager only ever deletes what the caller uploaded.
        assertThatThrownBy(() -> files.delete(bob, UserFileService.Scope.CONVERSATION, attachment.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        TestTx.freshRead(() -> {
            assertThat(conversationAttachmentRepo.findById(attachment.getId())).isPresent();
            assertThat(usedBy(alice)).isEqualTo(256);
        });
    }

    @Test
    void aChannelAdminCannotDeleteSomeoneElsesFileFromTheFileManager() throws IOException {
        var alice = newUser("alice");
        var owner = newUser("owner");
        var room = newChannel(owner); // owner is the channel's admin
        channels.join(room, alice);
        var attachment = upload(room, alice, "alices.bin", 64);
        Tx.commit();

        // MessageService.delete would allow this (author OR channel admin). The file manager is a
        // different thing: it manages *your* files, and a room's admin is not their owner. If it
        // deferred to that rule, "Your files" would quietly become "files an admin may reap".
        assertThatThrownBy(() -> files.delete(owner, UserFileService.Scope.CHANNEL, attachment.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        TestTx.freshRead(() -> assertThat(attachmentRepo.findById(attachment.getId())).isPresent());
    }

    // ------------------------------------------------------------------ delete: the policy

    @Test
    void deletingAFileLeavesItsMessageAndEveryReplyStanding() throws IOException {
        var alice = newUser("alice");
        var bob = newUser("bob");
        var room = newChannel(alice);
        channels.join(room, bob);
        var attachment = upload(room, alice, "spec.pdf", 100);
        var messageId = attachment.getMessage().getId();
        em.flush();
        messages.replyInThread(messageId, bob, "thanks!");
        Tx.commit();

        // This used to be refused: deleting the file deleted its message, and that cascade would
        // have taken bob's reply with it. Tombstoning the attachment instead means the replies
        // were never at risk, so the refusal had nothing left to protect — and the files most
        // worth removing are exactly the ones people replied to.
        var row = files.list(alice, "spec", 0).files().get(0);
        assertThat(row.deletable()).isTrue();
        assertThat(row.blockedReason()).isNull();

        files.delete(alice, UserFileService.Scope.CHANNEL, attachment.getId());
        Tx.commit();

        TestTx.freshRead(() -> {
            var kept = attachmentRepo.findById(attachment.getId()).orElseThrow();
            assertThat(kept.isDeleted()).describedAs("the row survives as a tombstone").isTrue();
            assertThat(kept.getDeletedByUsername()).isEqualTo(alice.getUsername());
            assertThat(messageRepo.findById(messageId))
                    .describedAs("the message that posted it is untouched").isPresent();
            assertThat(messageRepo.countRepliesIncludingDeletedByParentIds(List.of(messageId)))
                    .describedAs("bob's reply is still there")
                    .anySatisfy(r -> assertThat(((Number) r[1]).longValue()).isEqualTo(1L));
            assertThat(usedBy(alice)).describedAs("and the bytes came back").isZero();
        });
    }

    @Test
    void refusesToDeleteAFileHeldByAModeratorRemovedMessage() throws IOException {
        var alice = newUser("alice");
        var admin = newUser("root");
        var room = newChannel(alice);
        var attachment = upload(room, alice, "evidence.bin", 640);
        Tx.commit();
        moderation.deleteOne(admin, attachment.getMessage().getId());
        Tx.commit();

        // Still listed, because the account is still charged for it — hiding it would make the
        // quota figure look like a bug.
        var row = files.list(alice, null, 0).files().get(0);
        assertThat(row.deletable()).isFalse();
        assertThat(row.blockedReason()).contains("removed by a moderator");

        assertThatThrownBy(() -> files.delete(alice, UserFileService.Scope.CHANNEL, attachment.getId()))
                .isInstanceOf(UserFileService.FileDeleteRefusedException.class);
        TestTx.freshRead(() -> {
            // The soft delete is reversible and the retention purge owns these bytes; letting the
            // uploader finish it here would quietly make a moderation decision unreviewable.
            assertThat(attachmentRepo.findById(attachment.getId())).isPresent();
            assertThat(usedBy(alice)).isEqualTo(640);
            assertThat(attachments.resolve(attachment)).exists();
        });
    }

    @Test
    void aFileOnAThreadReplyIsDeletableBecauseNothingHangsUnderIt() throws IOException {
        var alice = newUser("alice");
        var room = newChannel(alice);
        var parent = messages.post(room, alice, "here is the thread");
        em.flush();
        var reply = messages.replyInThread(parent.getId(), alice, "and the file");
        em.flush();
        var attachment = attachOnto(reply, alice, 128);
        Tx.commit();

        var row = files.list(alice, null, 0).files().get(0);
        assertThat(row.deletable()).isTrue();
        // The link anchors on the parent: the channel page's ?m= anchor rejects reply ids and
        // would silently fall back to "latest 50".
        assertThat(row.locationUrl()).endsWith("?m=" + parent.getId());

        files.delete(alice, UserFileService.Scope.CHANNEL, attachment.getId());
        Tx.commit();

        assertThat(usedBy(alice)).isZero();
        assertThat(messageRepo.findById(parent.getId())).isPresent(); // the thread itself survives
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Run assertions in a transaction of their own. The refusal tests provoke an exception from a
     * {@code @Transactional} service method, which marks the test's transaction rollback-only —
     * every later read on it would fail on the mark rather than on the assertion.
     */
    private static final class TestTx {
        static void freshRead(Runnable assertions) {
            org.springframework.test.context.transaction.TestTransaction.end();
            org.springframework.test.context.transaction.TestTransaction.start();
            assertions.run();
        }
    }

    private long usedBy(User user) {
        return quotas.usageFor(user).bytesUsed();
    }

    /**
     * {@link #usedBy} forced to go to the database.
     *
     * <p>{@code UserStorageRepository.addBytes} is a {@code @Modifying} native upsert, so it
     * bypasses the persistence context: the row changes but the {@code UserStorage} entity already
     * loaded in this session does not, and {@code findById} keeps answering from that stale copy.
     * Reading the pre-commit value without clearing first therefore measures Hibernate's first-level
     * cache and reports "the credit never happened" for a credit that did.
     */
    private long usedByRereadingTheRow(User user) {
        em.clear();
        return quotas.usageFor(user).bytesUsed();
    }

    private ai.intellistream.chat.domain.Attachment upload(Channel room, User uploader,
                                                           String filename, int bytes)
            throws IOException {
        return attachments.upload(room, uploader, filename, "application/octet-stream",
                bytes, AttachmentBytes.DEFAULT_MAX_BYTES, "",
                new ByteArrayInputStream(new byte[bytes]));
    }

    private ai.intellistream.chat.domain.ConversationAttachment dmUpload(
            ai.intellistream.chat.domain.Conversation conversation, User uploader,
            String filename, int bytes) throws IOException {
        return conversationAttachments.upload(conversation, uploader, filename,
                "application/octet-stream", bytes, AttachmentBytes.DEFAULT_MAX_BYTES, "",
                new ByteArrayInputStream(new byte[bytes]));
    }

    /** Attach a file to an existing message — {@code AttachmentService.upload} always makes its own,
     *  and the thread-reply case needs one hanging off a reply. */
    private ai.intellistream.chat.domain.Attachment attachOnto(
            ai.intellistream.chat.domain.Message message, User uploader, int bytes)
            throws IOException {
        var key = java.util.UUID.randomUUID().toString();
        java.nio.file.Files.write(attachments.storageRoot().resolve(key), new byte[bytes]);
        var saved = em.merge(new ai.intellistream.chat.domain.Attachment(
                message, "on-reply.bin", "application/octet-stream", bytes, key));
        quotas.recordUpload(uploader, bytes);
        em.flush();
        return saved;
    }

    private Channel newChannel(User creator) {
        return channels.create("files-room-" + SEQ.incrementAndGet(), null, ChannelType.PUBLIC, creator);
    }

    private User newUser(String prefix) {
        var i = SEQ.incrementAndGet();
        return users.save(new User("kc-fm-" + prefix + i, prefix + i, prefix + i + "@e", prefix + " " + i));
    }
}
