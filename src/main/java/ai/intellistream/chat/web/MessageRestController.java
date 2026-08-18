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
import ai.intellistream.chat.service.PollService;
import ai.intellistream.chat.slash.PollCommand;
import ai.intellistream.chat.service.ReactionService;
import ai.intellistream.chat.web.dto.EditMessageRequest;
import ai.intellistream.chat.web.dto.MessageDto;
import ai.intellistream.chat.web.dto.MessageEvent;
import ai.intellistream.chat.web.dto.ReactionRequest;
import ai.intellistream.chat.web.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageRestController {

    private final MessageService messageService;
    private final AttachmentService attachmentService;
    private final ChannelService channelService;
    private final ReactionService reactionService;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;
    private final PollService pollService;
    private final LinkPreviews linkPreviews;

    public MessageRestController(MessageService messageService,
                                 AttachmentService attachmentService,
                                 ChannelService channelService,
                                 ReactionService reactionService,
                                 MarkdownRenderer markdown,
                                 CurrentUser currentUser,
                                 SimpMessagingTemplate broker,
                                 RateLimiter rateLimiter,
                                 PollService pollService,
                                 LinkPreviews linkPreviews) {
        this.linkPreviews = linkPreviews;
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.channelService = channelService;
        this.reactionService = reactionService;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
        this.pollService = pollService;
    }

    @PostMapping("/{id}/reactions")
    public MessageDto addReaction(@PathVariable Long id,
                                  @RequestBody @Valid ReactionRequest body,
                                  Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "reaction-toggle", 60, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("reaction rate exceeded");
        }
        var message = reactionService.addReaction(id, me, body.emoji());
        return broadcastUpdate(message, me);
    }

    @DeleteMapping("/{id}/reactions/{emoji}")
    public ResponseEntity<Void> removeReaction(@PathVariable Long id,
                                               @PathVariable String emoji,
                                               Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "reaction-toggle", 60, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("reaction rate exceeded");
        }
        var message = reactionService.removeReaction(id, me, emoji);
        broadcastUpdate(message, me);
        return ResponseEntity.noContent().build();
    }

    private MessageDto broadcastUpdate(ai.intellistream.chat.domain.Message message, ai.intellistream.chat.domain.User viewer) {
        var attachments = attachmentService.findForMessage(message);
        var reactions = reactionService.groupingsFor(message, viewer);
        // The poll has to be in here. Without it the update event carries poll=null and every
        // client re-renders the message without its widget — reacting to a poll made the poll
        // disappear for everyone until they reloaded.
        // And the link card, for the same reason: clients re-render the whole message from this
        // frame, so a reaction on a message with a card would otherwise take the card away.
        var dto = linkPreviews.decorate(MessageDto.from(message, markdown.render(message.getBodyMarkdown()),
                attachments, reactions, 0, java.util.List.of(), pollService.pollFor(message, viewer)));
        broker.convertAndSend("/topic/channels/" + dto.channelId(), MessageEvent.updated(dto));
        return dto;
    }

    /**
     * Pin a message to its channel. Any member may pin — see {@code MessageService.pin} for why
     * that is the line rather than the admin role.
     *
     * <p>POST/DELETE on a {@code /pin} sub-resource rather than a PATCH carrying a boolean, for
     * the same reason archive and unarchive are two endpoints: both are reached from a deliberate
     * click, and the verb is then legible in a server log and a proxy access log without anyone
     * having to read the body.
     *
     * <p>Broadcast on the channel topic so every open client repaints the message's pin marker and
     * re-reads the header count. A pin nobody else sees until they reload is not a channel-level
     * fact, which is the only thing a pin is.
     */
    @PostMapping("/{id}/pin")
    public MessageDto pin(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "msg-pin", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("pin rate exceeded");
        }
        return broadcastUpdate(messageService.pin(id, me), me);
    }

    @DeleteMapping("/{id}/pin")
    public MessageDto unpin(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "msg-pin", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("pin rate exceeded");
        }
        return broadcastUpdate(messageService.unpin(id, me), me);
    }

    @PatchMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public MessageDto edit(@PathVariable Long id,
                           @RequestBody @Valid EditMessageRequest body,
                           Principal principal) {
        var me = currentUser.resolve(principal);
        // Each edit rewrites the Lucene index entry too; cap to keep that work bounded.
        if (!rateLimiter.tryAcquire(me.getUsername(), "msg-edit", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("edit rate exceeded");
        }
        // A poll message is edited as the command that created it, because that is the only
        // form in which its options are visible and changeable. The stored body stays the short
        // "📊 Poll: <question>" line that search and notifications read.
        var pollEdit = PollCommand.parseEditedCommand(body.body());
        if (pollEdit != null) {
            // edit() first: it owns the author-only check, and doing the poll update before it
            // would let anyone rewrite anyone's poll. Both run in this method's transaction, so a
            // refused option change (votes already cast) rolls the question change back with it
            // rather than half-applying.
            var updated = messageService.edit(id, me, PollCommand.bodyFor(pollEdit.question()));
            pollService.update(updated, pollEdit.question(), pollEdit.options());
            return broadcastUpdate(updated, me);
        }
        var updated = messageService.edit(id, me, body.body());
        var dto = broadcastUpdate(updated, me);
        // The edit may have introduced or swapped the link; a card it already had rode on the
        // update frame above, a new one arrives as its own event.
        if (dto.linkPreview() == null) linkPreviews.unfurl(dto);
        return dto;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "msg-delete", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("delete rate exceeded");
        }
        var deleted = messageService.delete(id, me);
        broker.convertAndSend("/topic/channels/" + deleted.channelId(),
                MessageEvent.deleted(deleted.id(), deleted.channelId(), deleted.parentId()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/thread")
    public ThreadDto thread(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        var parent = messageService.requireById(id);
        channelService.requireMember(parent.getChannel(), me);
        var replies = messageService.threadReplies(id, me);

        var all = java.util.stream.Stream.concat(java.util.stream.Stream.of(parent), replies.stream()).toList();
        var attachmentMap = attachmentService.findForMessages(all);
        var reactionMap = reactionService.groupingsFor(all, me);

        var parentDto = MessageDto.from(parent, markdown.render(parent.getBodyMarkdown()),
                attachmentMap.getOrDefault(parent.getId(), List.of()),
                reactionMap.getOrDefault(parent.getId(), List.of()));
        var replyDtos = replies.stream()
                .map(r -> MessageDto.from(r, markdown.render(r.getBodyMarkdown()),
                        attachmentMap.getOrDefault(r.getId(), List.of()),
                        reactionMap.getOrDefault(r.getId(), List.of())))
                .toList();
        var decorated = linkPreviews.decorate(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(parentDto), replyDtos.stream()).toList());
        return new ThreadDto(decorated.getFirst(), decorated.subList(1, decorated.size()));
    }

    @PostMapping("/{id}/replies")
    public MessageDto reply(@PathVariable Long id,
                            @RequestBody @Valid SendMessageRequest body,
                            Principal principal) {
        var me = currentUser.resolve(principal);
        // Thread replies share the WS-send budget conceptually; use the same 30/min cap.
        if (!rateLimiter.tryAcquire(me.getUsername(), "http-send", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("reply rate exceeded");
        }
        var saved = messageService.replyInThread(id, me, body.body());
        // Who is in this thread, so the broadcast can tell them. Derived from the messages (parent
        // author + everyone who has replied), not from a follow table — see
        // MessageService.threadParticipants. Without this a reply produced no signal at all for the
        // people actually having the conversation, which is how threads die quietly.
        var participants = messageService.threadParticipants(saved.getParent(), me);
        var dto = MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()))
                .withThreadParticipants(participants);
        broker.convertAndSend("/topic/channels/" + dto.channelId(), MessageEvent.created(dto));
        linkPreviews.unfurl(dto);
        // The sender's own copy has no use for the list, and it names other people; strip it.
        return dto.withThreadParticipants(List.of());
    }

    public record ThreadDto(MessageDto parent, List<MessageDto> replies) {}
}
