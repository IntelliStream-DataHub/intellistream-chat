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

import java.util.List;

/**
 * One emoji's reactions on a message: total count, the usernames who reacted (so the UI
 * can show a tooltip), and a flag for whether the current viewer reacted.
 */
public record ReactionGroupDto(
        String emoji,
        long count,
        boolean mine,
        List<String> usernames
) {}
