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

package ai.intellistream.radiance.web.dto;

import ai.intellistream.radiance.domain.ChannelMember;
import ai.intellistream.radiance.domain.ChannelRole;

import java.time.Instant;

/**
 * Wire format for a single member of a {@link ai.intellistream.radiance.domain.Channel}. Mirrors
 * {@link ConversationMemberDto} so the JS can render an avatar + name + admin badge
 * without a second {@code /api/users/{u}} round-trip per row.
 *
 * <p>{@code role} comes from the channel-membership row (ADMIN vs MEMBER); {@code admin}
 * is the workspace-level chat-admin flag stored on the User entity. Two distinct concepts:
 * a channel admin is someone who can invite to <em>that</em> channel; a workspace admin
 * is whoever Keycloak granted the {@code chat-admin} realm role.
 */
public record ChannelMemberDto(
        String username,
        String displayName,
        boolean hasAvatar,
        long avatarVersion,
        ChannelRole role,
        boolean admin,
        Instant joinedAt
) {
    public static ChannelMemberDto from(ChannelMember m) {
        var u = m.getUser();
        return new ChannelMemberDto(
                u.getUsername(),
                u.getDisplayName(),
                u.hasAvatar(),
                u.avatarVersion(),
                m.getRole(),
                u.isAdmin(),
                m.getJoinedAt()
        );
    }
}
