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

package ai.intellistream.chat.service;

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * Owns user-avatar files on disk. Storage layout mirrors {@link AttachmentService}: a single
 * flat directory keyed by random UUID. The mapping from a user to their file lives on the
 * {@link User} row ({@code avatar_storage_key}); the on-disk file has no metadata of its own.
 */
@Service
public class AvatarService {

    /** 5 MiB is plenty for an avatar — anything bigger is almost certainly a mistake. */
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    /** After resize, fit the longer edge into this many pixels. 256px renders crisply at all UI sizes. */
    private static final int MAX_DIMENSION = 256;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");
    /** Formats Java's stock ImageIO can decode + re-encode losslessly. GIF and WebP pass through. */
    private static final Set<String> RESIZABLE_TYPES = Set.of("image/png", "image/jpeg", "image/jpg");

    private final UserRepository userRepository;
    private final StorageQuotaService quotas;
    private final Path storageRoot;

    public AvatarService(UserRepository userRepository,
                         StorageQuotaService quotas,
                         @Value("${ichat.avatars.dir:./data/avatars}") String storageDir) {
        this.userRepository = userRepository;
        this.quotas = quotas;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureStorage() throws IOException {
        Files.createDirectories(storageRoot);
    }

    /** On-disk root of the avatar store — used by the orphan-avatar sweep (CLEAN-2). */
    public Path storageRoot() {
        return storageRoot;
    }

    /**
     * Replace {@code user}'s avatar with the bytes in {@code in}. The stream is read in 8 KiB
     * chunks into a memory buffer (capped at {@link #MAX_BYTES} — anything bigger throws
     * mid-stream so we never hold the whole oversize payload). For PNG/JPEG we then decode,
     * scale to fit {@link #MAX_DIMENSION}, and re-encode in-place — typical 5 MiB photos
     * compress to under 50 KiB after this. GIF and WebP pass through (animation-preserving
     * resize would need extra deps). Sniffs the leading bytes for a truthful content type.
     *
     * <h2>Storage limits: the volume yes, the per-account quota no</h2>
     * The free-space reserve applies. An avatar is a write to a shared volume like any other, and
     * the reason {@code min-free-bytes} exists — leaving Postgres room for a WAL segment and Lucene
     * room to finish a merge — does not care what the bytes are for. Skipping the check here would
     * have made the profile page the one endpoint that can consume the last megabytes of the disk.
     *
     * <p>The per-account quota deliberately does not. An avatar is <em>one</em> file per account,
     * replaced rather than accumulated, capped at 5 MiB before resize and typically under 50 KiB
     * after: total avatar storage is bounded by the number of accounts, not by anyone's behaviour,
     * so metering it protects nothing a quota is for. Against that, charging it would coupling two
     * unrelated things in the way users notice — an account that filled its quota with attachments
     * could no longer fix a wrong or offensive profile picture, and could not even <em>clear</em> it
     * (replacing a file means writing the new one before deleting the old). It would also make the
     * admin storage screen's per-account number stop meaning "what this person has shared". Charging
     * would additionally need a credit on every replace, off a best-effort post-commit delete of the
     * previous file — a decrement that can silently not happen, which is precisely the drift the
     * accounting is built to avoid.
     */
    @Transactional
    public User upload(User user, String declaredContentType, InputStream in) throws IOException {
        var declared = (declaredContentType == null || declaredContentType.isBlank())
                ? "application/octet-stream" : declaredContentType.toLowerCase();
        var buffered = new BufferedInputStream(in);
        var resolvedType = sniffContentType(buffered, declared);
        if (!ALLOWED_TYPES.contains(resolvedType)) {
            throw new IllegalArgumentException("Avatar must be a PNG, JPEG, WEBP, or GIF image");
        }
        // Probes the avatar directory, not the attachment one — they can be separate volumes, and
        // the only free space that matters is the one about to be written to.
        quotas.requireHeadroom(storageRoot);

        var rawBytes = readAllCapped(buffered, MAX_BYTES);
        var storedBytes = maybeResize(rawBytes, resolvedType);

        var newKey = UUID.randomUUID().toString();
        var target = storageRoot.resolve(newKey);
        // Through AttachmentBytes rather than Files.write so an avatar gets the same disk-failure
        // treatment as an attachment: no partial file survives any failure path, and an ENOSPC /
        // EDQUOT write is re-thrown as StorageUnavailableException, which ApiExceptionHandler
        // answers 507 with an explanation instead of 500 with a stack trace — a full volume is an
        // operator problem and 500 sends whoever is on call looking in the wrong place. The local
        // try/catch this replaces called deleteIfExists and re-threw, so a cleanup that itself
        // failed replaced the real reason for the failure with a misleading one.
        //
        // The unmetered overload is the right one here despite its javadoc's warning, because these
        // bytes really are charged to nobody — see the quota discussion above. MAX_BYTES is passed
        // anyway: readAllCapped has already enforced it, and repeating it costs nothing and keeps
        // the ceiling true at the point the file is actually created.
        AttachmentBytes.streamToFile(new ByteArrayInputStream(storedBytes), target, MAX_BYTES);

        // Pull a managed instance so the change is flushed inside this transaction.
        var managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("User missing: " + user.getId()));

        // Best-effort cleanup of the previous file, deferred to afterCommit. Doing it inside
        // the tx leaves the user pointing at a missing file if the JPA flush rolls back —
        // and a failure to delete the old key never leaves the user with no avatar.
        var previousKey = managed.getAvatarStorageKey();
        managed.setAvatar(newKey, resolvedType);
        if (previousKey != null) {
            afterCommit(() -> {
                try { Files.deleteIfExists(storageRoot.resolve(previousKey)); }
                catch (IOException ignored) { /* orphan; cleanup later */ }
            });
        }
        return managed;
    }

