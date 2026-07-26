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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The editable half of a channel: its name and its description.
 *
 * <p>Same constraints as {@link CreateChannelRequest} for the same two fields, because a name that
 * could not have been created should not be reachable by editing into it. {@code type} is absent
 * deliberately — a PUBLIC↔PRIVATE flip is the one channel change that is an authorization decision
 * rather than a cosmetic one (see {@code ChannelAccessCache}), and it is not part of this.
 *
 * <p>Description is nullable and blank means "none". This is what Slack calls the topic and
 * Mattermost the purpose; the name here follows the column, which has been {@code description}
 * since the first migration.
 */
public record UpdateChannelRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description
) {
}
