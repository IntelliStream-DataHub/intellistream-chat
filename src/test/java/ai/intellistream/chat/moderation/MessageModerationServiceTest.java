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

import ai.intellistream.chat.domain.AdminAudit;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.security.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What matters about bulk moderation is not that it removes messages — it is that it removes
 * exactly the ones it says it did, that the search index follows, and that the audit trail is
 * written even when the operation goes wrong halfway. These tests are about those three things.
 */
class MessageModerationServiceTest {

    private final MessageRepository messages = mock(MessageRepository.class);
    private final MessageIndexService index = mock(MessageIndexService.class);
    private final AuditService audit = mock(AuditService.class);

    private final User admin = new User("sub-admin", "root", "root@example.com", "Root");
    private final User target = new User("sub-mallory", "mallory", "m@example.com", "Mallory");

    /**
     * Spring injects the bean's own transactional proxy as {@code self}. Two instances over the
     * same mocks behave identically for these tests and keep the page methods reachable, without
     * pretending a proxy exists.
     */
    private final MessageModerationService service = new MessageModerationService(
            messages, index, audit,
            new MessageModerationService(messages, index, audit, null));

    // ------------------------------------------------------------ bulk removal ----

    @Test
    void deleteAllByAuthorPagesUntilExhaustedAndReturnsTheCommittedCount() {
        var first = List.of(1L, 2L, 3L);
        var second = List.of(7L);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(first);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(3L), any())).thenReturn(second);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(7L), any())).thenReturn(List.of());
        when(messages.softDeleteByIds(eq(first), any(), eq(admin))).thenReturn(3);
        when(messages.softDeleteByIds(eq(second), any(), eq(admin))).thenReturn(1);

        assertThat(service.deleteAllByAuthor(admin, target)).isEqualTo(4);

        // The cursor is what stops the loop re-reading rows it has already flagged.
        verify(messages).findLiveIdsByAuthorAfter(eq(target), eq(0L), any());
        verify(messages).findLiveIdsByAuthorAfter(eq(target), eq(3L), any());
        verify(messages).findLiveIdsByAuthorAfter(eq(target), eq(7L), any());
    }

    @Test
    void deleteAllByAuthorDropsEachPageFromTheIndexAfterItCommits() {
        var page = List.of(4L, 5L);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(page);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(5L), any())).thenReturn(List.of());
        when(messages.softDeleteByIds(eq(page), any(), eq(admin))).thenReturn(2);

        service.deleteAllByAuthor(admin, target);

        var inOrder = org.mockito.Mockito.inOrder(messages, index);
        inOrder.verify(messages).softDeleteByIds(eq(page), any(), eq(admin));
        inOrder.verify(index).deleteAll(page);
    }

    @Test
    void deleteAllByAuthorStampsEveryPageWithOneRemovalInstant() {
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(List.of(1L));
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(1L), any())).thenReturn(List.of(2L));
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(2L), any())).thenReturn(List.of());
        when(messages.softDeleteByIds(anyList(), any(), any())).thenReturn(1);

        service.deleteAllByAuthor(admin, target);

        // One instant for the whole action: the retention window then expires for all of it at
        // once, instead of dribbling out over however long the purge took.
        var at = ArgumentCaptor.forClass(Instant.class);
        verify(messages, times(2)).softDeleteByIds(anyList(), at.capture(), eq(admin));
        var stamps = at.getAllValues();
        assertThat(stamps).hasSize(2);
        assertThat(stamps.get(0)).isEqualTo(stamps.get(1));
    }

    @Test
    void deleteAllByAuthorWithNothingToRemoveTouchesNeitherIndexNorRows() {
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(List.of());

        assertThat(service.deleteAllByAuthor(admin, target)).isZero();

        verifyNoInteractions(index);
        verify(messages, never()).softDeleteByIds(anyList(), any(), any());
        verify(audit).recordOnUser(admin, AdminAudit.PURGE_MESSAGES, target,
                "soft-deleted 0 message(s)");
    }

    @Test
    void deleteAllByAuthorAuditsTheCountThatActuallyCommitted() {
        var page = List.of(1L, 2L, 3L);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(page);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(3L), any())).thenReturn(List.of());
        // A concurrent removal already claimed one of the three.
        when(messages.softDeleteByIds(eq(page), any(), eq(admin))).thenReturn(2);

        service.deleteAllByAuthor(admin, target);

        verify(audit).recordOnUser(admin, AdminAudit.PURGE_MESSAGES, target,
                "soft-deleted 2 message(s)");
    }

    @Test
    void aFailedPageStillLeavesAnAuditRecordOfWhatWasRemoved() {
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(List.of(1L));
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(1L), any())).thenReturn(List.of(2L));
        when(messages.softDeleteByIds(eq(List.of(1L)), any(), any())).thenReturn(1);
        when(messages.softDeleteByIds(eq(List.of(2L)), any(), any()))
                .thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> service.deleteAllByAuthor(admin, target))
                .isInstanceOf(IllegalStateException.class);

        // The admin needs to know a partial removal happened; silence would be worse than
        // an incomplete number.
        verify(audit).recordOnUser(admin, AdminAudit.PURGE_MESSAGES, target,
                "soft-deleted 1 message(s)");
    }

    @Test
    void anIndexFailureDoesNotUndoACommittedRemoval() {
        var page = List.of(9L);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(page);
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(9L), any())).thenReturn(List.of());
        when(messages.softDeleteByIds(eq(page), any(), eq(admin))).thenReturn(1);
        doThrow(new java.io.UncheckedIOException(new java.io.IOException("disk full")))
                .when(index).deleteAll(page);

        // The rows are committed; the reconcile sweep repairs the index. Throwing here would
        // report a failure for an action that succeeded.
        assertThat(service.deleteAllByAuthor(admin, target)).isEqualTo(1);
        verify(audit).recordOnUser(admin, AdminAudit.PURGE_MESSAGES, target,
                "soft-deleted 1 message(s)");
    }

    @Test
    void deleteAllByAuthorRejectsAMissingTarget() {
        assertThatThrownBy(() -> service.deleteAllByAuthor(admin, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ----------------------------------------------------------------- restore ----

    @Test
    void restoreAllByAuthorPutsTheMessagesBackIntoTheIndex() {
        var page = List.of(11L, 12L);
        when(messages.findDeletedIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(page);
        when(messages.findDeletedIdsByAuthorAfter(eq(target), eq(12L), any())).thenReturn(List.of());
        when(messages.restoreByIds(page)).thenReturn(2);
        when(messages.findIndexRowsByIds(page)).thenReturn(List.of(
                new Object[]{11L, 3L, "mallory", "first"},
                new Object[]{12L, 3L, "mallory", "second"}));

        assertThat(service.restoreAllByAuthor(admin, target)).isEqualTo(2);

        verify(index).reindex(List.of(
                new MessageIndexService.IndexedMessage(11L, 3L, "mallory", "first"),
                new MessageIndexService.IndexedMessage(12L, 3L, "mallory", "second")));
        verify(audit).recordOnUser(admin, AdminAudit.RESTORE_MESSAGES, target,
                "restored 2 message(s)");
    }

    @Test
    void restoreAllByAuthorReindexesOnlyWhatCameBack() {
        var page = List.of(11L, 12L);
        when(messages.findDeletedIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(page);
        when(messages.findDeletedIdsByAuthorAfter(eq(target), eq(12L), any())).thenReturn(List.of());
        when(messages.restoreByIds(page)).thenReturn(2);
        // 12 was hard-deleted by the retention purge between the read and the restore, so the
        // live-rows-only projection no longer returns it — and it must not be re-indexed.
        when(messages.findIndexRowsByIds(page)).thenReturn(List.<Object[]>of(
                new Object[]{11L, 3L, "mallory", "first"}));

        service.restoreAllByAuthor(admin, target);

        verify(index).reindex(List.of(
                new MessageIndexService.IndexedMessage(11L, 3L, "mallory", "first")));
    }

    @Test
    void aReindexFailureDoesNotUndoACommittedRestore() {
        var page = List.of(11L);
        when(messages.findDeletedIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(page);
        when(messages.findDeletedIdsByAuthorAfter(eq(target), eq(11L), any())).thenReturn(List.of());
        when(messages.restoreByIds(page)).thenReturn(1);
        when(messages.findIndexRowsByIds(page)).thenThrow(new IllegalStateException("boom"));

        assertThat(service.restoreAllByAuthor(admin, target)).isEqualTo(1);
    }

    // -------------------------------------------------------------- single row ----

    @Test
    void deleteOneFlagsTheRowAuditsItAndDropsItFromTheIndex() {
        var message = message(42L, 7L);
        when(messages.findByIdIncludingDeleted(42L)).thenReturn(Optional.of(message));

        var ref = service.deleteOne(admin, 42L);

        assertThat(message.isDeleted()).isTrue();
        assertThat(message.getDeletedBy()).isSameAs(admin);
        assertThat(ref).isEqualTo(new MessageModerationService.MessageRef(42L, 7L));
        verify(index).deleteAll(List.of(42L));
        verify(audit).record(admin, AdminAudit.DELETE_MESSAGE, target, "message:42",
                "soft-deleted 1 message");
    }

    @Test
    void deletingAnAlreadyRemovedMessageDoesNotResetItsRetentionClock() {
        var message = message(42L, 7L);
        message.softDelete(admin);
        var originalDeletedAt = message.getDeletedAt();
        when(messages.findByIdIncludingDeleted(42L)).thenReturn(Optional.of(message));

        service.deleteOne(admin, 42L);

        // Re-stamping deleted_at would push the purge out by another full window every time
        // someone clicked the button.
        assertThat(message.getDeletedAt()).isEqualTo(originalDeletedAt);
        verify(audit).record(admin, AdminAudit.DELETE_MESSAGE, target, "message:42",
                "already removed; no change");
    }

    @Test
    void deleteOneOnAnUnknownIdIsNotFound() {
        when(messages.findByIdIncludingDeleted(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOne(admin, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(index);
    }

    @Test
    void restoreOneClearsTheFlagAndReindexesTheBody() {
        var message = message(42L, 7L);
        message.softDelete(admin);
        when(messages.findByIdIncludingDeleted(42L)).thenReturn(Optional.of(message));
        when(messages.findIndexRowsByIds(List.of(42L)))
                .thenReturn(List.<Object[]>of(new Object[]{42L, 7L, "mallory", "hello"}));

        var ref = service.restoreOne(admin, 42L);

        assertThat(message.isDeleted()).isFalse();
        assertThat(message.getDeletedBy()).isNull();
        assertThat(ref.channelId()).isEqualTo(7L);
        verify(audit).record(admin, AdminAudit.RESTORE_MESSAGES, target, "message:42",
                "restored 1 message");
    }

    @Test
    void restoringALiveMessageChangesNothingButStillLeavesATrail() {
        var message = message(42L, 7L);
        when(messages.findByIdIncludingDeleted(42L)).thenReturn(Optional.of(message));
        when(messages.findIndexRowsByIds(List.of(42L))).thenReturn(List.of());

        service.restoreOne(admin, 42L);

        assertThat(message.isDeleted()).isFalse();
        verify(audit).record(admin, AdminAudit.RESTORE_MESSAGES, target, "message:42",
                "was not removed; no change");
    }

    @Test
    void systemInitiatedRemovalIsRecordedWithNoActor() {
        when(messages.findLiveIdsByAuthorAfter(eq(target), eq(0L), any())).thenReturn(List.of());

        service.deleteAllByAuthor(null, target);

        verify(audit).recordOnUser(isNull(), eq(AdminAudit.PURGE_MESSAGES), eq(target),
                eq("soft-deleted 0 message(s)"));
    }

    // ------------------------------------------------------------------ helpers ----

    /** A message whose author is {@link #target}, in a channel with the given id. */
    private Message message(long id, long channelId) {
        var channel = new Channel("c", "c", "", ChannelType.PUBLIC, admin);
        setId(channel, channelId);
        var message = new Message(channel, target, "hello");
        setId(message, id);
        return message;
    }

    /** Ids are normally assigned by the database; set them directly for a unit test. */
    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
