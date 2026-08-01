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

import java.util.List;

/**
 * Everything the client needs to build an {@code RTCPeerConnection}, fetched at the moment a call
 * starts.
 *
 * <p>{@code iceTransportPolicy} is served rather than hardcoded in the client so that relaying
 * every call stays an operator decision in one place. A deployment that turns
 * {@code ichat.calls.force-relay} off gets direct connections without a rebuilt JS bundle, and
 * nobody has to remember that the policy was also written down somewhere in {@code static/js}.
 *
 * @param available whether calls can be placed at all — false when no TURN server is configured
 * @param video whether video calls are offered as well as audio
 * @param iceTransportPolicy {@code "relay"} or {@code "all"}, passed through verbatim
 * @param iceServers STUN/TURN entries, already carrying a freshly minted credential
 */
public record IceConfigDto(boolean available,
                           boolean video,
                           String iceTransportPolicy,
                           List<IceServerDto> iceServers) {

    /** No TURN configured: a well-formed "calling is off here" rather than an error. */
    public static IceConfigDto unavailable() {
        return new IceConfigDto(false, false, "relay", List.of());
    }
}
