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

package com.example.chat.service;

import com.example.chat.domain.Channel;
import com.example.chat.domain.Message;
import com.example.chat.domain.User;
import com.example.chat.repository.AttachmentRepository;
import com.example.chat.repository.MessageMentionRepository;
import com.example.chat.repository.MessageReactionRepository;
import com.example.chat.repository.MessageRepository;
import com.example.chat.search.MessageIndexService;
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
import java.util.UUID;

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

    public MessageService(MessageRepository messageRepository,
                          AttachmentRepository attachmentRepository,
                          MessageReactionRepository reactionRepository,
                          MessageMentionRepository mentionRepository,
                          AttachmentService attachmentService,
                          ChannelService channelService,
                          MentionService mentionService,
                          MessageIndexService messageIndex) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.reactionRepository = reactionRepository;
        this.mentionRepository = mentionRepository;
        this.attachmentService = attachmentService;
        this.channelService = channelService;
        this.mentionService = mentionService;
        this.messageIndex = messageIndex;
    }

    @Transactional
    public Message post(Channel channel, User author, String body) {
        channelService.requireMember(channel, author);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (body.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
        var saved = messageRepository.save(new Message(channel, author, body.trim()));
        mentionService.syncMentions(saved);
        indexNow(saved.getId(), channel.getId(), author.getUsername(), saved.getBodyMarkdown());
        return saved;
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
    public List<Message> around(Channel channel, User viewer, UUID anchorId, int radius) {
        channelService.requireMember(channel, viewer);
        var anchor = messageRepository.findByIdWithChannelAndAuthor(anchorId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + anchorId));
        if (!anchor.getChannel().getId().equals(channel.getId())) {
            // Mismatched channel → treat as not found so a probing user can't enumerate
            // message ids across channels.
            throw new IllegalArgumentException("Message not found: " + anchorId);
        }
        if (anchor.isThreadReply()) {
            throw new IllegalArgumentException("Anchor message is a thread reply; open the thread instead");
        }
        var capped = Math.min(Math.max(radius, 1), DEFAULT_PAGE_SIZE);
        var page = PageRequest.of(0, capped);
        var beforeRows = messageRepository.findByChannelAndParentIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(
                channel, anchor.getCreatedAt(), page);
        var afterRows = messageRepository.findByChannelAndParentIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
                channel, anchor.getCreatedAt(), page);
        beforeRows.sort(Comparator.comparing(Message::getCreatedAt));
        var combined = new ArrayList<Message>(beforeRows.size() + 1 + afterRows.size());
        combined.addAll(beforeRows);
        combined.add(anchor);
        combined.addAll(afterRows);
        return combined;
    }

    @Transactional
    public Message pin(UUID messageId, User actor) {
        var message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        channelService.requireAdmin(message.getChannel(), actor);
        message.pin(actor);
        return message;
    }

    @Transactional
    public Message unpin(UUID messageId, User actor) {
        var message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        channelService.requireAdmin(message.getChannel(), actor);
        message.unpin();
        return message;
    }

    @Transactional(readOnly = true)
    public List<Message> pinned(Channel channel, User viewer) {
        channelService.requireMember(channel, viewer);
        return messageRepository.findByChannelAndPinnedAtIsNotNullOrderByPinnedAtDesc(channel);
    }

    @Transactional
    public Message replyInThread(UUID parentId, User author, String body) {
        var parent = messageRepository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + parentId));
        if (parent.isThreadReply()) {
            throw new IllegalArgumentException("Cannot reply to a thread reply — reply to its parent instead");
        }
        var channel = parent.getChannel();
        channelService.requireMember(channel, author);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (body.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
        var saved = messageRepository.save(new Message(channel, author, body.trim(), parent));
        mentionService.syncMentions(saved);
        indexNow(saved.getId(), channel.getId(), author.getUsername(), saved.getBodyMarkdown());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Message> threadReplies(UUID parentId, User viewer) {
        var parent = messageRepository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + parentId));
        channelService.requireMember(parent.getChannel(), viewer);
        return messageRepository.findByParentOrderByCreatedAtAsc(parent);
    }

    @Transactional(readOnly = true)
    public long threadReplyCount(Message parent) {
        return messageRepository.countByParent(parent);
    }

    /** Reply-count map for a batch of top-level messages — parents with 0 replies are absent. */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, Long> threadReplyCounts(java.util.Collection<Message> parents) {
        if (parents.isEmpty()) return java.util.Map.of();
        var ids = parents.stream().map(Message::getId).toList();
        var rows = messageRepository.countRepliesByParentIds(ids);
        var out = new java.util.HashMap<UUID, Long>(rows.size());
        for (var row : rows) {
            out.put((UUID) row[0], (Long) row[1]);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Message requireById(UUID id) {
        // Join-fetch author + channel so the controller can serialize the message and call
        // channelService.requireMember(...) after this transaction closes (open-in-view is off).
        return messageRepository.findByIdWithChannelAndAuthor(id)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + id));
    }

    /** Author-only edit. Updates body and bumps {@code editedAt}. */
    @Transactional
    public Message edit(UUID messageId, User actor, String newBody) {
        var message = messageRepository.findByIdWithAuthor(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        if (!message.getAuthor().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the author can edit this message.");
        }
        if (newBody == null || newBody.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (newBody.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
        var oldBody = message.getBodyMarkdown();
        message.setBodyMarkdown(newBody.trim());
        mentionService.syncMentions(message);
        var channelId = message.getChannel().getId();
        var newBodyTrimmed = message.getBodyMarkdown();
        // Index immediately so reads in the same transaction see the new body; on rollback,
        // restore the previous body so we don't strand a stale entry pointing to the new content.
        var authorUsername = message.getAuthor().getUsername();
        messageIndex.index(messageId, channelId, authorUsername, newBodyTrimmed);
        onRollback(() -> messageIndex.index(messageId, channelId, authorUsername, oldBody));
        return message;
    }

    /**
     * Delete a message. Allowed when {@code actor} is the author or a channel admin.
     * Removes any thread replies and attachment rows in dependency order so the
     * Hibernate session stays consistent (the DB also has on-delete-cascade FKs,
     * but cascaded rows that the session still holds would error on the next flush).
     * Orphaned files on disk are best-effort cleaned up after the DB delete commits.
     */
    @Transactional
    public DeletedMessage delete(UUID messageId, User actor) {
        var message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));

        var channel = message.getChannel();
        var isAuthor = message.getAuthor().getId().equals(actor.getId());
        if (!isAuthor && !channelService.isAdmin(channel, actor)) {
            throw new AccessDeniedException("Only the author or a channel admin can delete this message.");
        }

        var channelId = channel.getId();
        var parentId = message.getParent() == null ? null : message.getParent().getId();

        var fileKeys = new ArrayList<String>();
        var indexedIds = new ArrayList<UUID>();

        // Replies first — gather attachments + reactions, delete dependents, then the replies.
        var replies = messageRepository.findByParentOrderByCreatedAtAsc(message);
        for (var reply : replies) {
            indexedIds.add(reply.getId());
            var replyAttachments = attachmentRepository.findByMessageOrderByCreatedAtAsc(reply);
            replyAttachments.forEach(a -> fileKeys.add(a.getStorageKey()));
            attachmentRepository.deleteAll(replyAttachments);
            reactionRepository.deleteAll(reactionRepository.findByMessageOrderByCreatedAtAsc(reply));
            mentionRepository.deleteAllByMessage(reply);
        }
        messageRepository.deleteAll(replies);

        // Then this message's own attachments + reactions.
        var ownAttachments = attachmentRepository.findByMessageOrderByCreatedAtAsc(message);
        ownAttachments.forEach(a -> fileKeys.add(a.getStorageKey()));
        attachmentRepository.deleteAll(ownAttachments);
        reactionRepository.deleteAll(reactionRepository.findByMessageOrderByCreatedAtAsc(message));
        mentionRepository.deleteAllByMessage(message);

        indexedIds.add(messageId);
        messageRepository.delete(message);

        attachmentService.deleteFiles(fileKeys);

        // Snapshot bodies + authors for rollback restoration before mutating the index.
        record IndexSnapshot(UUID id, String author, String body) {}
        var snapshots = new java.util.ArrayList<IndexSnapshot>();
        snapshots.add(new IndexSnapshot(messageId, message.getAuthor().getUsername(), message.getBodyMarkdown()));
        for (var reply : replies) {
            snapshots.add(new IndexSnapshot(reply.getId(), reply.getAuthor().getUsername(), reply.getBodyMarkdown()));
        }
        messageIndex.deleteAll(indexedIds);
        onRollback(() -> {
            for (var snap : snapshots) {
                messageIndex.index(snap.id(), channelId, snap.author(), snap.body());
            }
        });

        return new DeletedMessage(messageId, channelId, parentId);
    }

    /**
     * Index the message synchronously, with a compensating delete if the surrounding
     * transaction rolls back. Outside a transaction (no synchronization active) the index
     * write is immediate and there's nothing to compensate.
     */
    private void indexNow(UUID messageId, UUID channelId, String author, String body) {
        messageIndex.index(messageId, channelId, author, body);
        onRollback(() -> messageIndex.delete(messageId));
    }

    /** Run a compensating action if the surrounding transaction rolls back; no-op outside a TX. */
    private static void onRollback(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        action.run();
                    }
                }
            });
        }
    }

    public record DeletedMessage(UUID id, UUID channelId, UUID parentId) {}
}
