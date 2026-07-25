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
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
 * The purge is the one operation in moderation that cannot be undone, so the tests here are
 * mostly about the circumstances under which it must decline to run, and about it staying inside
 * its batch budget instead of trying to swallow a large backlog whole.
 */
// Consecutive-return stubbing (thenReturn(a, b, c)) is generic varargs, which javac cannot prove
// safe. Mockito never writes to the array; the alternative is five identical annotations.
@SuppressWarnings("unchecked")
class MessageModerationRetentionPurgeTest {

    private final MessageRepository messages = mock(MessageRepository.class);
    private final MessageIndexService index = mock(MessageIndexService.class);
    private final AuditService audit = mock(AuditService.class);

    private final Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

    /** {@code self} stands in for the Spring transactional proxy; see the sibling service test. */
    private RetentionPurgeScheduler scheduler(boolean enabled, int retentionDays,
                                              int batchSize, int maxBatches) {
        var inner = new RetentionPurgeScheduler(messages, index, audit, null,
                enabled, retentionDays, batchSize, maxBatches);
        return new RetentionPurgeScheduler(messages, index, audit, inner,
                enabled, retentionDays, batchSize, maxBatches);
    }

    private RetentionPurgeScheduler scheduler() {
        return scheduler(true, 30, 2, 100);
    }

    @Test
    void purgesInBatchesUntilThereIsNothingLeft() {
        when(messages.findPurgeableIds(eq(cutoff), eq(2)))
                .thenReturn(List.of(1L, 2L), List.of(3L), List.of());
        when(messages.deleteByIdIn(List.of(1L, 2L))).thenReturn(2);
        when(messages.deleteByIdIn(List.of(3L))).thenReturn(1);

        assertThat(scheduler().purgeDeletedBefore(cutoff)).isEqualTo(3);

        verify(messages, times(3)).findPurgeableIds(eq(cutoff), eq(2));
        verify(index).deleteAll(List.of(1L, 2L));
        verify(index).deleteAll(List.of(3L));
    }

    @Test
    void dropsEachBatchFromTheIndexOnlyAfterItIsCommitted() {
        when(messages.findPurgeableIds(eq(cutoff), anyInt()))
                .thenReturn(List.of(1L), List.of());
        when(messages.deleteByIdIn(List.of(1L))).thenReturn(1);

        scheduler().purgeDeletedBefore(cutoff);

        // The row is unrecoverable once deleted, so there is no rollback to protect the index
        // from — but doing it in this order keeps the invariant the rest of the system relies
        // on: the index never describes state the database has not committed.
        var inOrder = org.mockito.Mockito.inOrder(messages, index);
        inOrder.verify(messages).deleteByIdIn(List.of(1L));
        inOrder.verify(index).deleteAll(List.of(1L));
    }

    @Test
    void recordsOneSystemAuditEntryWithTheTotal() {
        when(messages.findPurgeableIds(eq(cutoff), anyInt()))
                .thenReturn(List.of(1L, 2L), List.of());
        when(messages.deleteByIdIn(anyList())).thenReturn(2);

        scheduler().purgeDeletedBefore(cutoff);

        // actor null — attributing an automatic policy action to a person would make the trail lie.
        verify(audit).record(isNull(), eq(AdminAudit.RETENTION_PURGE), isNull(), isNull(),
                eq("purged 2 message(s) removed before " + cutoff));
    }

    @Test
    void anIdleSweepWritesNoAuditRow() {
        when(messages.findPurgeableIds(eq(cutoff), anyInt())).thenReturn(List.of());

        assertThat(scheduler().purgeDeletedBefore(cutoff)).isZero();

        // Hourly "purged 0 messages" rows would bury the entries that matter.
        verifyNoInteractions(audit);
        verifyNoInteractions(index);
    }

    @Test
    void stopsAtTheBatchCapAndLeavesTheRestForTheNextRun() {
        when(messages.findPurgeableIds(eq(cutoff), eq(2))).thenReturn(List.of(1L, 2L));
        when(messages.deleteByIdIn(anyList())).thenReturn(2);

        // A backlog that never empties: without a cap this would spin for as long as rows exist.
        assertThat(scheduler(true, 30, 2, 3).purgeDeletedBefore(cutoff)).isEqualTo(6);

        verify(messages, times(3)).findPurgeableIds(eq(cutoff), eq(2));
    }

    @Test
    void aFailedBatchStillRecordsWhatWasPurgedAndDoesNotEscapeTheScheduler() {
        when(messages.findPurgeableIds(eq(cutoff), anyInt()))
                .thenReturn(List.of(1L), List.of(2L));
        when(messages.deleteByIdIn(List.of(1L))).thenReturn(1);
        when(messages.deleteByIdIn(List.of(2L))).thenThrow(new IllegalStateException("deadlock"));

        // An exception escaping into the scheduler reaches only the default error handler, and
        // the operator never learns that a partial purge happened.
        assertThat(scheduler().purgeDeletedBefore(cutoff)).isEqualTo(1);
        verify(audit).record(isNull(), eq(AdminAudit.RETENTION_PURGE), isNull(), isNull(),
                anyString());
    }

    @Test
    void anIndexFailureDoesNotStopThePurge() {
        when(messages.findPurgeableIds(eq(cutoff), anyInt()))
                .thenReturn(List.of(1L), List.of(2L), List.of());
        when(messages.deleteByIdIn(anyList())).thenReturn(1);
        doThrow(new java.io.UncheckedIOException(new java.io.IOException("disk full")))
                .when(index).deleteAll(List.of(1L));

        assertThat(scheduler().purgeDeletedBefore(cutoff)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- disabled ----

    @Test
    void theScheduledSweepDoesNothingWhenDisabled() {
        scheduler(false, 30, 2, 100).purgeExpired();

        verify(messages, never()).findPurgeableIds(any(), anyInt());
        verifyNoInteractions(audit);
    }

    @Test
    void aZeroRetentionWindowMeansKeepRemovedMessagesForever() {
        // Not "purge everything immediately" — the destructive reading of an unset value is the
        // wrong default for an irreversible operation.
        scheduler(true, 0, 2, 100).purgeExpired();

        verify(messages, never()).findPurgeableIds(any(), anyInt());
    }

    @Test
    void theScheduledSweepPurgesUpToTheConfiguredWindow() {
        when(messages.findPurgeableIds(any(), anyInt())).thenReturn(List.of());

        var before = Instant.now().minus(7, ChronoUnit.DAYS);
        scheduler(true, 7, 2, 100).purgeExpired();
        var after = Instant.now().minus(7, ChronoUnit.DAYS);

        var used = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(messages).findPurgeableIds(used.capture(), anyInt());
        assertThat(used.getValue()).isBetween(before, after);
    }
}
