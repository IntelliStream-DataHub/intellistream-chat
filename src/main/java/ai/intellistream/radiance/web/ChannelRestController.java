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

package ai.intellistream.radiance.web;

import ai.intellistream.radiance.security.CurrentUser;
import ai.intellistream.radiance.service.AttachmentService;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.MarkdownRenderer;
import ai.intellistream.radiance.service.MessageService;
import ai.intellistream.radiance.service.ReactionService;
import ai.intellistream.radiance.service.PollService;
import ai.intellistream.radiance.service.ReadStateService;
import ai.intellistream.radiance.service.UserService;
import ai.intellistream.radiance.web.dto.ChannelDto;
import ai.intellistream.radiance.web.dto.ChannelMemberDto;
import ai.intellistream.radiance.web.dto.CreateChannelRequest;
import ai.intellistream.radiance.web.dto.InviteRequest;
import ai.intellistream.radiance.web.dto.MessageDto;
import ai.intellistream.radiance.web.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/channels")
public class ChannelRestController {

    private final ChannelService channelService;
    private final MessageService messageService;
    private final AttachmentService attachmentService;
    private final ReactionService reactionService;
    private final ReadStateService readStateService;
    private final UserService userService;
    private final PollService pollService;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;

    public ChannelRestController(ChannelService channelService,
                                 MessageService messageService,
                                 AttachmentService attachmentService,
                                 ReactionService reactionService,
                                 ReadStateService readStateService,
                                 UserService userService,
                                 PollService pollService,
                                 MarkdownRenderer markdown,
                                 CurrentUser currentUser) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.reactionService = reactionService;
        this.readStateService = readStateService;
        this.userService = userService;
        this.pollService = pollService;
        this.markdown = markdown;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ChannelDto> listPublic() {
        return channelService.listPublic().stream().map(ChannelDto::from).toList();
    }

    @GetMapping("/mine")
    public List<ChannelDto> mine(Principal principal) {
        var me = currentUser.resolve(principal);
        return channelService.listForUser(me).stream().map(ChannelDto::from).toList();
    }

    @PostMapping
    public ChannelDto create(@RequestBody @Valid CreateChannelRequest body, Principal principal) {
        var me = currentUser.resolve(principal);
        return ChannelDto.from(channelService.create(body.name(), body.description(), body.type(), me));
    }

    @PostMapping("/{id}/join")
    public ResponseEntity<Void> join(@PathVariable UUID id, Principal principal) {
        var me = currentUser.resolve(principal);
        channelService.join(channelService.requireById(id), me);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/invite")
    public ResponseEntity<Void> invite(@PathVariable UUID id,
                                       @RequestBody @Valid InviteRequest body,
                                       Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        var invitee = userService.requireByUsername(body.username());
        channelService.invite(channel, invitee, me);
        return ResponseEntity.noContent().build();
    }

    /**
     * List the channel's membership. Same access posture as message reads:
     * any authenticated user can see members of a {@code PUBLIC} channel,
     * {@code PRIVATE} requires actual membership. Powers the "who's in this channel"
     * dropdown next to the channel search box.
     */
    @GetMapping("/{id}/members")
    public List<ChannelMemberDto> members(@PathVariable UUID id, Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        // requireMember short-circuits true for PUBLIC channels, throws for non-member of PRIVATE.
        channelService.requireMember(channel, me);
        return channelService.members(channel).stream()
                .map(ChannelMemberDto::from)
                .toList();
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> messages(@PathVariable UUID id,
                                     @RequestParam(required = false) Instant before,
                                     @RequestParam(required = false) Instant after,
                                     @RequestParam(defaultValue = "50") int limit,
                                     Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        // Three modes: recent (no params), before (up-scroll loading older), after (down-scroll
        // loading newer when the viewer is centered-on-anchor and reaches the bottom). before
        // wins if both are passed — the existing client only sends one at a time.
        List<ai.intellistream.radiance.domain.Message> rows;
        if (before != null) {
            rows = messageService.before(channel, me, before, limit);
        } else if (after != null) {
            rows = messageService.after(channel, me, after, limit);
        } else {
            rows = messageService.recent(channel, me, limit);
        }
        var attachments = attachmentService.findForMessages(rows);
        var reactions = reactionService.groupingsFor(rows, me);
        var replyCounts = messageService.threadReplyCounts(rows);
        // Batch-load polls so /poll-host messages render their vote widget on first paint.
        var polls = pollService.pollsForMessages(rows, me);
        return rows.stream()
                .map(m -> MessageDto.from(m, markdown.render(m.getBodyMarkdown()),
                        attachments.getOrDefault(m.getId(), List.of()),
                        reactions.getOrDefault(m.getId(), List.of()),
                        replyCounts.getOrDefault(m.getId(), 0L),
                        java.util.List.of(),
                        polls.get(m.getId())))
                .toList();
    }

    /**
     * Context-around fetch for permalinks: returns the {@code radius} messages immediately
     * before the anchor + the anchor itself + the {@code radius} messages immediately after,
     * oldest-first. Lets the UI jump to message N in a million-message channel without
     * scrolling up 50-at-a-time. Same authz rules as {@link #messages}.
     */
    @GetMapping("/{id}/messages/around")
    public List<MessageDto> messagesAround(@PathVariable UUID id,
                                           @RequestParam("messageId") UUID messageId,
                                           @RequestParam(defaultValue = "25") int radius,
                                           Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        var rows = messageService.around(channel, me, messageId, radius);
        var attachments = attachmentService.findForMessages(rows);
        var reactions = reactionService.groupingsFor(rows, me);
        var replyCounts = messageService.threadReplyCounts(rows);
        var polls = pollService.pollsForMessages(rows, me);
        return rows.stream()
                .map(m -> MessageDto.from(m, markdown.render(m.getBodyMarkdown()),
                        attachments.getOrDefault(m.getId(), List.of()),
                        reactions.getOrDefault(m.getId(), List.of()),
                        replyCounts.getOrDefault(m.getId(), 0L),
                        java.util.List.of(),
                        polls.get(m.getId())))
                .toList();
    }

    @PostMapping("/{id}/messages")
    public MessageDto post(@PathVariable UUID id,
                           @RequestBody @Valid SendMessageRequest body,
                           Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        var saved = messageService.post(channel, me, body.body());
        return MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        if (channelService.isMember(channel, me)) {
            readStateService.markRead(channel, me);
        }
        return ResponseEntity.noContent().build();
    }
}
