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

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.attachments.AttachmentBytes.Allowance;
import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.StorageQuotaExceededException;
import ai.intellistream.chat.security.StorageUnavailableException;
import ai.intellistream.chat.security.UploadTooLargeException;
import ai.intellistream.chat.service.AttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The streaming half of the quota: what happens while the bytes are arriving, when they run out
 * of allowance, and when the disk runs out from under them.
 */
class StorageQuotaStreamTest {

    @Test
    void writesEverythingWhenUnmetered(@TempDir Path dir) throws IOException {
        var target = dir.resolve("upload");

        var written = AttachmentBytes.streamToFile(bytes(3000), target,
                AttachmentBytes.UNLIMITED, Allowance.UNMETERED);

        assertThat(written).isEqualTo(3000);
        assertThat(Files.size(target)).isEqualTo(3000);
    }

    @Test
    void writesAnUploadThatExactlyFillsTheRemainingAllowance(@TempDir Path dir) throws IOException {
        var target = dir.resolve("upload");

        var written = AttachmentBytes.streamToFile(bytes(100), target,
                AttachmentBytes.UNLIMITED, new Allowance(1000, 900));

        assertThat(written).isEqualTo(100);
        assertThat(Files.exists(target)).isTrue();
    }

    /** The point of the whole exercise: a client that under-declares its size is still stopped. */
    @Test
    void stopsMidStreamWhenTheAllowanceRunsOutAndLeavesNoPartialFile(@TempDir Path dir) {
        var target = dir.resolve("upload");

        assertThatThrownBy(() -> AttachmentBytes.streamToFile(bytes(64 * 1024), target,
                AttachmentBytes.UNLIMITED, new Allowance(1000, 900)))
                .isInstanceOf(StorageQuotaExceededException.class);

        assertThat(Files.exists(target)).as("partial file must not survive").isFalse();
    }

    @Test
    void stopsMidStreamOnThePerFileCapAndLeavesNoPartialFile(@TempDir Path dir) {
        var target = dir.resolve("upload");

        assertThatThrownBy(() -> AttachmentBytes.streamToFile(bytes(64 * 1024), target,
                1024, Allowance.UNMETERED))
                .isInstanceOf(UploadTooLargeException.class);

        assertThat(Files.exists(target)).isFalse();
    }

    /** An account already over its quota (an override lowered under existing usage) gets nothing. */
    @Test
    void allowanceIsNeverNegative(@TempDir Path dir) {
        var target = dir.resolve("upload");
        var overdrawn = new Allowance(1000, 5000);

        assertThat(overdrawn.remaining()).isZero();
        assertThatThrownBy(() -> AttachmentBytes.streamToFile(bytes(1), target,
                AttachmentBytes.UNLIMITED, overdrawn))
                .isInstanceOf(StorageQuotaExceededException.class);
        assertThat(Files.exists(target)).isFalse();
    }

    // ------------------------------------------------------------------ full disk

    @Test
    void aFullDiskBecomesStorageUnavailableAndTakesThePartialFileWithIt(@TempDir Path dir) {
        var target = dir.resolve("upload");

        assertThatThrownBy(() -> AttachmentBytes.streamToFile(
                failingAfter(16 * 1024, new IOException("No space left on device")),
                target, AttachmentBytes.UNLIMITED, Allowance.UNMETERED))
                .isInstanceOf(StorageUnavailableException.class)
                .hasRootCauseMessage("No space left on device");

        assertThat(Files.exists(target)).as("partial file must not survive").isFalse();
    }

