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

package ai.intellistream.chat.web.dto;

import ai.intellistream.chat.attachments.PreviewableAttachments;
import ai.intellistream.chat.domain.Attachment;

import java.time.Instant;


public record AttachmentDto(
        Long id,
        String filename,
        String contentType,
        long sizeBytes,
        String downloadUrl,
        /**
         * Where to GET this file's preview, or null when it isn't one this app can show (see
         * {@link PreviewableAttachments}). It is the server's answer to "does this chip get a
         * preview button", so no client has to know what a markdown file looks like — and the
         * four places that draw an attachment chip cannot disagree about it.
         */
        String previewUrl,
        /**
         * {@code "markdown"} or {@code "html"} — how the client must show what that URL returns.
         * The two are not interchangeable: markdown comes back sanitised and goes into the page,
         * HTML comes back as the uploader wrote it and may only be shown inside a sandboxed
         * iframe. Null whenever {@code previewUrl} is.
         */
        String previewKind,
        Instant createdAt,
        /**
         * Set when the uploader removed the file from the file manager. The message survives an
         * attachment deletion, so the client needs to render something in the file's place —
         * these two fields are that something. Null for a live attachment.
         */
        Instant deletedAt,
        String deletedBy
) {
    public static AttachmentDto from(Attachment a) {
        boolean gone = a.isDeleted();
        var kind = gone ? null : PreviewableAttachments.kindOf(a.getFilename(), a.getContentType());
        return new AttachmentDto(
                a.getId(),
                a.getFilename(),
                a.getContentType(),
                a.getSizeBytes(),
                // No link for a tombstone: the bytes are gone, and offering a download that
                // 404s is worse than offering none.
                gone ? null : "/api/attachments/" + a.getId() + "/download",
                kind == null ? null : "/api/attachments/" + a.getId() + "/" + kind.slug(),
                kind == null ? null : kind.slug(),
                a.getCreatedAt(),
                a.getDeletedAt(),
                gone ? a.getDeletedByUsername() : null);
    }
}
