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

import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ConversationAttachmentService;
import ai.intellistream.chat.service.ConversationReactionService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.dto.AddGroupMemberRequest;
import ai.intellistream.chat.web.dto.ConversationDto;
import ai.intellistream.chat.web.dto.ConversationEvent;
import ai.intellistream.chat.web.dto.ConversationMemberDto;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
import ai.intellistream.chat.web.dto.CreateGroupRequest;
import ai.intellistream.chat.web.dto.EditMessageRequest;
import ai.intellistream.chat.web.dto.ReactionRequest;
import ai.intellistream.chat.web.dto.StartDirectRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    private final StorageQuotaService quotas;
    private final ConversationAlertPublisher alerts;

    public ConversationRestController(ConversationService conversations,
                                      UserService userService,
                                      CurrentUser currentUser,
                                      MarkdownRenderer markdown,
                                      ConversationAttachmentService attachments,
                                      ConversationReactionService reactions,
                                      SimpMessagingTemplate broker,
                                      RateLimiter rateLimiter,
                                      StorageQuotaService quotas,
                                      ConversationAlertPublisher alerts) {
        this.quotas = quotas;
        this.alerts = alerts;
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
        requireRate(me, "user-lookup", 20);
        var other = userService.requireByUsername(request.username());
        var conv = conversations.directBetween(me, other);
        return ConversationDto.of(conv, other);
    }

    /**
     * Create a named group conversation. The caller is added as a member automatically;
     * {@code request.members()} is the list of *other* usernames to seed. Unresolved names are
     * reported back generically ("one or more could not be found") — never listed individually,
     * so this endpoint can't be used as a username-existence oracle.
     */
    @PostMapping("/group")
    public ConversationDto createGroup(@Valid @RequestBody CreateGroupRequest request,
                                       Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me, "user-lookup", 20);
        var memberNames = request.members().stream()
                .filter(u -> u != null && !u.isBlank())
                .map(String::trim)
                .filter(u -> !u.equalsIgnoreCase(me.getUsername()))
                .distinct()
                .toList();
        if (memberNames.isEmpty()) {
            throw new ai.intellistream.chat.security.PublicBadRequestException(
                    "A group needs at least one other member besides yourself.");
        }
        // Cap the batch so one request can't probe a large candidate list at once.
        if (memberNames.size() > 50) {
            throw new ai.intellistream.chat.security.PublicBadRequestException(
                    "A group can be seeded with at most 50 members at once.");
        }
        var seed = new java.util.ArrayList<User>();
        boolean anyUnknown = false;
        for (var name : memberNames) {
            try {
                seed.add(userService.requireByUsername(name));
            } catch (IllegalArgumentException ex) {
                anyUnknown = true;
            }
        }
        if (anyUnknown) {
            throw new ai.intellistream.chat.security.PublicBadRequestException(
                    "One or more of those members could not be found.");
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
        requireRate(me, "user-lookup", 20);
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
    public List<ConversationMessageDto> messages(@PathVariable Long id,
                                                 @RequestParam(value = "after", required = false) java.time.Instant after,
                                                 Principal principal) {
        var me = currentUser.resolve(principal);
        var conv = conversations.requireById(id);
        // ?after= drives the reconnect backfill (N4/BUG-3): the messages missed during an outage,
        // oldest-first. No param → the latest 50 for the initial page load.
        var rows = after != null ? conversations.after(conv, me, after, 50)
                                  : conversations.recent(conv, me, 50);
        return renderAll(rows, me);
    }

    /**
     * One thread: the message that started it plus its replies, oldest first.
     *
     * <p>Keyed on the message rather than on {@code /conversations/{id}/messages/{mid}/thread}: the
     * conversation is a property of the message, and taking it from the path instead would let a
     * member of one conversation name another's message id and have the membership check pass
     * against the wrong room.
     */
    @GetMapping("/messages/{messageId}/thread")
    public ConversationThreadDto thread(@PathVariable Long messageId, Principal principal) {
        var me = currentUser.resolve(principal);
        var parent = conversations.requireMessageById(messageId);
        conversations.requireMember(parent.getConversation(), me);
        var replies = conversations.threadReplies(messageId, me);
        var parentDto = renderAll(List.of(parent), me).get(0);
        return new ConversationThreadDto(parentDto, renderAll(replies, me));
    }

    /**
     * Reply in a thread. HTTP rather than STOMP, matching the channel side: a reply is rare next to
     * a feed message and needs the saved row back to render optimistically, which the fire-and-forget
     * send path cannot give it.
     */
    @PostMapping("/messages/{messageId}/replies")
    public ConversationMessageDto reply(@PathVariable Long messageId,
                                        @Valid @RequestBody ai.intellistream.chat.web.dto.SendMessageRequest body,
                                        Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me, "dm-reply", 30);
        var saved = conversations.replyInThread(messageId, me, body.body());
        var participants = conversations.threadParticipants(messageId, me);
        var dto = ConversationMessageDto.from(saved, markdown.render(saved.getBodyMarkdown()),
                List.of(), List.of(), 0L, participants);
        // Same destination as a feed message; the client routes on parentId. A second topic for
        // replies would mean two subscriptions per conversation and a whole class of "the reply
        // arrived but the panel was on the other socket" bugs.
        broker.convertAndSend("/topic/conversations/" + dto.conversationId(), dto);
        alerts.alert(saved.getConversation(), saved);
        return dto;
    }

    /** Hydrate a batch of conversation messages: markdown, attachments, reactions, reply counts. */
    private List<ConversationMessageDto> renderAll(
            List<ai.intellistream.chat.domain.ConversationMessage> rows, User viewer) {
        if (rows.isEmpty()) return List.of();
        var attachmentMap = attachments.findForMessages(rows);
        var reactionMap = reactions.groupingsFor(rows, viewer);
        var replyCounts = conversations.threadReplyCounts(rows);
        return rows.stream()
                .map(m -> ConversationMessageDto.from(m,
                        markdown.render(m.getBodyMarkdown()),
                        attachmentMap.getOrDefault(m.getId(), List.of()),
                        reactionMap.getOrDefault(m.getId(), List.of()),
                        replyCounts.getOrDefault(m.getId(), 0L),
                        List.of()))
                .toList();
    }

    /** A thread as the panel wants it: the parent, then the replies. */
    public record ConversationThreadDto(ConversationMessageDto parent,
                                        List<ConversationMessageDto> replies) {}

    @PatchMapping("/messages/{messageId}")
    public ConversationMessageDto editMessage(@PathVariable Long messageId,
                                              @Valid @RequestBody EditMessageRequest body,
                                              Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me, "dm-msg-edit", 30);
        var updated = conversations.editMessage(messageId, me, body.body());
        return broadcastUpdate(updated, me);
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long messageId, Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me, "dm-msg-delete", 30);
        // Capture the attachment rows BEFORE the delete cascades them away: the storage keys so the
        // files can be reaped, and the (uploader, size) pairs so the bytes can be credited back.
        // Neither survives the cascade — the file on disk names no owner — and a DM delete is a
        // hard delete, so the bytes really are freed and the credit is owed. Both are applied after
        // deleteMessage's tx commits (it's @Transactional, so it has committed once it returns).
        // deleteMessage does the authz — on failure it throws and neither is touched.
        //
        // The thread goes with its parent, so the replies' attachments are gathered here too.
        // Missing them would leave their files on disk with no row naming them and their bytes
        // charged to an uploader forever — the rows are the only record of either.
        var doomed = new java.util.ArrayList<>(attachments.forMessage(messageId));
        for (var replyId : conversations.replyIdsOf(messageId)) {
            doomed.addAll(attachments.forMessage(replyId));
        }
        var keys = doomed.stream().map(ConversationAttachment::getStorageKey).toList();
        // Live rows only: one the uploader already deleted from the file manager was credited then.
        var credits = ConversationAttachmentService.creditsForLive(doomed);
        var deleted = conversations.deleteMessage(messageId, me);
        attachments.deleteFiles(keys);
        quotas.releaseAll(credits);
        var parent = deleted.getParent();
        broker.convertAndSend("/topic/conversations/" + deleted.getConversation().getId(),
                ConversationEvent.messageDeleted(deleted.getConversation().getId(), deleted.getId(),
                        parent == null ? null : parent.getId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages/{messageId}/reactions")
    public ConversationMessageDto addReaction(@PathVariable Long messageId,
                                              @Valid @RequestBody ReactionRequest body,
                                              Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me, "dm-reaction-toggle", 60);
        var message = reactions.addReaction(messageId, me, body.emoji());
        return broadcastUpdate(message, me);
    }

    @DeleteMapping("/messages/{messageId}/reactions/{emoji}")
    public ResponseEntity<Void> removeReaction(@PathVariable Long messageId,
                                               @PathVariable String emoji,
                                               Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me, "dm-reaction-toggle", 60);
        var message = reactions.removeReaction(messageId, me, emoji);
        broadcastUpdate(message, me);
        return ResponseEntity.noContent().build();
    }

    /** Per-(user,action) sliding-window guard; throws RateLimitExceededException on breach. */
    private void requireRate(User me, String action, int perMinute) {
        if (!rateLimiter.tryAcquire(me.getUsername(), action, perMinute, java.time.Duration.ofMinutes(1))) {
            throw new ai.intellistream.chat.security.RateLimitExceededException(action + " rate exceeded");
        }
    }

    /** Broadcast an updated DTO over the conversation topic and return it to the caller. */
    private ConversationMessageDto broadcastUpdate(ai.intellistream.chat.domain.ConversationMessage message, User viewer) {
        var atts = attachments.findForMessage(message);
        var rs = reactions.groupingsFor(message, viewer);
        // The reply count rides along, because the client repaints the whole message from this DTO
        // and a "3 replies" indicator that vanished when somebody reacted would look like the
        // replies had.
        var dto = ConversationMessageDto.from(message,
                markdown.render(message.getBodyMarkdown()), atts, rs,
                conversations.threadReplyCount(message), List.of());
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
                                              @Valid @RequestBody ai.intellistream.chat.web.dto.SendMessageRequest body,
                                              Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "dm-http-send", 30, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("send rate exceeded");
        }
        var conv = conversations.requireById(id);
        var saved = conversations.post(conv, me, body.body());
        var dto = ConversationMessageDto.from(saved, markdown.render(saved.getBodyMarkdown()));
        broker.convertAndSend("/topic/conversations/" + id, dto);
        alerts.alert(conv, saved);
        return dto;
    }

    @PostMapping("/{conversationId}/attachments")
    public ConversationMessageDto uploadAttachment(@PathVariable Long conversationId,
                                                   HttpServletRequest request,
                                                   Principal principal) throws IOException {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "dm-attachment-upload", 10, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("upload rate exceeded");
        }
        var conv = conversations.requireById(conversationId);
        var maxBytes = currentUser.uploadCapBytes(principal);
        var upload = RawUpload.from(request, true);

        // The file is the request body — streamed straight to disk, no multipart parsing.
        var savedAttachment = attachments.upload(
                conv, me, upload.filename(), upload.contentType(), upload.declaredLength(),
                maxBytes, upload.caption(), upload.body());

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
        ai.intellistream.chat.domain.ConversationAttachment attachment;
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
    private User otherParticipant(ai.intellistream.chat.domain.Conversation conv, User me) {
        if (conv.getType() != ConversationType.DIRECT) return null;
        return conversations.members(conv).stream()
                .map(m -> m.getUser())
                .filter(u -> !u.getId().equals(me.getId()))
                .findFirst()
                .orElse(null);
    }

}
