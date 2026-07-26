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

import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.Message;

import java.time.Instant;

/**
 * One row of {@code GET /api/search}. A search result can now come from a channel or from a
 * conversation (DM / group), and the two have genuinely different identities — a channel message
 * lives at {@code /channels/{channelId}}, a conversation message at
 * {@code /conversations/{conversationId}} — so the wire format is a small union rather than a
 * {@link MessageDto} with a nullable channel.
 *
 * <p>{@code scope} is the discriminator. Exactly one of the {@code channel*} / {@code conversation*}
 * groups is populated; {@link #url} is pre-computed so a client never has to branch on scope just
 * to build a link.
 */
public record SearchHitDto(
        /** Message id. Unique only within its scope — channel and conversation messages are separate tables. */
        Long id,
        /** {@code "channel"} or {@code "conversation"}. */
        String scope,
        /** Non-null iff {@code scope == "channel"}. */
        Long channelId,
        /** Channel name, non-null iff {@code scope == "channel"}. */
        String channelName,
        /**
         * Whether the viewer belongs to this hit's channel. Channel hits only; always true for a
         * conversation hit, which you cannot see without being in it. Search spans every public
         * channel, so false is routine — and a result the viewer has no membership of has to say
         * so, or the read-only page it opens looks broken rather than joinable.
         */
        boolean channelJoined,
        /** Non-null iff {@code scope == "conversation"}. */
        Long conversationId,
        /** {@code "DIRECT"} or {@code "GROUP"}; non-null iff {@code scope == "conversation"}. */
        String conversationType,
        /** Group title, or for a DM the other participant's display name. Conversation hits only. */
        String conversationTitle,
        /** Ready-to-use permalink for this hit, anchored on the message. */
        String url,
        String authorUsername,
        String authorDisplayName,
        boolean authorHasAvatar,
        long authorAvatarVersion,
        String bodyMarkdown,
        String bodyHtml,
        /** Lucene-highlighted excerpt: HTML-escaped, match terms wrapped in {@code <mark>}. May be null. */
        String bodySnippet,
        Instant createdAt,
        Instant editedAt
) {

    public static SearchHitDto ofChannel(Message message, boolean joined, String html, String snippet) {
        var author = message.getAuthor();
        var channel = message.getChannel();
        return new SearchHitDto(
                message.getId(),
                "channel",
                channel.getId(),
                channel.getName(),
                joined,
                null, null, null,
                "/channels/" + channel.getId() + "?m=" + message.getId() + "#m=" + message.getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.hasAvatar(),
                author.avatarVersion(),
                message.getBodyMarkdown(),
                html,
                snippet,
                message.getCreatedAt(),
                message.getEditedAt()
        );
    }

    /** @param title group title, or the other participant's name for a DM; may be null */
    public static SearchHitDto ofConversation(ConversationMessage message, String title,
                                              String html, String snippet) {
        var author = message.getAuthor();
        var conversation = message.getConversation();
        ConversationType type = conversation.getType();
        return new SearchHitDto(
                message.getId(),
                "conversation",
                null, null,
                true, // a conversation you can see is one you are in; there is no non-member tier
                conversation.getId(),
                type == null ? null : type.name(),
                title,
                "/conversations/" + conversation.getId() + "#m=" + message.getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.hasAvatar(),
                author.avatarVersion(),
                message.getBodyMarkdown(),
                html,
                snippet,
                message.getCreatedAt(),
                message.getEditedAt()
        );
    }
}
