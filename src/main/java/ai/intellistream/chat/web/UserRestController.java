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
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MentionService;
import ai.intellistream.chat.service.UserService;
import ai.intellistream.chat.web.dto.MentionCandidateDto;
import ai.intellistream.chat.web.dto.UserProfileDto;
import ai.intellistream.chat.web.dto.UserSearchResultDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

/**
 * Public user lookup powering the avatar hovercard. Same authorization stance as
 * {@code GET /api/users/{username}/avatar}: any authenticated user can read.
 *
 * <p>Rate-limited per viewer to slow down account enumeration: an attacker that obtains
 * one valid session can otherwise probe the full username space by walking 200 vs 4xx
 * responses. 120 lookups/min is enough for the hovercard (one per hover, throttled
 * client-side) but well below brute-force throughput.
 *
 * <p>Also home to the composer's @-mention typeahead, which is a search over people rather than
 * a lookup of one, and is therefore scoped to a channel or conversation instead of being open
 * over the whole user table — see {@link #mentionCandidates}. The one deliberately unscoped
 * search is {@link #directory}, which feeds starting a conversation — the action in this app
 * that by nature has no shared scope yet; its own doc says what bounds it.
 */
@RestController
public class UserRestController {

    private final UserService userService;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;
    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final MentionService mentionService;

    public UserRestController(UserService userService,
                              CurrentUser currentUser,
                              RateLimiter rateLimiter,
                              ChannelService channelService,
                              ConversationService conversationService,
                              MentionService mentionService) {
        this.userService = userService;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.mentionService = mentionService;
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

    /**
     * Backs the composer's @-mention typeahead: the people the caller could mean by {@code @q},
     * within the channel or conversation they are composing to.
     *
     * <p><b>Scoped, not global.</b> Exactly one of {@code channelId} / {@code conversationId} is
     * required, and the answer is that room's members (a {@code PUBLIC} channel also pads with
     * non-members, since a mention there reaches them — see
     * {@link MentionService#candidatesInChannel}). There is deliberately no unscoped mode: an
     * endpoint that answers prefix queries over every user is a workspace directory, and shipping
     * one as a side effect of an autocomplete is how directories leak.
     *
     * <p><b>Write access, not read access.</b> A typeahead only helps someone who is about to
     * post, and {@code requireWriteAccess} is the check that means "may post here" — it demands
     * real membership even for a {@code PUBLIC} channel, where {@code requireMember} would wave
     * any authenticated viewer through. Using the read check here would hand the member list of
     * every public channel to a lurker one prefix at a time.
     *
     * <p>Its own rate-limit action rather than the neighbouring {@code user-lookup}: that budget
     * is 20/min, sized for a deliberate act like inviting somebody, and a typeahead would exhaust
     * it inside one sentence. 120/min matches the hovercard — the client debounces and narrows
     * cached results locally, so a mention costs about one request.
     */
    /**
     * "Find user" browser for the new-conversation form: up to
     * {@link UserService#MAX_INVITE_CANDIDATES} accounts, optionally narrowed by a username
     * wildcard and/or an email-domain prefix, newest-created first by default
     * ({@code recent=false} for username A–Z). The whole-directory sibling of
     * {@code GET /api/channels/{id}/invite-candidates}, at the bar of the action it feeds:
     * starting a DM needs no shared channel, so there is nothing narrower to scope by.
     * Bounded the same three ways — the cap, the {@code user-search} budget shared with the
     * channel browser, and a DTO that never carries an email address.
     */
    @GetMapping("/api/users/directory")
    public List<UserSearchResultDto> directory(
            @RequestParam(required = false, defaultValue = "") String username,
            @RequestParam(required = false, defaultValue = "") String emailDomain,
            @RequestParam(required = false, defaultValue = "true") boolean recent,
            Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "user-search", 60, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("search rate exceeded");
        }
        return userService.searchDirectory(username, emailDomain, recent,
                        UserService.MAX_INVITE_CANDIDATES)
                .stream()
                .map(UserSearchResultDto::from)
                .toList();
    }

    @GetMapping("/api/mention-candidates")
    public List<MentionCandidateDto> mentionCandidates(
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "8") int limit,
            Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "mention-candidates", 120, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("mention lookup rate exceeded");
        }
        if ((channelId == null) == (conversationId == null)) {
            throw new PublicBadRequestException(
                    "Provide exactly one of channelId or conversationId");
        }
        if (channelId != null) {
            var channel = channelService.requireById(channelId);
            channelService.requireWriteAccess(channel, me);
            return mentionService.candidatesInChannel(channel, q, limit);
        }
        var conversation = conversationService.requireById(conversationId);
        conversationService.requireMember(conversation, me);
        return mentionService.candidatesInConversation(conversation, q, limit);
    }
}
