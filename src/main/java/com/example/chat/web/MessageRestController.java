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

package com.example.chat.web;

import com.example.chat.security.CurrentUser;
import com.example.chat.service.AttachmentService;
import com.example.chat.service.ChannelService;
import com.example.chat.service.MarkdownRenderer;
import com.example.chat.service.MessageService;
import com.example.chat.service.ReactionService;
import com.example.chat.web.dto.EditMessageRequest;
import com.example.chat.web.dto.MessageDto;
import com.example.chat.web.dto.MessageEvent;
import com.example.chat.web.dto.ReactionRequest;
import com.example.chat.web.dto.SendMessageRequest;
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
import java.util.List;
import java.util.UUID;

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

    public MessageRestController(MessageService messageService,
                                 AttachmentService attachmentService,
                                 ChannelService channelService,
                                 ReactionService reactionService,
                                 MarkdownRenderer markdown,
                                 CurrentUser currentUser,
                                 SimpMessagingTemplate broker) {
        this.messageService = messageService;
        this.attachmentService = attachmentService;
        this.channelService = channelService;
        this.reactionService = reactionService;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.broker = broker;
    }

    @PostMapping("/{id}/reactions")
    public MessageDto addReaction(@PathVariable UUID id,
                                  @RequestBody @Valid ReactionRequest body,
                                  Principal principal) {
        var me = currentUser.resolve(principal);
        var message = reactionService.addReaction(id, me, body.emoji());
        return broadcastUpdate(message, me);
    }

    @DeleteMapping("/{id}/reactions/{emoji}")
    public ResponseEntity<Void> removeReaction(@PathVariable UUID id,
                                               @PathVariable String emoji,
                                               Principal principal) {
        var me = currentUser.resolve(principal);
        var message = reactionService.removeReaction(id, me, emoji);
        broadcastUpdate(message, me);
        return ResponseEntity.noContent().build();
    }

    private MessageDto broadcastUpdate(com.example.chat.domain.Message message, com.example.chat.domain.User viewer) {
        var attachments = attachmentService.findForMessage(message);
        var reactions = reactionService.groupingsFor(message, viewer);
        var dto = MessageDto.from(message, markdown.render(message.getBodyMarkdown()), attachments, reactions);
        broker.convertAndSend("/topic/channels/" + dto.channelId(), MessageEvent.updated(dto));
        return dto;
    }

    @PatchMapping("/{id}")
    public MessageDto edit(@PathVariable UUID id,
                           @RequestBody @Valid EditMessageRequest body,
                           Principal principal) {
        var me = currentUser.resolve(principal);
        var updated = messageService.edit(id, me, body.body());
        var attachments = attachmentService.findForMessage(updated);
        var reactions = reactionService.groupingsFor(updated, me);
        var dto = MessageDto.from(updated, markdown.render(updated.getBodyMarkdown()), attachments, reactions);
        broker.convertAndSend("/topic/channels/" + dto.channelId(), MessageEvent.updated(dto));
        return dto;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Principal principal) {
        var me = currentUser.resolve(principal);
        var deleted = messageService.delete(id, me);
        broker.convertAndSend("/topic/channels/" + deleted.channelId(),
                MessageEvent.deleted(deleted.id(), deleted.channelId(), deleted.parentId()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/thread")
    public ThreadDto thread(@PathVariable UUID id, Principal principal) {
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
    public MessageDto reply(@PathVariable UUID id,
                            @RequestBody @Valid SendMessageRequest body,
                            Principal principal) {
        var me = currentUser.resolve(principal);
        var saved = messageService.replyInThread(id, me, body.body());
        var dto = MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()));
        broker.convertAndSend("/topic/channels/" + dto.channelId(), MessageEvent.created(dto));
        return dto;
    }

    public record ThreadDto(MessageDto parent, List<MessageDto> replies) {}
}
