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

package ai.intellistream.threadorbit.web;

import ai.intellistream.threadorbit.security.CurrentUser;
import ai.intellistream.threadorbit.security.RateLimitExceededException;
import ai.intellistream.threadorbit.security.RateLimiter;
import ai.intellistream.threadorbit.service.AvatarService;
import ai.intellistream.threadorbit.service.UserService;
import ai.intellistream.threadorbit.web.dto.UserEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.security.Principal;
import java.time.Duration;

@RestController
public class AvatarRestController {

    private final AvatarService avatarService;
    private final UserService userService;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;
    private final SimpMessagingTemplate broker;

    public AvatarRestController(AvatarService avatarService,
                                UserService userService,
                                CurrentUser currentUser,
                                RateLimiter rateLimiter,
                                SimpMessagingTemplate broker) {
        this.avatarService = avatarService;
        this.userService = userService;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
        this.broker = broker;
    }

    /**
     * Streamed avatar upload: the image is the raw request body (see {@link RawUpload}), piped to
     * disk by {@link AvatarService#upload}. No filename header is required — the stored file is
     * keyed by the user, and the type is sniffed from the bytes anyway.
     */
    @PostMapping("/api/profile/avatar")
    public ResponseEntity<Void> upload(HttpServletRequest request, Principal principal) throws IOException {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "avatar-upload", 5, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("avatar upload rate exceeded");
        }
        var upload = RawUpload.from(request, false);
        var saved = avatarService.upload(me, upload.contentType(), upload.body());
        broker.convertAndSend("/topic/users",
                UserEvent.avatarUpdated(saved.getUsername(), saved.avatarVersion()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/profile/avatar")
    public ResponseEntity<Void> clear(Principal principal) {
        var me = currentUser.resolve(principal);
        var cleared = avatarService.clear(me);
        broker.convertAndSend("/topic/users",
                UserEvent.avatarRemoved(cleared.getUsername()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Public to all authenticated users — anyone you can chat with can see your avatar.
     * The {@code v} query string is just a cache-buster the client appends from the
     * server-supplied {@code avatarVersion}; we don't validate it.
     */
    @GetMapping("/api/users/{username}/avatar")
    public ResponseEntity<Resource> get(@PathVariable String username, Principal principal) throws IOException {
        var me = currentUser.resolve(principal);
        // Hot path during chat rendering — be permissive but bounded so a single signed-in
        // attacker can't saturate disk I/O / bandwidth by hammering this endpoint.
        if (!rateLimiter.tryAcquire(me.getUsername(), "avatar-download", 600, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("avatar download rate exceeded");
        }
        // Return a uniform 404 for an unknown user, matching the known-user-without-avatar case —
        // otherwise the 400-vs-404 split is a username-existence oracle at 5× the profile
        // endpoint's anti-enumeration budget (N18).
        ai.intellistream.threadorbit.domain.User user;
        try {
            user = userService.requireByUsername(username);
        } catch (IllegalArgumentException unknownUser) {
            return ResponseEntity.notFound().build();
        }
        var path = avatarService.resolve(user);
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        var contentType = user.getAvatarContentType() == null
                ? "application/octet-stream" : user.getAvatarContentType();
        var resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(Files.size(path))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}
