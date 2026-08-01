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

package ai.intellistream.chat.web;

import ai.intellistream.chat.calls.CallProperties;
import ai.intellistream.chat.calls.TurnCredentialService;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.web.dto.IceConfigDto;
import ai.intellistream.chat.web.dto.IceServerDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/calls/ice} — the ICE servers and transport policy a client needs before it can
 * open a peer connection.
 *
 * <p>This exists as an endpoint rather than as fields on the page because the TURN credential in it
 * expires. Rendering it into the HTML would tie a ten-minute credential to the lifetime of a tab,
 * and a DM left open over lunch would then fail to place a call with a credential that looked
 * present and was not. Fetching it at the moment a call starts means it is always fresh, and it is
 * one request on a path that is about to negotiate a media session anyway.
 *
 * <p>It is authenticated like every other browser API call — session cookie on the web filter
 * chain — because minting TURN credentials for anonymous callers is the same as not having a secret.
 */
@RestController
@RequestMapping("/api/calls")
public class CallRestController {

    private final CallProperties properties;
    private final TurnCredentialService turnCredentials;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public CallRestController(CallProperties properties,
                              TurnCredentialService turnCredentials,
                              CurrentUser currentUser,
                              RateLimiter rateLimiter) {
        this.properties = properties;
        this.turnCredentials = turnCredentials;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/ice")
    public IceConfigDto ice(Principal principal) {
        var user = currentUser.resolve(principal);
        // One call needs one of these. The budget is for the client that retries in a loop after a
        // failed connection — minting is an HMAC, so the cost being defended is the credential
        // itself, not the CPU: every mint is another ten-minute key to the relay.
        if (!rateLimiter.tryAcquire(user.getUsername(), "call-ice", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("ICE configuration rate exceeded");
        }
        if (!properties.isConfigured()) {
            // Not an error. An operator who has not set up TURN gets a well-formed answer saying
            // calling is unavailable, and the client hides the buttons rather than offering one
            // that would fail at the moment somebody pressed it.
            return IceConfigDto.unavailable();
        }

        var servers = new ArrayList<IceServerDto>(2);
        if (!properties.isForceRelay() && !properties.getStunUrls().isEmpty()) {
            // No credentials on STUN: it only reflects the address it sees. Omitted entirely under
            // force-relay, where the browser will not gather a server-reflexive candidate anyway.
            servers.add(IceServerDto.stun(properties.getStunUrls()));
        }
        var credential = turnCredentials.mint(user.getUsername());
        servers.add(new IceServerDto(
                properties.getTurnUrls(), credential.username(), credential.credential()));

        return new IceConfigDto(
                true,
                properties.isVideo(),
                properties.isForceRelay() ? "relay" : "all",
                List.copyOf(servers));
    }
}
