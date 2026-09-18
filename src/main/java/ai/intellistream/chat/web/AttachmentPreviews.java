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

import ai.intellistream.chat.attachments.PreviewableAttachments;
import ai.intellistream.chat.attachments.PreviewableAttachments.Kind;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.web.dto.HtmlPreviewDto;
import ai.intellistream.chat.web.dto.MarkdownPreviewDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turn an already-authorised attachment into what its viewer shows. Channel attachments and
 * conversation attachments are different entities behind different access checks, but from the
 * point where the caller has proved they may read the file, showing it is the same three steps —
 * is it this kind, read it capped, hand it over. One copy of that, so the two pairs of endpoints
 * cannot drift into different caps, different refusals, or one of them rendering through
 * {@code render()} and decorating mentions the other doesn't.
 *
 * <p>Each caller does its own authorisation first and passes the resolved path; nothing here
 * checks who is asking, which is exactly why it must never be called before that check.
 */
final class AttachmentPreviews {

    private AttachmentPreviews() {}

    static MarkdownPreviewDto markdown(String filename, String contentType, Path path,
                                       MarkdownRenderer markdown) throws IOException {
        var source = read(filename, contentType, path, Kind.MARKDOWN);
        // renderDocument, not render: the same parse and safelist a message body gets, without the
        // mention pass — an @name in a file notified nobody, and pretending otherwise in the UI
        // would be a lie the reader can't check. See MarkdownRenderer.renderDocument.
        return new MarkdownPreviewDto(filename, markdown.renderDocument(source.text()), source.truncated());
    }

    /**
     * The file's own markup, unsanitised — see {@link HtmlPreviewDto} for the single way it may
     * be shown, and why nothing here tries to clean it first.
     */
    static HtmlPreviewDto html(String filename, String contentType, Path path) throws IOException {
        var source = read(filename, contentType, path, Kind.HTML);
        return new HtmlPreviewDto(filename, source.text(), source.truncated());
    }

    private static PreviewableAttachments.Source read(String filename, String contentType, Path path,
                                                      Kind expected) throws IOException {
        if (PreviewableAttachments.kindOf(filename, contentType) != expected) {
            // The client only draws the button when the DTO carries a previewUrl, and only ever
            // calls the endpoint that URL names. Reaching here means a hand-made request asking
            // for the wrong reader — answer it plainly rather than, say, framing a ZIP.
            throw new PublicBadRequestException("That file can't be shown as a " + expected.slug() + " document.");
        }
        if (!Files.isRegularFile(path)) {
            throw new ResourceNotFoundException("attachment bytes missing: " + filename);
        }
        return PreviewableAttachments.read(path, expected.maxChars());
    }
}
