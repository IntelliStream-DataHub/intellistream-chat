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
import ai.intellistream.radiance.security.RateLimitExceededException;
import ai.intellistream.radiance.security.RateLimiter;
import ai.intellistream.radiance.service.AttachmentService;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.MarkdownRenderer;
import ai.intellistream.radiance.web.dto.MessageDto;
import ai.intellistream.radiance.web.dto.MessageEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
public class AttachmentRestController {

    private final AttachmentService attachmentService;
    private final ChannelService channelService;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;

    public AttachmentRestController(AttachmentService attachmentService,
                                    ChannelService channelService,
                                    MarkdownRenderer markdown,
                                    CurrentUser currentUser,
                                    SimpMessagingTemplate broker,
                                    RateLimiter rateLimiter) {
        this.attachmentService = attachmentService;
        this.channelService = channelService;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Streamed multipart upload. Spring's MultipartResolver is bypassed for this URL
     * (see {@code MultipartConfig}), so we read the request body chunk by chunk with
     * Apache Commons FileUpload and pipe the file part directly to disk.
     */
    @PostMapping("/api/channels/{channelId}/attachments")
    public MessageDto upload(@PathVariable UUID channelId,
                             HttpServletRequest request,
                             Principal principal) throws IOException {
        if (!JakartaServletFileUpload.isMultipartContent(request)) {
            throw new IllegalArgumentException("Expected multipart/form-data");
        }
        var me = currentUser.resolve(principal);
        // 10 uploads per minute per user — well above what real chatting produces.
        if (!rateLimiter.tryAcquire(me.getUsername(), "attachment-upload", 10, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("upload rate exceeded");
        }
        var channel = channelService.requireById(channelId);
        var maxBytes = currentUser.uploadCapBytes(principal);

        String caption = "";
        String filename = null;
        String contentType = null;
        var savedAttachment = (ai.intellistream.radiance.domain.Attachment) null;

        var upload = new JakartaServletFileUpload<>();
        try {
            var iter = upload.getItemIterator(request);
            while (iter.hasNext()) {
                var item = iter.next();
                if (item.isFormField()) {
                    if ("caption".equals(item.getFieldName())) {
                        caption = UploadParts.readSmallField(item);
                    } else {
                        // Reject unknown form fields so a client typo (or probing) surfaces clearly
                        // rather than silently being ignored.
                        item.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                        throw new IllegalArgumentException("Unknown form field: " + item.getFieldName());
                    }
                    continue;
                }
                if (!"file".equals(item.getFieldName())) {
                    item.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                    throw new IllegalArgumentException("Unknown file part: " + item.getFieldName());
                }
                if ("file".equals(item.getFieldName())) {
                    if (savedAttachment != null) {
                        throw new IllegalArgumentException("Only one file per upload");
                    }
                    filename = item.getName();
                    contentType = item.getContentType();
                    // The service consumes the InputStream and copies straight to disk —
                    // never holds the whole file in memory.
                    savedAttachment = attachmentService.upload(
                            channel, me, filename, contentType, -1L, maxBytes, caption,
                            item.getInputStream());
                }
            }
        } catch (org.apache.commons.fileupload2.core.FileUploadException e) {
            throw new IllegalArgumentException("Malformed upload: " + e.getMessage(), e);
        }

        if (savedAttachment == null) {
            throw new IllegalArgumentException("File part is required");
        }

        var message = savedAttachment.getMessage();
        var dto = MessageDto.from(message,
                markdown.render(message.getBodyMarkdown()),
                List.of(savedAttachment));
        broker.convertAndSend("/topic/channels/" + channelId, MessageEvent.created(dto));
        return dto;
    }

    @GetMapping("/api/attachments/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id,
                                             @RequestParam(value = "disposition", required = false) String dispositionParam,
                                             Principal principal) throws IOException {
        var me = currentUser.resolve(principal);
        // Cap a single signed-in user's download fan-out so they can't trivially exhaust
        // upstream bandwidth by hot-looping every attachment they have read access to.
        if (!rateLimiter.tryAcquire(me.getUsername(), "attachment-download", 200, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("attachment download rate exceeded");
        }
        var attachment = attachmentService.requireForDownload(id, me);
        var path = attachmentService.resolve(attachment);
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        var resource = new FileSystemResource(path);
        var encoded = URLEncoder.encode(attachment.getFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        // Only honor inline for image types — letting arbitrary user-uploaded HTML/SVG render
        // in-browser would be an XSS vector even with nosniff. The X-Content-Type-Options header
        // below blocks MIME sniffing, but inline-rendering an image/svg+xml would still execute
        // scripts in some browsers, so cap inline to image/* (excluding SVG).
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

}
