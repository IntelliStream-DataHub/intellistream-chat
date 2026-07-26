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
import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.MessageRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AttachmentService {

    private static final int MAX_FILENAME = 255;

    private final AttachmentRepository attachmentRepository;
    private final MessageRepository messageRepository;
    private final ChannelService channelService;
    private final StorageQuotaService quotas;
    private final Path storageRoot;

    private final MessageService messageService;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             MessageRepository messageRepository,
                             ChannelService channelService,
                             StorageQuotaService quotas,
                             // @Lazy breaks the AttachmentService <-> MessageService construction cycle.
                             @org.springframework.context.annotation.Lazy MessageService messageService,
                             @Value("${ichat.attachments.dir:./data/attachments}") String storageDir) {
        this.attachmentRepository = attachmentRepository;
        this.messageRepository = messageRepository;
        this.channelService = channelService;
        this.quotas = quotas;
        this.messageService = messageService;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureStorage() throws IOException {
        Files.createDirectories(storageRoot);
    }

    public Path storageRoot() {
        return storageRoot;
    }

    /**
     * Stream the upload directly to disk, then create a Message + Attachment row.
     * The {@code in} stream is read incrementally with an 8 KiB buffer and copied
     * straight to the target file — at no point is the full payload buffered in
     * memory. {@code maxBytes} is the per-user ceiling (typically resolved by
     * {@link ai.intellistream.chat.security.CurrentUser#uploadCapBytes} from the
     * {@code chat_max_upload_bytes} Keycloak attribute); pass
     * {@link AttachmentBytes#UNLIMITED} for admins. {@code declaredSize} is the
     * client-volunteered length (informational; pass {@code -1} when unknown) — when
     * it exceeds {@code maxBytes} we reject before touching the disk.
     *
     * <p>Three storage limits apply, cheapest first: free space on the volume
     * ({@code StorageQuotaService.requireHeadroom}), the uploader's total quota
     * ({@code allowanceFor}), and this file's own cap. The first two are checked before the file
     * is opened; all three are re-checked byte by byte while streaming, since the only honest
     * measure of an upload's size is the one taken as it arrives.
     */
    @Transactional
    public Attachment upload(Channel channel, User uploader,
                             String originalFilename, String contentType,
                             long declaredSize, long maxBytes,
                             String caption, InputStream in) throws IOException {
        channelService.requireWriteAccess(channel, uploader);
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Filename required");
        }
        if (maxBytes >= 0 && declaredSize > maxBytes) {
            throw new ai.intellistream.chat.security.UploadTooLargeException(maxBytes);
        }
        quotas.requireHeadroom(storageRoot);
        var allowance = quotas.allowanceFor(uploader, declaredSize, maxBytes);
        var safeName = sanitizeFilename(originalFilename);
        var safeType = (contentType == null || contentType.isBlank())
                ? "application/octet-stream" : contentType;
        var captionText = caption == null ? "" : caption.trim();
        if (captionText.length() > 8000) {
            throw new IllegalArgumentException("Caption too long (max 8000 chars)");
        }

        var storageKey = UUID.randomUUID().toString();
        var target = storageRoot.resolve(storageKey);

        // Sniff the leading bytes to defend against a client that lies about the content type
        // (e.g. uploads HTML claiming to be image/png). We pick the sniffed MIME when it
        // disagrees with the declared one, so downloads are served with the truthful header.
        var buffered = new BufferedInputStream(in);
        // Filename hint helps Tika disambiguate ZIP-based formats (docx vs xlsx vs odt).
        var resolvedType = AttachmentBytes.sniffContentType(buffered, safeType, safeName);

        // No local cleanup on failure: streamToFile removes the partial file on every failure
        // path itself (over-cap, over-quota, full disk, client hang-up), which is also the only
        // place that can delete it after the output stream has closed rather than racing the
        // final flush. Doing it again here risked the delete's own IOException replacing the real
        // reason the upload failed.
        long bytesWritten = AttachmentBytes.streamToFile(buffered, target, maxBytes, allowance);

        // Orphan guard: if the surrounding tx rolls back AFTER the file is on disk
        // (a constraint violation in the message/attachment save, or a controller-level
        // exception that triggers rollback later), the file would otherwise be stranded
        // forever. Wire a rollback-only cleanup hook before any further DB activity.
        deleteOnRollback(target);

        // A non-empty caption goes through MessageService.post so it gets the SAME treatment as a
        // normal message: @mention rows synced (so mentions in a caption actually notify) and the
        // body indexed for search. A blank caption (file only) skips that — nothing to mention or
        // index — and keeps the bare save. (post re-checks write access, harmless; it doesn't
        // broadcast, so no double-send.)
        Message message = (captionText == null || captionText.isBlank())
                ? messageRepository.save(new Message(channel, uploader, captionText))
                : messageService.post(channel, uploader, captionText);
        var saved = attachmentRepository.save(
                new Attachment(message, safeName, resolvedType, bytesWritten, storageKey));
        // Charged only now, and with the bytes actually written rather than the declared length.
        // Inside this transaction on purpose: if the row above had failed, the file would have been
        // removed by the rollback hook and usage must roll back with it.
        quotas.recordUpload(uploader, bytesWritten);
        return saved;
    }

    private static void deleteOnRollback(Path file) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try { Files.deleteIfExists(file); }
                        catch (IOException ignored) { /* orphan; cleanup later */ }
                    }
                }
            });
        }
    }

    /**
     * The download gate: an attachment is fetchable only while the message carrying it is live and
     * the viewer may read its channel.
     *
     * <h2>Why the removal check is here and not only in the feed</h2>
     * Hiding a removed message from the message list does not take its files out of circulation. The
     * download URL is a bare {@code /api/attachments/{id}} — it outlives the message in browser
     * history, in link unfurls, and in whatever the first person who saw it pasted somewhere else.
     * Without this check "clear everything this account wrote" leaves the account's photos and
     * documents served to anyone who kept a link, which is the part of the removal a user actually
     * cares about. Membership was never the whole question.
     *
     * <h2>No admin exemption, deliberately</h2>
     * An admin who genuinely needs a removed attachment has {@code MessageModerationService.restoreOne}:
     * it brings the message back through the ordinary path <em>and</em> writes an audit row naming
     * who did it. An admin-only download would instead make the one access nobody can see afterwards
     * the one aimed at content the workspace decided to remove — the exact inversion of what the
     * audit trail is for. Keeping one rule for everybody also means the leaked link stops working
     * for everybody the moment the message is removed, which is what the person who asked for the
     * removal believes happened.
     *
     * <p>Removal is checked before membership so it holds regardless of channel type: a PUBLIC
     * channel short-circuits {@link ChannelService#requireMember}, and checking in the other order
     * would leave the gate open on exactly the channels with the widest audience.
     */
    @Transactional(readOnly = true)
    public Attachment requireForDownload(Long attachmentId, User viewer) {
        var attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Attachment not found: " + attachmentId));
        var message = attachment.getMessage();
        // 404, not 403: to everyone outside moderation a removed message does not exist, and the
        // soft delete is an implementation detail of making the removal reversible — it should not
        // become a way to tell "was removed" apart from "never existed".
        if (message.isDeleted()) {
            throw new ai.intellistream.chat.security.ResourceNotFoundException("Attachment not found: " + attachmentId);
        }
        channelService.requireMember(message.getChannel(), viewer);
        return attachment;
    }

    public Path resolve(Attachment attachment) {
        var p = storageRoot.resolve(attachment.getStorageKey()).normalize();
        if (!p.startsWith(storageRoot)) {
            throw new IllegalStateException("Attachment path escaped storage root");
        }
        return p;
    }

    @Transactional(readOnly = true)
    public List<Attachment> findForMessage(Message message) {
        return attachmentRepository.findByMessageOrderByCreatedAtAsc(message);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Attachment>> findForMessages(Collection<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        return attachmentRepository.findByMessageInOrderByCreatedAtAsc(messages).stream()
                .collect(Collectors.groupingBy(a -> a.getMessage().getId()));
    }

    /**
     * Bytes each uploader should be credited back when these attachments are removed, keyed by
     * user id and ready for {@code StorageQuotaService.releaseAll}.
     *
     * <p><b>Call this while the rows still exist</b> — inside the transaction that is about to
     * delete them, next to wherever the storage keys are already being snapshotted for
     * {@link #deleteFiles}. By the time the files are reaped (after commit) the rows are gone and
     * neither the size nor the uploader can be recovered: the file on disk carries no owner, and
     * the storage key is only ever recorded in the row that just disappeared. Apply the result
     * after the commit, for the same reason the file cleanup waits — a rolled-back delete must not
     * hand back bytes that are still stored.
     *
     * <p>An attachment's uploader is its message's author; attachments cannot be moved between
     * messages, so this holds for replies and channel-wide deletes alike.
     */
    public static Map<Long, Long> creditsFor(Collection<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return Map.of();
        var credits = new java.util.HashMap<Long, Long>();
        for (var attachment : attachments) {
            var author = attachment.getMessage().getAuthor();
            if (author == null || author.getId() == null) continue;
            credits.merge(author.getId(), attachment.getSizeBytes(), Long::sum);
        }
        return credits;
    }

    /**
     * {@link #creditsFor} for a <b>bulk</b> delete — deleting a message, purging one, destroying a
     * channel — where the set being removed may contain tombstones.
     *
     * <p>A tombstoned attachment was credited the moment it was tombstoned: the file manager
     * marks the row deleted and releases the bytes in the same transaction, and the file is
     * already off disk. Crediting it again when the message it hung on is later deleted hands the
     * account bytes it never had. That is not self-correcting — {@code UserStorage} exposes an
     * atomic delta and no absolute set, so nothing downstream can notice or repair it, and the
     * account quietly reads as having room it does not. Two paths make the double credit reachable
     * in ordinary use: delete your own file, then delete the message; or delete your own file, then
     * have a moderator remove the message and the retention purge sweep it.
     *
     * <p>Which is why this is a separate method rather than a filter inside {@code creditsFor}:
     * the file manager's own delete legitimately credits a row it has just tombstoned, so the
     * filter belongs to the bulk callers, not to everyone.
     */
    public static Map<Long, Long> creditsForLive(Collection<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return Map.of();
        return creditsFor(attachments.stream().filter(a -> !a.isDeleted()).toList());
    }

    /** Best-effort filesystem cleanup for the given storage keys. Errors are swallowed — orphans can be GC'd later. */
    public void deleteFiles(Collection<String> storageKeys) {
        for (var key : storageKeys) {
            try {
                Files.deleteIfExists(storageRoot.resolve(key));
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    public static String sanitizeFilename(String name) {
        if (name == null) return "file";
        // NFC-normalise so visually-identical unicode forms compare equal downstream.
        var normalised = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).trim();
        // Strip any path component a (mis)behaving client may have included (covers "/" and "\").
        var slash = Math.max(normalised.lastIndexOf('/'), normalised.lastIndexOf('\\'));
        var base = slash >= 0 ? normalised.substring(slash + 1) : normalised;
        // Drop control chars and NUL — many filesystems and viewers misbehave on these.
        var sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            sb.append(c);
        }
        var cleaned = sb.toString();
        // After stripping, "." or ".." alone would mean "current/parent dir" — replace.
        if (cleaned.equals(".") || cleaned.equals("..") || cleaned.isEmpty()) {
            cleaned = "file";
        }
        if (cleaned.length() > MAX_FILENAME) {
            cleaned = cleaned.substring(0, MAX_FILENAME);
        }
        return cleaned;
    }
}
