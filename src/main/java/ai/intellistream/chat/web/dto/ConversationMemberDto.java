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

package ai.intellistream.chat.web.dto;

import ai.intellistream.chat.domain.ConversationMember;

import java.time.Instant;

/**
 * Wire format for a single member of a {@link ai.intellistream.chat.domain.Conversation}.
 * Used by {@code GET /api/conversations/{id}/members} and the group conversation
 * page header. Mirrors the relevant subset of {@link UserProfileDto} so the JS can
 * render an avatar without a second {@code /api/users/{u}} round-trip per member.
 */
public record ConversationMemberDto(
        String username,
        String displayName,
        boolean hasAvatar,
        long avatarVersion,
        boolean admin,
        Instant joinedAt
) {
    public static ConversationMemberDto from(ConversationMember m) {
        var u = m.getUser();
        return new ConversationMemberDto(
                u.getUsername(),
                u.getDisplayName(),
                u.hasAvatar(),
                u.avatarVersion(),
                u.isAdmin(),
                m.getJoinedAt()
        );
    }
}
