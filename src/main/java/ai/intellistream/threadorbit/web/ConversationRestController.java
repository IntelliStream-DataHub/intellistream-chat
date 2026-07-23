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

import ai.intellistream.threadorbit.domain.ConversationType;
import ai.intellistream.threadorbit.domain.User;
import ai.intellistream.threadorbit.security.CurrentUser;
import ai.intellistream.threadorbit.security.RateLimitExceededException;
import ai.intellistream.threadorbit.security.RateLimiter;
import ai.intellistream.threadorbit.service.ConversationAttachmentService;
import ai.intellistream.threadorbit.service.ConversationReactionService;
import ai.intellistream.threadorbit.service.ConversationService;
import ai.intellistream.threadorbit.service.MarkdownRenderer;
import ai.intellistream.threadorbit.service.UserService;
import ai.intellistream.threadorbit.web.dto.AddGroupMemberRequest;
import ai.intellistream.threadorbit.web.dto.ConversationDto;
import ai.intellistream.threadorbit.web.dto.ConversationEvent;
import ai.intellistream.threadorbit.web.dto.ConversationMemberDto;
import ai.intellistream.threadorbit.web.dto.ConversationMessageDto;
import ai.intellistream.threadorbit.web.dto.CreateGroupRequest;
import ai.intellistream.threadorbit.web.dto.EditMessageRequest;
import ai.intellistream.threadorbit.web.dto.ReactionRequest;
import ai.intellistream.threadorbit.web.dto.StartDirectRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Principal;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * REST surface for direct messages. Membership checks live in the service layer
 * ({@link ConversationService#requireMember}); controllers just resolve the viewer
 * and shape DTOs. The matching live channel is {@code /topic/conversations/{id}}
 * (see {@code ConversationWebSocketController}).
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationRestController {

    private final ConversationService conversations;
    private final UserService userService;
    private final CurrentUser currentUser;
    private final MarkdownRenderer markdown;
    private final ConversationAttachmentService attachments;
    private final ConversationReactionService reactions;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;

    public ConversationRestController(ConversationService conversations,
                                      UserService userService,
                                      CurrentUser currentUser,
                                      MarkdownRenderer markdown,
                                      ConversationAttachmentService attachments,
                                      ConversationReactionService reactions,
                                      SimpMessagingTemplate broker,
                                      RateLimiter rateLimiter) {
        this.conversations = conversations;
        this.userService = userService;
        this.currentUser = currentUser;
        this.markdown = markdown;
        this.attachments = attachments;
        this.reactions = reactions;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<ConversationDto> list(Principal principal) {
        var me = currentUser.resolve(principal);
        return conversations.listForUser(me).stream()
                .map(c -> ConversationDto.of(c, otherParticipant(c, me)))
                .toList();
    }

    @PostMapping("/direct")
    public ConversationDto startDirect(@Valid @RequestBody StartDirectRequest request,
                                       Principal principal) {
        var me = currentUser.resolve(principal);
        var other = userService.requireByUsername(request.username());
        var conv = conversations.directBetween(me, other);
        return ConversationDto.of(conv, other);
    }

    /**
     * Create a named group conversation. The caller is added as a member automatically;
     * {@code request.members()} is the list of *other* usernames to seed. Unknown
     * usernames are collected and reported back as a single error so the client knows
     * exactly which name(s) didn't resolve, rather than failing on the first one.
     */
    @PostMapping("/group")
    public ConversationDto createGroup(@Valid @RequestBody CreateGroupRequest request,
                                       Principal principal) {
        var me = currentUser.resolve(principal);
        var memberNames = request.members().stream()
                .filter(u -> u != null && !u.isBlank())
                .map(String::trim)
                .filter(u -> !u.equalsIgnoreCase(me.getUsername()))
                .distinct()
                .toList();
        if (memberNames.isEmpty()) {
            throw new ai.intellistream.threadorbit.security.PublicBadRequestException(
                    "A group needs at least one other member besides yourself.");
        }
        var unknown = new java.util.ArrayList<String>();
        var seed = new java.util.ArrayList<User>();
        for (var name : memberNames) {
            try {
                seed.add(userService.requireByUsername(name));
            } catch (IllegalArgumentException ex) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ai.intellistream.threadorbit.security.PublicBadRequestException(
                    "Unknown user" + (unknown.size() == 1 ? ": " : "s: ") + String.join(", ", unknown));
        }
        var conv = conversations.createGroup(request.title(), me, seed);
        // Group conversations carry the title as the surfaced name; no "other" participant.
        return ConversationDto.of(conv, null);
    }

    /**
     * Membership of a conversation. Reuses the same access posture as message reads —
     * you can only see who's in a conversation if you're in it yourself.
     */
    @GetMapping("/{id}/members")
    public List<ConversationMemberDto> members(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        var conv = conversations.requireById(id);
        conversations.requireMember(conv, me);
        return conversations.members(conv).stream()
                .map(ConversationMemberDto::from)
                .toList();
    }

    /**
     * Add a user to a group conversation. The acting member must already be in the
     * group; the service rejects this on a DIRECT conversation (those are sealed at
     * two participants).
     */
    @PostMapping("/{id}/members")
    public ConversationMemberDto addMember(@PathVariable Long id,
                                           @Valid @RequestBody AddGroupMemberRequest request,
                                           Principal principal) {
        var me = currentUser.resolve(principal);
        var conv = conversations.requireById(id);
        var invitee = userService.requireByUsername(request.username());
        var membership = conversations.addToGroup(conv, invitee, me);
        // Broadcast a tiny event so other members' open tabs can refresh their member list
        // without polling. Reuses the existing /topic/conversations/{id} fan-out.
        broker.convertAndSend("/topic/conversations/" + id,
                ConversationEvent.memberAdded(id, invitee.getUsername()));
        return ConversationMemberDto.from(membership);
    }

    @GetMapping("/{id}/messages")
    public List<ConversationMessageDto> messages(@PathVariable Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        var conv = conversations.requireById(id);
        var rows = conversations.recent(conv, me, 50);
        var attachmentMap = attachments.findForMessages(rows);
        var reactionMap = reactions.groupingsFor(rows, me);
        return rows.stream()
                .map(m -> ConversationMessageDto.from(m,
                        markdown.render(m.getBodyMarkdown()),
                        attachmentMap.getOrDefault(m.getId(), List.of()),
                        reactionMap.getOrDefault(m.getId(), List.of())))
                .toList();
    }

    @PatchMapping("/messages/{messageId}")
    public ConversationMessageDto editMessage(@PathVariable Long messageId,
                                              @Valid @RequestBody EditMessageRequest body,
                                              Principal principal) {
        var me = currentUser.resolve(principal);
        var updated = conversations.editMessage(messageId, me, body.body());
        return broadcastUpdate(updated, me);
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long messageId, Principal principal) {
        var me = currentUser.resolve(principal);
        var deleted = conversations.deleteMessage(messageId, me);
        broker.convertAndSend("/topic/conversations/" + deleted.getConversation().getId(),
                ConversationEvent.messageDeleted(deleted.getConversation().getId(), deleted.getId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{messageId}/reactions")
    public ConversationMessageDto addReaction(@PathVariable Long messageId,
                                              @Valid @RequestBody ReactionRequest body,
                                              Principal principal) {
        var me = currentUser.resolve(principal);
        var message = reactions.addReaction(messageId, me, body.emoji());
        return broadcastUpdate(message, me);
    }

    @DeleteMapping("/messages/{messageId}/reactions/{emoji}")
    public ResponseEntity<Void> removeReaction(@PathVariable Long messageId,
                                               @PathVariable String emoji,
                                               Principal principal) {
        var me = currentUser.resolve(principal);
        var message = reactions.removeReaction(messageId, me, emoji);
        broadcastUpdate(message, me);
        return ResponseEntity.noContent().build();
    }

    /** Broadcast an updated DTO over the conversation topic and return it to the caller. */
    private ConversationMessageDto broadcastUpdate(ai.intellistream.threadorbit.domain.ConversationMessage message, User viewer) {
        var atts = attachments.findForMessage(message);
        var rs = reactions.groupingsFor(message, viewer);
        var dto = ConversationMessageDto.from(message,
                markdown.render(message.getBodyMarkdown()), atts, rs);
        broker.convertAndSend("/topic/conversations/" + dto.conversationId(),
                ConversationEvent.messageUpdated(dto));
        return dto;
    }

    /**
     * Streaming multipart upload of a single file attached to a new conversation message.
     * Mirrors {@code AttachmentRestController.upload} for channels: the InputStream goes
     * straight to disk, never fully buffered in memory. Membership is enforced inside the
     * service layer via {@code conversationService.requireMember}.
     */
    /**
     * HTTP fallback for sending a DM when the WebSocket connection isn't available.
     * Persists the message and broadcasts the same {@link ConversationMessageDto} via STOMP
     * so other connected members receive it through the live channel they're subscribed to.
     */
    @PostMapping("/{id}/messages")
    public ConversationMessageDto sendMessage(@PathVariable Long id,
                                              @Valid @RequestBody ai.intellistream.threadorbit.web.dto.SendMessageRequest body,
                                              Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "dm-http-send", 30, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("send rate exceeded");
        }
        var conv = conversations.requireById(id);
        var saved = conversations.post(conv, me, body.body());
        var dto = ConversationMessageDto.from(saved, markdown.render(saved.getBodyMarkdown()));
        broker.convertAndSend("/topic/conversations/" + id, dto);
        return dto;
    }

    @PostMapping("/{conversationId}/attachments")
    public ConversationMessageDto uploadAttachment(@PathVariable Long conversationId,
                                                   HttpServletRequest request,
                                                   Principal principal) throws IOException {
        if (!JakartaServletFileUpload.isMultipartContent(request)) {
            throw new IllegalArgumentException("Expected multipart/form-data");
        }
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "dm-attachment-upload", 10, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("upload rate exceeded");
        }
        var conv = conversations.requireById(conversationId);
        var maxBytes = currentUser.uploadCapBytes(principal);

        String caption = "";
        ai.intellistream.threadorbit.domain.ConversationAttachment savedAttachment = null;

        var upload = new JakartaServletFileUpload<>();
        try {
            var iter = upload.getItemIterator(request);
            while (iter.hasNext()) {
                var item = iter.next();
                if (item.isFormField()) {
                    if ("caption".equals(item.getFieldName())) {
                        caption = UploadParts.readSmallField(item);
                    } else {
                        item.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                        throw new IllegalArgumentException("Unknown form field: " + item.getFieldName());
                    }
                    continue;
                }
                if (!"file".equals(item.getFieldName())) {
                    item.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                    throw new IllegalArgumentException("Unknown file part: " + item.getFieldName());
                }
                if (savedAttachment != null) {
                    throw new IllegalArgumentException("Only one file per upload");
                }
                savedAttachment = attachments.upload(
                        conv, me, item.getName(), item.getContentType(), -1L, maxBytes, caption,
                        item.getInputStream());
            }
        } catch (org.apache.commons.fileupload2.core.FileUploadException e) {
            throw new IllegalArgumentException("Malformed upload: " + e.getMessage(), e);
        }

        if (savedAttachment == null) {
            throw new IllegalArgumentException("File part is required");
        }

        var message = savedAttachment.getMessage();
        var dto = ConversationMessageDto.from(message,
                markdown.render(message.getBodyMarkdown()),
                List.of(savedAttachment));
        broker.convertAndSend("/topic/conversations/" + conversationId, dto);
        return dto;
    }

    /**
     * Download a conversation attachment. Non-members get a flat 404 — same response as
     * "this attachment id doesn't exist" — so a probing user can't tell whether a given
     * attachment lives in a DM they don't belong to.
     */
    @GetMapping("/{conversationId}/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long conversationId,
                                                       @PathVariable Long attachmentId,
                                                       @RequestParam(value = "disposition", required = false) String dispositionParam,
                                                       Principal principal) throws IOException {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "dm-attachment-download", 200, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("attachment download rate exceeded");
        }
        ai.intellistream.threadorbit.domain.ConversationAttachment attachment;
        try {
            attachment = attachments.requireForDownload(conversationId, attachmentId, me);
        } catch (NoSuchElementException ex) {
            return ResponseEntity.notFound().build();
        }
        var path = attachments.resolve(attachment);
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        var resource = new FileSystemResource(path);
        var encoded = URLEncoder.encode(attachment.getFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        var contentType = attachment.getContentType() == null ? "" : attachment.getContentType();
        var inlineSafe = contentType.startsWith("image/") && !contentType.equalsIgnoreCase("image/svg+xml");
        var inline = "inline".equalsIgnoreCase(dispositionParam) && inlineSafe;
        var disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded;

        return ResponseEntity.ok()
                .contentType(UploadParts.parseMediaType(attachment.getContentType()))
                .contentLength(attachment.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    /**
     * For a DIRECT conversation, return the participant that *isn't* the viewer.
     * Returns {@code null} for GROUP conversations or if no other member is found
     * (shouldn't happen in practice — DM creation always seeds two members).
     */
    private User otherParticipant(ai.intellistream.threadorbit.domain.Conversation conv, User me) {
        if (conv.getType() != ConversationType.DIRECT) return null;
        return conversations.members(conv).stream()
                .map(m -> m.getUser())
                .filter(u -> !u.getId().equals(me.getId()))
                .findFirst()
                .orElse(null);
    }

}
