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
 * Something happened to the channel itself, broadcast on {@code /topic/channels/{id}} beside the
 * message traffic so open clients repaint the header and the sidebar row without a reload.
 *
 * <p>The precedent is {@code /topic/users} carrying {@code avatar-updated}: metadata that several
 * open pages are already displaying, changed by one of them, and stale everywhere else until
 * somebody refreshes. A channel's name is the same kind of fact, and it is on screen in more places
 * than an avatar — the header, the sidebar row, the composer placeholder, the leave button.
 *
 * <p><b>Every type is prefixed {@code channel-}, and that is not decoration.</b> This shares a
 * destination with {@link MessageEvent}, which already uses the bare words {@code created},
 * {@code updated} and {@code deleted}. An unprefixed {@code updated} here would land in the client's
 * message-edit branch and be handed to code that immediately dereferences {@code event.message},
 * which is null on this record. The prefix is what keeps two event vocabularies on one topic from
 * being one ambiguous vocabulary.
 *
 * <p>Clients that predate this record ignore these frames: both handlers in {@code chat/index.js}
 * dispatch on an exact type match and fall through silently on anything unrecognised.
 *
 * @param type one of {@code channel-updated}, {@code channel-archived},
 *             {@code channel-unarchived}, {@code channel-deleted}.
 * @param id   the channel.
 * @param slug     the current slug. Carried because a rename moves it, and a client holding the old
 *                 one would build a wrong link if it ever built one from a slug.
 * @param archived the resulting state, carried on <em>every</em> type rather than being implied by
 *                 the archive/unarchive ones. A client that missed a frame is then corrected by the
 *                 next one it does see, instead of accumulating drift from a stream of deltas.
 */
public record ChannelEvent(
        String type,
        Long id,
        String slug,
        String name,
        String description,
        boolean archived
) {
    public static ChannelEvent updated(Channel c) {
        return of("channel-updated", c);
    }

    public static ChannelEvent archived(Channel c) {
        return of("channel-archived", c);
    }

    public static ChannelEvent unarchived(Channel c) {
        return of("channel-unarchived", c);
    }

    private static ChannelEvent of(String type, Channel c) {
        return new ChannelEvent(type, c.getId(), c.getSlug(), c.getName(), c.getDescription(),
                c.isArchived());
    }
}
