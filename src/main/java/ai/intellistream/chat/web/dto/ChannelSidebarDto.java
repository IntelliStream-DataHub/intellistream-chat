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
import ai.intellistream.chat.domain.ChannelMember;
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.NotificationLevel;


/**
 * One channel row in the sidebar.
 *
 * <p>Everything per-user on this record comes off a {@link ChannelMember}, which is why the two
 * factories are {@link #notJoined} and {@link #joined}: a channel the viewer is not in has no
 * membership, and therefore no notification level, no star and no meaningful counts. Passing those
 * as loose booleans invited exactly the mistake of claiming a setting for a row that cannot have one.
 *
 * <p>There is deliberately no {@code admin} flag. It existed to render a star meaning "you are an
 * admin of this channel" — which is not what a star means in any comparable product, and is not
 * something worth seeing in a list you scan fifty times a day. The role is shown where it is
 * actually needed, in the members panel.
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
 * @param favourite   whether the viewer has starred the channel. Starred channels group at the top
 *                    of the sidebar. Always {@code false} when {@code joined == false}.
 */
public record ChannelSidebarDto(
        Long id,
        String slug,
        String name,
        ChannelType type,
        boolean joined,
        boolean favourite,
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

    /** A channel the viewer can see but has not joined: no membership, so no per-user state. */
    public static ChannelSidebarDto notJoined(Channel c) {
        return new ChannelSidebarDto(c.getId(), c.getSlug(), c.getName(), c.getType(),
                false, false, 0, 0, NotificationLevel.DEFAULT);
    }

    /** A channel the viewer is a member of. Counts are filled in later by {@link #withCounts}. */
    public static ChannelSidebarDto joined(Channel c, ChannelMember membership) {
        var level = membership.getNotifyLevel();
        return new ChannelSidebarDto(c.getId(), c.getSlug(), c.getName(), c.getType(),
                true, membership.isFavourite(), 0, 0,
                level == null ? NotificationLevel.DEFAULT : level);
    }

    public ChannelSidebarDto withCounts(long unread, long mentions) {
        return new ChannelSidebarDto(id, slug, name, type, joined, favourite, unread, mentions,
                notifyLevel);
    }

    /**
     * How loudly this row should announce what it is holding.
     *
     * <p>Three states, and the reason there are three rather than "badge / no badge" is that a
     * number on every channel with any unread is noise. A busy channel produces one permanently,
     * so the badge stops meaning "look at this" and starts meaning "this channel exists", and a
     * user who learns to ignore all of them also ignores the one that mattered. Slack and
     * Mattermost both solve it the same way and it is the right answer: ordinary unread is a
     * <em>weight</em> change, and the number is reserved for the thing a number is useful for —
     * how many times somebody addressed you by name.
     */
    public enum UnreadCue {
        /** Nothing to say. Also what a muted channel says about ordinary traffic. */
        NONE,
        /** There is unread here: emphasise the name. No number. */
        BOLD,
        /** You were mentioned: show how many times. */
        COUNT
    }

    /** The level actually in force for this row, resolving {@code DEFAULT} against the account. */
    public NotificationLevel effectiveNotifyLevel(NotificationLevel accountDefault) {
        return notifyLevel.resolvedAgainst(accountDefault);
    }

    /** True when this channel is muted ({@code NONE}, resolved) — visibly de-emphasised. */
    public boolean muted(NotificationLevel accountDefault) {
        return effectiveNotifyLevel(accountDefault) == NotificationLevel.NONE;
    }

    /**
     * The cue for this row. One implementation, because the server render and the live JS update
     * both go through it (JS reproduces this table against the same two counts, carried on the row
     * as data attributes) — and a sidebar whose badges change when you reload is worse than either
     * behaviour on its own.
     *
     * <p>Muting is the interesting case. A muted channel <b>still counts</b> its unread: muting
     * means "stop telling me", not "pretend nothing happened", and a count that silently stopped
     * accruing would be a lie the user could not detect. So the count is kept and the cue is NONE —
     * no bold, no badge, and the row is dimmed by whoever renders it.
     *
     * <p>A mention in a muted channel is the one exception, and it gets its badge. The reasoning:
     * mute governs <em>interruption</em>, and a badge is not an interruption — it makes no sound,
     * raises no toast, and is only seen by someone already looking at the sidebar. What it does is
     * make the thing findable later, which is exactly what you want from a channel you muted and
     * where somebody has now called you by name. The toast and the chime stay suppressed, which is
     * the part the user actually asked for. Slack behaves the same way.
     */
    public UnreadCue unreadCue(NotificationLevel accountDefault) {
        if (mentionCount > 0) {
            return UnreadCue.COUNT;
        }
        if (unreadCount <= 0 || muted(accountDefault)) {
            return UnreadCue.NONE;
        }
        return UnreadCue.BOLD;
    }
}
