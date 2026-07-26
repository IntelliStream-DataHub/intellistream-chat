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

import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code PUT /api/channels/{id}/favourite}: {@code {"favourite":true}}.
 *
 * <p>A PUT carrying the desired state rather than a POST/DELETE pair, matching
 * {@code PUT /api/channels/{id}/notify}: a star is a per-membership setting, and a client that
 * double-fires — a mis-click, a retry after a timeout — lands on the state it asked for instead of
 * toggling to the opposite one.
 *
 * <p>Boxed {@code Boolean} with {@code @NotNull} on purpose: a primitive would silently read a
 * missing field as {@code false} and unstar the channel.
 */
public record SetFavouriteRequest(@NotNull Boolean favourite) {
}
