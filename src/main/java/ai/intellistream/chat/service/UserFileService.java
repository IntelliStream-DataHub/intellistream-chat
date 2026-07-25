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

import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.ConversationAttachmentRepository;
import ai.intellistream.chat.repository.ConversationMemberRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.web.dto.UserFileDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-user file manager behind {@code GET /files}: everything one account has uploaded, across
 * channels and direct messages, searchable by filename and deletable by its uploader.
 *
 * <h2>Ownership is the query, not a check after it</h2>
 * Neither attachment table has an uploader column — a file's owner is its message's author and
 * nothing else records it. Every listing query therefore carries {@code message.author = :owner} in
 * its {@code where} clause, so there is no id from the client anywhere in the read path and no
 * request shape that can return somebody else's row. The delete path cannot be written that way (it
 * is given an id by definition), so it re-derives the owner from the row it loaded and compares —
 * and answers {@link ResourceNotFoundException} rather than "forbidden" when they differ, because
 * "that file exists but is not yours" is itself a disclosure about a stranger's account.
 *
 * <h2>Why Postgres owns the search and Lucene does not</h2>
 * Message bodies are searched through the embedded Lucene index ({@code MessageIndexService});
 * filenames are not, and putting them there would be a bad trade:
 * <ul>
 *   <li><b>The benefit is small.</b> The search runs against one account's uploads, already narrowed
 *       by an indexed {@code author_id} predicate ({@code ix_messages_author},
 *       {@code ix_conv_messages_author}). A substring test over the few hundred rows that survive
 *       is a filter, not a scan. Lucene's value is ranking and tokenised matching over a whole
 *       workspace's prose; a filename is a short opaque token where users want substring
 *       ("invoice" finding {@code 2026-invoice-final.pdf"}) — precisely what an analyser tokenising
 *       on punctuation is worst at.</li>
 *   <li><b>The cost is a whole new consistency surface.</b> The index today holds exactly one
 *       document type keyed by message id. Filenames would need a second type or new fields, a
 *       write on every upload, a delete on every attachment delete, an extension of
 *       {@code LuceneBootstrap}'s rebuild-if-empty, and — the expensive one — an extension of
 *       {@code CleanupTasks.reconcileSearchIndex}, which today reconciles indexed ids against the
 *       {@code messages} table and would happily delete any document whose id it did not recognise.
 *       Every one of those is a place the index can drift from the database, and a drifted file
 *       manager either hides a file the user is being charged for or offers one that is gone.</li>
 *   <li><b>The database is already the authority here.</b> The listing has to read the rows anyway
 *       for size, type, location and the delete policy. Filtering in the same query costs one extra
 *       predicate; filtering in Lucene means a search, then a second query for the ids it returned,
 *       and a decision about what to do when the two disagree.</li>
 * </ul>
 * So: {@code lower(filename) like '%term%'}. This is not the "switch search to ILIKE" the project
 * guide forbids — that rule is about message-body search, which stays in Lucene. This is a filter on
 * an already-selective, single-account result set.
 */
@Service
public class UserFileService {

    /** Files per page. */
    public static final int PAGE_SIZE = 50;

    /**
     * Hard ceiling on how deep the paging goes. Two sources are merged in memory, so page N costs
     * {@code (N+1) * PAGE_SIZE} rows from <em>each</em> table; capping the depth caps that cost. A
     * user past 1,000 of their own files is looking for something, and the search box is the tool
     * for that.
     */
    public static final int MAX_ROWS = 1000;

    private final AttachmentRepository channelFiles;
    private final ConversationAttachmentRepository conversationFiles;
    private final MessageRepository messages;
    private final ConversationMemberRepository conversationMembers;
    private final MessageService messageService;
    private final ConversationService conversationService;
    private final AttachmentService channelAttachments;
    private final ConversationAttachmentService conversationAttachments;
    private final StorageQuotaService quotas;

    public UserFileService(AttachmentRepository channelFiles,
                           ConversationAttachmentRepository conversationFiles,
                           MessageRepository messages,
                           ConversationMemberRepository conversationMembers,
                           MessageService messageService,
                           ConversationService conversationService,
                           AttachmentService channelAttachments,
                           ConversationAttachmentService conversationAttachments,
                           StorageQuotaService quotas) {
        this.channelFiles = channelFiles;
        this.conversationFiles = conversationFiles;
        this.messages = messages;
        this.conversationMembers = conversationMembers;
        this.messageService = messageService;
        this.conversationService = conversationService;
        this.channelAttachments = channelAttachments;
        this.conversationAttachments = conversationAttachments;
        this.quotas = quotas;
    }

    /** Which table a file lives in. Part of its identity — the two id sequences are independent. */
    public enum Scope {
        CHANNEL, CONVERSATION;

        /** Parse a path segment, rejecting anything else. Never throws on user input's behalf. */
        public static Scope parse(String raw) {
            if (raw != null) {
                for (var s : values()) {
                    if (s.name().equalsIgnoreCase(raw)) return s;
                }
            }
            throw new ResourceNotFoundException("Unknown file scope: " + raw);
        }

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One page of the file manager plus the totals around it.
     *
     * @param total          matching files, before paging
     * @param totalBytes     what the listed files actually occupy, summed from the rows
     * @param quotaUsedBytes what {@code user_storage} says this account is using — the number the
     *                       upload path enforces. Sent alongside {@code totalBytes} rather than
     *                       instead of it: they are computed from different places and a
     *                       disagreement is a bug worth being able to see, not one to average away
     * @param quotaBytes     the enforced ceiling; negative means unlimited
     */
    public record FilePage(List<UserFileDto> files, long total, long totalBytes,
                           long quotaUsedBytes, long quotaBytes,
                           int page, int pageSize, boolean hasMore) {}

    /** What a completed delete freed, and where to announce it. */
    public record DeletedFile(Scope scope, Long attachmentId, String filename, long bytesFreed,
                              Long messageId, Long channelId, Long parentMessageId,
                              Long conversationId) {}

    // ------------------------------------------------------------------ read

    /**
     * One page of {@code owner}'s uploads, newest first, optionally narrowed to filenames containing
     * {@code query}.
     *
     * <p>The two tables are paged separately and merged here rather than joined in SQL: they share
     * no ancestor, so a database-level union would need matching column lists and would still leave
     * the ordering to the caller. Taking {@code offset + pageSize} from each and slicing the merge
     * is exactly correct for a descending merge of two sorted streams, and the {@link #MAX_ROWS} cap
     * keeps the wasted rows bounded.
     */
    @Transactional(readOnly = true)
    public FilePage list(User owner, String query, int page) {
        var pattern = likePattern(query);
        int safePage = Math.max(0, page);
        int offset = safePage * PAGE_SIZE;
        var usage = quotas.usageFor(owner);
        if (offset >= MAX_ROWS) {
            return new FilePage(List.of(),
                    channelFiles.countUploadedBy(owner, pattern)
                            + conversationFiles.countUploadedBy(owner, pattern),
                    totalBytes(owner), usage.bytesUsed(), usage.effectiveQuotaBytes(),
                    safePage, PAGE_SIZE, false);
        }
        int take = Math.min(offset + PAGE_SIZE, MAX_ROWS);
        var window = PageRequest.of(0, take);

        var channelRows = channelFiles.findUploadedBy(owner, pattern, window);
        var conversationRows = conversationFiles.findUploadedBy(owner, pattern, window);

        var merged = new ArrayList<UserFileDto>(channelRows.size() + conversationRows.size());
        merged.addAll(toChannelDtos(channelRows));
        merged.addAll(toConversationDtos(owner, conversationRows));
        // Newest first, id as the tiebreaker so two uploads sharing a timestamp keep a stable order
        // across pages instead of swapping and hiding one of themselves.
        merged.sort(Comparator.comparing(UserFileDto::createdAt).reversed()
                .thenComparing(Comparator.comparing(UserFileDto::id).reversed()));

        long total = channelFiles.countUploadedBy(owner, pattern)
                + conversationFiles.countUploadedBy(owner, pattern);
        var slice = merged.subList(Math.min(offset, merged.size()),
                Math.min(offset + PAGE_SIZE, merged.size()));
        return new FilePage(List.copyOf(slice), total, totalBytes(owner),
                usage.bytesUsed(), usage.effectiveQuotaBytes(), safePage, PAGE_SIZE,
                offset + PAGE_SIZE < Math.min(total, MAX_ROWS));
    }

    /** Bytes this account's surviving uploads occupy — the file manager's own arithmetic, which is
     *  what makes a disagreement with {@code user_storage} visible rather than silent. */
    @Transactional(readOnly = true)
    public long totalBytes(User owner) {
        return channelFiles.sumBytesUploadedBy(owner) + conversationFiles.sumBytesUploadedBy(owner);
    }

    // ------------------------------------------------------------------ delete

    /**
     * Delete one of {@code owner}'s files — and, deliberately, the message that posted it.
     *
     * <h2>The policy, and why this one</h2>
     * In this application a file is not a standalone object that a message happens to point at: an
     * upload <em>is</em> a message ({@code AttachmentService.upload} creates both in one
     * transaction, {@code attachments.message_id} is {@code not null}), and the message is the only
     * way anyone reaches the file. That rules out the tempting middle option:
     *
     * <ul>
     *   <li><b>Unhook the file, leave the message.</b> A blank-caption upload — the common case —
     *       would render as an empty bubble, and a captioned one as text pointing at nothing. Making
     *       that <em>not</em> silent needs a tombstone column plus a matching "file removed"
     *       placeholder in all three renderers (the server-side template and both JS message
     *       renderers). A half-built version of that is exactly the silent corruption to avoid: the
     *       row quietly disappears from the conversation and nobody is told. Rejected.</li>
     *   <li><b>Refuse whenever a live message shows the file.</b> Safe, and useless: every
     *       attachment here has a live message, so the delete button would never work and the
     *       account could never free its own quota.</li>
     *   <li><b>Delete both, visibly.</b> What this does. It is the same operation the uploader can
     *       already perform from the channel or DM (they are the message's author), it goes through
     *       the same service that owns it, and the removal is broadcast on the same topic, so every
     *       open client sees the message go. Nothing is left rendering a hole.</li>
     * </ul>
     *
     * <h2>Where it refuses, and what it says</h2>
     * Two cases are refused rather than performed, and the listing marks them before the click:
     * <ul>
     *   <li><b>The message is a thread parent with replies.</b> {@code MessageService.delete} takes
     *       every reply with the parent ({@code messages.parent_id} is {@code on delete cascade}),
     *       so deleting the file here would destroy other people's messages. That is not the
     *       uploader's to delete, and it is the one outcome that genuinely corrupts a conversation.
     *       The refusal names the thread and links to it.</li>
     *   <li><b>The message was removed by a moderator.</b> A soft delete is deliberately reversible
     *       and the retention purge owns those bytes (it credits them back when it hard-deletes —
     *       see {@code RetentionPurgeScheduler}). Letting the uploader hard-delete underneath it
     *       would quietly make a moderation decision unreviewable. The file is listed, because the
     *       account is still charged for it, and marked as held.</li>
     * </ul>
     *
     * <h2>The quota credit commits</h2>
     * This method is {@code @Transactional} and the delete it delegates to joins that transaction
     * ({@code REQUIRED}), so the {@code user_storage} decrement and the row delete are one commit —
     * they cannot disagree. That is not an incidental detail: an {@code afterCommit} hook runs while
     * the finished transaction's resources are still bound to the thread, so a plain database write
     * registered there joins a transaction that has <em>already committed</em>. The UPDATE is
     * issued, nothing commits it, and there is no exception and no log line. Every post-commit hook
     * below is therefore filesystem or broadcast work and none of them touch the database.
     *
     * <p>For channel files the credit is {@code MessageService.delete}'s own in-transaction
     * {@code quotas.releaseAll}; for DM files {@code ConversationService.deleteMessage} has no
     * credit of its own, so this method issues it — still inside this transaction, next to the
     * delete, for the same reason.
     *
     * <p>File cleanup waits for the commit, because a rolled-back delete must not take the bytes
     * with it. If that cleanup fails the file is orphaned on disk with its row gone, which is the
     * exact condition {@code CleanupTasks.sweepOrphanFiles} exists to reconcile — nothing here
     * touches its live-key set or its empty-live-set guard, so the sweep stays the backstop it was
     * designed to be.
     */
    @Transactional
    public DeletedFile delete(User owner, Scope scope, Long attachmentId) {
        return scope == Scope.CHANNEL
                ? deleteChannelFile(owner, attachmentId)
                : deleteConversationFile(owner, attachmentId);
    }

    private DeletedFile deleteChannelFile(User owner, Long attachmentId) {
        var attachment = channelFiles.findById(attachmentId).orElseThrow(UserFileService::notYours);
        var message = attachment.getMessage();
        requireOwner(owner, message.getAuthor());
        if (attachment.isDeleted()) throw notYours();   // already a tombstone; nothing to remove

        var block = blockReasonFor(message);
        if (block != null) {
            throw new FileDeleteRefusedException(block);
        }

        var filename = attachment.getFilename();
        var bytes = attachment.getSizeBytes();
        var storageKey = attachment.getStorageKey();
        var messageId = message.getId();
        var channelId = message.getChannel().getId();
        var parentId = message.getParent() == null ? null : message.getParent().getId();

        // The message stays. Only the attachment is tombstoned, so the caption survives, replies
        // survive, and the message can say what happened to the file instead of leaving a gap.
        attachment.softDelete(owner);
        // In this transaction, next to the tombstone — see the class note on afterCommit.
        quotas.releaseAll(AttachmentService.creditsFor(List.of(attachment)));
        afterCommit(() -> channelAttachments.deleteFiles(List.of(storageKey)));

        return new DeletedFile(Scope.CHANNEL, attachmentId, filename, bytes,
                messageId, channelId, parentId, null);
    }

    private DeletedFile deleteConversationFile(User owner, Long attachmentId) {
        var attachment = conversationFiles.findById(attachmentId).orElseThrow(UserFileService::notYours);
        var message = attachment.getMessage();
        requireOwner(owner, message.getAuthor());
        if (attachment.isDeleted()) throw notYours();

        var filename = attachment.getFilename();
        var bytes = attachment.getSizeBytes();
        var storageKey = attachment.getStorageKey();
        var messageId = message.getId();
        var conversationId = message.getConversation().getId();

        attachment.softDelete(owner);
        quotas.releaseAll(ConversationAttachmentService.creditsFor(List.of(attachment)));
        afterCommit(() -> conversationAttachments.deleteFiles(List.of(storageKey)));

        return new DeletedFile(Scope.CONVERSATION, attachmentId, filename, bytes,
                messageId, null, null, conversationId);
    }

    /** Raised when the delete policy refuses; the message is written to be shown to the user. */
    public static class FileDeleteRefusedException extends RuntimeException {
        public FileDeleteRefusedException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ mapping

    private List<UserFileDto> toChannelDtos(List<Attachment> rows) {
        if (rows.isEmpty()) return List.of();
        // Reply counts for the page's top-level messages in one query — a reply cannot itself be
        // replied to, so anything with a parent is safe by construction.
        var parentIds = rows.stream()
                .map(a -> a.getMessage())
                .filter(m -> m.getParent() == null)
                .map(m -> m.getId())
                .distinct()
                .toList();
        Map<Long, Long> replyCounts = new HashMap<>();
        if (!parentIds.isEmpty()) {
            for (var row : messages.countRepliesIncludingDeletedByParentIds(parentIds)) {
                replyCounts.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }

        var out = new ArrayList<UserFileDto>(rows.size());
        for (var a : rows) {
            var message = a.getMessage();
            var channel = message.getChannel();
            var parent = message.getParent();
            long replies = parent == null ? replyCounts.getOrDefault(message.getId(), 0L) : 0L;
            var block = blockReasonFor(message);
            // Anchor on the thread parent for a reply: the channel page's ?m= anchor rejects
            // thread-reply ids and would silently fall back to "latest 50", losing the link's point.
            var anchorId = parent == null ? message.getId() : parent.getId();
            out.add(new UserFileDto(
                    Scope.CHANNEL.wire(), a.getId(), a.getFilename(), a.getContentType(),
                    a.getSizeBytes(), a.getCreatedAt(),
                    "/api/attachments/" + a.getId() + "/download",
                    "#" + channel.getName(),
                    "/channels/" + channel.getId() + "?m=" + anchorId,
                    "channel",
                    block == null, block));
        }
        return out;
    }

    private List<UserFileDto> toConversationDtos(User owner, List<ConversationAttachment> rows) {
        if (rows.isEmpty()) return List.of();
        var directIds = rows.stream()
                .map(a -> a.getMessage().getConversation())
                .filter(c -> c.getType() == ConversationType.DIRECT)
                .map(c -> c.getId())
                .distinct()
                .toList();
        Map<Long, String> counterparts = new HashMap<>();
        if (!directIds.isEmpty()) {
            for (var row : conversationMembers.findCounterparts(directIds, owner.getId())) {
                var displayName = (String) row[2];
                counterparts.putIfAbsent((Long) row[0],
                        displayName != null && !displayName.isBlank() ? displayName : (String) row[1]);
            }
        }

        var out = new ArrayList<UserFileDto>(rows.size());
        for (var a : rows) {
            var conversation = a.getMessage().getConversation();
            boolean direct = conversation.getType() == ConversationType.DIRECT;
            var label = direct
                    // "Direct message" rather than a name when the other side's row is gone —
                    // better an honest placeholder than an empty link.
                    ? counterparts.getOrDefault(conversation.getId(), "Direct message")
                    : (conversation.getTitle() == null ? "Group message" : conversation.getTitle());
            out.add(new UserFileDto(
                    Scope.CONVERSATION.wire(), a.getId(), a.getFilename(), a.getContentType(),
                    a.getSizeBytes(), a.getCreatedAt(),
                    "/api/conversations/" + conversation.getId()
                            + "/attachments/" + a.getId() + "/download",
                    label,
                    "/conversations/" + conversation.getId(),
                    direct ? "direct" : "group",
                    // A DM has neither a soft delete nor threads, so nothing can be refused here.
                    true, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ policy helpers

    /**
     * The single place the delete policy is decided, so the listing's badge and the endpoint's
     * refusal can never drift apart. Returns null when the file may be deleted.
     */
    private static String blockReasonFor(ai.intellistream.chat.domain.Message message) {
        if (message.isDeleted()) {
            return "The message holding this file was removed by a moderator. That removal is "
                    + "reversible, so the file is kept — and still counted against your storage — "
                    + "until the retention window expires and the purge deletes it for good.";
        }
        return null;
    }

    private long repliesTo(ai.intellistream.chat.domain.Message message) {
        if (message.getParent() != null) return 0; // a reply cannot itself be replied to
        var rows = messages.countRepliesIncludingDeletedByParentIds(List.of(message.getId()));
        return rows.isEmpty() ? 0L : ((Number) rows.get(0)[1]).longValue();
    }

    /**
     * 404, not 403. "That file exists but belongs to someone else" is a fact about a stranger's
     * account, and a file manager that leaks it turns a sequential id scan into an inventory of the
     * workspace's uploads.
     */
    private static void requireOwner(User owner, User uploader) {
        if (uploader == null || owner == null || uploader.getId() == null
                || !uploader.getId().equals(owner.getId())) {
            throw notYours();
        }
    }

    private static ResourceNotFoundException notYours() {
        return new ResourceNotFoundException("File not found");
    }

    /**
     * Build the {@code like} pattern, escaping the wildcards so a filename containing {@code _} or
     * {@code %} searches for itself. A blank query becomes {@code %}, which matches every row —
     * filename is {@code not null}, so there is no row it silently drops.
     */
    static String likePattern(String query) {
        if (query == null || query.isBlank()) return "%";
        var trimmed = query.trim().toLowerCase(java.util.Locale.ROOT);
        var escaped = new StringBuilder(trimmed.length() + 8);
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '%' || c == '_' || c == '!') escaped.append('!');
            escaped.append(c);
        }
        return "%" + escaped + "%";
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserFileService.class);

    /**
     * Run after a successful commit; immediately when no transaction is active. Guarded so a failing
     * hook cannot skip one registered after it (Spring's dispatch stops at the first throwing
     * synchronization). Filesystem work only — never a database write; see the class javadoc.
     */
    private static void afterCommit(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Post-commit file cleanup failed; the orphan sweep will reconcile", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }
}
