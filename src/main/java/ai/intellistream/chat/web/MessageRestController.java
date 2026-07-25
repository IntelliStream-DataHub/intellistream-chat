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

    public MessageRestController(MessageService messageService,
                                 AttachmentService attachmentService,
                                 ChannelService channelService,
                                 ReactionService reactionService,
                                 MarkdownRenderer markdown,
                                 CurrentUser currentUser,
                                 SimpMessagingTemplate broker,
                                 RateLimiter rateLimiter) {
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.channelService = channelService;
        this.reactionService = reactionService;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
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
        var dto = MessageDto.from(message, markdown.render(message.getBodyMarkdown()), attachments, reactions);
        broker.convertAndSend("/topic/channels/" + dto.channelId(), MessageEvent.updated(dto));
        return dto;
    }

    @PatchMapping("/{id}")
    public MessageDto edit(@PathVariable Long id,
                           @RequestBody @Valid EditMessageRequest body,
                           Principal principal) {
        var me = currentUser.resolve(principal);
        // Each edit rewrites the Lucene index entry too; cap to keep that work bounded.
        if (!rateLimiter.tryAcquire(me.getUsername(), "msg-edit", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("edit rate exceeded");
        }
        var updated = messageService.edit(id, me, body.body());
        var attachments = attachmentService.findForMessage(updated);
        var reactions = reactionService.groupingsFor(updated, me);
        var dto = MessageDto.from(updated, markdown.render(updated.getBodyMarkdown()), attachments, reactions);
        broker.convertAndSend("/topic/channels/" + dto.channelId(), MessageEvent.updated(dto));
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
        return new ThreadDto(parentDto, replyDtos);
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
        var dto = MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()));
        broker.convertAndSend("/topic/channels/" + dto.channelId(), MessageEvent.created(dto));
        return dto;
    }

    public record ThreadDto(MessageDto parent, List<MessageDto> replies) {}
}
