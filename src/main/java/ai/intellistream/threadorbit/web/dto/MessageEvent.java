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



/**
 * Envelope broadcast on {@code /topic/channels/{id}} so clients can distinguish
 * new messages from edits and deletes.
 *
 * <ul>
 *   <li>{@code created} — {@code message} carries the new {@link MessageDto}.</li>
 *   <li>{@code updated} — {@code message} carries the freshly-rendered {@link MessageDto}.</li>
 *   <li>{@code deleted} — only {@code id} (and optionally {@code parentId}) are populated.</li>
 *   <li>{@code poll-vote} — only {@code id}, {@code channelId} and {@code poll} are populated.
 *       Carries the latest tally so other viewers can update their poll widget without a full
 *       message re-render. {@code poll.myVoteOptionId} reflects the actor's vote, so
 *       receivers should ignore it and keep their local "I voted for X" state.</li>
 * </ul>
 */
public record MessageEvent(
        String type,
        Long id,
        Long channelId,
        Long parentId,
        MessageDto message,
        PollDto poll
) {
    public static MessageEvent created(MessageDto m) {
        return new MessageEvent("created", m.id(), m.channelId(), m.parentId(), m, null);
    }

    public static MessageEvent updated(MessageDto m) {
        return new MessageEvent("updated", m.id(), m.channelId(), m.parentId(), m, null);
    }

    public static MessageEvent deleted(Long id, Long channelId, Long parentId) {
        return new MessageEvent("deleted", id, channelId, parentId, null, null);
    }

    public static MessageEvent pollVote(Long messageId, Long channelId, PollDto poll) {
        return new MessageEvent("poll-vote", messageId, channelId, null, null, poll);
    }
}
