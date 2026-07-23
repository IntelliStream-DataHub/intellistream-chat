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

import ai.intellistream.threadorbit.domain.PresenceKind;
import ai.intellistream.threadorbit.security.CurrentUser;
import ai.intellistream.threadorbit.service.PresenceService;
import ai.intellistream.threadorbit.web.dto.PresenceDto;
import ai.intellistream.threadorbit.web.dto.SetPresenceKindRequest;
import ai.intellistream.threadorbit.web.dto.SetStatusRequest;
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

    private final PresenceService presenceService;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;

    public PresenceRestController(PresenceService presenceService,
                                  CurrentUser currentUser,
                                  SimpMessagingTemplate broker) {
        this.presenceService = presenceService;
        this.currentUser = currentUser;
        this.broker = broker;
    }

    @GetMapping
    public List<PresenceDto> get(@RequestParam(value = "usernames", required = false) String usernames) {
        if (usernames == null || usernames.isBlank()) return List.of();
        var split = Arrays.stream(usernames.split(",")).map(String::trim).toList();
        return presenceService.presenceFor(split);
    }

    @PostMapping("/status")
    public PresenceDto setStatus(@RequestBody SetStatusRequest body, Principal principal) {
        var me = currentUser.resolve(principal);
        var dto = presenceService.setStatus(me, body.emoji(), body.text(), body.clearAt());
        broker.convertAndSend("/topic/presence", dto);
        return dto;
    }

    @DeleteMapping("/status")
    public PresenceDto clearStatus(Principal principal) {
        var me = currentUser.resolve(principal);
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
        var kind = body == null || body.kind() == null ? PresenceKind.ACTIVE : body.kind();
        var dto = presenceService.setKind(me, kind);
        broker.convertAndSend("/topic/presence", dto);
        return dto;
    }

    @DeleteMapping("/kind")
    public PresenceDto clearKind(Principal principal) {
        var me = currentUser.resolve(principal);
        var dto = presenceService.clearKind(me);
        broker.convertAndSend("/topic/presence", dto);
        return dto;
    }
}
