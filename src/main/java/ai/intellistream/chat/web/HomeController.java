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

package ai.intellistream.chat.web;

import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.ConversationReactionService;
import ai.intellistream.chat.service.ReactionService;
import ai.intellistream.chat.service.ReadStateService;
import ai.intellistream.chat.service.SidebarService;
import ai.intellistream.chat.web.dto.ConversationDto;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
import ai.intellistream.chat.web.dto.MessageDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;
import java.util.List;

@Controller
public class HomeController {

    private final SidebarService sidebarService;
    private final ChannelService channelService;
    private final MessageService messageService;
    private final AttachmentService attachmentService;
    private final ReactionService reactionService;
    private final ConversationReactionService conversationReactionService;
    private final ReadStateService readStateService;
    private final ConversationService conversationService;
    private final ai.intellistream.chat.service.ConversationAttachmentService conversationAttachmentService;
    private final ai.intellistream.chat.service.PollService pollService;
    private final ai.intellistream.chat.service.NotificationPreferenceService notificationPreferences;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;

    public HomeController(SidebarService sidebarService,
                          ChannelService channelService,
                          MessageService messageService,
                          AttachmentService attachmentService,
                          ReactionService reactionService,
                          ConversationReactionService conversationReactionService,
                          ReadStateService readStateService,
                          ConversationService conversationService,
                          ai.intellistream.chat.service.ConversationAttachmentService conversationAttachmentService,
                          ai.intellistream.chat.service.PollService pollService,
                          ai.intellistream.chat.service.NotificationPreferenceService notificationPreferences,
                          MarkdownRenderer markdown,
                          CurrentUser currentUser) {
        this.sidebarService = sidebarService;
        this.channelService = channelService;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.reactionService = reactionService;
        this.conversationReactionService = conversationReactionService;
        this.readStateService = readStateService;
        this.conversationService = conversationService;
        this.conversationAttachmentService = conversationAttachmentService;
        this.pollService = pollService;
        this.notificationPreferences = notificationPreferences;
        this.markdown = markdown;
        this.currentUser = currentUser;
    }

    @GetMapping("/")
    public String index(Principal principal) {
        return principal == null ? "landing" : "redirect:/channels";
    }

    @GetMapping("/channels")
    public String channels(Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        model.addAttribute("me", me);
        model.addAttribute("sidebar", sidebarService.joinedFor(me));
        model.addAttribute("conversations", listDirectConversations(me));
        model.addAttribute("activeChannelId", null);
        model.addAttribute("activeChannel", null);
        model.addAttribute("activeConversationId", null);
        model.addAttribute("messages", List.of());
        model.addAttribute("isMember", false);
        model.addAttribute("isAdmin", false);
        return "channels";
    }

    @GetMapping("/channels/{id}")
    public String channel(@PathVariable Long id,
                          @org.springframework.web.bind.annotation.RequestParam(name = "m", required = false) Long anchorMessageId,
                          Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        var member = channelService.isMember(channel, me);
        var admin = channelService.isAdmin(channel, me);

        List<MessageDto> messages = List.of();
        boolean centeredOnAnchor = false;
        if (member || channel.getType().name().equals("PUBLIC")) {
            try {
                // ?m=<id> renders 25 before + the anchor + 25 after instead of "latest 50",
                // so a search-result permalink to message N of a 100k-message channel actually
                // shows the user that message in context. Falls back to recent() if the anchor
                // is missing or in another channel — better than 404'ing the page.
                var rows = (anchorMessageId != null)
                        ? safeAround(channel, me, anchorMessageId)
                        : null;
                if (rows == null) {
                    rows = messageService.recent(channel, me, 50);
                } else {
                    centeredOnAnchor = true;
                }
                var attachments = attachmentService.findForMessages(rows);
                var reactions = reactionService.groupingsFor(rows, me);
                var replyCounts = messageService.threadReplyCounts(rows);
                var polls = pollService.pollsForMessages(rows, me);
                messages = rows.stream()
                        .map(m -> MessageDto.from(m, markdown.render(m.getBodyMarkdown()),
                                attachments.getOrDefault(m.getId(), List.of()),
                                reactions.getOrDefault(m.getId(), List.of()),
                                replyCounts.getOrDefault(m.getId(), 0L),
                                List.of(),
                                polls.get(m.getId())))
                        .toList();
            } catch (AccessDeniedException ignored) {
                // private channel, not a member -> render join screen
            }
        }

        // Mark this channel as read for the viewer (only if they're a member; we don't track read
        // state for drive-by views of public channels they haven't joined).
        if (member) {
            readStateService.markRead(channel, me);
        }

        model.addAttribute("me", me);
        model.addAttribute("sidebar", sidebarService.joinedFor(me));
        model.addAttribute("conversations", listDirectConversations(me));
        model.addAttribute("activeChannelId", channel.getId());
        model.addAttribute("activeChannel", channel);
        model.addAttribute("activeConversationId", null);
        model.addAttribute("messages", messages);
        model.addAttribute("isMember", member);
        model.addAttribute("isAdmin", admin);
        // The notification picker's two inputs, so opening a channel costs no extra request:
        // the RAW level for this channel (DEFAULT when it follows the account default, null when
        // the viewer isn't a member and so has no setting), and the account default it may be
        // inheriting. Raw, not resolved — the picker shows "Default" as a selectable option, and a
        // resolved value would make an inherited MENTIONS indistinguishable from a pinned one.
        model.addAttribute("notifyLevel", member ? notificationPreferences.levelFor(channel, me) : null);
        model.addAttribute("notifyDefault", notificationPreferences.accountDefault(me));
        // Tells the template "you're not at the latest" so the JS can render a Jump to latest
        // banner; also drives whether infinite-scroll is enabled in BOTH directions vs. only up.
        model.addAttribute("centeredOnAnchor", centeredOnAnchor);
        model.addAttribute("anchorMessageId", centeredOnAnchor ? anchorMessageId : null);
        if (admin) {
            model.addAttribute("members", channelService.members(channel));
        }
        return "channels";
    }

