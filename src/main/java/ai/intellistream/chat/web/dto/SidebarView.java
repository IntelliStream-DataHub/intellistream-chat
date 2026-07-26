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

import ai.intellistream.chat.domain.NotificationLevel;

import java.util.List;

/**
 * The left sidebar: <b>every channel the viewer is a member of</b>, in a stable order.
 *
 * <p>This replaced a ranked shortlist — "your five largest channels" and "your five most active
 * ones", everything else collapsed into "and 812 more". Ranking read well and worked badly. A
 * sidebar is spatial memory: people find {@code #deploys} by where it sits, not by reading the
 * list, and both of those rankings are computed from numbers that drift on their own (someone joins
 * a channel, a quiet channel gets busy) so the list reordered itself under the user for reasons
 * they had no part in. Member count is also a dimension nobody thinks in — nobody has ever wanted
 * their channels sorted by population.
 *
 * <p>So: all of them, alphabetically, and the column scrolls. Alphabetical is the honest default
 * precisely because it is not a judgement — the position of a channel changes only when the user
 * joins or leaves one, which is the one kind of change they caused themselves.
 *
 * <p>Discovering channels the viewer has <em>not</em> joined is a different job and stays with the
 * search box, which queries the server and renders into the main content area where there is room
 * to show what a channel is and offer a Join button.
 *
 * @param channels      every channel the viewer belongs to, ordered by name (case-insensitive,
 *                      ties by id so the order is total). Each row carries its own unread and
 *                      mention counts and its raw notification level.
 * @param notifyDefault the viewer's account-wide notification default — never {@code DEFAULT}.
 *                      Here so the page can render every row's notification state, and the
 *                      per-channel picker, without a second request: each row carries its raw
 *                      level, which is only meaningful next to the default it may be inheriting.
 *                      Resolve as {@code row.notifyLevel == DEFAULT ? notifyDefault : row.notifyLevel}.
 */
public record SidebarView(
        List<ChannelSidebarDto> channels,
        NotificationLevel notifyDefault
) {
    public boolean isEmpty() {
        return channels.isEmpty();
    }

    /**
     * The ids of every channel the viewer belongs to, comma-joined.
     *
     * <p>This is the <b>notification subscription set</b>, and it is deliberately derived here
     * rather than scraped out of the rendered sidebar. The client subscribes to
     * {@code /topic/channels/{id}} for each id in this list; if it instead read the ids off the
     * DOM, then any future change to how the sidebar renders — a collapsed group, a virtualised
     * list, a filter applied server-side — would silently narrow which channels can produce a
     * toast, a chime or a badge. That is exactly the bug this list exists to make impossible: the
     * rendering is a view of the membership, never the source of it.
     */
    public String channelIds() {
        var out = new StringBuilder();
        for (var c : channels) {
            if (out.length() > 0) out.append(',');
            out.append(c.id());
        }
        return out.toString();
    }
}
