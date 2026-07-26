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

import java.time.Instant;

/**
 * One row of a channel's file list (GET /api/channels/{id}/files).
 *
 * <p>Deliberately <b>not</b> {@link UserFileDto}. That record answers "what am I storing, and may I
 * delete it" and carries the delete policy to draw a button with; this one answers "what has been
 * shared in this room, and by whom" and carries an uploader instead. Sharing a record would mean
 * every field being right for one surface and inert on the other — and the inert half is
 * {@code deletable}, which is the one field nobody may guess at.
 *
 * <p>There is no delete here at all. Removing a file stays where it already is: the file manager,
 * for your own uploads, and moderation, for anyone else's. A channel file browser that could delete
 * would be a third removal path with a third rule, and the quota accounting behind it
 * ({@code creditsFor} vs {@code creditsForLive}) has already been the source of one double-credit
 * bug.
 */
public record ChannelFileDto(
        /** Attachment id — for the download URL below, which is the only thing it is used for. */
        Long id,
        String filename,
        String contentType,
        long sizeBytes,
        Instant createdAt,
        String downloadUrl,
        /**
         * Deep link to the message that posted the file, anchored on it. A file posted as a thread
         * reply anchors on the thread's parent — the channel page's {@code ?m=} anchor rejects reply
         * ids and would fall back to "the latest 50", which loses the point of the link.
         */
        String messageUrl,
        String uploaderUsername,
        String uploaderDisplayName,
        boolean uploaderHasAvatar,
        long uploaderAvatarVersion) {
}
