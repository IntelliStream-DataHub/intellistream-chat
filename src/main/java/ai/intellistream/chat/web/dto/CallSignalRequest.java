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

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * One SDP or ICE candidate on its way to the other peer.
 *
 * <p>{@code kind} is constrained to the three things WebRTC negotiation actually exchanges. The
 * server does not act on it — the payload is opaque and gets relayed whatever this says — but an
 * open string field on a pass-through relay is an invitation to use the call channel as a private
 * message bus, and the allowlist costs one annotation.
 *
 * @param payload the {@code RTCSessionDescription} or {@code RTCIceCandidate}, verbatim. Size is
 *        capped in {@code CallService}.
 */
public record CallSignalRequest(
        @NotBlank @Pattern(regexp = "offer|answer|candidate") String kind,
        @NotNull JsonNode payload) {
}
