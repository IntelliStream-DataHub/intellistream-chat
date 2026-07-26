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

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMember;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ConversationMemberRepository;
import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.ConversationRepository;
import ai.intellistream.chat.search.MessageIndexService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Private (off-channel) conversations: 1-to-1 DMs and named group DMs.
 * DIRECT conversations are deduplicated by a sorted-userId dm key so that
 * the same pair of users always reuses the same conversation row.
 */
@Service
public class ConversationService {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final ConversationMessageRepository messages;
    private final MessageIndexService messageIndex;

    public ConversationService(ConversationRepository conversations,
                               ConversationMemberRepository members,
                               ConversationMessageRepository messages,
                               MessageIndexService messageIndex) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.messageIndex = messageIndex;
    }

    /**
     * The DIRECT conversation between two users, created on first use.
     *
     * <p>{@code a == b} is allowed and gives a conversation with one member: a DM with yourself.
     * This used to throw, and the throw was the right guard for the UI it was written for (you do
     * not want a "message yourself" row appearing because someone clicked their own avatar) and the
     * wrong one for anything that needs to deliver something to a single person durably —
     * {@code /remind me} above all. Slack has exactly this conversation, for exactly that reason.
     *
     * <p>Callers that mean "start a chat with someone else" should still reject self themselves;
     * this method deliberately no longer decides that for them.
     */
    @Transactional
    public Conversation directBetween(User a, User b) {
        var key = directKey(a, b);
        // Insert-or-ignore the conversation, then ensure both memberships (N1). ON CONFLICT keeps
        // the tx usable when both peers open the DM at once — the loser reads the winner's row
        // instead of the old catch-and-reread re-querying an aborted transaction.
        conversations.insertDirectIgnore(key, a.getId());
        var conv = conversations.findByDmKey(key).orElseThrow();
        members.insertMemberIgnore(conv.getId(), a.getId());
        // Idempotent for the self case: the same (conversation, user) pair hits the unique
        // constraint and is ignored, so one member is what we end up with.
        members.insertMemberIgnore(conv.getId(), b.getId());
        return conv;
    }

    @Transactional
    public Conversation createGroup(String title, User creator, List<User> otherMembers) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Group title is required");
        }
        if (otherMembers == null || otherMembers.isEmpty()) {
            throw new IllegalArgumentException("Group must include at least one other user");
        }
        var conv = conversations.save(new Conversation(ConversationType.GROUP, title.trim(), null, creator));
        Set<Long> seen = new LinkedHashSet<>();
        seen.add(creator.getId());
        members.save(new ConversationMember(conv, creator));
        for (var u : otherMembers) {
            if (seen.add(u.getId())) {
                members.save(new ConversationMember(conv, u));
            }
        }
        return conv;
    }

    @Transactional
    public ConversationMember addToGroup(Conversation conversation, User invitee, User actor) {
        requireMember(conversation, actor);
        if (conversation.getType() != ConversationType.GROUP) {
            throw new IllegalArgumentException("Cannot add members to a direct conversation");
        }
        // Insert-or-ignore then read (N1): idempotent re-add, race-free, tx-safe. Fetch the user
        // eagerly so the controller can build the DTO after this @Transactional closes.
        members.insertMemberIgnore(conversation.getId(), invitee.getId());
        return members.findByConversationAndUserFetchingUser(conversation, invitee).orElseThrow();
    }

    @Transactional
    public ConversationMessage post(Conversation conversation, User author, String body) {
        requireMember(conversation, author);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (body.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
        var saved = messages.save(new ConversationMessage(conversation, author, body.trim()));
        indexAfterCommit(saved.getId(), conversation.getId(), author.getUsername(),
                saved.getBodyMarkdown());
        return saved;
    }

    @Transactional
    public ConversationMessage requireMessageById(Long id) {
        return messages.findByIdWithAuthor(id)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + id));
    }

    /** Edit own message body. Author-only; admins do not edit other users' DMs. */
    @Transactional
    public ConversationMessage editMessage(Long messageId, User actor, String newBody) {
        var message = requireMessageById(messageId);
        requireMember(message.getConversation(), actor);
        if (!message.getAuthor().getId().equals(actor.getId())) {
            throw new AccessDeniedException("You can only edit your own messages.");
        }
        if (newBody == null || newBody.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (newBody.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
        message.setBodyMarkdown(newBody.trim());
        indexAfterCommit(message.getId(), message.getConversation().getId(),
                message.getAuthor().getUsername(), message.getBodyMarkdown());
        return message;
    }

    /** Delete own message. Workspace admins can also delete anyone's DM (parity with channel delete). */
    @Transactional
    public ConversationMessage deleteMessage(Long messageId, User actor) {
        var message = requireMessageById(messageId);
        requireMember(message.getConversation(), actor);
        boolean isAuthor = message.getAuthor().getId().equals(actor.getId());
        if (!isAuthor && !actor.isAdmin()) {
            throw new AccessDeniedException("You can only delete your own messages.");
        }
        messages.delete(message);
        var doomedId = message.getId();
        afterCommit(() -> messageIndex.deleteConversationMessage(doomedId));
        return message;
    }

    /**
     * Push the Lucene write to after the commit, for the same reasons as the channel path
     * ({@code MessageService.indexNow}): an in-transaction index write would expose a body to
     * concurrent searchers before the row exists, and would survive a rollback. On the delete
     * side the ordering matters more than convenience — an index document that outlives its row
     * is content that stays searchable after the user removed it.
     */
    private void indexAfterCommit(Long messageId, Long conversationId, String author, String body) {
        afterCommit(() -> messageIndex.indexConversationMessage(messageId, conversationId, author, body));
    }

    /**
     * Run after a successful commit, or immediately when no transaction is active. Failures are
     * logged, not propagated: Spring stops dispatching synchronizations at the first thrower, and
     * a failed index write must not take out anything registered behind it. The startup reconcile
     * and the CLEAN-3 sweep are the backstop for whatever is lost here.
     */
    private static void afterCommit(Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Post-commit conversation index write failed; search may be stale for this "
                        + "message until the next reconcile", e);
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

    @Transactional(readOnly = true)
    public List<ConversationMessage> recent(Conversation conversation, User viewer, int limit) {
        requireMember(conversation, viewer);
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), DEFAULT_PAGE_SIZE));
        var rows = messages.findByConversationOrderByCreatedAtDesc(conversation, page);
        rows.sort(Comparator.comparing(ConversationMessage::getCreatedAt));
        return rows;
    }

    /** Forward page of messages with {@code createdAt > after}, oldest-first — the DM reconnect
     *  backfill (N4/BUG-3), mirroring MessageService.after for channels. */
    @Transactional(readOnly = true)
    public List<ConversationMessage> after(Conversation conversation, User viewer,
                                           java.time.Instant after, int limit) {
        requireMember(conversation, viewer);
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), DEFAULT_PAGE_SIZE));
        return messages.findByConversationAndCreatedAtAfterOrderByCreatedAtAsc(conversation, after, page);
    }

    @Transactional(readOnly = true)
    public List<ConversationMember> members(Conversation conversation) {
        return members.findAllByConversationOrderByJoinedAtAsc(conversation);
    }

    @Transactional(readOnly = true)
    public List<Conversation> listForUser(User user) {
        return members.findConversationsForUser(user);
    }

    @Transactional(readOnly = true)
    public Conversation requireById(Long id) {
        return conversations.findById(id)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Conversation not found: " + id));
    }

    @Transactional(readOnly = true)
    public boolean isMember(Conversation conversation, User user) {
        return members.existsByConversationAndUser(conversation, user);
    }

    public void requireMember(Conversation conversation, User user) {
        if (!isMember(conversation, user)) {
            throw new AccessDeniedException("Not a participant in this conversation.");
        }
    }

    /**
     * Stamp {@code last_read_at = now()} on the viewer's membership row. Quietly no-op
     * for non-members so this is safe to call from the page-render path without an
     * extra membership pre-check.
     */
    @Transactional
    public void markRead(Conversation conversation, User viewer) {
        members.findByConversationAndUser(conversation, viewer)
                .ifPresent(m -> m.markRead(Instant.now()));
    }

    /** {@code conversationId -> count of messages from someone else after viewer's last_read_at.} */
    @Transactional(readOnly = true)
    public Map<Long, Long> unreadCounts(User viewer, java.util.Collection<Long> convIds) {
        if (convIds == null || convIds.isEmpty()) return Map.of();
        var rows = members.countUnreadPerConversation(viewer.getId(), convIds);
        var out = new HashMap<Long, Long>(rows.size());
        for (var row : rows) {
            out.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    /**
     * Sorted user ids, so {@code (a,b)} and {@code (b,a)} collide on purpose and one row serves
     * both directions.
     *
     * <p>A self-conversation lands on {@code "7:7"}, which is stable and cannot collide with any
     * two-person key: those always hold two <em>distinct</em> ids. {@code Conversation.isSelfDirect}
     * reads the same shape back, which is how the DTO layer knows to label it "You" without being
     * told who is looking.
     */
    private static String directKey(User a, User b) {
        return java.util.stream.Stream.of(a.getId(), b.getId())
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(":"));
    }
}
