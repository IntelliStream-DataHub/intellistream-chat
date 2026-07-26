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

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.MessageSave;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ConversationMemberRepository;
import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.repository.MessageSaveRepository;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.web.dto.SavedMessageDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Saved messages — the private, per-user, cross-room queue of things to come back to.
 *
 * <p><b>Saving is a read, not a write.</b> The check to save a channel message is
 * {@code requireMember}, the read tier, so you can save from a public channel you have not joined
 * and from an archived one you can still read. Nothing about a save is visible in the room: no row
 * in the channel changes, nobody is notified, and the message's author cannot tell. Gating it on
 * write access would mean joining a channel in order to bookmark something in it, which is the
 * opposite of what a reading list is for.
 *
 * <p><b>Access is re-checked on the way out, not remembered from the way in.</b> A save outlives
 * the access that created it — you leave a private channel, or are removed from one, and the row is
 * still there. {@link #listFor} therefore recomputes readability for every row it returns rather
 * than trusting that the save exists. Rows that fail are returned with their content withheld, so
 * the owner can still see and clear them; see {@link SavedMessageDto}.
 */
@Service
public class SavedMessageService {

    /** A page of the saved list. Generous — this is a reading queue, not a feed. */
    public static final int MAX_PAGE_SIZE = 100;

    private final MessageSaveRepository saves;
    private final MessageRepository messages;
    private final ConversationMessageRepository conversationMessages;
    private final ConversationMemberRepository conversationMembers;
    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final MarkdownRenderer markdown;

    public SavedMessageService(MessageSaveRepository saves,
                               MessageRepository messages,
                               ConversationMessageRepository conversationMessages,
                               ConversationMemberRepository conversationMembers,
                               ChannelService channelService,
                               ConversationService conversationService,
                               MarkdownRenderer markdown) {
        this.saves = saves;
        this.messages = messages;
        this.conversationMessages = conversationMessages;
        this.conversationMembers = conversationMembers;
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.markdown = markdown;
    }

    // ------------------------------------------------------------------ save / unsave

    @Transactional
    public void saveChannelMessage(Long messageId, User actor) {
        var message = messages.findByIdWithChannelAndAuthor(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + messageId));
        channelService.requireMember(message.getChannel(), actor);
        saves.insertMessageSaveIgnore(actor.getId(), message.getId());
    }

    /**
     * Unsaving deliberately performs <b>no</b> access check. It is a delete of the caller's own row,
     * and the one case where it matters most is precisely the one where the check would fail: a
     * message saved from a channel you have since left is exactly the entry somebody wants to clear.
     * Refusing to let them tidy their own list because they can no longer read the thing it points
     * at would be an access rule protecting nobody from anything.
     */
    @Transactional
    public void unsaveChannelMessage(Long messageId, User actor) {
        saves.deleteMessageSave(actor, messageId);
    }

    @Transactional
    public void saveConversationMessage(Long messageId, User actor) {
        var message = conversationMessages.findByIdWithAuthor(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + messageId));
        conversationService.requireMember(message.getConversation(), actor);
        saves.insertConversationSaveIgnore(actor.getId(), message.getId());
    }

    /** See {@link #unsaveChannelMessage} for why this has no access check either. */
    @Transactional
    public void unsaveConversationMessage(Long messageId, User actor) {
        saves.deleteConversationSave(actor, messageId);
    }

    @Transactional(readOnly = true)
    public boolean hasSavedChannelMessage(User user, Long messageId) {
        return saves.existsByUserAndMessageId(user, messageId);
    }

    @Transactional(readOnly = true)
    public boolean hasSavedConversationMessage(User user, Long messageId) {
        return saves.existsByUserAndConversationMessageId(user, messageId);
    }

    @Transactional(readOnly = true)
    public long countFor(User user) {
        return saves.countByUser(user);
    }

    /** The viewer's saved message ids in one channel — what the channel page marks its rows with. */
    @Transactional(readOnly = true)
    public List<Long> savedIdsInChannel(User user, Long channelId) {
        return saves.findSavedMessageIdsInChannel(user, channelId);
    }

    /**
     * The viewer's saved message ids in one conversation — the DM page's counterpart, and the last
     * piece that was missing between a saved DM being supported server-side and being reachable.
     *
     * <p>No membership check, deliberately: this returns only ids the caller already saved, so it
     * can tell them nothing they did not already know. Reading the messages behind them still goes
     * through the ordinary conversation checks.
     */
    @Transactional(readOnly = true)
    public List<Long> savedIdsInConversation(User user, Long conversationId) {
        return saves.findSavedMessageIdsInConversation(user, conversationId);
    }

    /** Which of these channel-message ids the viewer has saved. One query for a whole page. */
    @Transactional(readOnly = true)
    public Set<Long> savedIdsAmong(User user, Collection<Long> messageIds) {
        if (messageIds.isEmpty()) return Set.of();
        return new HashSet<>(saves.findSavedMessageIdsAmong(user, messageIds));
    }

    // ------------------------------------------------------------------ the list

    /**
     * One page of the viewer's saved items, newest save first.
     *
     * <p>Three bulk lookups, not one per row: the page itself, the viewer's current channel
     * memberships, and their current conversation memberships. Everything else is decided in
     * memory from those.
     */
    @Transactional(readOnly = true)
    public List<SavedMessageDto> listFor(User viewer, int page, int size) {
        var capped = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var rows = saves.findPageForUser(viewer, PageRequest.of(Math.max(page, 0), capped));
        if (rows.isEmpty()) {
            return List.of();
        }

        var joinedChannelIds = new HashSet<Long>();
        for (var c : channelService.listForUser(viewer)) {
            joinedChannelIds.add(c.getId());
        }
        var memberConversationIds = new HashSet<Long>();
        for (var c : conversationService.listForUser(viewer)) {
            memberConversationIds.add(c.getId());
        }

        var directLabels = directConversationLabels(viewer, rows, memberConversationIds);

        var out = new ArrayList<SavedMessageDto>(rows.size());
        for (var save : rows) {
            out.add(save.isChannelMessage()
                    ? channelRow(save, joinedChannelIds)
                    : conversationRow(save, memberConversationIds, directLabels));
        }
        return out;
    }

    private SavedMessageDto channelRow(MessageSave save, Set<Long> joinedChannelIds) {
        var message = save.getMessage();
        var channel = message.getChannel();
        // The same rule requireMember applies, decided from the sets already loaded: a PUBLIC
        // channel is readable by anyone, a PRIVATE one only by a current member.
        var readable = channel.getType() == ChannelType.PUBLIC
                || joinedChannelIds.contains(channel.getId());
        if (!readable) {
            return SavedMessageDto.unreadableChannelSave(save.getId(), save.getCreatedAt(),
                    message.getId(), channel.getId(), channel.getName());
        }
        var author = message.getAuthor();
        return new SavedMessageDto(
                save.getId(),
                save.getCreatedAt(),
                "channel",
                message.getId(),
                channel.getId(),
                channel.getName(),
                channel.isArchived(),
                channel.getType() == ChannelType.PRIVATE,
                null,
                null,
                "/channels/" + channel.getId() + "?m=" + message.getId() + "#m=" + message.getId(),
                author.getUsername(),
                author.getDisplayName(),
                message.getCreatedAt(),
                message.getEditedAt(),
                markdown.render(message.getBodyMarkdown()),
                true);
    }

    private SavedMessageDto conversationRow(MessageSave save, Set<Long> memberConversationIds,
                                            java.util.Map<Long, String> directLabels) {
        var message = save.getConversationMessage();
        var conversation = message.getConversation();
        if (!memberConversationIds.contains(conversation.getId())) {
            return SavedMessageDto.unreadableConversationSave(save.getId(), save.getCreatedAt(),
                    message.getId(), conversation.getId());
        }
        var author = message.getAuthor();
        return new SavedMessageDto(
                save.getId(),
                save.getCreatedAt(),
                "conversation",
                message.getId(),
                null,
                null,
                false,
                false,
                conversation.getId(),
                labelFor(conversation, directLabels),
                "/conversations/" + conversation.getId() + "#m=" + message.getId(),
                author.getUsername(),
                author.getDisplayName(),
                message.getCreatedAt(),
                message.getEditedAt(),
                markdown.render(message.getBodyMarkdown()),
                true);
    }

    private static String labelFor(Conversation conversation, java.util.Map<Long, String> directLabels) {
        if (conversation.getType() == ConversationType.GROUP) {
            return conversation.getTitle();
        }
        return directLabels.get(conversation.getId());
    }

    /**
     * Names for the DIRECT conversations on this page: a DM has no title, it is identified by who
     * is on the other end. Only conversations the viewer is still in are asked about — this must
     * not become a general lookup, because with arbitrary ids it would happily name the
     * participants of a stranger's DM.
     */
    private java.util.Map<Long, String> directConversationLabels(
            User viewer, List<MessageSave> rows, Set<Long> memberConversationIds) {
        var directIds = new ArrayList<Long>();
        for (var save : rows) {
            if (save.isChannelMessage()) continue;
            var conversation = save.getConversationMessage().getConversation();
            if (conversation.getType() == ConversationType.DIRECT
                    && memberConversationIds.contains(conversation.getId())) {
                directIds.add(conversation.getId());
            }
        }
        if (directIds.isEmpty()) {
            return java.util.Map.of();
        }
        var labels = new HashMap<Long, String>();
        for (var row : conversationMembers.findCounterparts(directIds, viewer.getId())) {
            var conversationId = (Long) row[0];
            var username = (String) row[1];
            var displayName = (String) row[2];
            labels.putIfAbsent(conversationId,
                    displayName == null || displayName.isBlank() ? username : displayName);
        }
        // A DM with yourself has no counterpart at all, so nothing above filled it in.
        for (var id : directIds) {
            labels.putIfAbsent(id, ai.intellistream.chat.web.dto.ConversationDto.SELF_TITLE);
        }
        return labels;
    }
}
