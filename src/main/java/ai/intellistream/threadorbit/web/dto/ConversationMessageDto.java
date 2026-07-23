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

import ai.intellistream.threadorbit.domain.ConversationAttachment;
import ai.intellistream.threadorbit.domain.ConversationMessage;

import java.time.Instant;
import java.util.List;


/**
 * Wire format for messages inside a {@link ai.intellistream.threadorbit.domain.Conversation}.
 * Mirrors the relevant subset of {@link MessageDto}; threads / reactions / mentions
 * stay out of scope, but attachments are first-class so DM file shares render with
 * the same UI affordances as channel messages.
 */
public record ConversationMessageDto(
        Long id,
        Long conversationId,
        String authorUsername,
        String authorDisplayName,
        boolean authorHasAvatar,
        long authorAvatarVersion,
        String bodyMarkdown,
        String bodyHtml,
        Instant createdAt,
        Instant editedAt,
        List<ConversationAttachmentDto> attachments,
        List<ReactionGroupDto> reactions
) {
    public static ConversationMessageDto from(ConversationMessage m, String html) {
        return from(m, html, List.of(), List.of());
    }

    public static ConversationMessageDto from(ConversationMessage m, String html,
                                              List<ConversationAttachment> attachments) {
        return from(m, html, attachments, List.of());
    }

    public static ConversationMessageDto from(ConversationMessage m, String html,
                                              List<ConversationAttachment> attachments,
                                              List<ReactionGroupDto> reactions) {
        var author = m.getAuthor();
        return new ConversationMessageDto(
                m.getId(),
                m.getConversation().getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.hasAvatar(),
                author.avatarVersion(),
                m.getBodyMarkdown(),
                html,
                m.getCreatedAt(),
                m.getEditedAt(),
                attachments.stream().map(ConversationAttachmentDto::from).toList(),
                reactions
        );
    }
}
