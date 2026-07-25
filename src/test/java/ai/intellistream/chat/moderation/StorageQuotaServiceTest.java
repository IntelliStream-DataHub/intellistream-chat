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

package ai.intellistream.chat.moderation;

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.domain.AdminAudit;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.domain.UserStorage;
import ai.intellistream.chat.repository.UserRepository;
import ai.intellistream.chat.repository.UserStorageRepository;
import ai.intellistream.chat.security.StorageQuotaExceededException;
import ai.intellistream.chat.security.StorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for the quota decisions themselves — no Spring context, no database. */
class StorageQuotaServiceTest {

    private static final long DEFAULT_QUOTA = 1000L;
    private static final long PER_FILE_CAP = 500L;

    private UserStorageRepository storage;
    private UserRepository users;
    private AuditService audit;
    private StorageQuotaService service;

    private final User alice = user(1L, "alice");

    @BeforeEach
    void setUp() {
        storage = mock(UserStorageRepository.class);
        users = mock(UserRepository.class);
        audit = mock(AuditService.class);
        service = new StorageQuotaService(storage, users, audit, DEFAULT_QUOTA, 0L);
    }

    // ------------------------------------------------------------------ allowanceFor

    @Test
    void allowanceIsTheUnusedPartOfTheDefaultQuota() {
        when(storage.findById(1L)).thenReturn(Optional.of(row(1L, 400L, null)));

        var allowance = service.allowanceFor(alice, -1, PER_FILE_CAP);

        assertThat(allowance.quotaBytes()).isEqualTo(DEFAULT_QUOTA);
        assertThat(allowance.usedBytes()).isEqualTo(400L);
        assertThat(allowance.remaining()).isEqualTo(600L);
    }

    @Test
    void accountWithNoRowYetHasTheWholeQuota() {
        when(storage.findById(1L)).thenReturn(Optional.empty());

        assertThat(service.allowanceFor(alice, -1, PER_FILE_CAP).remaining()).isEqualTo(DEFAULT_QUOTA);
    }

    @Test
    void perAccountOverrideBeatsTheConfiguredDefault() {
        when(storage.findById(1L)).thenReturn(Optional.of(row(1L, 100L, 300L)));

        assertThat(service.allowanceFor(alice, -1, PER_FILE_CAP).remaining()).isEqualTo(200L);
    }

    @Test
    void negativeOverrideMeansUnlimitedForThatAccount() {
        when(storage.findById(1L)).thenReturn(Optional.of(row(1L, 999_999L, -1L)));

        assertThat(service.allowanceFor(alice, 10_000, PER_FILE_CAP).unmetered()).isTrue();
    }

    @Test
    void alreadyFullAccountIsRefusedBeforeAnyBytesAreRead() {
        when(storage.findById(1L)).thenReturn(Optional.of(row(1L, DEFAULT_QUOTA, null)));

        assertThatThrownBy(() -> service.allowanceFor(alice, 1, PER_FILE_CAP))
                .isInstanceOf(StorageQuotaExceededException.class)
                .satisfies(e -> {
                    var quota = (StorageQuotaExceededException) e;
                    assertThat(quota.getRemainingBytes()).isZero();
                    assertThat(quota.getUsedBytes()).isEqualTo(DEFAULT_QUOTA);
                });
    }

    @Test
    void declaredLengthThatCannotFitIsRefusedUpFront() {
        when(storage.findById(1L)).thenReturn(Optional.of(row(1L, 900L, null)));

        assertThatThrownBy(() -> service.allowanceFor(alice, 200, PER_FILE_CAP))
                .isInstanceOf(StorageQuotaExceededException.class);
    }

    /** A lying Content-Length must not buy anything: the allowance still reflects the real room left. */
    @Test
    void unknownDeclaredLengthStillYieldsTheRealRemainingAllowance() {
        when(storage.findById(1L)).thenReturn(Optional.of(row(1L, 900L, null)));

        assertThat(service.allowanceFor(alice, -1, PER_FILE_CAP).remaining()).isEqualTo(100L);
    }

