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
import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code PUT /api/channels/{id}/notify} and {@code PUT /api/profile/notify-default}:
 * {@code {"level":"ALL|MENTIONS|NONE"}}, plus {@code DEFAULT} on the channel endpoint, where it
 * clears the override and puts the channel back to following the account default.
 *
 * <p>{@code DEFAULT} on the profile endpoint is a 400 — the account default is the bottom of the
 * inheritance chain and has nothing to inherit from. Jackson maps the enum by name,
 * case-sensitive; an unrecognised name is a 400 as well.
 */
public record SetNotifyLevelRequest(@NotNull NotificationLevel level) {
}
