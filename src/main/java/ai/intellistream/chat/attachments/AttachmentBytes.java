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

package ai.intellistream.chat.attachments;

import ai.intellistream.chat.security.StorageQuotaExceededException;
import ai.intellistream.chat.security.StorageUnavailableException;
import ai.intellistream.chat.security.UploadTooLargeException;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Shared low-level byte handling for attachment uploads (channel + DM). Both
 * {@link ai.intellistream.chat.service.AttachmentService} and
 * {@link ai.intellistream.chat.service.ConversationAttachmentService} stream their
 * bytes through these helpers so there's a single implementation of size-capping,
 * MIME sniffing, and the on-disk write path.
 */
public final class AttachmentBytes {

    private static final Logger log = LoggerFactory.getLogger(AttachmentBytes.class);

    /**
     * Server-side default cap on a single uploaded attachment. Applies when the
     * caller doesn't pass an explicit limit (e.g. when the user has no
     * {@code chat_max_upload_bytes} Keycloak attribute set). Admins and users
     * with an explicit attribute get a different value via
     * {@link ai.intellistream.chat.security.CurrentUser#uploadCapBytes}.
     */
    public static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024; // 50 MiB

    /** Sentinel: pass this as {@code maxBytes} to disable the cap entirely (admins). */
    public static final long UNLIMITED = -1L;

    /**
     * How little free space has to be left before an {@link IOException} is read as "the volume is
     * full" even though its message didn't say so. The message check is the primary signal; this
     * covers the case where it can't be trusted (see {@link #isOutOfSpace}).
     */
    private static final long NEAR_EMPTY_BYTES = 1024 * 1024; // 1 MiB

    /**
     * What is left of one uploader's total storage allowance at the moment a transfer starts.
     *
     * <p>Both numbers are carried, not just the difference, because the error the user sees should
     * say <em>1.9 of 2.0 GiB used</em> — a bare "0 bytes remaining" tells them nothing about what
     * to delete or how much. They are a snapshot: a second upload from the same account running
     * concurrently is checked against the same {@code usedBytes} and the pair can overshoot the
     * quota by up to one upload each. That is deliberate — the alternative is holding a row lock
     * for the duration of a file transfer — and it is bounded by the per-upload cap and the
     * 10-uploads-per-minute rate limit. The filesystem quota is the backstop that actually matters.
     *
     * @param quotaBytes the effective total allowance, or {@link AttachmentBytes#UNLIMITED}
     * @param usedBytes  bytes already stored for this account
     */
    public record Allowance(long quotaBytes, long usedBytes) {

        /** No total quota applies — admins, and deployments that set the quota to a negative value. */
        public static final Allowance UNMETERED = new Allowance(AttachmentBytes.UNLIMITED, 0L);

        public boolean unmetered() {
            return quotaBytes < 0;
        }

        /** Bytes this transfer may still write. {@link Long#MAX_VALUE} when unmetered, never negative. */
        public long remaining() {
            return unmetered() ? Long.MAX_VALUE : Math.max(0L, quotaBytes - usedBytes);
        }
    }

    /**
     * Tika's detector reads the first ~few KiB and uses MIME magic + container probing
     * (ZIP signatures for OOXML, ID3 tags for MP3, HEIC's ftyp box, etc.). One instance
     * is thread-safe and cheap to share — Tika's MimeTypes registry is loaded once.
     */
    private static final Tika TIKA = new Tika();

    private AttachmentBytes() {}

    /**
     * Peek at the leading bytes and pick the more trustworthy MIME. If the sniffed type
     * disagrees with what the client declared (especially when the client claimed an image
     * type), we trust the sniff so we can't be tricked into rendering attacker-controlled
     * HTML inline by a future thumbnail endpoint.
     *
     * <p>Backed by Apache Tika — recognises ~1500 MIME types via magic bytes + container
     * probing, including HEIC, AVIF, modern Office formats, and polyglot files where the
     * declared type is a lie. Filename hint is supplied so Tika can break ties for formats
     * that share magic bytes (e.g. ZIP-based: docx vs xlsx vs odp).
     */
    public static String sniffContentType(BufferedInputStream in, String declared) throws IOException {
        return sniffContentType(in, declared, null);
    }

