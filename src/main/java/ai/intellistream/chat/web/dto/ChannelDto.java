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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;

import java.time.Instant;


/**
 * A channel as an API client sees it.
 *
 * @param archived   read-only, out of the sidebar and out of channel discovery. Present on every
 *                   channel rather than only on archived ones, so a client can render the state
 *                   without inferring it from {@code archivedAt}'s absence.
 * @param archivedAt when that happened; {@code null} while the channel is live.
 * @param archivedBy the archiver's username as it was at the time — a copy, not a live lookup, which
 *                   is what lets the header render it without touching a LAZY association under
 *                   {@code open-in-view=false}.
 */
public record ChannelDto(
        Long id,
        String slug,
        String name,
        String description,
        ChannelType type,
        Instant createdAt,
        boolean archived,
        Instant archivedAt,
        String archivedBy
) {
    public static ChannelDto from(Channel c) {
        return new ChannelDto(c.getId(), c.getSlug(), c.getName(), c.getDescription(), c.getType(),
                c.getCreatedAt(), c.isArchived(), c.getArchivedAt(), c.getArchivedByUsername());
    }
}
