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
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.ConversationAttachmentRepository;
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
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Mirrors {@link AttachmentService} for direct/group conversations. Files share the same
 * on-disk storage root; access control is the conversation's membership set, looked up
 * via the parent {@link ConversationMessage}.
 *
 * <p>{@link #requireForDownload(Long, Long, User)} intentionally throws
 * {@link NoSuchElementException} for both "doesn't exist" and "viewer isn't a member" so
 * callers can render a 404 in either case — preventing existence leaks across DMs.
 */
@Service
public class ConversationAttachmentService {

    private final ConversationAttachmentRepository repo;
    private final ai.intellistream.chat.repository.ConversationMessageRepository messages;
    private final ConversationService conversations;
    private final StorageQuotaService quotas;
    private final Path storageRoot;

    public ConversationAttachmentService(ConversationAttachmentRepository repo,
                                         ai.intellistream.chat.repository.ConversationMessageRepository messages,
                                         ConversationService conversations,
                                         StorageQuotaService quotas,
                                         @Value("${ichat.attachments.dir:./data/attachments}") String storageDir) {
        this.repo = repo;
        this.messages = messages;
        this.conversations = conversations;
        this.quotas = quotas;
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
     * A message's attachment rows — call BEFORE deleting the message (the FK cascade removes them).
     *
     * <p>Returns the rows rather than just their storage keys because a delete owes two things to
     * two different places: the files have to be reaped from disk ({@link #deleteFiles}) and the
     * bytes have to be credited back to whoever uploaded them ({@link #creditsFor} →
     * {@code StorageQuotaService.releaseAll}). Both are recorded only on these rows, so both have
     * to be read in one pass while they still exist; afterwards the file on disk names no owner.
     * Apply either only once the delete has committed.
     */
    @Transactional(readOnly = true)
    public List<ConversationAttachment> forMessage(Long messageId) {
        return repo.findByMessageIdWithAuthor(messageId);
    }

    /**
     * Bytes each uploader should be credited back when these attachments are removed, keyed by
     * user id and ready for {@code StorageQuotaService.releaseAll}. The DM mirror of
     * {@link AttachmentService#creditsFor}, with the same rule: gather it while the rows still
     * exist, apply it once the delete has committed.
     */
    public static Map<Long, Long> creditsFor(Collection<ConversationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return Map.of();
        var credits = new java.util.HashMap<Long, Long>();
        for (var attachment : attachments) {
            var author = attachment.getMessage().getAuthor();
            if (author == null || author.getId() == null) continue;
            credits.merge(author.getId(), attachment.getSizeBytes(), Long::sum);
        }
        return credits;
    }

    /** Best-effort delete of attachment files by storage key (used after a message/DM is removed). */
    public void deleteFiles(java.util.Collection<String> storageKeys) {
        if (storageKeys == null) return;
        for (var key : storageKeys) {
            if (key == null || key.isBlank()) continue;
            var p = storageRoot.resolve(key).normalize();
            if (!p.startsWith(storageRoot)) continue; // never delete outside the store
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // Orphan on disk; a future orphan-sweep (CLEAN-*) reaps it. Don't fail the request.
            }
        }
    }

    /**
     * Stream the upload to disk, then create a conversation message + attachment row.
     * Same shape as {@code AttachmentService.upload}: {@code maxBytes} is the per-user
     * cap from {@link ai.intellistream.chat.security.CurrentUser#uploadCapBytes}; pass
     * {@link AttachmentBytes#UNLIMITED} for admins. Storage quota and free-space checks are
     * identical too — a DM is not a way around either of them.
     */
    @Transactional
    public ConversationAttachment upload(Conversation conversation, User uploader,
                                         String originalFilename, String contentType,
                                         long declaredSize, long maxBytes, String caption,
                                         InputStream in) throws IOException {
        conversations.requireMember(conversation, uploader);
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Filename required");
        }
        if (maxBytes >= 0 && declaredSize > maxBytes) {
            throw new ai.intellistream.chat.security.UploadTooLargeException(maxBytes);
        }
        quotas.requireHeadroom(storageRoot);
        var allowance = quotas.allowanceFor(uploader, declaredSize, maxBytes);
        var safeName = AttachmentService.sanitizeFilename(originalFilename);
        var safeType = (contentType == null || contentType.isBlank())
                ? "application/octet-stream" : contentType;
        var captionText = caption == null ? "" : caption.trim();
        if (captionText.length() > 8000) {
            throw new IllegalArgumentException("Caption too long (max 8000 chars)");
        }

        var storageKey = UUID.randomUUID().toString();
        var target = storageRoot.resolve(storageKey);

        var buffered = new BufferedInputStream(in);
        var resolvedType = AttachmentBytes.sniffContentType(buffered, safeType, safeName);

        // streamToFile removes the partial file on every failure path itself — see its javadoc.
        long bytesWritten = AttachmentBytes.streamToFile(buffered, target, maxBytes, allowance);

        // Orphan guard: rollback after the file is on disk would otherwise strand it.
        deleteOnRollback(target);

        // A file with no caption posts an EMPTY message, exactly as the channel path does — the
        // attachment is the content, and the clients already skip the body element when there is
        // nothing in it. This used to substitute the literal text "(attachment)", which is why a
        // DM upload read differently from the same upload in a channel.
        //
        // Saved through the repository rather than conversations.post(), which rejects a blank
        // body. That guard is right for typing a message and wrong for posting a file, and it is
        // the same reason AttachmentService bypasses MessageService for the no-caption case.
        var savedMessage = captionText.isEmpty()
                ? messages.save(new ai.intellistream.chat.domain.ConversationMessage(
                        conversation, uploader, ""))
                : conversations.post(conversation, uploader, captionText);
        var saved = repo.save(new ConversationAttachment(
                savedMessage, safeName, resolvedType, bytesWritten, storageKey));
        // Charged with the bytes actually written, inside this transaction so a rollback takes the
        // usage with it.
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
     * Look up an attachment under a specific conversation. Returns the entity only when:
     * (1) an attachment with that id exists, (2) it belongs to {@code conversationId},
     * (3) the viewer is a current member of that conversation. All three failure modes
     * throw {@link NoSuchElementException} so the controller can answer 404 uniformly,
     * never disclosing whether the attachment exists at all to a non-member.
     *
     * <p>There is no removed-message check to make here, unlike
     * {@link AttachmentService#requireForDownload}: a DM has no soft delete. {@code ConversationMessage}
     * carries no {@code deletedAt} — moderation's reversible removal covers channel messages only —
     * so {@code ConversationService.deleteMessage} destroys the row, the cascade destroys this one,
     * and the lookup above already 404s. If a soft delete is ever added to DMs, this method needs
     * the same gate its channel twin has.
     */
    @Transactional(readOnly = true)
    public ConversationAttachment requireForDownload(Long conversationId, Long attachmentId, User viewer) {
        var attachment = repo.findById(attachmentId).orElseThrow(NoSuchElementException::new);
        var owningConversation = attachment.getMessage().getConversation();
        if (!owningConversation.getId().equals(conversationId)) {
            throw new NoSuchElementException();
        }
        if (!conversations.isMember(owningConversation, viewer)) {
            throw new NoSuchElementException();
        }
        return attachment;
    }

    public Path resolve(ConversationAttachment attachment) {
        var p = storageRoot.resolve(attachment.getStorageKey()).normalize();
        if (!p.startsWith(storageRoot)) {
            throw new IllegalStateException("Attachment path escaped storage root");
        }
        return p;
    }

    @Transactional(readOnly = true)
    public List<ConversationAttachment> findForMessage(ConversationMessage message) {
        return repo.findByMessageOrderByCreatedAtAsc(message);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<ConversationAttachment>> findForMessages(Collection<ConversationMessage> messages) {
        if (messages.isEmpty()) return Map.of();
        return repo.findByMessageInOrderByCreatedAtAsc(messages).stream()
                .collect(Collectors.groupingBy(a -> a.getMessage().getId()));
    }

}
