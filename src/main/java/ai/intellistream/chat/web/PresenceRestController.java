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

import ai.intellistream.chat.domain.PresenceKind;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.PresenceService;
import ai.intellistream.chat.web.dto.PresenceDto;
import ai.intellistream.chat.web.dto.SetPresenceKindRequest;
import ai.intellistream.chat.web.dto.SetStatusRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;

/**
 * Read-and-set surface for presence:
 * <ul>
 *   <li>{@code GET /api/presence?usernames=a,b} — batch lookup for the sidebar dots and
 *       hovercards. Order matches the input (with blanks dropped).</li>
 *   <li>{@code POST /api/presence/status} — set the caller's custom status; broadcasts the new
 *       state to {@code /topic/presence} so other tabs and other users update live.</li>
 *   <li>{@code DELETE /api/presence/status} — clear it.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/presence")
public class PresenceRestController {

    /** Cap on the batch presence lookup — keeps one request from forcing an oversized IN-query. */
    private static final int MAX_LOOKUP = 200;

    private final PresenceService presenceService;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;

    public PresenceRestController(PresenceService presenceService,
                                  CurrentUser currentUser,
                                  SimpMessagingTemplate broker,
                                  RateLimiter rateLimiter) {
        this.presenceService = presenceService;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<PresenceDto> get(@RequestParam(value = "usernames", required = false) String usernames) {
        if (usernames == null || usernames.isBlank()) return List.of();
        var split = Arrays.stream(usernames.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_LOOKUP)
                .toList();
        return presenceService.presenceFor(split);
    }

    @PostMapping("/status")
    public PresenceDto setStatus(@RequestBody SetStatusRequest body, Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me);
        var dto = presenceService.setStatus(me, body.emoji(), body.text(), body.clearAt());
        broker.convertAndSend("/topic/presence", dto);
        return dto;
    }

    @DeleteMapping("/status")
    public PresenceDto clearStatus(Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me);
        var dto = presenceService.clearStatus(me);
        broker.convertAndSend("/topic/presence", dto);
        return dto;
    }

    /**
     * Set the manual presence override (AWAY / DND / OFFLINE). Pass {@code ACTIVE}
     * to clear back to the auto-derived state — equivalent to {@code DELETE /kind}.
     * Broadcasts the new effective DTO so other clients update.
     */
    @PutMapping("/kind")
    public PresenceDto setKind(@RequestBody SetPresenceKindRequest body, Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me);
        var kind = body == null || body.kind() == null ? PresenceKind.ACTIVE : body.kind();
        var dto = presenceService.setKind(me, kind);
        broker.convertAndSend("/topic/presence", dto);
        return dto;
    }

    @DeleteMapping("/kind")
    public PresenceDto clearKind(Principal principal) {
        var me = currentUser.resolve(principal);
        requireRate(me);
        var dto = presenceService.clearKind(me);
        broker.convertAndSend("/topic/presence", dto);
        return dto;
    }

    /** Each mutation fans out to every /topic/presence subscriber; cap the broadcast rate. */
    private void requireRate(User me) {
        if (!rateLimiter.tryAcquire(me.getUsername(), "presence-mutate", 30, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("presence update rate exceeded");
        }
    }
}
