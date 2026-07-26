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
 * One row of the composer's @-mention typeahead: either a person, or one of the broadcast handles
 * that addresses the whole room.
 *
 * <p>For a person, this carries exactly what a row needs to look like the avatar hovercard —
 * avatar, display name, handle — because the whole point of the feature is that the UI leads with
 * display names while the mention syntax needs the handle, and the user cannot be expected to know
 * the mapping. Two people called "Alice Anderson" are distinguishable here and nowhere else.
 *
 * <p>Nothing private rides along: no email, no last-active timestamp. Same posture as
 * {@link UserProfileDto}, for the same reason — this endpoint answers prefix queries, so anything
 * it exposes is enumerable by walking the alphabet.
 *
 * @param kind {@code "user"} or {@code "broadcast"}. The client renders the two differently but
 *   completes them identically, which is why the handle lives in one field for both.
 * @param username the text inserted after the {@code @} — a username, or {@code channel} /
 *   {@code here} / {@code everyone}.
 * @param displayName {@code null} for a broadcast: the client phrases those, since only it knows
 *   how much room the row has.
 * @param member {@code false} for a hit that is not in the channel being composed to. Only
 *   {@code PUBLIC} channels ever produce those, and the client marks them, because mentioning
 *   someone who isn't in the room is a different act from mentioning someone who is.
 * @param notifyCount how many people the row would notify, or {@code 0} when that isn't a fixed
 *   number. It is the warning Slack shows as "this will notify 240 people", delivered before the
 *   handle is even typed rather than as a dialog after the fact. {@code @here} deliberately reports
 *   0: its audience is whoever is connected at send time, and a count captured while you are still
 *   typing would be a number that is already wrong.
 */
public record MentionCandidateDto(
        String kind,
        String username,
        String displayName,
        boolean member,
        boolean hasAvatar,
        long avatarVersion,
        int notifyCount
) {
    public static final String KIND_USER = "user";
    public static final String KIND_BROADCAST = "broadcast";

    public static MentionCandidateDto user(String username, String displayName, boolean member,
                                           boolean hasAvatar, long avatarVersion) {
        return new MentionCandidateDto(KIND_USER, username, displayName, member,
                hasAvatar, avatarVersion, 0);
    }

    public static MentionCandidateDto broadcast(String handle, int notifyCount) {
        return new MentionCandidateDto(KIND_BROADCAST, handle, null, true, false, 0L, notifyCount);
    }
}
