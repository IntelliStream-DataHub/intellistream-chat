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

package ai.intellistream.chat.moderation;

import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.service.AttachmentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The purge is where a removed message's storage is actually reclaimed, and therefore the only
 * place its bytes may honestly be credited back.
 *
 * <p>Admin removal is a soft delete: the row stays, the file stays, nothing is freed. Crediting
 * there would let an account delete and re-upload its way past a quota that had released nothing —
 * the exact failure a quota exists to prevent. So the credit lives here, together with the file
 * reap, and both happen strictly after the batch has committed: a file deleted for a row that then
 * failed to delete is a message that renders with a broken attachment forever.
 */
// Consecutive-return stubbing (thenReturn(a, b)) is generic varargs, which javac cannot prove safe.
@SuppressWarnings("unchecked")
class RetentionPurgeStorageReleaseTest {

    private final MessageRepository messages = mock(MessageRepository.class);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final MessageIndexService index = mock(MessageIndexService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AttachmentService files = mock(AttachmentService.class);
    private final StorageQuotaService quotas = mock(StorageQuotaService.class);

    private final Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

    private final User alice = user(1L, "alice");
    private final User bob = user(2L, "bob");

    @Test
    void hardDeletingAMessageReapsItsFilesAndCreditsItsUploader() {
        oneBatch(List.of(42L), List.of(attachment(message(42L, alice), "key-42", 900L)));

        assertThat(scheduler().purgeDeletedBefore(cutoff)).isEqualTo(1);

        // Rows alone are not enough: nothing in the database can delete a file, so a purge that
        // only cascades leaves the volume exactly as full as it was before retention expired.
        verify(files).deleteFiles(List.of("key-42"));
        verify(quotas).releaseAll(Map.of(1L, 900L));
    }

    @Test
    void theAttachmentRowsAreReadBeforeTheDeleteAndActedOnAfterIt() {
        oneBatch(List.of(42L), List.of(attachment(message(42L, alice), "key-42", 900L)));

        scheduler().purgeDeletedBefore(cutoff);

        // The whole point of the ordering: `attachments.message_id` is `on delete cascade`, so the
        // storage key, the size and the uploader all disappear together with the message. Read them
        // afterwards and there is nothing left to read — and no later pass could work out whose
        // bytes the orphaned file was.
        var ordered = inOrder(attachments, messages, files, quotas);
        ordered.verify(attachments).findByMessageIdsIncludingReplies(List.of(42L));
        ordered.verify(messages).deleteByIdIn(List.of(42L));
        ordered.verify(files).deleteFiles(anyList());
        ordered.verify(quotas).releaseAll(any());
    }

    @Test
    void aCascadedReplysAttachmentIsCreditedToItsOwnAuthor() {
        // The purge only ever sees thread parents; `messages.parent_id` cascades, so a reply's file
        // goes with it without the reply's id ever appearing in a batch. Charging its bytes to the
        // parent's author — or missing them entirely — is the easy bug here.
        oneBatch(List.of(42L), List.of(
                attachment(message(42L, alice), "parent-key", 100L),
                attachment(message(43L, bob), "reply-key", 250L)));

        scheduler().purgeDeletedBefore(cutoff);

        verify(quotas).releaseAll(Map.of(1L, 100L, 2L, 250L));
    }

    @Test
    void aBatchThatFailsToDeleteCreditsNothing() {
        when(messages.findPurgeableIds(eq(cutoff), anyInt())).thenReturn(List.of(42L));
        when(attachments.findByMessageIdsIncludingReplies(List.of(42L)))
                .thenReturn(List.of(attachment(message(42L, alice), "key-42", 900L)));
        when(messages.deleteByIdIn(List.of(42L))).thenThrow(new IllegalStateException("deadlock"));

        assertThat(scheduler().purgeDeletedBefore(cutoff)).isZero();

        // Nothing was freed, so nothing may be handed back — and the file must survive too, or the
        // still-live row would point at a file that is gone.
        verify(quotas, never()).releaseAll(any());
        verify(files, never()).deleteFiles(anyList());
    }

    @Test
    void aFailedFileReapStillCreditsTheBytes() {
        oneBatch(List.of(42L), List.of(attachment(message(42L, alice), "key-42", 900L)));
        doThrow(new RuntimeException("filesystem gone")).when(files).deleteFiles(anyList());

        scheduler().purgeDeletedBefore(cutoff);

        // An unreaped file is an orphan the CLEAN-1 sweep can still collect. An uncredited account
        // is permanent — user_storage takes deltas, never an absolute value — so the credit must
        // not be collateral damage of a disk problem.
        verify(quotas).releaseAll(Map.of(1L, 900L));
    }

    @Test
    void aFailedCreditDoesNotStopThePurge() {
        when(messages.findPurgeableIds(eq(cutoff), anyInt()))
                .thenReturn(List.of(42L), List.of(43L), List.of());
        when(attachments.findByMessageIdsIncludingReplies(anyList()))
                .thenReturn(List.of(attachment(message(42L, alice), "key-42", 900L)));
        when(messages.deleteByIdIn(anyList())).thenReturn(1);
        doThrow(new RuntimeException("connection reset")).when(quotas).releaseAll(any());

        // The rows are already gone; aborting the run would only leave a larger backlog and would
        // not recover the credit either, since a retry finds nothing left to read.
        assertThat(scheduler().purgeDeletedBefore(cutoff)).isEqualTo(2);
    }

    @Test
    void aBatchWithNoAttachmentsTouchesNeitherTheDiskNorTheQuota() {
        oneBatch(List.of(42L), List.of());

        scheduler().purgeDeletedBefore(cutoff);

        verify(files, never()).deleteFiles(anyList());
        verify(quotas, never()).releaseAll(any());
    }

    // ------------------------------------------------------------------ helpers

    /** One purgeable batch, then an empty one so the loop terminates. */
    private void oneBatch(List<Long> ids, List<Attachment> doomed) {
        when(messages.findPurgeableIds(eq(cutoff), anyInt())).thenReturn(ids, List.of());
        when(attachments.findByMessageIdsIncludingReplies(ids)).thenReturn(doomed);
        when(messages.deleteByIdIn(ids)).thenReturn(ids.size());
    }

    /** {@code self} stands in for the Spring transactional proxy; see the sibling purge test. */
    private RetentionPurgeScheduler scheduler() {
        var inner = new RetentionPurgeScheduler(messages, attachments, index, audit, files, quotas,
                null, true, 30, 500, 100);
        return new RetentionPurgeScheduler(messages, attachments, index, audit, files, quotas,
                inner, true, 30, 500, 100);
    }

    private static Attachment attachment(Message message, String storageKey, long sizeBytes) {
        return new Attachment(message, "file.bin", "application/octet-stream", sizeBytes, storageKey);
    }

    private Message message(long id, User author) {
        var channel = new Channel("general", "General", null, ChannelType.PUBLIC, author);
        ReflectionTestUtils.setField(channel, "id", 10L);
        var message = new Message(channel, author, "body");
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private static User user(long id, String username) {
        var user = new User("sub-" + username, username, username + "@example.test", username);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
