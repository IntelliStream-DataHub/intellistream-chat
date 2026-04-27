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

package com.example.chat.web.dto;

import com.example.chat.domain.Conversation;
import com.example.chat.domain.ConversationType;
import com.example.chat.domain.User;

import java.util.UUID;

/**
 * Sidebar/page entry for a {@link Conversation}. For DIRECT conversations we surface
 * the *other* participant's identity (the viewer doesn't want to see their own name
 * in the DM list); for GROUP conversations we use the conversation title.
 */
public record ConversationDto(
        UUID id,
        ConversationType type,
        String title,
        String otherUsername,
        String otherDisplayName,
        boolean otherHasAvatar,
        long otherAvatarVersion,
        long unreadCount
) {
    /**
     * Build a sidebar/page entry. {@code other} is the participant the viewer is talking to
     * for DIRECT conversations, or {@code null} for GROUP (where the title carries identity).
     */
    public static ConversationDto of(Conversation conversation, User other) {
        return of(conversation, other, 0L);
    }

    public static ConversationDto of(Conversation conversation, User other, long unreadCount) {
        if (conversation.getType() == ConversationType.DIRECT && other != null) {
            return new ConversationDto(
                    conversation.getId(),
                    conversation.getType(),
                    other.getDisplayName() == null ? other.getUsername() : other.getDisplayName(),
                    other.getUsername(),
                    other.getDisplayName(),
                    other.hasAvatar(),
                    other.avatarVersion(),
                    unreadCount
            );
        }
        return new ConversationDto(
                conversation.getId(),
                conversation.getType(),
                conversation.getTitle(),
                null, null, false, 0L,
                unreadCount
        );
    }
}
