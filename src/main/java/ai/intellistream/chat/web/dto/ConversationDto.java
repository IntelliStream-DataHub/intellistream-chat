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

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;



/**
 * Sidebar/page entry for a {@link Conversation}. For DIRECT conversations we surface
 * the *other* participant's identity (the viewer doesn't want to see their own name
 * in the DM list); for GROUP conversations we use the conversation title.
 *
 * <p>A DM with yourself has no other participant, so it is titled here — see
 * {@link #SELF_TITLE}. Doing it in the DTO rather than storing a title on the row keeps
 * {@code Conversation.title} meaning "a group's name" and nothing else, and every caller already
 * routes through here on its way to a template.
 */
public record ConversationDto(
        Long id,
        ConversationType type,
        String title,
        String otherUsername,
        String otherDisplayName,
        boolean otherHasAvatar,
        long otherAvatarVersion,
        long unreadCount,
        NotificationLevel notifyLevel
) {
    /**
     * What a DM with yourself is called. "You", not the user's own display name: in a list of
     * people you are talking to, your own name reads as somebody else.
     */
    public static final String SELF_TITLE = "You";

    /**
     * Build a sidebar/page entry. {@code other} is the participant the viewer is talking to
     * for DIRECT conversations, or {@code null} for GROUP (where the title carries identity).
     */
    public static ConversationDto of(Conversation conversation, User other) {
        return of(conversation, other, 0L);
    }

    public static ConversationDto of(Conversation conversation, User other, long unreadCount) {
        return of(conversation, other, unreadCount, NotificationLevel.DEFAULT);
    }

    /**
     * @param notifyLevel the viewer's <em>raw</em> level for this conversation — {@code DEFAULT}
     *        when it follows the account default. Raw, not resolved, because the sidebar row
     *        resolves it against {@code me-notify-default} in the same expression a channel row
     *        uses, and resolving it here would put that decision in two places.
     */
    public static ConversationDto of(Conversation conversation, User other, long unreadCount,
                                     NotificationLevel notifyLevel) {
        var level = notifyLevel == null ? NotificationLevel.DEFAULT : notifyLevel;
        if (conversation.isSelfDirect()) {
            // Callers reach this two ways and neither can supply an "other": the page/sidebar path
            // filters the viewer out of the member list and gets null, while the start-a-DM endpoint
            // passes the person asked for, who here is the viewer. Both must render the same, so the
            // title comes from the conversation's shape rather than from the argument. The avatar
            // fields stay populated when we were handed the user, so the row keeps a picture.
            return new ConversationDto(
                    conversation.getId(),
                    conversation.getType(),
                    SELF_TITLE,
                    other == null ? null : other.getUsername(),
                    other == null ? null : other.getDisplayName(),
                    other != null && other.hasAvatar(),
                    other == null ? 0L : other.avatarVersion(),
                    unreadCount,
                    level
            );
        }
        if (conversation.getType() == ConversationType.DIRECT && other != null) {
            return new ConversationDto(
                    conversation.getId(),
                    conversation.getType(),
                    other.getDisplayName() == null ? other.getUsername() : other.getDisplayName(),
                    other.getUsername(),
                    other.getDisplayName(),
                    other.hasAvatar(),
                    other.avatarVersion(),
                    unreadCount,
                    level
            );
        }
        return new ConversationDto(
                conversation.getId(),
                conversation.getType(),
                conversation.getTitle(),
                null, null, false, 0L,
                unreadCount,
                level
        );
    }
}
