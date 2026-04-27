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

/**
 * Broadcast on {@code /topic/users} when a user-scoped detail changes that the UI shows
 * across many places — currently just the profile picture. Clients listen and refresh
 * the matching avatars without needing a full page reload.
 *
 * <ul>
 *   <li>{@code avatar-updated} — {@code username} now has a fresh picture; clients should
 *       swap their {@code <img>} src to include the new {@code avatarVersion} cache-buster.</li>
 *   <li>{@code avatar-removed} — {@code username} cleared their picture; clients should
 *       drop the {@code <img>} so the fallback initial+colour shows through. The
 *       {@code avatarVersion} field is always {@code 0} for this type — there is no
 *       new picture to cache-bust.</li>
 * </ul>
 */
public record UserEvent(
        String type,
        String username,
        long avatarVersion
) {
    public static UserEvent avatarUpdated(String username, long avatarVersion) {
        return new UserEvent("avatar-updated", username, avatarVersion);
    }

    public static UserEvent avatarRemoved(String username) {
        return new UserEvent("avatar-removed", username, 0L);
    }
}