    @Test
    void unlimitedPerFileCapMeansExemptFromTheTotalQuotaToo() {
        var allowance = service.allowanceFor(alice, 10_000_000, AttachmentBytes.UNLIMITED);

        assertThat(allowance.unmetered()).isTrue();
        // Exempt accounts shouldn't even cost a lookup on the upload path.
        verifyNoInteractions(storage);
    }

    @Test
    void negativeConfiguredDefaultDisablesQuotasEntirely() {
        var disabled = new StorageQuotaService(storage, users, audit, -1L, 0L);
        when(storage.findById(1L)).thenReturn(Optional.of(row(1L, 5_000L, null)));

        assertThat(disabled.allowanceFor(alice, 1_000, PER_FILE_CAP).unmetered()).isTrue();
    }

    // ------------------------------------------------------------------ accounting

    @Test
    void recordUploadChargesTheBytesActuallyWritten() {
        service.recordUpload(alice, 4096L);

        verify(storage).addBytes(1L, 4096L);
    }

    @Test
    void recordUploadIgnoresEmptyAndNegativeWrites() {
        service.recordUpload(alice, 0L);
        service.recordUpload(alice, -5L);

        verify(storage, never()).addBytes(anyLong(), anyLong());
    }

    @Test
    void releaseCreditsBackAsANegativeDelta() {
        service.release(alice, 700L);

        verify(storage).addBytes(1L, -700L);
    }

    @Test
    void releaseAllCreditsEachOwnerSeparately() {
        service.releaseAll(Map.of(1L, 100L, 2L, 250L));

        verify(storage).addBytes(1L, -100L);
        verify(storage).addBytes(2L, -250L);
    }

    @Test
    void releaseAllSkipsNothingToCredit() {
        service.releaseAll(Map.of(1L, 0L));
        service.releaseAll(Map.of());

        verify(storage, never()).addBytes(anyLong(), anyLong());
    }

    // ------------------------------------------------------------------ free-space floor

