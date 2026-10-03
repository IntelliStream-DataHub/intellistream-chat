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
import ai.intellistream.chat.web.dto.HtmlPreviewDto;
import ai.intellistream.chat.web.dto.MarkdownPreviewDto;
import ai.intellistream.chat.web.dto.MessageDto;
import ai.intellistream.chat.web.dto.MessageEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.security.Principal;
import java.util.List;

@RestController
public class AttachmentRestController {

    private final AttachmentService attachmentService;
    private final ChannelService channelService;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;
    private final LinkPreviews linkPreviews;

    public AttachmentRestController(AttachmentService attachmentService,
                                    ChannelService channelService,
                                    MarkdownRenderer markdown,
                                    CurrentUser currentUser,
                                    SimpMessagingTemplate broker,
                                    RateLimiter rateLimiter,
                                    LinkPreviews linkPreviews) {
        this.linkPreviews = linkPreviews;
        this.attachmentService = attachmentService;
        this.channelService = channelService;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Streamed upload: the file <b>is</b> the request body, and its metadata rides in headers
     * (see {@link RawUpload}). The bytes go from the socket to the disk without being parsed,
     * buffered in memory, or staged in a temp file.
     */
    @PostMapping("/api/channels/{channelId}/attachments")
    public MessageDto upload(@PathVariable Long channelId,
                             HttpServletRequest request,
                             Principal principal) throws IOException {
        var me = currentUser.resolve(principal);
        // 10 uploads per minute per user — well above what real chatting produces.
        if (!rateLimiter.tryAcquire(me.getUsername(), "attachment-upload", 10, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("upload rate exceeded");
        }
        var channel = channelService.requireById(channelId);
        var maxBytes = currentUser.uploadCapBytes(principal);
        var upload = RawUpload.from(request, true);

        // Content-Length lets an over-cap upload be refused before a single byte is written;
        // the service still enforces the cap while streaming, for clients that under-declare.
        var savedAttachment = attachmentService.upload(
                channel, me, upload.filename(), upload.contentType(), upload.declaredLength(),
                maxBytes, upload.caption(), upload.body());

        var message = savedAttachment.getMessage();
        var dto = MessageDto.from(message,
                markdown.render(message.getBodyMarkdown()),
                List.of(savedAttachment));
        broker.convertAndSend("/topic/channels/" + channelId, MessageEvent.created(dto));
        linkPreviews.unfurl(dto);
        return dto;
    }

    /**
     * The rendered form of a markdown attachment, for the in-page viewer behind the chip's
     * preview button.
     *
     * <p>This is what makes showing the file safe. {@link #download} refuses {@code inline}
     * disposition for everything but raster images and video, because letting user-uploaded bytes
     * render as a document in this origin is how an upload becomes stored XSS. Here the bytes never reach the
     * browser: they are read, parsed and sanitised on the server, and the client gets HTML that
     * went through the same safelist as every message body.
     *
     * <p>Same read authorisation as the download — {@code requireForDownload} — so a preview can
     * never show a file its viewer could not have fetched. Same for {@link #htmlPreview} below.
     */
    @GetMapping("/api/attachments/{id}/markdown")
    public MarkdownPreviewDto markdownPreview(@PathVariable Long id, Principal principal) throws IOException {
        var attachment = requireForPreview(id, principal);
        return AttachmentPreviews.markdown(attachment.getFilename(), attachment.getContentType(),
                attachmentService.resolve(attachment), markdown);
    }

    /**
     * An HTML attachment, as its uploader wrote it — for the sandboxed iframe the viewer puts it
     * in. Unsanitised on purpose; {@link HtmlPreviewDto} carries the whole reasoning and the one
     * way the client is allowed to show it.
     */
    @GetMapping("/api/attachments/{id}/html")
    public HtmlPreviewDto htmlPreview(@PathVariable Long id, Principal principal) throws IOException {
        var attachment = requireForPreview(id, principal);
        return AttachmentPreviews.html(attachment.getFilename(), attachment.getContentType(),
                attachmentService.resolve(attachment));
    }

    private ai.intellistream.chat.domain.Attachment requireForPreview(Long id, Principal principal) {
        var me = currentUser.resolve(principal);
        // A preview costs a file read plus, for markdown, a CommonMark parse and two jsoup passes,
        // so it is capped well below the download limit. Opening previews by hand never
        // approaches 30/min.
        if (!rateLimiter.tryAcquire(me.getUsername(), "attachment-preview", 30, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("attachment preview rate exceeded");
        }
        return attachmentService.requireForDownload(id, me);
    }

    @GetMapping("/api/attachments/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id,
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
        return UploadParts.fileResponse(new FileSystemResource(path),
                attachment.getFilename(), attachment.getContentType(), dispositionParam);
    }

}
