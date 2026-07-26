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

import java.time.Instant;

/**
 * One row of the saved-items page.
 *
 * <p>{@code readable} is the interesting field. A save outlives the access that created it — you
 * can leave a private channel, or be removed from one, months after bookmarking something in it —
 * and the honest rendering of that is neither a 500 nor a silent disappearance. The row stays, so
 * the owner can see that something is there and unsave it, and {@code bodyHtml} and the author are
 * withheld, because access to the message is what actually lapsed.
 *
 * <p>An archived channel is a different case and stays fully readable: archiving removes no read
 * access. {@code channelArchived} exists so the page can say so rather than leaving the reader to
 * wonder why the link goes somewhere frozen.
 *
 * @param kind {@code "channel"} or {@code "conversation"}
 * @param url  permalink into the room, anchored on the message — the same shapes
 *             {@code SearchHitDto} builds, so a saved item and a search hit lead to the same place
 */
public record SavedMessageDto(
        Long saveId,
        Instant savedAt,
        String kind,
        Long messageId,
        Long channelId,
        String channelName,
        boolean channelArchived,
        boolean channelPrivate,
        Long conversationId,
        String conversationTitle,
        String url,
        String authorUsername,
        String authorDisplayName,
        Instant createdAt,
        Instant editedAt,
        String bodyHtml,
        boolean readable
) {

    /** The withheld form: everything needed to identify and unsave the row, and nothing else. */
    public static SavedMessageDto unreadableChannelSave(Long saveId, Instant savedAt, Long messageId,
                                                        Long channelId, String channelName) {
        return new SavedMessageDto(saveId, savedAt, "channel", messageId, channelId, channelName,
                false, true, null, null, null, null, null, null, null, null, false);
    }

    public static SavedMessageDto unreadableConversationSave(Long saveId, Instant savedAt,
                                                             Long messageId, Long conversationId) {
        return new SavedMessageDto(saveId, savedAt, "conversation", messageId, null, null,
                false, false, conversationId, null, null, null, null, null, null, null, false);
    }
}
