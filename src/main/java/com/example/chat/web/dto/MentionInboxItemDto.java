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

package com.example.chat.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the topbar mention inbox. {@code snippet} is the raw markdown body
 * truncated to a short preview — the dropdown clamps further with CSS, but we
 * cap the wire size so a long message can't bloat the inbox payload.
 */
public record MentionInboxItemDto(
        UUID messageId,
        UUID channelId,
        String channelSlug,
        String channelName,
        String authorUsername,
        String authorDisplayName,
        String snippet,
        Instant createdAt
) {
    private static final int SNIPPET_MAX = 240;

    public static MentionInboxItemDto of(UUID messageId, UUID channelId,
                                         String channelSlug, String channelName,
                                         String authorUsername, String authorDisplayName,
                                         String body, Instant createdAt) {
        var snippet = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (snippet.length() > SNIPPET_MAX) {
            snippet = snippet.substring(0, SNIPPET_MAX) + "…";
        }
        return new MentionInboxItemDto(messageId, channelId, channelSlug, channelName,
                authorUsername, authorDisplayName, snippet, createdAt);
    }
}
