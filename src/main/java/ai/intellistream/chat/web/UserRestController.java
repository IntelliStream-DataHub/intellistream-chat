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

import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.dto.UserProfileDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;

/**
 * Public user lookup powering the avatar hovercard. Same authorization stance as
 * {@code GET /api/users/{username}/avatar}: any authenticated user can read.
 *
 * <p>Rate-limited per viewer to slow down account enumeration: an attacker that obtains
 * one valid session can otherwise probe the full username space by walking 200 vs 4xx
 * responses. 120 lookups/min is enough for the hovercard (one per hover, throttled
 * client-side) but well below brute-force throughput.
 */
@RestController
public class UserRestController {

    private final UserService userService;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public UserRestController(UserService userService,
                              CurrentUser currentUser,
                              RateLimiter rateLimiter) {
        this.userService = userService;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/users/{username}")
    public ResponseEntity<UserProfileDto> profile(@PathVariable String username, Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "user-profile-lookup", 120, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("profile lookup rate exceeded");
        }
        var user = userService.requireByUsername(username);
        return ResponseEntity.ok(UserProfileDto.from(user));
    }
}