    @Test
    void headroomCheckRefusesWhenFreeSpaceIsBelowTheFloor(@TempDir Path dir) {
        // No real filesystem has Long.MAX_VALUE/2 bytes free, so this trips deterministically
        // wherever the suite runs.
        var strict = new StorageQuotaService(storage, users, audit, DEFAULT_QUOTA, Long.MAX_VALUE / 2);

        assertThatThrownBy(() -> strict.requireHeadroom(dir))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void headroomCheckIsSkippedWhenDisabled(@TempDir Path dir) {
        service.requireHeadroom(dir); // minFreeBytes = 0 in setUp
    }

    @Test
    void headroomCheckLetsUploadsThroughWhenTheProbeFails() {
        var strict = new StorageQuotaService(storage, users, audit, DEFAULT_QUOTA, 1024L);

        // Unreadable path → usable space unknown. Failing every upload because statvfs didn't
        // answer would be a self-inflicted outage, so "unknown" must mean "allow".
        strict.requireHeadroom(Path.of("/definitely/not/a/mounted/path/" + System.nanoTime()));
    }

    // ------------------------------------------------------------------ admin API

    @Test
    void setQuotaPersistsTheOverrideAndAuditsTheChange() {
        var bob = user(2L, "bob");
        when(storage.findById(2L)).thenReturn(Optional.of(row(2L, 10L, 100L)));

        service.setQuota(alice, bob, 5_000L);

        var saved = ArgumentCaptor.forClass(UserStorage.class);
        verify(storage).save(saved.capture());
        assertThat(saved.getValue().getQuotaBytes()).isEqualTo(5_000L);

        var detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordOnUser(eq(alice), eq(AdminAudit.QUOTA_SET), eq(bob), detail.capture());
        assertThat(detail.getValue()).contains("100 bytes").contains("5000 bytes");
    }

    @Test
    void setQuotaCreatesTheRowForAnAccountThatHasNeverUploaded() {
        var bob = user(2L, "bob");
        when(storage.findById(2L)).thenReturn(Optional.empty());

        service.setQuota(alice, bob, 42L);

        var saved = ArgumentCaptor.forClass(UserStorage.class);
        verify(storage).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(2L);
        assertThat(saved.getValue().getQuotaBytes()).isEqualTo(42L);
    }

    @Test
    void setQuotaNullRestoresTheConfiguredDefault() {
        var bob = user(2L, "bob");
        when(storage.findById(2L)).thenReturn(Optional.of(row(2L, 0L, 100L)));

        service.setQuota(alice, bob, null);

        var saved = ArgumentCaptor.forClass(UserStorage.class);
        verify(storage).save(saved.capture());
        assertThat(saved.getValue().getQuotaBytes()).isNull();
        verify(audit).recordOnUser(any(), eq(AdminAudit.QUOTA_SET), any(),
                org.mockito.ArgumentMatchers.contains("default(" + DEFAULT_QUOTA + ")"));
    }

    @Test
    void setQuotaNormalisesAnyNegativeValueToTheUnlimitedSentinel() {
        var bob = user(2L, "bob");
        when(storage.findById(2L)).thenReturn(Optional.empty());

        service.setQuota(alice, bob, -9999L);

        var saved = ArgumentCaptor.forClass(UserStorage.class);
        verify(storage).save(saved.capture());
        assertThat(saved.getValue().getQuotaBytes()).isEqualTo(-1L);
    }

    @Test
    void usageForReportsTheDefaultWhenNoOverrideIsSet() {
        when(storage.findById(1L)).thenReturn(Optional.of(row(1L, 250L, null)));

        var usage = service.usageFor(alice);

        assertThat(usage.username()).isEqualTo("alice");
        assertThat(usage.bytesUsed()).isEqualTo(250L);
        assertThat(usage.quotaBytes()).isNull();
        assertThat(usage.effectiveQuotaBytes()).isEqualTo(DEFAULT_QUOTA);
        assertThat(usage.percentUsed()).isEqualTo(25);
    }

    @Test
    void usageForAnAccountWithNoRowReadsZero() {
        when(storage.findById(1L)).thenReturn(Optional.empty());

        assertThat(service.usageFor(alice).bytesUsed()).isZero();
    }

    @Test
    void percentUsedIsCappedAndZeroWhenUnlimited() {
        assertThat(new StorageQuotaService.Usage(1L, "a", "A", 300L, 100L, 100L).percentUsed())
                .isEqualTo(100);
        assertThat(new StorageQuotaService.Usage(1L, "a", "A", 300L, -1L, -1L).percentUsed())
                .isZero();
    }

    @Test
    void topUsersJoinsUsageRowsToTheirAccounts() {
        var bob = user(2L, "bob");
        when(storage.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(2L, 900L, null), row(1L, 100L, 50L))));
        when(users.findAllById(List.of(2L, 1L))).thenReturn(List.of(bob, alice));

        var top = service.topUsers(10);

        assertThat(top).extracting(StorageQuotaService.Usage::username).containsExactly("bob", "alice");
        assertThat(top.getFirst().bytesUsed()).isEqualTo(900L);
        assertThat(top.getLast().effectiveQuotaBytes()).isEqualTo(50L);
    }

    @Test
    void totalBytesUsedIsRelayedFromTheRepository() {
        when(storage.totalBytesUsed()).thenReturn(123_456L);

        assertThat(service.totalBytesUsed()).isEqualTo(123_456L);
    }

    // ------------------------------------------------------------------ helpers

    private static User user(long id, String username) {
        var user = new User("sub-" + username, username, username + "@example.test", username);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static UserStorage row(long userId, long bytesUsed, Long quotaBytes) {
        var row = new UserStorage(userId);
        ReflectionTestUtils.setField(row, "bytesUsed", bytesUsed);
        row.setQuotaBytes(quotaBytes);
        return row;
    }
}
