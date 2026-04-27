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

package ai.intellistream.radiance.service;

import ai.intellistream.radiance.domain.Conversation;
import ai.intellistream.radiance.domain.ConversationMember;
import ai.intellistream.radiance.domain.ConversationMessage;
import ai.intellistream.radiance.domain.ConversationType;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.ConversationMemberRepository;
import ai.intellistream.radiance.repository.ConversationMessageRepository;
import ai.intellistream.radiance.repository.ConversationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Private (off-channel) conversations: 1-to-1 DMs and named group DMs.
 * DIRECT conversations are deduplicated by a sorted-userId dm key so that
 * the same pair of users always reuses the same conversation row.
 */
@Service
public class ConversationService {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final ConversationMessageRepository messages;

    public ConversationService(ConversationRepository conversations,
                               ConversationMemberRepository members,
                               ConversationMessageRepository messages) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
    }

    @Transactional
    public Conversation directBetween(User a, User b) {
        if (a.getId().equals(b.getId())) {
            throw new IllegalArgumentException("Cannot start a direct conversation with yourself");
        }
        var key = directKey(a, b);
        var existing = conversations.findByDmKey(key);
        if (existing.isPresent()) return existing.get();
        try {
            var conv = conversations.save(new Conversation(ConversationType.DIRECT, null, key, a));
            members.save(new ConversationMember(conv, a));
            members.save(new ConversationMember(conv, b));
            return conv;
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Both peers opened the DM at once; the unique constraint on dm_key rejected
            // one. The winner's row is in the DB now — return it and let this caller share.
            return conversations.findByDmKey(key).orElseThrow(() -> race);
        }
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
        Set<UUID> seen = new LinkedHashSet<>();
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
        return members.findByConversationAndUser(conversation, invitee)
                .orElseGet(() -> members.save(new ConversationMember(conversation, invitee)));
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
        return messages.save(new ConversationMessage(conversation, author, body.trim()));
    }

    @Transactional
    public ConversationMessage requireMessageById(UUID id) {
        return messages.findByIdWithAuthor(id)
                .orElseThrow(() -> new ai.intellistream.radiance.security.ResourceNotFoundException("Message not found: " + id));
    }

    /** Edit own message body. Author-only; admins do not edit other users' DMs. */
    @Transactional
    public ConversationMessage editMessage(UUID messageId, User actor, String newBody) {
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
        return message;
    }

    /** Delete own message. Workspace admins can also delete anyone's DM (parity with channel delete). */
    @Transactional
    public ConversationMessage deleteMessage(UUID messageId, User actor) {
        var message = requireMessageById(messageId);
        requireMember(message.getConversation(), actor);
        boolean isAuthor = message.getAuthor().getId().equals(actor.getId());
        if (!isAuthor && !actor.isAdmin()) {
            throw new AccessDeniedException("You can only delete your own messages.");
        }
        messages.delete(message);
        return message;
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> recent(Conversation conversation, User viewer, int limit) {
        requireMember(conversation, viewer);
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), DEFAULT_PAGE_SIZE));
        var rows = messages.findByConversationOrderByCreatedAtDesc(conversation, page);
        rows.sort(Comparator.comparing(ConversationMessage::getCreatedAt));
        return rows;
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
    public Conversation requireById(UUID id) {
        return conversations.findById(id)
                .orElseThrow(() -> new ai.intellistream.radiance.security.ResourceNotFoundException("Conversation not found: " + id));
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
    public Map<UUID, Long> unreadCounts(User viewer, java.util.Collection<UUID> convIds) {
        if (convIds == null || convIds.isEmpty()) return Map.of();
        var rows = members.countUnreadPerConversation(viewer.getId(), convIds);
        var out = new HashMap<UUID, Long>(rows.size());
        for (var row : rows) {
            out.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    private static String directKey(User a, User b) {
        return java.util.stream.Stream.of(a.getId(), b.getId())
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.joining(":"));
    }
}