    @Test
    void anUnrelatedIoFailureStaysAnIoExceptionButStillCleansUp(@TempDir Path dir) {
        var target = dir.resolve("upload");

        assertThatThrownBy(() -> AttachmentBytes.streamToFile(
                failingAfter(16 * 1024, new IOException("Connection reset by peer")),
                target, AttachmentBytes.UNLIMITED, Allowance.UNMETERED))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(StorageUnavailableException.class);

        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void recognisesTheWaysAFilesystemSaysItIsFull() {
        assertThat(AttachmentBytes.isOutOfSpace(new IOException("No space left on device"))).isTrue();
        // EDQUOT — what a per-user ZFS quota reports, as opposed to a dataset quota's ENOSPC.
        assertThat(AttachmentBytes.isOutOfSpace(new IOException("Disk quota exceeded"))).isTrue();
        assertThat(AttachmentBytes.isOutOfSpace(
                new IOException("write failed", new IOException("No space left on device")))).isTrue();
        assertThat(AttachmentBytes.isOutOfSpace(new IOException("Broken pipe"))).isFalse();
        assertThat(AttachmentBytes.isOutOfSpace(new IOException())).isFalse();
    }

    @Test
    void usableSpaceIsUnknownRatherThanZeroForAPathThatIsNotThere() {
        assertThat(AttachmentBytes.usableSpaceBytes(null)).isEqualTo(-1L);
        assertThat(AttachmentBytes.usableSpaceBytes(Path.of("/no/such/mount/" + System.nanoTime())))
                .isEqualTo(-1L);
    }

    @Test
    void usableSpaceIsReadableForARealDirectory(@TempDir Path dir) {
        assertThat(AttachmentBytes.usableSpaceBytes(dir)).isNotNegative();
    }

    // ------------------------------------------------------------------ credit-back tally

    @Test
    void creditsForTalliesEachUploadersBytes() {
        var alice = user(1L, "alice");
        var bob = user(2L, "bob");
        var channel = new Channel("general", "General", null, ChannelType.PUBLIC, alice);

        var credits = AttachmentService.creditsFor(List.of(
                attachment(alice, channel, 100),
                attachment(alice, channel, 250),
                attachment(bob, channel, 40)));

        assertThat(credits).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(1L, 350L, 2L, 40L));
    }

    @Test
    void creditsForIsEmptyWhenThereIsNothingToDelete() {
        assertThat(AttachmentService.creditsFor(List.of())).isEmpty();
        assertThat(AttachmentService.creditsFor(null)).isEmpty();
    }

    /**
     * The bulk-delete rule. A tombstoned attachment was credited when the file manager tombstoned
     * it, so a later delete of the message it hung on must not credit it again — that hands the
     * account bytes it never had, and {@code UserStorage} offers only an atomic delta, so nothing
     * downstream can spot it or put it right.
     */
    @Test
    void creditsForLiveSkipsAttachmentsAlreadyCreditedWhenTheyWereTombstoned() {
        var alice = user(1L, "alice");
        var channel = new Channel("general", "General", null, ChannelType.PUBLIC, alice);
        var live = attachment(alice, channel, 100);
        var tombstoned = attachment(alice, channel, 250);
        tombstoned.softDelete(alice);

        assertThat(AttachmentService.creditsForLive(List.of(live, tombstoned)))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(1L, 100L));
    }

    /**
     * The counterpart, pinned deliberately: the file manager credits a row it has just tombstoned,
     * in the same transaction. That is why the filter lives in {@code creditsForLive} and not
     * inside {@code creditsFor} — moving it would silently stop the file manager crediting at all.
     */
    @Test
    void creditsForStillCountsATombstoneSoTheFileManagerCanCreditItsOwnDelete() {
        var alice = user(1L, "alice");
        var channel = new Channel("general", "General", null, ChannelType.PUBLIC, alice);
        var justTombstoned = attachment(alice, channel, 250);
        justTombstoned.softDelete(alice);

        assertThat(AttachmentService.creditsFor(List.of(justTombstoned)))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(1L, 250L));
    }

    @Test
    void creditsForLiveIsEmptyWhenThereIsNothingToDelete() {
        assertThat(AttachmentService.creditsForLive(List.of())).isEmpty();
        assertThat(AttachmentService.creditsForLive(null)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static InputStream bytes(int count) {
        return new ByteArrayInputStream(new byte[count]);
    }

    /** A stream that delivers {@code good} bytes and then fails — a socket dying mid-upload, or a
     *  write that hits a full volume once the page cache stops absorbing it. */
    private static InputStream failingAfter(int good, IOException failure) {
        return new InputStream() {
            private int delivered;

            @Override
            public int read() {
                throw new UnsupportedOperationException("bulk reads only");
            }

            @Override
            public int read(byte[] buffer, int off, int len) throws IOException {
                if (delivered >= good) throw failure;
                var n = Math.min(len, good - delivered);
                delivered += n;
                return n;
            }
        };
    }

    private static User user(long id, String username) {
        var user = new User("sub-" + username, username, username + "@example.test", username);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Attachment attachment(User author, Channel channel, long size) {
        return new Attachment(new Message(channel, author, ""), "f.bin", "application/octet-stream",
                size, "key-" + size + "-" + author.getId());
    }
}
