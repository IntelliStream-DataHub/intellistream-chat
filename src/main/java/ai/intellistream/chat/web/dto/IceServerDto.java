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

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One entry of an {@code RTCConfiguration.iceServers} array, shaped to what the browser expects —
 * the client hands this straight to {@code new RTCPeerConnection({iceServers})} without remapping.
 *
 * <p>{@code username} and {@code credential} are omitted from the JSON when absent rather than sent
 * as nulls, because a STUN entry has neither and a browser handed {@code "username": null} for one
 * is being told something untrue about a server that needs no authentication.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IceServerDto(List<String> urls, String username, String credential) {

    /** A STUN entry: address reflection only, nothing to authenticate to. */
    public static IceServerDto stun(List<String> urls) {
        return new IceServerDto(List.copyOf(urls), null, null);
    }
}
