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

import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.ConversationMessage;

import java.time.Instant;
import java.util.List;


/**
 * Wire format for messages inside a {@link ai.intellistream.chat.domain.Conversation}.
 * Mirrors the relevant subset of {@link MessageDto}: attachments, reactions and — since DMs got
 * threads — {@code parentId} and {@code replyCount}, which carry the same meanings they do there.
 *
 * <p>{@code parentId} is how the client tells a reply from a feed message on a topic that carries
 * both, so it is populated on every message and not only on the ones a thread panel asked for.
 * {@code threadParticipants} rides along on a reply's broadcast for the same reason it does on the
 * channel side: the people in a thread are the ones a reply is addressed to, and the client cannot
 * work that out from a message it never saw.
 */
public record ConversationMessageDto(
        Long id,
        Long conversationId,
        Long parentId,
        String authorUsername,
        String authorDisplayName,
        boolean authorHasAvatar,
        long authorAvatarVersion,
        String bodyMarkdown,
        String bodyHtml,
        Instant createdAt,
        Instant editedAt,
        long replyCount,
        List<String> threadParticipants,
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
        return from(m, html, attachments, reactions, 0L, List.of());
    }

    public static ConversationMessageDto from(ConversationMessage m, String html,
                                              List<ConversationAttachment> attachments,
                                              List<ReactionGroupDto> reactions,
                                              long replyCount,
                                              List<String> threadParticipants) {
        var author = m.getAuthor();
        // getParent() hands back a lazy proxy on the paths that don't join-fetch it; getId() reads
        // the identifier off the proxy without initialising it, so this is safe after the
        // transaction has closed. Nothing else on the parent is touched.
        var parent = m.getParent();
        return new ConversationMessageDto(
                m.getId(),
                m.getConversation().getId(),
                parent == null ? null : parent.getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.hasAvatar(),
                author.avatarVersion(),
                m.getBodyMarkdown(),
                html,
                m.getCreatedAt(),
                m.getEditedAt(),
                replyCount,
                threadParticipants,
                attachments.stream().map(ConversationAttachmentDto::from).toList(),
                reactions
        );
    }
}
