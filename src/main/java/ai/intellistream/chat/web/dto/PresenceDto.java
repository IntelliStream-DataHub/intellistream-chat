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

import ai.intellistream.chat.domain.PresenceKind;

import java.time.Instant;

/**
 * Wire shape for both the REST batch endpoint and the {@code /topic/presence} WS broadcast.
 *
 * <p>{@code kind} is the effective presence state — Slack/Mattermost-style:
 * {@code ACTIVE} (auto: connected and no override), {@code AWAY} / {@code DND} /
 * {@code OFFLINE} (user-chosen overrides that beat the auto state).
 *
 * <p>{@code online} is kept for backwards-compatibility and is true exactly when
 * {@code kind == ACTIVE} — old clients that only know the boolean still work,
 * new clients should switch on {@code kind} for the proper four-way dot.
 *
 * <p>{@code statusEmoji} / {@code statusText} are the user's persisted custom
 * status (lunch break etc.) — independent of {@code kind} and already filtered
 * for {@code statusClearAt} expiry by the service before sending.
 */
public record PresenceDto(
        String username,
        boolean online,
        PresenceKind kind,
        String statusEmoji,
        String statusText,
        Instant statusClearAt
) {
    public static PresenceDto online(String username) {
        return new PresenceDto(username, true, PresenceKind.ACTIVE, null, null, null);
    }

    public static PresenceDto offline(String username) {
        return new PresenceDto(username, false, PresenceKind.OFFLINE, null, null, null);
    }
}
