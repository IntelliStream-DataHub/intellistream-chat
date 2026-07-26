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
import ai.intellistream.chat.domain.NotificationLevel;


/**
 * One channel row in the sidebar.
 *
 * @param notifyLevel this member's <b>raw</b> notification setting for the channel —
 *                    {@code DEFAULT} when they are following their account-wide default. Carried
 *                    here so opening a channel page costs zero extra requests to render the
 *                    per-channel picker, and raw rather than resolved so the picker can show
 *                    <em>Default</em> as the selected option instead of silently pre-selecting
 *                    whatever it happens to resolve to. Resolve it against
 *                    {@link SidebarView#notifyDefault} for display. Always {@code DEFAULT} on a
 *                    channel the viewer has not joined ({@code joined == false}), where there is
 *                    no membership and so no setting.
 */
public record ChannelSidebarDto(
        Long id,
        String slug,
        String name,
        ChannelType type,
        boolean joined,
        boolean admin,
        long unreadCount,
        long mentionCount,
        NotificationLevel notifyLevel
) {
    /**
     * The sidebar's order: case-insensitive by name, ties broken by id.
     *
     * <p>Alphabetical is the honest default for a list whose job is spatial memory — the position
     * of a row changes only when the viewer joins or leaves something, which is a change they made
     * themselves. The id tiebreak is what makes the order <em>total</em>: two channels sharing a
     * name would otherwise swap places between page loads, reintroducing exactly the instability
     * this ordering exists to remove.
     */
    public static final java.util.Comparator<ChannelSidebarDto> BY_NAME = java.util.Comparator
            .comparing((ChannelSidebarDto d) -> d.name().toLowerCase(java.util.Locale.ROOT))
            .thenComparing(ChannelSidebarDto::id);

    /** For a channel the viewer has not joined: no membership, so nothing but {@code DEFAULT}. */
    public static ChannelSidebarDto of(Channel c, boolean joined, boolean admin) {
        return of(c, joined, admin, NotificationLevel.DEFAULT);
    }

    public static ChannelSidebarDto of(Channel c, boolean joined, boolean admin,
                                       NotificationLevel notifyLevel) {
        return new ChannelSidebarDto(c.getId(), c.getSlug(), c.getName(), c.getType(), joined, admin,
                0, 0, notifyLevel == null ? NotificationLevel.DEFAULT : notifyLevel);
    }

    public ChannelSidebarDto withCounts(long unread, long mentions) {
        return new ChannelSidebarDto(id, slug, name, type, joined, admin, unread, mentions, notifyLevel);
    }
}
