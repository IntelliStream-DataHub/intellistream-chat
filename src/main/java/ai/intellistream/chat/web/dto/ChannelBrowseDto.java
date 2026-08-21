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

/**
 * One row of the channel directory: what a channel is, how many people are in it, and whether the
 * viewer is one of them. Rendered by the sidebar's <em>Suggested for you</em> group (Thymeleaf) and
 * by the Browse channels panel (chrome.js) — one shape for both so the two cannot disagree about
 * what a channel row says.
 *
 * <p>Distinct from {@link ChannelSidebarDto}, which is about the viewer's <em>relationship</em>
 * with a channel they are in — unread, mentions, star, notification level — none of which exists
 * for a channel being offered to them. What does matter here is the description and the member
 * count, which the sidebar row has no room for and no use for.
 *
 * @param memberCount how many people are in the channel. The number the directory is ranked by,
 *                    and shown, because "where everybody already is" is the whole basis on which
 *                    a newcomer picks a first channel.
 * @param joined      whether the viewer is already a member; the directory shows a tag instead of
 *                    a Join button for these.
 */
public record ChannelBrowseDto(
        Long id,
        String slug,
        String name,
        String description,
        long memberCount,
        boolean joined
) {
    public static ChannelBrowseDto of(Channel c, long memberCount, boolean joined) {
        return new ChannelBrowseDto(c.getId(), c.getSlug(), c.getName(), c.getDescription(),
                memberCount, joined);
    }
}
