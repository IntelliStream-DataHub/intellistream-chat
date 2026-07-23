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

package ai.intellistream.threadorbit.web.dto;

import ai.intellistream.threadorbit.domain.PresenceKind;

/**
 * Body for {@code PUT /api/presence/kind}. Carries the manual override the user
 * picked from the topbar status menu — one of {@code ACTIVE} (clears the
 * override), {@code AWAY}, {@code DND}, or {@code OFFLINE}. Jackson maps the
 * enum by name, case-sensitive.
 */
public record SetPresenceKindRequest(PresenceKind kind) {
}
