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

import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.AttachmentService;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MessageService;
import ai.intellistream.chat.service.ReactionService;
import ai.intellistream.chat.service.PollService;
import ai.intellistream.chat.service.ReadStateService;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.dto.ChannelDto;
import ai.intellistream.chat.web.dto.ChannelMemberDto;
import ai.intellistream.chat.web.dto.CreateChannelRequest;
import ai.intellistream.chat.web.dto.InviteRequest;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.web.dto.MessageDto;
import ai.intellistream.chat.web.dto.MessageEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import ai.intellistream.chat.web.dto.SendMessageRequest;
import ai.intellistream.chat.web.dto.SetMemberRoleRequest;
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
    private final SimpMessagingTemplate broker;
    private final MessageMentionRepository mentionRepository;
    private final ai.intellistream.chat.service.SidebarService sidebarService;

    public ChannelRestController(ChannelService channelService,
                                 MessageService messageService,
                                 AttachmentService attachmentService,
                                 ReactionService reactionService,
                                 ReadStateService readStateService,
                                 UserService userService,
                                 PollService pollService,
                                 MarkdownRenderer markdown,
                                 CurrentUser currentUser,
                                 RateLimiter rateLimiter,
                                 SimpMessagingTemplate broker,
                                 MessageMentionRepository mentionRepository,
                                 ai.intellistream.chat.service.SidebarService sidebarService) {
        this.sidebarService = sidebarService;
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
        this.broker = broker;
        this.mentionRepository = mentionRepository;
    }

    @GetMapping
    public List<ChannelDto> listPublic() {
        return channelService.listPublic().stream().map(ChannelDto::from).toList();
    }

    /**
     * Channel name/slug search, backing the sidebar's search box. The sidebar shows a shortlist,
     * so this is how a user reaches everything else; results render in the main content area
     * rather than in the sidebar, because there is room there to show what each channel is.
     */
    @GetMapping("/search")
    public List<ai.intellistream.chat.web.dto.ChannelSidebarDto> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "25") int limit,
            Principal principal) {
        var me = currentUser.resolve(principal);
        // Same budget as the user-lookup endpoints — enough for type-ahead, bounded against
        // someone enumerating the channel list one query at a time.
        if (!rateLimiter.tryAcquire(me.getUsername(), "channel-search", 120, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("search rate exceeded");
        }
        return sidebarService.search(me, q, limit);
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
            throw new ai.intellistream.chat.security.RateLimitExceededException("channel create rate exceeded");
        }
        return ChannelDto.from(channelService.create(body.name(), body.description(), body.type(), me));
    }

    /**
     * Rename a channel and rewrite its description — the two facts about a channel that were
     * write-once at creation until now.
     *
     * <p>PATCH rather than PUT because the body is not a whole channel: {@code type}, {@code slug}
     * and {@code createdBy} are not editable here, and a PUT that silently ignores most of the
     * resource it claims to replace is a worse contract than a PATCH that names what it changes.
     *
     * <p>Channel admin only, authorised exactly as {@link #setMemberRole} is — {@code requireById}
     * then {@code requireAdmin}, before anything else touches the request. Any member can invite;
     * changing what the channel <em>is</em> stays with the people who run it.
     *
     * <p>Broadcast on the channel topic afterwards so open clients repaint. A rename with no
     * broadcast leaves every other tab in the workspace showing the old name in the header, the
     * sidebar and the composer placeholder until its next page load, which is the same
     * stale-metadata problem {@code /topic/users} already solves for avatars.
     */
    @PatchMapping("/{id}")
    public ChannelDto update(@PathVariable Long id,
                             @RequestBody @Valid ai.intellistream.chat.web.dto.UpdateChannelRequest body,
                             Principal principal) {
        var me = currentUser.resolve(principal);
        // A rename rewrites the slug, fans out to every connected member and invalidates a cache
        // entry. Nobody renames a channel twenty times a minute; a script would.
        if (!rateLimiter.tryAcquire(me.getUsername(), "channel-update", 20, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("channel update rate exceeded");
        }
        var channel = channelService.requireById(id);
        var updated = channelService.rename(channel, body.name(), body.description(), me);
        broker.convertAndSend("/topic/channels/" + id,
                ai.intellistream.chat.web.dto.ChannelEvent.updated(updated));
        return ChannelDto.from(updated);
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
        // Throttle the username lookup and authorize BEFORE resolving the name (N8). Without this,
        // a non-member gets 400 (unknown user) vs 403 (forbidden) as an unbounded existence oracle
        // — SEC-5 gave the DM/group invite siblings the same user-lookup limiter; invite was missed.
        if (!rateLimiter.tryAcquire(me.getUsername(), "user-lookup", 20, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("lookup rate exceeded");
        }
        var channel = channelService.requireById(id);
        channelService.requireWriteAccess(channel, me);
        var invitee = userService.requireByUsername(body.username());
        channelService.invite(channel, invitee, me);
        return ResponseEntity.noContent().build();
    }

    /**
     * Leave a channel. The mirror of {@code POST /{id}/join}, and named the same way for the same
     * reason: it is the one-click action, not a general membership edit.
     *
     * <p>Your messages stay — you are leaving, not deleting. Leaving a PRIVATE channel is
     * irreversible from your side (coming back needs an invitation), which the UI warns about; the
     * service allows it, because a channel you cannot leave is a worse trap than one you cannot
     * re-enter.
     */
    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leave(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        channelService.leave(channelService.requireById(id), me);
        return ResponseEntity.noContent().build();
    }

    /**
     * Remove a member — an admin's kick, or your own departure if {@code username} is you.
     *
     * <p>The self case is checked against the caller's own username <em>before</em> the admin check
     * and before any user lookup. That ordering is deliberate: a plain member must be able to remove
     * themselves through the endpoint that names them, and a non-admin naming somebody else must get
     * 403 without the lookup, so the endpoint can't be used to test whether an account exists (N8).
     */
    @DeleteMapping("/{id}/members/{username}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id,
                                             @PathVariable String username,
                                             Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        if (username.equals(me.getUsername())) {
            channelService.leave(channel, me);
            return ResponseEntity.noContent().build();
        }
        channelService.requireAdmin(channel, me);
        if (!rateLimiter.tryAcquire(me.getUsername(), "user-lookup", 20, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("lookup rate exceeded");
        }
        channelService.removeMember(channel, userService.requireByUsername(username), me);
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
        // Throttle + authorize before resolving the username, same rationale as invite (N8).
        if (!rateLimiter.tryAcquire(me.getUsername(), "user-lookup", 20, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("lookup rate exceeded");
        }
        var channel = channelService.requireById(id);
        channelService.requireAdmin(channel, me);
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
        List<ai.intellistream.chat.domain.Message> rows;
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
        var posted = messageService.postWithMentions(channel, me, body.body());
        var saved = posted.message();
        var dto = MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()),
                List.of(), List.of(), 0L,
                posted.mentionedUsernames(), null);
        // Broadcast on the channel topic so connected clients see the message live and fire their
        // @mention notifications — mirroring the WS send path (N6). Without this, a message posted
        // via HTTP was invisible until reload.
        broker.convertAndSend("/topic/channels/" + id, MessageEvent.created(dto));
        return dto;
    }

    /**
     * Star / unstar a channel for the caller. Starred channels group at the top of their sidebar.
     *
     * <p>Returns the stored state rather than 204 so a client that raced itself repaints from the
     * server's answer instead of from what it assumed.
     */
    @PutMapping("/{id}/favourite")
    public java.util.Map<String, Boolean> setFavourite(
            @PathVariable Long id,
            @RequestBody @Valid ai.intellistream.chat.web.dto.SetFavouriteRequest body,
            Principal principal) {
        var me = currentUser.resolve(principal);
        var channel = channelService.requireById(id);
        return java.util.Map.of("favourite",
                channelService.setFavourite(channel, me, body.favourite()));
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
