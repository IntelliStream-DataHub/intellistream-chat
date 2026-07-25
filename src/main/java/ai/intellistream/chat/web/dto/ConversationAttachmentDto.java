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

package ai.intellistream.chat.web.dto;

import ai.intellistream.chat.domain.ConversationAttachment;

import java.time.Instant;


/**
 * Wire format for files attached to a {@link ai.intellistream.chat.domain.ConversationMessage}.
 * The {@code downloadUrl} is namespaced under the owning conversation so the download
 * endpoint can do membership checks against that conversation's roster.
 */
public record ConversationAttachmentDto(
        Long id,
        String filename,
        String contentType,
        long sizeBytes,
        String downloadUrl,
        Instant createdAt
) {
    public static ConversationAttachmentDto from(ConversationAttachment a) {
        var convId = a.getMessage().getConversation().getId();
        return new ConversationAttachmentDto(
                a.getId(),
                a.getFilename(),
                a.getContentType(),
                a.getSizeBytes(),
                "/api/conversations/" + convId + "/attachments/" + a.getId() + "/download",
                a.getCreatedAt()
        );
    }
}
