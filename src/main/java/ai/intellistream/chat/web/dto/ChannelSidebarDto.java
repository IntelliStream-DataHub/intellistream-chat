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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;



public record ChannelSidebarDto(
        Long id,
        String slug,
        String name,
        ChannelType type,
        boolean joined,
        boolean admin,
        long unreadCount,
        long mentionCount
) {
    public static ChannelSidebarDto of(Channel c, boolean joined, boolean admin) {
        return new ChannelSidebarDto(c.getId(), c.getSlug(), c.getName(), c.getType(), joined, admin, 0, 0);
    }

    public ChannelSidebarDto withCounts(long unread, long mentions) {
        return new ChannelSidebarDto(id, slug, name, type, joined, admin, unread, mentions);
    }
}
