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
    /**
     * Absent in the service-and-repository-only integration context, which has no broker at all —
     * hence the provider and the {@code ifAvailable}. Same arrangement {@code ChannelService} uses
     * for its own revoker, and for the same reason.
     */
    private final org.springframework.beans.factory.ObjectProvider<ConversationSubscriptionRevoker>
            subscriptionRevoker;

    public ConversationService(ConversationRepository conversations,
                               ConversationMemberRepository members,
                               ConversationMessageRepository messages,
                               MessageIndexService messageIndex,
                               org.springframework.beans.factory.ObjectProvider<ConversationSubscriptionRevoker>
                                       subscriptionRevoker) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.messageIndex = messageIndex;
        this.subscriptionRevoker = subscriptionRevoker;
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

    private static void validateBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Message body cannot be empty");
        }
        if (body.length() > 8000) {
            throw new IllegalArgumentException("Message body too long (max 8000 chars)");
        }
    }

    /**
     * Leave a group conversation.
     *
     * <p><b>A DIRECT conversation cannot be left, and that is not an omission.</b> Slack draws the
     * same line: a 1:1 is closed, not left. There is nothing to leave — the conversation *is* the
     * pair, and a "DM with alice" that alice is not in is not a thing the rest of the code could
     * describe. Nor would it be reversible in any useful sense: messaging that person again would
     * resolve the same {@code dm_key} and put you straight back, so "leave" would mean "hide until
     * the next message", which is a different feature (closing a DM) wearing this one's name. A
     * self-conversation is refused by the same rule and for the same reason, with the added point
     * that leaving it would strand every future {@code /remind me} with nowhere to deliver.
     *
     * <p><b>The messages stay.</b> You are leaving, not deleting, and the people still in the group
     * were part of the conversation you are removing yourself from. Their copy of it does not become
     * less true because you left.
     *
     * <p><b>The last member may leave</b>, and the conversation is left holding its messages with
     * nobody in it — the same outcome {@code ChannelService} settled on for an emptied channel. The
     * alternative is trapping the last person in a group everybody else has already abandoned,
     * which is the exact failure this exists to fix. Unlike a channel there is no way back in
     * (nobody remains to add anyone), so the row is inert; it stays because deleting it would
     * destroy other people's history the moment the last of them stopped reading it, and because
     * {@code conversation_messages} is what search and the file manager index against.
     *
     * <p>Read markers and the notification level go with the membership row, because they are
     * facts about a membership. Re-added later, you start fresh — which is right: you were not
     * there for what happened in between.
     */
    @Transactional
    public void leave(Conversation conversation, User user) {
        if (conversation.getType() != ConversationType.GROUP) {
            throw new ai.intellistream.chat.security.PublicBadRequestException(
                    "A direct message can't be left — close it instead.");
        }
        var membership = members.findByConversationAndUser(conversation, user)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException(
                        "Not a participant in this conversation."));
        members.delete(membership);

        var conversationId = conversation.getId();
        var userId = user.getId();
        // The membership row is gone, which stops the ex-member subscribing again. It does nothing
        // at all about the subscription they already hold: the broker authorises SUBSCRIBE once and
        // never re-checks, so without this an open socket keeps receiving a private conversation's
        // messages until it happens to reconnect. Nothing in a page-reload test can see that,
        // because reloading is the thing that fixes it.
        afterCommit(() -> subscriptionRevoker.ifAvailable(r -> r.revoke(conversationId, userId)));
    }

    @Transactional
    public ConversationMessage post(Conversation conversation, User author, String body) {
        requireMember(conversation, author);
        validateBody(body);
        var saved = messages.save(new ConversationMessage(conversation, author, body.trim()));
        // [attachment-filename search] No filenames: the row was created a line ago, so nothing can
        // be attached yet. ConversationAttachmentService re-indexes once its row exists.
        indexAfterCommit(saved.getId(), conversation.getId(), author.getUsername(),
                saved.getBodyMarkdown(), List.of());
        return saved;
    }

    @Transactional
    public ConversationMessage requireMessageById(Long id) {
        return messages.findByIdWithAuthor(id)
                .orElseThrow(() -> new ai.intellistream.chat.security.ResourceNotFoundException("Message not found: " + id));
    }

    // ------------------------------------------------------------------ threads

    /**
     * Reply in {@code parentId}'s thread. Mirrors {@code MessageService.replyInThread}, including
     * the one rule that matters: a reply may not be replied to.
     *
     * <p>Threads are one level deep because that is what makes a thread readable — a tree turns
     * "what did people say about this" into a navigation problem, and both Slack and Mattermost
     * settled on the same shape. It is also what lets the reply count be one {@code count(*)} and
     * the panel a flat list.
     *
     * <p>Membership is checked against the <em>parent's</em> conversation, never against a
     * conversation id the caller supplied: the reply endpoint is keyed on the message, so taking the
     * conversation from anywhere else would let a member of conversation A reply into conversation B
     * by naming one of B's message ids.
     */
    @Transactional
    public ConversationMessage replyInThread(Long parentId, User author, String body) {
        var parent = requireMessageById(parentId);
        if (parent.isThreadReply()) {
            throw new IllegalArgumentException(
                    "Cannot reply to a thread reply — reply to its parent instead");
        }
        var conversation = parent.getConversation();
        requireMember(conversation, author);
        validateBody(body);
        var saved = messages.save(new ConversationMessage(conversation, author, body.trim(), parent));
        indexAfterCommit(saved.getId(), conversation.getId(), author.getUsername(),
                saved.getBodyMarkdown());
        return saved;
    }

    /** A thread's replies, oldest first. Read access is the conversation's membership, as ever. */
    @Transactional(readOnly = true)
    public List<ConversationMessage> threadReplies(Long parentId, User viewer) {
        var parent = requireMessageById(parentId);
        requireMember(parent.getConversation(), viewer);
        return messages.findByParentOrderByCreatedAtAsc(parent);
    }

    @Transactional(readOnly = true)
    public long threadReplyCount(ConversationMessage parent) {
        return messages.countByParent(parent);
    }

    /** Reply-count map for a batch of top-level messages — parents with 0 replies are absent. */
    @Transactional(readOnly = true)
    public Map<Long, Long> threadReplyCounts(java.util.Collection<ConversationMessage> parents) {
        if (parents == null || parents.isEmpty()) return Map.of();
        var ids = parents.stream().map(ConversationMessage::getId).toList();
        var rows = messages.countRepliesByParentIds(ids);
        var out = new HashMap<Long, Long>(rows.size());
        for (var row : rows) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    /**
     * The usernames to tell about a reply in {@code parentId}'s thread: everyone who has written in
     * it — the parent's author plus every replier — except {@code excluding}, narrowed to people who
     * are still members of the conversation.
     *
     * <p>The narrowing is not theoretical now that a group conversation can be left: a thread can
     * hold messages from someone who has since gone, and the participant list rides on a broadcast
     * the client acts on.
     */
    @Transactional(readOnly = true)
    public List<String> threadParticipants(Long parentId, User excluding) {
        var parent = requireMessageById(parentId);
        var rows = messages.findThreadParticipants(parentId);
        if (rows.isEmpty()) return List.of();
        var byId = new java.util.LinkedHashMap<Long, String>(rows.size());
        for (var row : rows) {
            var id = ((Number) row[0]).longValue();
            if (excluding != null && id == excluding.getId().longValue()) continue;
            byId.put(id, (String) row[1]);
        }
        if (byId.isEmpty()) return List.of();
        var stillMembers = members.findAllByConversationOrderByJoinedAtAsc(parent.getConversation())
                .stream().map(m -> m.getUser().getId()).collect(Collectors.toSet());
        return byId.entrySet().stream()
                .filter(e -> stillMembers.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** Ids of a message's thread replies — read before a delete so their files and index docs go too. */
    @Transactional(readOnly = true)
    public List<Long> replyIdsOf(Long parentId) {
        return messages.findReplyIds(parentId);
    }

    /** Edit own message body. Author-only; admins do not edit other users' DMs. */
    @Transactional
    public ConversationMessage editMessage(Long messageId, User actor, String newBody) {
        var message = requireMessageById(messageId);
        requireMember(message.getConversation(), actor);
        if (!message.getAuthor().getId().equals(actor.getId())) {
            throw new AccessDeniedException("You can only edit your own messages.");
        }
        validateBody(newBody);
        message.setBodyMarkdown(newBody.trim());
        // [attachment-filename search] The document is rewritten whole, so the live filenames have
        // to be re-read here — otherwise editing a caption un-finds the file it was captioning.
        indexAfterCommit(message.getId(), message.getConversation().getId(),
                message.getAuthor().getUsername(), message.getBodyMarkdown(),
                messages.findIndexFilenames(message.getId()));
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
        // Replies go with the parent. The FK cascades the rows; the index does not cascade, so the
        // ids are collected here — while they still exist — and their documents dropped after the
        // commit. An index entry that outlives its row is content that stays searchable after
        // somebody removed it, which is the one failure this ordering exists to prevent.
        var doomed = new java.util.ArrayList<Long>();
        doomed.add(message.getId());
        if (!message.isThreadReply()) {
            // Removed through the repository rather than left to the FK cascade: a row the database
            // deletes behind Hibernate's back is a row the session still believes in, and it errors
            // on the next flush. Same reason MessageService.delete walks its replies first.
            var replies = messages.findByParentOrderByCreatedAtAsc(message);
            replies.forEach(r -> doomed.add(r.getId()));
            messages.deleteAll(replies);
        }
        messages.delete(message);
        var doomedSnapshot = List.copyOf(doomed);
        afterCommit(() -> doomedSnapshot.forEach(messageIndex::deleteConversationMessage));
        return message;
    }

    /**
     * Push the Lucene write to after the commit, for the same reasons as the channel path
     * ({@code MessageService.indexNow}): an in-transaction index write would expose a body to
     * concurrent searchers before the row exists, and would survive a rollback. On the delete
     * side the ordering matters more than convenience — an index document that outlives its row
     * is content that stays searchable after the user removed it.
     */
    private void indexAfterCommit(Long messageId, Long conversationId, String author, String body,
                                  List<String> filenames) {
        afterCommit(() -> messageIndex.indexConversationMessage(messageId, conversationId, author,
                body, filenames));
    }

    /**
     * [attachment-filename search] Rewrite this message's index document because its attachment set
     * changed — a file was uploaded onto it, or one of its files was tombstoned in the file manager.
     *
     * <p>The mirror of {@code MessageService.reindexAfterAttachmentChange}, and here for the same
     * reason: an attachment is created after the message it hangs on, so the filename is not known
     * when the document is first written. Callers are
     * {@code ConversationAttachmentService.upload} and {@code UserFileService}, both of which own
     * the change and neither of which holds the index.
     */
    public void reindexAfterAttachmentChange(ConversationMessage message) {
        indexAfterCommit(message.getId(), message.getConversation().getId(),
                message.getAuthor().getUsername(), message.getBodyMarkdown(),
                messages.findIndexFilenames(message.getId()));
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

    /**
     * {@code conversationId -> the viewer's raw notification level}, for every conversation they
     * are in. Raw: {@code DEFAULT} means the row follows the account default and must keep saying
     * so, or the sidebar would freeze each row at whatever the default happened to be.
     */
    @Transactional(readOnly = true)
    public Map<Long, ai.intellistream.chat.domain.NotificationLevel> notifyLevelsFor(User viewer) {
        var rows = members.findNotifyLevelsForUser(viewer);
        var out = new HashMap<Long, ai.intellistream.chat.domain.NotificationLevel>(rows.size());
        for (var row : rows) {
            out.put((Long) row[0], (ai.intellistream.chat.domain.NotificationLevel) row[1]);
        }
        return out;
    }

    /**
     * Where {@code viewer}'s read marker stands in this conversation, or {@code null} if they have
     * never read it (or are not a member).
     *
     * <p>Read <b>before</b> {@link #markRead} on the page-render path, because that call is about to
     * move it: the "new messages" divider needs the position the reader left off at, and after the
     * stamp there is nothing left to draw it from.
     */
    @Transactional(readOnly = true)
    public Instant lastReadAt(Conversation conversation, User viewer) {
        return members.findByConversationAndUser(conversation, viewer)
                .map(ConversationMember::getLastReadAt)
                .orElse(null);
    }

    /**
     * {@code conversationId -> count of messages from someone else after viewer's last_read_at.}
     *
     * <p>Thread replies are conversation messages and are counted, which is the channel side's
     * semantic since replies started counting toward a channel's unread. A reply is a message in
     * the room; that it is filed under another one does not make it something you have read.
     */
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
