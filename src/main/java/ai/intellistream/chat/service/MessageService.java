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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.AttachmentRepository;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.MessageReactionRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class MessageService {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final MessageReactionRepository reactionRepository;
    private final MessageMentionRepository mentionRepository;
    private final AttachmentService attachmentService;
    private final ChannelService channelService;
    private final MentionService mentionService;
    private final MessageIndexService messageIndex;
    private final ai.intellistream.chat.metrics.WritePathMetrics metrics;
    private final MessageWriteBehind writeBehind;
    private final ai.intellistream.chat.moderation.StorageQuotaService quotas;
    /** Self-proxy, so the dispatcher can enter a {@code @Transactional} method for real. */
    private final MessageService self;

    public MessageService(MessageRepository messageRepository,
                          AttachmentRepository attachmentRepository,
                          MessageReactionRepository reactionRepository,
                          MessageMentionRepository mentionRepository,
                          AttachmentService attachmentService,
                          ChannelService channelService,
                          MentionService mentionService,
                          MessageIndexService messageIndex,
                          ai.intellistream.chat.metrics.WritePathMetrics metrics,
                          MessageWriteBehind writeBehind,
                          ai.intellistream.chat.moderation.StorageQuotaService quotas,
                          @org.springframework.context.annotation.Lazy MessageService self) {
        this.metrics = metrics;
        this.writeBehind = writeBehind;
        this.quotas = quotas;
        this.self = self;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.reactionRepository = reactionRepository;
        this.mentionRepository = mentionRepository;
        this.attachmentService = attachmentService;
        this.channelService = channelService;
        this.mentionService = mentionService;
        this.messageIndex = messageIndex;
    }

    /**
     * Post a message that is <b>durably stored by the time this returns</b>. This is the safe
     * default and what every caller should use unless it is the throughput-critical send path:
     * attachments, polls and reminders all insert rows that reference the message id, and a
     * foreign key can't point at a row that is still sitting in a write-behind queue.
     */
    public Message post(Channel channel, User author, String body) {
        return postWithMentions(channel, author, body).message();
    }

    /** As {@link #post}, plus the usernames the body mentioned. Durable on return. */
    public Posted postWithMentions(Channel channel, User author, String body) {
        validate(body);
        return self.postPersistent(channel, author, body.trim());
    }

    /**
     * The throughput path, for the WebSocket send handler and nothing else.
     *
     * <p>Unlike {@link #post}, the row may <b>not</b> be in the database when this returns: with
     * write-behind enabled it is queued for a batched INSERT a few milliseconds later. The caller
     * must therefore publish only through {@link Posted#whenDurable}, and must not insert anything
     * that references the message id. Everything the message needs is either already resolved here
     * (mentions) or done by the batcher after the commit (broadcast, search indexing).
     *
     * <p>Deliberately <b>not</b> {@code @Transactional}: the batched path must not open a
     * transaction it will never use. It falls back to {@link #postPersistent} when write-behind is
     * off, when the queue is full, or when the body might need mention rows.
     *
     * <p>The mention test is a bare {@code '@'} scan. It's a conservative filter, not a parse: no
     * {@code '@'} means the mention pattern cannot match, so no {@code message_mentions} row can be
     * needed, so the message row doesn't have to exist yet for a foreign key to be satisfiable.
     * A body containing {@code '@'} takes the transactional path even if the handle resolves to
     * nobody — being occasionally slower is the right way to be wrong here.
     */
    public Posted postBuffered(Channel channel, User author, String body) {
        var lap = metrics.lap();
        validate(body);
        channelService.requireWriteAccessCached(channel, author);
        lap.mark(metrics.accessCheck);
        var trimmed = body.trim();
        if (writeBehind.isEnabled() && trimmed.indexOf('@') < 0) {
            var batched = postBatched(channel, author, trimmed);
            if (batched != null) {
                lap.mark(metrics.insert);
                return batched;
            }
            // Queue full — fall through and write it synchronously rather than drop it.
        }
        // Through the proxy, so @Transactional actually applies (a plain this.call would not).
        var posted = self.postPersistent(channel, author, trimmed);
        lap.mark(metrics.insert);
        return posted;
    }

    /**
     * Accept the message without touching Hibernate: take an id from the pre-allocated block, hand
     * the row to the write-behind batcher, index it, and return an entity the caller can broadcast.
     * Returns {@code null} if the batcher couldn't accept it, so the caller can fall back.
     */
    private Posted postBatched(Channel channel, User author, String body) {
        var lap = metrics.lap();
        var id = writeBehind.nextMessageId();
        var createdAt = Instant.now();
        var durability = new Durability();
        var accepted = writeBehind.enqueue(new MessageWriteBehind.PendingMessage(
                id, channel.getId(), author.getId(), author.getUsername(), body, createdAt, null,
                durability));
        if (!accepted) {
            return null;
        }
        lap.mark(metrics.enqueue);
        // Indexing is the batcher's job now, after the row commits — the index must never describe
        // a message the database doesn't have.
        return new Posted(Message.preAssigned(id, channel, author, body, createdAt), List.of(),
                durability);
    }

    /**
     * The original path: insert, sync mentions, index after commit — all in one transaction. The
     * returned handle is already durable by the time the caller sees it, because the surrounding
     * transaction commits as this method returns, so a registered broadcast runs immediately.
     */
    @Transactional
    public Posted postPersistent(Channel channel, User author, String body) {
        channelService.requireWriteAccessCached(channel, author);
        var saved = messageRepository.save(new Message(channel, author, body));
        var mentioned = mentionService.syncMentions(saved, true);
        // No filenames: the row was created a line ago, so nothing can be attached to it yet. An
        // upload attaches its file after this returns and re-indexes the document then
        // (AttachmentService.upload → reindexAfterAttachmentChange).
        indexNow(saved.getId(), channel.getId(), author.getUsername(), saved.getBodyMarkdown(),
                List.of());
        return new Posted(saved, mentioned.stream().map(User::getUsername).toList(),
                Durability.alreadyCommitted());
    }

    private static void validate(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (body.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
    }

    /**
     * A freshly posted message, the usernames its body mentioned, and a handle for work that must
     * not happen until the row is actually on disk.
     *
     * @param durability register the broadcast with {@link Durability#whenDurable}. On the
     *   transactional path it fires immediately; on the batched path it fires once the batch
     *   commits, and never at all if the insert failed.
     */
    public record Posted(Message message, List<String> mentionedUsernames, Durability durability) {

        /** Do this once the message is durably stored — typically, tell everybody about it. */
        public void whenDurable(Runnable action) {
            durability.whenDurable(action);
        }
    }

    @Transactional(readOnly = true)
    public List<Message> recent(Channel channel, User viewer, int limit) {
        channelService.requireMember(channel, viewer);
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), DEFAULT_PAGE_SIZE));
        var rows = messageRepository.findByChannelAndParentIsNullOrderByCreatedAtDesc(channel, page);
        rows.sort(Comparator.comparing(Message::getCreatedAt));
        return rows;
    }

    @Transactional(readOnly = true)
    public List<Message> before(Channel channel, User viewer, Instant before, int limit) {
        channelService.requireMember(channel, viewer);
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), DEFAULT_PAGE_SIZE));
        var rows = messageRepository.findByChannelAndParentIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(channel, before, page);
        rows.sort(Comparator.comparing(Message::getCreatedAt));
        return rows;
    }

    /**
     * Forward-paging counterpart to {@link #before}. Returns up to {@code limit} top-level
     * messages with {@code createdAt > after}, oldest-first. Used by the down-scroll
     * infinite-scroll path when a viewer is reading context-around an old anchor and reaches
     * the bottom of their loaded batch.
     */
    @Transactional(readOnly = true)
    public List<Message> after(Channel channel, User viewer, Instant after, int limit) {
        channelService.requireMember(channel, viewer);
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), DEFAULT_PAGE_SIZE));
        return messageRepository.findByChannelAndParentIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
                channel, after, page);
    }

    /**
     * Load the {@code radius} messages immediately before the anchor + the anchor + the
     * {@code radius} messages immediately after, oldest-first. Used to render context around
     * a search-result permalink so jumping to message 50,000 of 100,000 doesn't dump the
     * user at "latest 50". The anchor must be a top-level message (parent IS NULL) belonging
     * to {@code channel}; thread replies aren't supported here.
     */
    @Transactional(readOnly = true)
    public List<Message> around(Channel channel, User viewer, Long anchorId, int radius) {
        channelService.requireMember(channel, viewer);
        var anchor = messageRepository.findByIdWithChannelAndAuthor(anchorId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + anchorId));
        if (!anchor.getChannel().getId().equals(channel.getId())) {
            // Mismatched channel → treat as not found so a probing user can't enumerate
            // message ids across channels.
            throw new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + anchorId);
        }
        if (anchor.isThreadReply()) {
            throw new IllegalArgumentException("Anchor message is a thread reply; open the thread instead");
        }
        var capped = Math.min(Math.max(radius, 1), DEFAULT_PAGE_SIZE);
        var page = PageRequest.of(0, capped);
        // Composite (createdAt, id) keyset around the anchor so a message sharing the anchor's
        // exact timestamp lands on the correct side by id instead of being omitted from both the
        // before and after sets (BUG-20).
        var beforeRows = messageRepository.findTopLevelBeforeKeyset(
                channel, anchor.getCreatedAt(), anchor.getId(), page);
        var afterRows = messageRepository.findTopLevelAfterKeyset(
                channel, anchor.getCreatedAt(), anchor.getId(), page);
        beforeRows.sort(Comparator.comparing(Message::getCreatedAt).thenComparing(Message::getId));
        var combined = new ArrayList<Message>(beforeRows.size() + 1 + afterRows.size());
        combined.addAll(beforeRows);
        combined.add(anchor);
        combined.addAll(afterRows);
        return combined;
    }

    // The lookups below go through findByIdWithChannelAndAuthor rather than the inherited
    // findById: it filters soft-deleted rows, so a removed message is "not found" to pinning,
    // replying, thread reads and deletion alike, instead of only being hidden from the feed.

    /**
     * Pin a message to its channel. <b>Any member may pin</b>, which is a widening of what this
     * method used to require ({@code requireAdmin}).
     *
     * <p>Slack draws the line here and it is the right one for a tool that trusts the people using
     * it: a pin is the channel's "read this first", and the person who knows which message that is
     * is usually the person who just read it, not whoever happens to hold the admin role. The cost
     * of being wrong is one click to undo — an unpin is as available as the pin, to the same people
     * — which is not the shape of a decision worth gating. Admin-only pinning has the failure mode
     * every unnecessary permission has: the useful thing does not happen at all.
     *
     * <p>{@code requireWriteAccess}, not {@code requireMember}, because pinning writes to the
     * channel: it changes what the channel says about itself to everybody who reads it. That also
     * makes it refuse on an archived channel for free, which is right — an archive is a record, and
     * re-curating a record is exactly the sort of change archiving exists to stop.
     */
    @Transactional
    public Message pin(Long messageId, User actor) {
        var message = messageRepository.findByIdWithChannelAndAuthor(messageId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + messageId));
        channelService.requireWriteAccess(message.getChannel(), actor);
        if (message.isThreadReply()) {
            // A pinned reply has no home in the list: the pins panel links into the channel feed,
            // and a reply is not in it. Pin the parent, which is what someone means anyway.
            throw new ai.intellistream.chat.security.PublicBadRequestException(
                    "Thread replies can't be pinned — pin the message that starts the thread.");
        }
        message.pin(actor);
        return message;
    }

    /** Unpin. Same bar as {@link #pin}: whoever may pin may unpin, including someone else's pin. */
    @Transactional
    public Message unpin(Long messageId, User actor) {
        var message = messageRepository.findByIdWithChannelAndAuthor(messageId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + messageId));
        channelService.requireWriteAccess(message.getChannel(), actor);
        message.unpin();
        return message;
    }

    /**
     * The channel's pins, most recently pinned first.
     *
     * <p>{@code requireMember} — the read check — so a public channel's pins are visible to anyone
     * who can read the channel, member or not. Pins belong to the channel, not to its membership
     * list, and a "read this first" nobody can read before joining is the wrong way round.
     */
    @Transactional(readOnly = true)
    public List<Message> pinned(Channel channel, User viewer) {
        channelService.requireMember(channel, viewer);
        return messageRepository.findByChannelAndPinnedAtIsNotNullOrderByPinnedAtDesc(channel);
    }

    /** How many pins the channel has — the header badge, without shipping the bodies. */
    @Transactional(readOnly = true)
    public long pinnedCount(Channel channel, User viewer) {
        channelService.requireMember(channel, viewer);
        return messageRepository.countByChannelAndPinnedAtIsNotNull(channel);
    }

    @Transactional
    public Message replyInThread(Long parentId, User author, String body) {
        var parent = messageRepository.findByIdWithChannelAndAuthor(parentId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + parentId));
        if (parent.isThreadReply()) {
            throw new IllegalArgumentException("Cannot reply to a thread reply — reply to its parent instead");
        }
        var channel = parent.getChannel();
        channelService.requireWriteAccess(channel, author);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (body.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
        var saved = messageRepository.save(new Message(channel, author, body.trim(), parent));
        mentionService.syncMentions(saved, true);
        indexNow(saved.getId(), channel.getId(), author.getUsername(), saved.getBodyMarkdown(),
                List.of()); // brand-new row; nothing attached yet
        return saved;
    }

    /**
     * The usernames to notify about a reply in {@code parent}'s thread: everyone who has written in
     * the thread — the parent's author plus every replier — except {@code excluding}, and narrowed to
     * people who are still members of the channel.
     *
     * <p>Derived from the messages rather than from a follow table; see
     * {@code MessageRepository.findThreadParticipants} for why that is the answer and not an
     * approximation of it. {@code excluding} is the person who just replied: telling them about their
     * own message is the one guaranteed-useless notification.
     *
     * <p>The membership narrowing is not paranoia. A private channel's thread can contain messages
     * from someone who has since left, and a public channel's too; broadcasting the participant list
     * to the channel topic means anything in it is a name the client will act on.
     */
    @Transactional(readOnly = true)
    public List<String> threadParticipants(Message parent, User excluding) {
        var rows = messageRepository.findThreadParticipants(parent.getId());
        if (rows.isEmpty()) {
            return List.of();
        }
        var byId = new java.util.LinkedHashMap<Long, String>(rows.size());
        for (var row : rows) {
            var id = ((Number) row[0]).longValue();
            if (excluding != null && id == excluding.getId()) continue;
            byId.put(id, (String) row[1]);
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        var stillMembers = channelService.membersAmong(parent.getChannel(), byId.keySet());
        return byId.entrySet().stream()
                .filter(e -> stillMembers.contains(e.getKey()))
                .map(java.util.Map.Entry::getValue)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Message> threadReplies(Long parentId, User viewer) {
        var parent = messageRepository.findByIdWithChannelAndAuthor(parentId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + parentId));
        channelService.requireMember(parent.getChannel(), viewer);
        return messageRepository.findByParentOrderByCreatedAtAsc(parent);
    }

    @Transactional(readOnly = true)
    public long threadReplyCount(Message parent) {
        return messageRepository.countByParent(parent);
    }

    /** Reply-count map for a batch of top-level messages — parents with 0 replies are absent. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Long> threadReplyCounts(java.util.Collection<Message> parents) {
        if (parents.isEmpty()) return java.util.Map.of();
        var ids = parents.stream().map(Message::getId).toList();
        var rows = messageRepository.countRepliesByParentIds(ids);
        var out = new java.util.HashMap<Long, Long>(rows.size());
        for (var row : rows) {
            out.put((Long) row[0], (Long) row[1]);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Message requireById(Long id) {
        // Join-fetch author + channel so the controller can serialize the message and call
        // channelService.requireMember(...) after this transaction closes (open-in-view is off).
        return messageRepository.findByIdWithChannelAndAuthor(id)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + id));
    }

    /** Author-only edit. Updates body and bumps {@code editedAt}. */
    @Transactional
    public Message edit(Long messageId, User actor, String newBody) {
        var message = messageRepository.findByIdWithAuthor(messageId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + messageId));
        // Membership re-check: defends against the case where the channel's type was
        // flipped PUBLIC → PRIVATE and the original author is no longer a member.
        channelService.requireWriteAccess(message.getChannel(), actor);
        if (!message.getAuthor().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the author can edit this message.");
        }
        if (newBody == null || newBody.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (newBody.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
        message.setBodyMarkdown(newBody.trim());
        mentionService.syncMentions(message);
        var channelId = message.getChannel().getId();
        var newBodyTrimmed = message.getBodyMarkdown();
        var authorUsername = message.getAuthor().getUsername();
        // Re-read the attachment filenames rather than passing an empty list. The index document is
        // rewritten whole, so an edit that forgot them would silently un-find every file this
        // message carries — a regression nobody sees until someone searches for an old attachment
        // and gets nothing.
        var filenames = messageRepository.findIndexFilenames(messageId);
        // Push the index write to afterCommit. A within-tx index update would leak the
        // new body to concurrent searchers before the row is committed; a rollback
        // compensator can fail silently and leaves the index inconsistent with the DB.
        afterCommit(() -> messageIndex.index(messageId, channelId, authorUsername, newBodyTrimmed,
                filenames));
        return message;
    }

    /**
     * Rewrite a message's index document because its <b>attachment set</b> changed — a file was
     * just uploaded onto it, or one of its files was tombstoned in the file manager.
     *
     * <p>Exists because the filename is part of the message's document but is not known when that
     * document is first written: {@code AttachmentService.upload} is handed an already-persisted
     * message and creates the attachment row afterwards. The index write therefore has to happen a
     * second time, and it has to happen from whichever service owns the change.
     *
     * <p>Reads the filenames now, inside the caller's transaction (a JPQL query flushes the pending
     * insert or tombstone first, so it sees them), and writes the document after the commit — the
     * same ordering as every other index write here, for the same reason.
     *
     * <p>This is also what first indexes a caption-less upload at all: those messages are saved
     * straight through the repository with an empty body and never went near {@link #post}, so
     * before this they had no document until a reconcile sweep noticed one was missing.
     */
    public void reindexAfterAttachmentChange(Message message) {
        var messageId = message.getId();
        var channelId = message.getChannel().getId();
        var author = message.getAuthor().getUsername();
        var body = message.getBodyMarkdown();
        var filenames = messageRepository.findIndexFilenames(messageId);
        afterCommit(() -> messageIndex.index(messageId, channelId, author, body, filenames));
    }

    /**
     * Delete a message. Allowed when {@code actor} is the author or a channel admin.
     * Removes any thread replies and attachment rows in dependency order so the
     * Hibernate session stays consistent (the DB also has on-delete-cascade FKs,
     * but cascaded rows that the session still holds would error on the next flush).
     * Orphaned files on disk are best-effort cleaned up after the DB delete commits,
     * and their bytes are credited back to whoever uploaded them at the same point.
     *
     * <p>This is the <em>hard</em> delete — the author's or channel admin's, not moderation's
     * reversible one. The bytes really do leave the disk here, which is what makes crediting them
     * back correct; the admin soft delete deliberately does not credit, because nothing is freed
     * until the retention purge runs.
     */
    @Transactional
    public DeletedMessage delete(Long messageId, User actor) {
        var message = messageRepository.findByIdWithChannelAndAuthor(messageId)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + messageId));

        var channel = message.getChannel();
        var isAuthor = message.getAuthor().getId().equals(actor.getId());
        if (!isAuthor && !channelService.isAdmin(channel, actor)) {
            throw new AccessDeniedException("Only the author or a channel admin can delete this message.");
        }

        var channelId = channel.getId();
        var parentId = message.getParent() == null ? null : message.getParent().getId();

        var fileKeys = new ArrayList<String>();
        var indexedIds = new ArrayList<Long>();
        // The rows are the only record of who uploaded each file and how big it was — the file on
        // disk carries neither. Collected here, while they still exist, and turned into per-account
        // credits below; by the time the post-commit hooks run there would be nothing left to read.
        var doomedAttachments = new ArrayList<ai.intellistream.chat.domain.Attachment>();

        // Replies first — gather attachments + reactions, delete dependents, then the replies.
        // Soft-deleted replies are included: parent_id cascades on delete, so they are going
        // regardless, and skipping them would strand their files on disk and their index docs.
        var replies = messageRepository.findRepliesIncludingDeleted(message);
        for (var reply : replies) {
            indexedIds.add(reply.getId());
            var replyAttachments = attachmentRepository.findByMessageOrderByCreatedAtAsc(reply);
            replyAttachments.forEach(a -> fileKeys.add(a.getStorageKey()));
            doomedAttachments.addAll(replyAttachments);
            attachmentRepository.deleteAll(replyAttachments);
            reactionRepository.deleteAll(reactionRepository.findByMessageOrderByCreatedAtAsc(reply));
            mentionRepository.deleteAllByMessage(reply);
        }
        messageRepository.deleteAll(replies);

        // Then this message's own attachments + reactions.
        var ownAttachments = attachmentRepository.findByMessageOrderByCreatedAtAsc(message);
        ownAttachments.forEach(a -> fileKeys.add(a.getStorageKey()));
        doomedAttachments.addAll(ownAttachments);
        attachmentRepository.deleteAll(ownAttachments);
        reactionRepository.deleteAll(reactionRepository.findByMessageOrderByCreatedAtAsc(message));
        mentionRepository.deleteAllByMessage(message);

        indexedIds.add(messageId);
        messageRepository.delete(message);

        // Both side effects deferred to afterCommit. Doing them inside the tx leaves files
        // and index entries inconsistent with the DB if the JPA delete rolls back — the
        // previous rollback-compensator pattern relied on a snapshot/restore that could
        // fail silently.
        var indexedIdsSnapshot = List.copyOf(indexedIds);
        var fileKeysSnapshot = List.copyOf(fileKeys);
        // Computed now (the entities are still attached and their authors resolvable), applied
        // after the commit for the same reason the file cleanup waits: a delete that rolls back
        // must not hand back bytes that are still stored. Registered last so a failing index purge
        // or file reap cannot skip it — afterCommit guards each hook, but order still decides what
        // a thrown-and-logged failure costs, and an uncredited account is the one that ends up
        // unable to upload.
        //
        // The credit is applied HERE, inside the deleting transaction, not after it. Both
        // orderings are defensible and this one is better:
        //
        //   in-transaction  — the delete and the refund commit or roll back together, so the
        //                     recorded usage can never disagree with the rows. If the file
        //                     cleanup below then fails, an orphan file survives while its bytes
        //                     read as free, which is exactly what the orphan sweep already exists
        //                     to reconcile.
        //   after-commit    — a failed credit leaves the bytes deleted but still charged forever,
        //                     and UserStorage exposes only an atomic delta, so nothing can repair
        //                     it. The account quietly loses quota it will never get back.
        //
        // A recoverable inconsistency beats an unrecoverable one.
        // creditsForLive, not creditsFor: doomedAttachments is gathered unfiltered because every
        // row has to be deleted and every file reaped, but a tombstoned one was credited when the
        // file manager tombstoned it. Crediting it again here is bytes the account never had back.
        quotas.releaseAll(AttachmentService.creditsForLive(doomedAttachments));
        afterCommit(() -> messageIndex.deleteAll(indexedIdsSnapshot));
        afterCommit(() -> attachmentService.deleteFiles(fileKeysSnapshot));

        return new DeletedMessage(messageId, channelId, parentId);
    }

    // NOTE, kept because it cost real time to find: an afterCommit hook runs while the finished
    // transaction's resources are still bound to the thread, so a plain REQUIRED database write
    // there joins a transaction that has ALREADY COMMITTED. The UPDATE is issued, nothing ever
    // commits it, the connection is released, and there is no exception and no log line. That is
    // why every remaining afterCommit hook in this class is Lucene or filesystem work and none of
    // them touch the database. If you ever need a post-commit write, it must be REQUIRES_NEW and
    // it must go through the proxy.

    /**
     * Defer the index write to {@code afterCommit} so the index never reflects a row the
     * DB later rolled back, and concurrent searchers don't see uncommitted entries.
     * Outside a transaction (no synchronization active) the write is immediate.
     *
     * <p>IT classes are {@code @Transactional} at class level — the test method's tx
     * rolls back automatically for isolation, which means {@code afterCommit} hooks
     * never fire under test. Tests that exercise post-then-search or delete-then-check
     * call {@link Tx#commit()} between the action and the assertion to flush the inner
     * tx so its hooks fire.
     */
    private void indexNow(Long messageId, Long channelId, String author, String body,
                          List<String> filenames) {
        afterCommit(() -> messageIndex.index(messageId, channelId, author, body, filenames));
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageService.class);

    /**
     * Register an action to run after a successful commit; if no tx is active, run it now. The
     * body is wrapped in try/catch-log: Spring's afterCommit dispatch stops at the first throwing
     * synchronization, so an unguarded index-write failure would skip the file-cleanup hook
     * registered after it (BUG-21). Logged rather than swallowed silently so the desync is visible
     * (a periodic reconcile — CLEAN-3 — is the backstop for what's lost here).
     */
    private static void afterCommit(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Post-commit side effect (index / file cleanup) failed; state may be "
                        + "temporarily inconsistent until the next reconcile", e);
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

    public record DeletedMessage(Long id, Long channelId, Long parentId) {}
}