    /** Read the stream into a byte array, aborting if we exceed {@code cap}. */
    private static byte[] readAllCapped(InputStream in, long cap) throws IOException {
        var out = new ByteArrayOutputStream();
        var buf = new byte[8 * 1024];
        long total = 0;
        try (in) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > cap) {
                    throw new ai.intellistream.chat.security.UploadTooLargeException(cap);
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /**
     * Decode → scale → re-encode for PNG/JPEG when either dimension exceeds {@link #MAX_DIMENSION}.
     * Returns the original bytes for any format we can't safely round-trip (GIF preserves animation
     * by passing through; WebP isn't supported by the JDK's ImageIO writers) or when the source is
     * already small enough to skip the work.
     */
    private static byte[] maybeResize(byte[] original, String contentType) {
        if (!RESIZABLE_TYPES.contains(contentType)) return original;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
            if (src == null) return original; // not really an image we can decode — keep the bytes
            int w = src.getWidth(), h = src.getHeight();
            if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) return original;

            double scale = Math.min((double) MAX_DIMENSION / w, (double) MAX_DIMENSION / h);
            int newW = Math.max(1, (int) Math.round(w * scale));
            int newH = Math.max(1, (int) Math.round(h * scale));

            // For JPEG, drop the alpha channel — JPEG can't store it and would otherwise come out grey.
            int outType = "image/png".equals(contentType)
                    ? BufferedImage.TYPE_INT_ARGB
                    : BufferedImage.TYPE_INT_RGB;
            var dst = new BufferedImage(newW, newH, outType);
            Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setComposite(AlphaComposite.Src);
                g.drawImage(src, 0, 0, newW, newH, null);
            } finally {
                g.dispose();
            }

            var out = new ByteArrayOutputStream();
            String formatName = "image/png".equals(contentType) ? "png" : "jpeg";
            if (!ImageIO.write(dst, formatName, out)) {
                return original; // no encoder available — fall back to the original
            }
            return out.toByteArray();
        } catch (Exception e) {
            // Anything goes wrong → store the original, never fail the upload over a resize.
            return original;
        }
    }

    @Transactional
    public User clear(User user) {
        var managed = userRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("User missing: " + user.getId()));
        var previousKey = managed.getAvatarStorageKey();
        managed.clearAvatar();
        if (previousKey != null) {
            afterCommit(() -> {
                try { Files.deleteIfExists(storageRoot.resolve(previousKey)); }
                catch (IOException ignored) { /* orphan; cleanup later */ }
            });
        }
        return managed;
    }

    /** Run an action after the surrounding transaction commits; if no tx is active, run now. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** Return the file path for a user's avatar, or {@code null} if they don't have one. */
    public Path resolve(User user) {
        var key = user.getAvatarStorageKey();
        if (key == null) return null;
        var p = storageRoot.resolve(key).normalize();
        if (!p.startsWith(storageRoot)) {
            throw new IllegalStateException("Avatar path escaped storage root");
        }
        return p;
    }

    static String sniffContentType(BufferedInputStream in, String declared) throws IOException {
        // Reuse the centralised Tika-backed sniffer so avatars and attachments use the
        // same MIME detection path. Avatars are further restricted to ALLOWED_TYPES below.
        return ai.intellistream.chat.attachments.AttachmentBytes.sniffContentType(in, declared);
    }
}
