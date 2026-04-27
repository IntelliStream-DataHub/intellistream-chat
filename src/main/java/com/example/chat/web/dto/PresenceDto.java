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

import java.time.Instant;

/**
 * Wire shape for both the REST batch endpoint and the {@code /topic/presence} WS broadcast.
 * {@code online} is the live-connection signal; {@code statusEmoji} / {@code statusText} are
 * the user's persisted custom status (already filtered for {@code statusClearAt} expiry by the
 * service before sending — clients render whatever they receive).
 */
public record PresenceDto(
        String username,
        boolean online,
        String statusEmoji,
        String statusText,
        Instant statusClearAt
) {
    public static PresenceDto online(String username) {
        return new PresenceDto(username, true, null, null, null);
    }

    public static PresenceDto offline(String username) {
        return new PresenceDto(username, false, null, null, null);
    }
}
