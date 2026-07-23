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

package ai.intellistream.threadorbit.web;

import ai.intellistream.threadorbit.security.CurrentUser;
import ai.intellistream.threadorbit.security.RateLimitExceededException;
import ai.intellistream.threadorbit.security.RateLimiter;
import ai.intellistream.threadorbit.service.AttachmentService;
import ai.intellistream.threadorbit.service.ChannelService;
import ai.intellistream.threadorbit.service.MarkdownRenderer;
import ai.intellistream.threadorbit.service.MessageService;
import ai.intellistream.threadorbit.service.ReactionService;
import ai.intellistream.threadorbit.service.PollService;
import ai.intellistream.threadorbit.service.ReadStateService;
import ai.intellistream.threadorbit.service.UserService;
import ai.intellistream.threadorbit.web.dto.ChannelDto;
import ai.intellistream.threadorbit.web.dto.ChannelMemberDto;
import ai.intellistream.threadorbit.web.dto.CreateChannelRequest;
import ai.intellistream.threadorbit.web.dto.InviteRequest;
import ai.intellistream.threadorbit.web.dto.MessageDto;
import ai.intellistream.threadorbit.web.dto.SendMessageRequest;
import ai.intellistream.threadorbit.web.dto.SetMemberRoleRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
    private final RateLimiter rateLimiter;

    public ChannelRestController(ChannelService channelService,
                                 MessageService messageService,
                                 AttachmentService attachmentService,
                                 ReactionService reactionService,
                                 ReadStateService readStateService,
                                 UserService userService,
                                 PollService pollService,
                                 MarkdownRenderer markdown,
                                 CurrentUser currentUser,
                                 RateLimiter rateLimiter) {
        this.channelService = channelService;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.reactionService = reactionService;
        this.readStateService = readStateService;
        this.userService = userService;
        this.pollService = pollService;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
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
        // 20 new channels per minute per user — far above legitimate use; each reserves a unique
        // slug + a membership row, so a tight create loop is otherwise unbounded DB churn.
        if (!rateLimiter.tryAcquire(me.getUsername(), "channel-create", 20, java.time.Duration.ofMinutes(1))) {
            throw new ai.intellistream.threadorbit.security.RateLimitExceededException("channel create rate exceeded");
        }
        return ChannelDto.from(channelService.create(body.name(), body.description(), body.type(), me));
    }

    @PostMapping("/{id}/join")
    public ResponseEntity<Void> join(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        channelService.join(channelService.requireById(id), me);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/invite")
    public ResponseEntity<Void> invite(@PathVariable Long id,
                                       @RequestBody @Valid InviteRequest body,
                                       Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        var invitee = userService.requireByUsername(body.username());
        channelService.invite(channel, invitee, me);
        return ResponseEntity.noContent().build();
    }

    /**
     * Promote / demote a member's role. Admin-only — the service double-checks. Role
     * change won't strip the last admin (service throws). The members panel polls
     * this endpoint when an admin clicks the role-toggle button next to a name.
     */
    @PutMapping("/{id}/members/{username}/role")
    public ResponseEntity<Void> setMemberRole(@PathVariable Long id,
                                              @PathVariable String username,
                                              @RequestBody @Valid SetMemberRoleRequest body,
                                              Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        var target = userService.requireByUsername(username);
        switch (body.role()) {
            case ADMIN  -> channelService.promote(channel, target, me);
            case MEMBER -> channelService.demote(channel, target, me);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * List the channel's membership. Same access posture as message reads:
     * any authenticated user can see members of a {@code PUBLIC} channel,
     * {@code PRIVATE} requires actual membership. Powers the "who's in this channel"
     * dropdown next to the channel search box.
     */
    @GetMapping("/{id}/members")
    public List<ChannelMemberDto> members(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        // requireMember short-circuits true for PUBLIC channels, throws for non-member of PRIVATE.
        channelService.requireMember(channel, me);
        return channelService.members(channel).stream()
                .map(ChannelMemberDto::from)
                .toList();
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> messages(@PathVariable Long id,
                                     @RequestParam(required = false) Instant before,
                                     @RequestParam(required = false) Instant after,
                                     @RequestParam(defaultValue = "50") int limit,
                                     Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        // Three modes: recent (no params), before (up-scroll loading older), after (down-scroll
        // loading newer when the viewer is centered-on-anchor and reaches the bottom). Reject
        // both-at-once explicitly so a confused client gets a 400 instead of silent precedence.
        if (before != null && after != null) {
            throw new IllegalArgumentException("'before' and 'after' are mutually exclusive");
        }
        List<ai.intellistream.threadorbit.domain.Message> rows;
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
    public List<MessageDto> messagesAround(@PathVariable Long id,
                                           @RequestParam("messageId") Long messageId,
                                           @RequestParam(defaultValue = "25") int radius,
                                           Principal principal) {
        var me = currentUser.resolve(principal);
        // Permalink fan-out: 60/min is generous for a human clicking around but caps a hostile
        // loop that would otherwise spike DB on a hot channel.
        if (!rateLimiter.tryAcquire(me.getUsername(), "msg-around", 60, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("around rate exceeded");
        }
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
    public MessageDto post(@PathVariable Long id,
                           @RequestBody @Valid SendMessageRequest body,
                           Principal principal) {
        var me = currentUser.resolve(principal);
        // Mirror the WS limiter — without this, a client can trivially bypass the 30/min cap
        // on /app/channels/{id}/send by switching transports to HTTP.
        if (!rateLimiter.tryAcquire(me.getUsername(), "http-send", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("send rate exceeded");
        }
        var channel = channelService.requireById(id);
        var saved = messageService.post(channel, me, body.body());
        return MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        if (channelService.isMember(channel, me)) {
            readStateService.markRead(channel, me);
        }
        return ResponseEntity.noContent().build();
    }
}
