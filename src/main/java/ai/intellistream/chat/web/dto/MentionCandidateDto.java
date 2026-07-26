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

/**
 * One row of the composer's @-mention typeahead.
 *
 * <p>Carries exactly what a row needs to look like the avatar hovercard — avatar, display name,
 * handle — because the whole point of the feature is that the UI leads with display names while
 * the mention syntax needs the handle, and the user cannot be expected to know the mapping. Two
 * people called "Alice Anderson" are distinguishable here and nowhere else.
 *
 * <p>Nothing private rides along: no email, no last-active timestamp. Same posture as
 * {@link UserProfileDto}, for the same reason — this endpoint answers prefix queries, so anything
 * it exposes is enumerable by walking the alphabet.
 *
 * @param member {@code false} for a hit that is not in the channel being composed to. Only
 *   {@code PUBLIC} channels ever produce those, and the client marks them, because mentioning
 *   someone who isn't in the room is a different act from mentioning someone who is.
 */
public record MentionCandidateDto(
        String username,
        String displayName,
        boolean member,
        boolean hasAvatar,
        long avatarVersion
) {
}
