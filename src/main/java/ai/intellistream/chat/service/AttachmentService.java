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

package ai.intellistream.chat.service;

import ai.intellistream.chat.attachments.AttachmentBytes;
import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
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
    private final Path storageRoot;

    private final MessageService messageService;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             MessageRepository messageRepository,
                             ChannelService channelService,
                             // @Lazy breaks the AttachmentService <-> MessageService construction cycle.
                             @org.springframework.context.annotation.Lazy MessageService messageService,
                             @Value("${ichat.attachments.dir:./data/attachments}") String storageDir) {
        this.attachmentRepository = attachmentRepository;
        this.messageRepository = messageRepository;
        this.channelService = channelService;
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

        long bytesWritten;
        try {
            bytesWritten = AttachmentBytes.streamToFile(buffered, target, maxBytes);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }

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
        return attachmentRepository.save(
                new Attachment(message, safeName, resolvedType, bytesWritten, storageKey));
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

    @Transactional(readOnly = true)
    public Attachment requireForDownload(Long attachmentId, User viewer) {
        var attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Attachment not found: " + attachmentId));
        var channel = attachment.getMessage().getChannel();
        channelService.requireMember(channel, viewer);
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
