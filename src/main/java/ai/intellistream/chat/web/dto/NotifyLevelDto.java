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

import ai.intellistream.chat.domain.NotificationLevel;

/**
 * {@code {"level":"..."}} — the response for all four notification-preference endpoints.
 *
 * <p>Deliberately the same shape for the channel setting and the account default, even though
 * their vocabularies differ ({@code DEFAULT} is valid only on a channel). One envelope means the
 * client has one parser and one render path, and the picker for a channel is the picker for the
 * account with one extra option.
 *
 * <p>The channel value is the <b>raw</b> level, so {@code DEFAULT} comes back as {@code DEFAULT}
 * rather than as whatever it currently resolves to. The client pairs it with the account default
 * (also carried on the channel-page payload) to label the option "Default (Mentions)".
 */
public record NotifyLevelDto(NotificationLevel level) {

    public static NotifyLevelDto of(NotificationLevel level) {
        return new NotifyLevelDto(level);
    }
}
