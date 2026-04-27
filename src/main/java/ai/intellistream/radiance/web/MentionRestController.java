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

package ai.intellistream.radiance.web;

import ai.intellistream.radiance.security.CurrentUser;
import ai.intellistream.radiance.service.MentionService;
import ai.intellistream.radiance.service.ReadStateService;
import ai.intellistream.radiance.web.dto.MentionInboxItemDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Read-only inbox of unread @-mentions for the topbar bell. "Unread" reuses the
 * per-channel {@code channel_reads.last_read_at} marker that already drives sidebar
 * badges — visiting a channel and acknowledging it via {@code POST /api/channels/{id}/read}
 * naturally clears the inbox, so there's no separate per-mention read flag to maintain.
 */
@RestController
@RequestMapping("/api/mentions")
public class MentionRestController {

    private static final int DEFAULT_LIMIT = 20;

    private final MentionService mentionService;
    private final ReadStateService readStateService;
    private final CurrentUser currentUser;

    public MentionRestController(MentionService mentionService,
                                 ReadStateService readStateService,
                                 CurrentUser currentUser) {
        this.mentionService = mentionService;
        this.readStateService = readStateService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<MentionInboxItemDto> inbox(@RequestParam(value = "limit", required = false) Integer limit,
                                           Principal principal) {
        var me = currentUser.resolve(principal);
        return mentionService.unreadInbox(me, limit == null ? DEFAULT_LIMIT : limit);
    }

    @GetMapping("/count")
    public Map<String, Long> count(Principal principal) {
        var me = currentUser.resolve(principal);
        return Map.of("unread", mentionService.unreadInboxCount(me));
    }

    /**
     * Advance the viewer's read marker for every channel that has at least one unread
     * mention. Clears both the bell count and any sidebar mention badges in those channels.
     */
    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(Principal principal) {
        var me = currentUser.resolve(principal);
        var marked = readStateService.markAllMentionedChannelsRead(me);
        return Map.of("channelsMarkedRead", marked);
    }
}
