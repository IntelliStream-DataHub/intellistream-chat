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

import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Message;

import java.time.Instant;
import java.util.List;


public record MessageDto(
        Long id,
        Long channelId,
        Long parentId,
        String authorUsername,
        String authorDisplayName,
        boolean authorHasAvatar,
        long authorAvatarVersion,
        String bodyMarkdown,
        String bodyHtml,
        Instant createdAt,
        Instant editedAt,
        List<AttachmentDto> attachments,
        List<ReactionGroupDto> reactions,
        long replyCount,
        List<String> mentions,
        PollDto poll,
        List<String> threadParticipants
) {
    public static MessageDto from(Message message, String html) {
        return from(message, html, List.of(), List.of(), 0L, List.of(), null);
    }

    public static MessageDto from(Message message, String html, List<Attachment> attachments) {
        return from(message, html, attachments, List.of(), 0L, List.of(), null);
    }

    public static MessageDto from(Message message, String html,
                                  List<Attachment> attachments,
                                  List<ReactionGroupDto> reactions) {
        return from(message, html, attachments, reactions, 0L, List.of(), null);
    }

    public static MessageDto from(Message message, String html,
                                  List<Attachment> attachments,
                                  List<ReactionGroupDto> reactions,
                                  long replyCount) {
        return from(message, html, attachments, reactions, replyCount, List.of(), null);
    }

    public static MessageDto from(Message message, String html,
                                  List<Attachment> attachments,
                                  List<ReactionGroupDto> reactions,
                                  long replyCount,
                                  List<String> mentions) {
        return from(message, html, attachments, reactions, replyCount, mentions, null);
    }

    public static MessageDto from(Message message, String html,
                                  List<Attachment> attachments,
                                  List<ReactionGroupDto> reactions,
                                  long replyCount,
                                  List<String> mentions,
                                  PollDto poll) {
        return build(message, html, attachments, reactions, replyCount, mentions, poll);
    }

    // Search results are no longer MessageDto: a hit can come from a channel or from a
    // conversation, and the two have different identities and different permalinks. See
    // SearchHitDto — it is where the highlighted snippet lives now.

    /**
     * Attach the thread's participants — the parent's author plus everyone who has replied, minus
     * the person who just did.
     *
     * <p>This is how a thread reply reaches the people in the thread. It rides the existing
     * {@code /topic/channels/{id}} broadcast rather than a per-recipient queue: the reply is already
     * being sent to the whole channel (everyone who can read the channel can read the thread), so the
     * list costs one array on a message that was going out anyway, and each client decides whether it
     * is in it. A fan-out of one message per participant would be strictly more work to deliver
     * strictly less-visible information.
     *
     * <p>Deliberately <b>not</b> the mention bell. The bell is an inbox of things addressed to you by
     * name; a reply in a thread you are in is not that, and folding it in would turn "things
     * addressed to me" into "everything", which is the one property it has. It produces the toast,
     * the chime and the ordinary unread cue, and nothing else.
     *
     * <p>Empty on every other kind of message.
     */
    public MessageDto withThreadParticipants(List<String> participants) {
        return new MessageDto(id, channelId, parentId, authorUsername, authorDisplayName,
                authorHasAvatar, authorAvatarVersion, bodyMarkdown, bodyHtml, createdAt, editedAt,
                attachments, reactions, replyCount, mentions, poll,
                participants == null ? List.of() : List.copyOf(participants));
    }

    private static MessageDto build(Message message, String html,
                                    List<Attachment> attachments,
                                    List<ReactionGroupDto> reactions,
                                    long replyCount,
                                    List<String> mentions,
                                    PollDto poll) {
        var author = message.getAuthor();
        return new MessageDto(
                message.getId(),
                message.getChannel().getId(),
                message.getParent() == null ? null : message.getParent().getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.hasAvatar(),
                author.avatarVersion(),
                message.getBodyMarkdown(),
                html,
                message.getCreatedAt(),
                message.getEditedAt(),
                attachments.stream().map(AttachmentDto::from).toList(),
                reactions,
                replyCount,
                mentions,
                poll,
                List.of()
        );
    }
}
