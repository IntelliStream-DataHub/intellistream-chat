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

import jakarta.validation.constraints.Size;

/**
 * Where a message is being forwarded to, and what the forwarder wants to say about it.
 *
 * <p>Exactly one of {@code channelId} and {@code conversationId} must be set — the controller
 * rejects zero or two rather than picking a winner, because a request that names two destinations
 * does not know what it wants and guessing sends somebody's message to the wrong room.
 *
 * @param acknowledgeDisclosure the caller has been shown that forwarding out of a private channel
 *   shows the message to people who are not in it, and means to do it anyway. Required only for a
 *   private source; see {@code MessageForwardService} for why this exists and what it is not.
 */
public record ForwardMessageRequest(
        Long channelId,
        Long conversationId,
        @Size(max = 2000) String comment,
        boolean acknowledgeDisclosure
) {}
