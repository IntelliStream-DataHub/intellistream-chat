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

import ai.intellistream.chat.domain.User;

import java.time.Instant;

/**
 * Public-facing snapshot of a user, served to any authenticated viewer that hovers
 * over their avatar. Mirrors the avatar visibility posture: anyone you can chat with
 * can see your name, username, and rough activity.
 *
 * <p>Email is intentionally NOT exposed here. With this endpoint reachable to any
 * authenticated peer at 120 req/min, including email turned a single compromised
 * account into a full org email-directory dump by walking usernames. The admin page
 * still exposes email (with its own {@code expose-user-emails} privacy toggle) for
 * operators who actively need the directory; the hovercard does not.
 */
public record UserProfileDto(
        String username,
        String displayName,
        Instant createdAt,
        Instant lastActiveAt,
        boolean hasAvatar,
        long avatarVersion,
        boolean admin
) {
    public static UserProfileDto from(User user) {
        return new UserProfileDto(
                user.getUsername(),
                user.getDisplayName(),
                user.getCreatedAt(),
                user.getLastActiveAt(),
                user.hasAvatar(),
                user.avatarVersion(),
                user.isAdmin()
        );
    }
}