    /**
     * Resolve {@code messageService.around} with a friendly fallback: if the anchor doesn't
     * exist (deleted), belongs to a different channel, or is a thread reply, return null so
     * the caller falls back to recent(50) instead of 404'ing the page. Stale search-result
     * links and admins poking at URLs both go through this path.
     */
    private java.util.List<ai.intellistream.chat.domain.Message> safeAround(
            ai.intellistream.chat.domain.Channel channel,
            ai.intellistream.chat.domain.User me,
            Long anchorId) {
        try {
            return messageService.around(channel, me, anchorId, 25);
        } catch (IllegalArgumentException | ai.intellistream.chat.security.ResourceNotFoundException unknownAnchor) {
            // around() throws ResourceNotFoundException for a deleted or channel-mismatched anchor
            // and IllegalArgumentException for a thread-reply anchor. Both mean "no usable anchor" —
            // fall back to recent() rather than letting the API 404 handler replace the HTML page
            // with a JSON error envelope (N7).
            return null;
        }
    }

    @GetMapping("/conversations/{id}")
    public String conversation(@PathVariable Long id, Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        var conversation = conversationService.requireById(id);
        // Membership enforced inside recent(); throws AccessDeniedException if the viewer
        // isn't part of this DM, which the global handler turns into a 403.
        var rows = conversationService.recent(conversation, me, 50);
        var attachmentMap = conversationAttachmentService.findForMessages(rows);
        var reactionMap = conversationReactionService.groupingsFor(rows, me);
        var replyCounts = conversationService.threadReplyCounts(rows);
        var messages = rows.stream()
                .map(m -> ConversationMessageDto.from(m,
                        markdown.render(m.getBodyMarkdown()),
                        attachmentMap.getOrDefault(m.getId(), List.of()),
                        reactionMap.getOrDefault(m.getId(), List.of()),
                        replyCounts.getOrDefault(m.getId(), 0L),
                        List.of()))
                .toList();

        var other = conversation.getType() == ConversationType.DIRECT
                ? conversationService.members(conversation).stream()
                        .map(cm -> cm.getUser())
                        .filter(u -> !u.getId().equals(me.getId()))
                        .findFirst().orElse(null)
                : null;

        // Where the reader left off, read BEFORE the stamp below moves it. This is what the client
        // draws the "new messages" divider from, and after markRead there is nothing left to draw
        // it from — the marker would say "now", and everything would be read.
        var lastReadAt = conversationService.lastReadAt(conversation, me);
        // Stamp the read marker so the next sidebar render shows zero unread for this DM.
        conversationService.markRead(conversation, me);

        model.addAttribute("me", me);
        model.addAttribute("sidebar", sidebarService.joinedFor(me));
        model.addAttribute("conversations", listDirectConversations(me));
        model.addAttribute("activeConversationId", conversation.getId());
        // Null here, but present: the shared sidebar fragment reads both ids to decide which row
        // is highlighted, and a page that silently omits one is how the two sidebars drifted apart
        // in the first place.
        model.addAttribute("activeChannelId", null);
        var notifyLevel = conversationService.notifyLevelsFor(me).get(conversation.getId());
        model.addAttribute("activeConversation",
                ConversationDto.of(conversation, other, 0L, notifyLevel));
        model.addAttribute("conversationNotifyLevel",
                notifyLevel == null ? ai.intellistream.chat.domain.NotificationLevel.DEFAULT : notifyLevel);
        model.addAttribute("messages", messages);
        model.addAttribute("lastReadAt", lastReadAt);
        // A conversation with one member — a DM with yourself, where /remind me delivers. Its own
        // messages count as unread to it (there is nobody else to write them), so the divider has
        // to know, and it cannot work it out from the member list it does not have.
        model.addAttribute("conversationIsSolo", conversationService.members(conversation).size() == 1);
        model.addAttribute("isAdmin", me.isAdmin());
        return "conversation";
    }

    private List<ConversationDto> listDirectConversations(ai.intellistream.chat.domain.User me) {
        var convs = conversationService.listForUser(me);
        var ids = convs.stream().map(ai.intellistream.chat.domain.Conversation::getId).toList();
        var unread = conversationService.unreadCounts(me, ids);
        var levels = conversationService.notifyLevelsFor(me);
        return convs.stream()
                .map(c -> {
                    var other = c.getType() == ConversationType.DIRECT
                            ? conversationService.members(c).stream()
                                    .map(cm -> cm.getUser())
                                    .filter(u -> !u.getId().equals(me.getId()))
                                    .findFirst().orElse(null)
                            : null;
                    return ConversationDto.of(c, other, unread.getOrDefault(c.getId(), 0L),
                            levels.get(c.getId()));
                })
                .toList();
    }
}
