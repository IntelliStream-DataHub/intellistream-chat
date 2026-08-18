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
        PollDto poll,
        /**
         * Echo of {@link SendMessageRequest#clientId()} on a {@code created} event, so the sender
         * can retire the optimistic bubble it drew when they hit enter. Null everywhere else. It's
         * a client-generated nonce carrying no information, so broadcasting it to the whole channel
         * costs nothing; only the originating client has anything to match it against.
         */
        String clientId,
        /**
         * The card for message {@code id}, on a {@code link-preview} event — the one field that
         * event carries, the same narrow shape as {@code poll-vote}. It arrives a moment after the
         * {@code created} frame, because the page has to be fetched first; the client finds the
         * message and slots the card in.
         */
        LinkPreviewDto linkPreview
) {
    public static MessageEvent created(MessageDto m) {
        return created(m, null);
    }

    public static MessageEvent created(MessageDto m, String clientId) {
        return new MessageEvent("created", m.id(), m.channelId(), m.parentId(), m, null, clientId, null);
    }

    public static MessageEvent updated(MessageDto m) {
        return new MessageEvent("updated", m.id(), m.channelId(), m.parentId(), m, null, null, null);
    }

    public static MessageEvent deleted(Long id, Long channelId, Long parentId) {
        return new MessageEvent("deleted", id, channelId, parentId, null, null, null, null);
    }

    public static MessageEvent pollVote(Long messageId, Long channelId, PollDto poll) {
        return new MessageEvent("poll-vote", messageId, channelId, null, null, poll, null, null);
    }

    public static MessageEvent linkPreview(Long messageId, Long channelId, LinkPreviewDto preview) {
        return new MessageEvent("link-preview", messageId, channelId, null, null, null, null, preview);
    }
}
