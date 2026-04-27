package com.example.chat.service;

import com.example.chat.domain.ConversationMessage;
import com.example.chat.domain.ConversationReaction;
import com.example.chat.domain.User;
import com.example.chat.repository.ConversationMessageRepository;
import com.example.chat.repository.ConversationReactionRepository;
import com.example.chat.web.dto.ReactionGroupDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-message emoji reactions for direct/group conversations. Mirrors
 * {@link ReactionService} for channel messages but scoped to ConversationMessage and
 * gated on conversation membership.
 */
@Service
public class ConversationReactionService {

    private static final int MAX_EMOJI_LEN = 64;

    private final ConversationMessageRepository messageRepository;
    private final ConversationReactionRepository reactionRepository;
    private final ConversationService conversationService;

    public ConversationReactionService(ConversationMessageRepository messageRepository,
                                       ConversationReactionRepository reactionRepository,
                                       ConversationService conversationService) {
        this.messageRepository = messageRepository;
        this.reactionRepository = reactionRepository;
        this.conversationService = conversationService;
    }

    @Transactional
    public ConversationMessage addReaction(UUID messageId, User actor, String emoji) {
        var message = requireMessage(messageId);
        conversationService.requireMember(message.getConversation(), actor);
        if (message.getAuthor() != null && actor.getId().equals(message.getAuthor().getId())) {
            throw new AccessDeniedException("You cannot react to your own message.");
        }
        var trimmed = sanitize(emoji);
        reactionRepository.findByMessageAndUserAndEmoji(message, actor, trimmed)
                .orElseGet(() -> reactionRepository.save(new ConversationReaction(message, actor, trimmed)));
        return message;
    }

    @Transactional
    public ConversationMessage removeReaction(UUID messageId, User actor, String emoji) {
        var message = requireMessage(messageId);
        conversationService.requireMember(message.getConversation(), actor);
        var trimmed = sanitize(emoji);
        reactionRepository.deleteByMessageAndUserAndEmoji(message, actor, trimmed);
        return message;
    }

    @Transactional(readOnly = true)
    public List<ReactionGroupDto> groupingsFor(ConversationMessage message, User viewer) {
        return collapse(reactionRepository.findByMessageOrderByCreatedAtAsc(message), viewer);
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<ReactionGroupDto>> groupingsFor(Collection<ConversationMessage> messages, User viewer) {
        if (messages.isEmpty()) return Map.of();
        var rows = reactionRepository.findByMessageInOrderByCreatedAtAsc(messages);
        var byMsg = new LinkedHashMap<UUID, List<ConversationReaction>>();
        for (var r : rows) {
            byMsg.computeIfAbsent(r.getMessage().getId(), k -> new ArrayList<>()).add(r);
        }
        var out = new HashMap<UUID, List<ReactionGroupDto>>();
        for (var entry : byMsg.entrySet()) {
            out.put(entry.getKey(), collapse(entry.getValue(), viewer));
        }
        return out;
    }

    private static List<ReactionGroupDto> collapse(List<ConversationReaction> rows, User viewer) {
        var grouped = new LinkedHashMap<String, List<ConversationReaction>>();
        for (var r : rows) {
            grouped.computeIfAbsent(r.getEmoji(), k -> new ArrayList<>()).add(r);
        }
        var out = new ArrayList<ReactionGroupDto>(grouped.size());
        for (var e : grouped.entrySet()) {
            var emoji = e.getKey();
            var entries = e.getValue();
            var usernames = new ArrayList<String>(entries.size());
            boolean mine = false;
            for (var r : entries) {
                usernames.add(r.getUser().getUsername());
                if (viewer != null && r.getUser().getId().equals(viewer.getId())) mine = true;
            }
            out.add(new ReactionGroupDto(emoji, entries.size(), mine, List.copyOf(usernames)));
        }
        return out;
    }

    private ConversationMessage requireMessage(UUID messageId) {
        return messageRepository.findByIdWithAuthor(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
    }

    private static String sanitize(String emoji) {
        if (emoji == null) throw new IllegalArgumentException("Emoji required");
        var trimmed = emoji.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Emoji required");
        if (trimmed.length() > MAX_EMOJI_LEN) {
            throw new IllegalArgumentException("Emoji too long (max " + MAX_EMOJI_LEN + " chars)");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("Emoji contains control characters");
            }
        }
        return trimmed;
    }
}