    public static String sniffContentType(BufferedInputStream in, String declared, String filenameHint) throws IOException {
        in.mark(64 * 1024);
        try {
            var metadata = new Metadata();
            if (filenameHint != null && !filenameHint.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filenameHint);
            }
            // Tika's detect() reads from the stream but only the prefix bytes covered by
            // the mark; we restore the position so the subsequent streamToFile() writes
            // the full payload — including the prefix Tika just consumed.
            var sniffed = TIKA.detect(in, metadata);
            if (sniffed == null || sniffed.isBlank()) return declared;
            return sniffed.equalsIgnoreCase(declared) ? declared : sniffed;
        } finally {
            in.reset();
        }
    }

    /**
     * Copy bytes from {@code in} into {@code target} chunk by chunk, aborting if the
     * total exceeds {@code maxBytes}. Pass {@link #UNLIMITED} (or any value &lt; 0)
     * to skip the cap entirely. The stream is closed when this returns.
     *
     * <p><b>Charges nothing to any account.</b> No per-user storage quota is applied, so this is
     * only correct for bytes that belong to nobody. Every attachment belongs to someone — use
     * {@link #streamToFile(InputStream, Path, long, Allowance)} with the allowance from
     * {@code StorageQuotaService.allowanceFor}, or the quota is silently not enforced.
     */
    public static long streamToFile(InputStream in, Path target, long maxBytes) throws IOException {
        return streamToFile(in, target, maxBytes, Allowance.UNMETERED);
    }

    /**
     * Copy bytes from {@code in} into {@code target}, enforcing both limits <b>as the bytes
     * arrive</b>: {@code maxBytes} (this one file) and {@code allowance} (everything this account
     * has stored). The stream is closed when this returns.
     *
     * <h2>Why the checks are in the copy loop</h2>
     * Nothing about an upload's size is known before it is read. {@code Content-Length} is a claim
     * by the client and {@code chunked} requests don't carry one at all, so a caller that only
     * checks the declared length is trusting an attacker to declare their own limit. The counter
     * here is the only number that is a fact. The declared length is still worth checking first —
     * it refuses the obvious cases without opening a file — but it is an optimisation, not the
     * enforcement.
     *
     * <h2>What is guaranteed on failure</h2>
     * <b>No partial file survives any failure path</b> — over-cap, over-quota, a full disk, or the
     * client hanging up mid-transfer. That matters more than it sounds: the file is written under a
     * random storage key that is only ever recorded in the row this upload is about to create, so
     * an abandoned partial is unreachable by every code path in the application, invisible in the
     * UI, and counted against the volume forever. A "your upload was rejected" that silently keeps
     * the bytes is how a disk fills up with files nobody can name.
     *
     * <p>An {@link IOException} that turns out to be the filesystem refusing the write is
     * re-thrown as {@link StorageUnavailableException} so the request answers 507 with an
     * explanation instead of 500 with a stack trace, and is logged at ERROR — a full volume is an
     * operator problem, and it stays broken until someone is told.
     *
     * @throws UploadTooLargeException        the single file exceeded {@code maxBytes}
     * @throws StorageQuotaExceededException  the account's total allowance ran out mid-transfer
     * @throws StorageUnavailableException    the volume is full or otherwise refusing writes
     */
    public static long streamToFile(InputStream in, Path target, long maxBytes, Allowance allowance)
            throws IOException {
        var buffer = new byte[8 * 1024];
        var remaining = allowance.remaining();
        long total = 0;
        try (in;
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (maxBytes >= 0 && total > maxBytes) {
                    throw new UploadTooLargeException(maxBytes);
                }
                if (total > remaining) {
                    throw new StorageQuotaExceededException(allowance.quotaBytes(), allowance.usedBytes());
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            // Cleanup happens after the try-with-resources has closed the file, so the delete
            // can't race the last flush.
            deleteQuietly(target);
            if (isOutOfSpace(e) || nearlyEmpty(target)) {
                var usable = usableSpaceBytes(target.getParent());
                log.error("Attachment write failed: no space left on the attachments volume at {} "
                                + "({} bytes usable). Every upload will fail until space is freed — check the "
                                + "dataset/volume quota. The partial file was removed.",
                        target.getParent(), usable, e);
                throw new StorageUnavailableException(
                        "The server is out of storage space. Please try again later.", e);
            }
            throw e;
        } catch (RuntimeException e) {
            // The cap/quota trips land here, as does anything the input stream throws unchecked.
            deleteQuietly(target);
            throw e;
        }
        return total;
    }

    /**
     * True when this failure is the filesystem refusing a write for want of space.
     *
     * <p>Matched on the message because that is all the JDK gives us: a write that fails with
     * ENOSPC or EDQUOT surfaces as a plain {@link IOException} carrying the C library's
     * {@code strerror} text, with no errno, no dedicated subclass, and nothing else to switch on.
     * ("No space left on device" is ENOSPC — a full pool or a full ZFS <em>dataset</em> quota;
     * "Disk quota exceeded" is EDQUOT — a ZFS <em>user</em> quota. Both are reachable here and
     * both mean the same thing to us.)
     *
     * <p>The text is in principle locale-dependent, which is why the caller also probes free space
     * before concluding this was something else; between the two, a full disk on a server running
     * under a non-English locale is still diagnosed correctly.
     */
    public static boolean isOutOfSpace(Throwable error) {
        for (var t = error; t != null && t != t.getCause(); t = t.getCause()) {
            var message = t.getMessage();
            if (message == null) continue;
            var lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("no space left on device")   // ENOSPC — Linux, macOS
                    || lower.contains("disk quota exceeded")   // EDQUOT — per-user fs quota
                    || lower.contains("not enough space")      // Windows
                    || lower.contains("insufficient disk space")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Free space on the filesystem holding {@code path}, or {@code -1} when it can't be determined
     * (unmounted, permission denied, exotic filesystem). Callers must treat {@code -1} as "unknown"
     * and not as "empty" — refusing uploads because a probe failed would be its own outage.
     *
     * <p>On ZFS this already accounts for a dataset quota: a 10 GiB dataset with 10 GiB written
     * reports zero usable, not the pool's remaining space. That is what makes the pre-flight check
     * in {@code StorageQuotaService.requireHeadroom} meaningful on the deployment this is aimed at.
     */
    public static long usableSpaceBytes(Path path) {
        if (path == null) return -1L;
        try {
            return Files.getFileStore(path).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    private static boolean nearlyEmpty(Path target) {
        var usable = usableSpaceBytes(target == null ? null : target.getParent());
        return usable >= 0 && usable < NEAR_EMPTY_BYTES;
    }

    /**
     * Remove a file we no longer want, swallowing failure. Called only on paths that are already
     * failing; letting the cleanup's own {@link IOException} propagate would replace the real
     * reason for the failure with a misleading one.
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not remove partial upload {} — it is now an orphan on disk", file, e);
        }
    }
}
