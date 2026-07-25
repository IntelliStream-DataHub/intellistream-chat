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

package ai.intellistream.threadorbit.web.dto;

import java.util.List;

/**
 * The curated left sidebar: a short, ranked shortlist rather than every channel that exists.
 *
 * <p>Listing everything stops working long before it stops rendering. A user in several hundred
 * channels gets a wall of links they have to read linearly to find anything, and the page pays for
 * loading all of it on every request. So the sidebar shows two small groups — the channels with
 * the most people in them, and the ones with the most traffic lately — and everything else is
 * found by searching, with results in the main content area where there's room to show them.
 *
 * @param largest      the user's channels with the most members.
 * @param mostActive   the user's channels with the most recent traffic, plus anything demanding
 *                     attention: a channel with unread messages is promoted here even if it is
 *                     otherwise quiet, because an unread badge nobody can see is pointless.
 * @param hiddenCount  how many of the user's joined channels didn't make either list — surfaced so
 *                     the UI can say "and 812 more" rather than silently pretending they're gone.
 */
public record SidebarView(
        List<ChannelSidebarDto> largest,
        List<ChannelSidebarDto> mostActive,
        int hiddenCount
) {
    public boolean isEmpty() {
        return largest.isEmpty() && mostActive.isEmpty();
    }
}
