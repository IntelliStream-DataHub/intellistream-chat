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

package ai.intellistream.threadorbit.service;

import ai.intellistream.threadorbit.domain.Message;
import ai.intellistream.threadorbit.domain.MessageReaction;
import ai.intellistream.threadorbit.domain.User;
import ai.intellistream.threadorbit.repository.MessageReactionRepository;
import ai.intellistream.threadorbit.repository.MessageRepository;
import ai.intellistream.threadorbit.web.dto.ReactionGroupDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-message emoji reactions. Authorization mirrors message read/post —
 * channel members (or anyone authenticated for PUBLIC channels) can react.
 */
@Service
public class ReactionService {

    private static final int MAX_EMOJI_LEN = 64;

    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final ChannelService channelService;

    public ReactionService(MessageRepository messageRepository,
                           MessageReactionRepository reactionRepository,
                           ChannelService channelService) {
        this.messageRepository = messageRepository;
        this.reactionRepository = reactionRepository;
        this.channelService = channelService;
    }

    @Transactional
    public Message addReaction(Long messageId, User actor, String emoji) {
        var message = requireMessage(messageId);
        channelService.requireWriteAccess(message.getChannel(), actor);
        // Authors can't react to their own messages — matches Slack/Mattermost.
        if (message.getAuthor() != null && actor.getId().equals(message.getAuthor().getId())) {
            throw new AccessDeniedException("You cannot react to your own message.");
        }
        var trimmed = sanitize(emoji);
        // Insert-or-ignore (N1): idempotent on a concurrent identical reaction, and ON CONFLICT
        // keeps the transaction usable (the old saveAndFlush + catch aborted the tx on Postgres).
        reactionRepository.insertReactionIgnore(message.getId(), actor.getId(), trimmed);
        return message;
    }

    @Transactional
    public Message removeReaction(Long messageId, User actor, String emoji) {
        var message = requireMessage(messageId);
        channelService.requireWriteAccess(message.getChannel(), actor);
        var trimmed = sanitize(emoji);
        reactionRepository.deleteByMessageAndUserAndEmoji(message, actor, trimmed);
        return message;
    }

    @Transactional(readOnly = true)
    public List<ReactionGroupDto> groupingsFor(Message message, User viewer) {
        return collapse(reactionRepository.findByMessageOrderByCreatedAtAsc(message), viewer);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<ReactionGroupDto>> groupingsFor(Collection<Message> messages, User viewer) {
        if (messages.isEmpty()) return Map.of();
        var rows = reactionRepository.findByMessageInOrderByCreatedAtAsc(messages);
        var byMsg = new LinkedHashMap<Long, List<MessageReaction>>();
        for (var r : rows) {
            byMsg.computeIfAbsent(r.getMessage().getId(), k -> new ArrayList<>()).add(r);
        }
        var out = new HashMap<Long, List<ReactionGroupDto>>();
        for (var entry : byMsg.entrySet()) {
            out.put(entry.getKey(), collapse(entry.getValue(), viewer));
        }
        return out;
    }

    private static List<ReactionGroupDto> collapse(List<MessageReaction> rows, User viewer) {
        // Preserve first-seen order so the UI is stable across reloads.
        var grouped = new LinkedHashMap<String, List<MessageReaction>>();
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

    private Message requireMessage(Long messageId) {
        // Join-fetch author + channel so the controller can hand the returned Message to
        // MessageDto.from(...) after this @Transactional closes — open-in-view is off, so
        // a bare findById leaves both as lazy proxies that LazyInitialize when the DTO is
        // built, breaking the broadcast.
        return messageRepository.findByIdWithChannelAndAuthor(messageId)
                .orElseThrow(() -> new ai.intellistream.threadorbit.security.ResourceNotFoundException("Message not found: " + messageId));
    }

    private static String sanitize(String emoji) {
        if (emoji == null) throw new IllegalArgumentException("Emoji required");
        var trimmed = emoji.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Emoji required");
        if (trimmed.length() > MAX_EMOJI_LEN) {
            throw new IllegalArgumentException("Emoji too long (max " + MAX_EMOJI_LEN + " chars)");
        }
        // Reject control chars; allow anything else (covers unicode emoji + custom shortcodes like :smile:).
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("Emoji contains control characters");
            }
        }
        return trimmed;
    }
}
