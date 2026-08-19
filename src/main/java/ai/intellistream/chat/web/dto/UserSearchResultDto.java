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

import ai.intellistream.chat.domain.User;

import java.time.Instant;

/**
 * Wire format for a row in the "Find user" browsers — the channel settings one
 * ({@code GET /api/channels/{id}/invite-candidates}) and the new-conversation one
 * ({@code GET /api/users/directory}). Deliberately narrower than
 * {@link ChannelMemberDto}: no {@code role}/{@code admin} (the person isn't a member yet, so
 * neither applies) and no email — a viewer can filter by email domain, but the endpoints never
 * hand back an address they didn't already know.
 */
public record UserSearchResultDto(
        String username,
        String displayName,
        boolean hasAvatar,
        long avatarVersion,
        Instant createdAt
) {
    public static UserSearchResultDto from(User u) {
        return new UserSearchResultDto(
                u.getUsername(),
                u.getDisplayName(),
                u.hasAvatar(),
                u.avatarVersion(),
                u.getCreatedAt()
        );
    }
}
