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

import ai.intellistream.chat.attachments.AttachmentMedia;
import ai.intellistream.chat.domain.Attachment;

import java.time.Instant;


public record AttachmentDto(
        Long id,
        String filename,
        String contentType,
        long sizeBytes,
        String downloadUrl,
        Instant createdAt,
        /**
         * Set when the uploader removed the file from the file manager. The message survives an
         * attachment deletion, so the client needs to render something in the file's place —
         * these two fields are that something. Null for a live attachment.
         */
        Instant deletedAt,
        String deletedBy,
        /**
         * The type to offer the browser's video player, or null when this isn't a video (or is a
         * tombstone). Derived from the stored row on every read — see {@link AttachmentMedia} —
         * so files uploaded before the player existed get one too. Whether the browser can
         * actually play it is decided client-side; this only says it is worth asking.
         */
        String videoType
) {
    public static AttachmentDto from(Attachment a) {
        boolean gone = a.isDeleted();
        return new AttachmentDto(
                a.getId(),
                a.getFilename(),
                a.getContentType(),
                a.getSizeBytes(),
                // No link for a tombstone: the bytes are gone, and offering a download that
                // 404s is worse than offering none.
                gone ? null : "/api/attachments/" + a.getId() + "/download",
                a.getCreatedAt(),
                a.getDeletedAt(),
                gone ? a.getDeletedByUsername() : null,
                gone ? null : AttachmentMedia.videoType(a.getContentType(), a.getFilename()));
    }
}
