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

import ai.intellistream.chat.calls.CallMedia;
import jakarta.validation.constraints.NotNull;

/**
 * "Call the other person in this conversation."
 *
 * <p>There is no callee field, and that is the security property rather than a convenience: the
 * server derives who is being called from the conversation's membership, so a caller cannot name
 * somebody they have no conversation with. A payload that carried a username would need checking
 * against exactly the membership this derives it from, and the check is the kind that gets added
 * later.
 */
public record StartCallRequest(@NotNull Long conversationId, @NotNull CallMedia media) {
}
