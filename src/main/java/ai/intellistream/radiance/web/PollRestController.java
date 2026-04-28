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

import ai.intellistream.radiance.repository.PollRepository;
import ai.intellistream.radiance.security.CurrentUser;
import ai.intellistream.radiance.security.RateLimiter;
import ai.intellistream.radiance.service.ChannelService;
import ai.intellistream.radiance.service.PollService;
import ai.intellistream.radiance.web.dto.CastVoteRequest;
import ai.intellistream.radiance.web.dto.MessageEvent;
import ai.intellistream.radiance.web.dto.PollDto;
import jakarta.validation.Valid;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Vote / unvote endpoints for the poll widget. Membership in the host channel is enforced
 * before the write — non-members can't vote in a private channel's polls even if they
 * somehow got the poll id. Each successful mutation also broadcasts a {@code poll-vote}
 * event on the host channel topic so other viewers update their tally live.
 */
@RestController
@RequestMapping("/api/polls")
public class PollRestController {

    private final PollService pollService;
    private final PollRepository pollRepository;
    private final ChannelService channelService;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;

    public PollRestController(PollService pollService,
                              PollRepository pollRepository,
                              ChannelService channelService,
                              CurrentUser currentUser,
                              SimpMessagingTemplate broker,
                              RateLimiter rateLimiter) {
        this.pollService = pollService;
        this.pollRepository = pollRepository;
        this.channelService = channelService;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/{pollId}")
    public PollDto get(@PathVariable Long pollId, Principal principal) {
        var me = currentUser.resolve(principal);
        requireMembership(pollId, me);
        // findByIdWithOptions join-fetches message + channel; plain findById returns a poll
        // whose message is lazy, and with open-in-view off the .getMessage() below would
        // throw LazyInitializationException once the Spring-Data tx closes.
        return pollService.pollFor(
                pollRepository.findByIdWithOptions(pollId).orElseThrow().getMessage(), me);
    }

    @PostMapping("/{pollId}/vote")
    public PollDto castVote(@PathVariable Long pollId,
                            @Valid @RequestBody CastVoteRequest body,
                            Principal principal) {
        var me = currentUser.resolve(principal);
        // 30 votes per minute per user — well above legitimate use, blocks scripted abuse.
        if (!rateLimiter.tryAcquire(me.getUsername(), "poll-vote", 30, java.time.Duration.ofMinutes(1))) {
            throw new ai.intellistream.radiance.security.RateLimitExceededException("vote rate exceeded");
        }
        var hostChannelId = requireMembership(pollId, me);
        var dto = pollService.castVote(pollId, body.optionId(), me);
        broker.convertAndSend("/topic/channels/" + hostChannelId,
                MessageEvent.pollVote(messageIdOf(pollId), hostChannelId, dto));
        return dto;
    }

    @DeleteMapping("/{pollId}/vote")
    public PollDto removeVote(@PathVariable Long pollId, Principal principal) {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "poll-vote", 30, java.time.Duration.ofMinutes(1))) {
            throw new ai.intellistream.radiance.security.RateLimitExceededException("vote rate exceeded");
        }
        var hostChannelId = requireMembership(pollId, me);
        var dto = pollService.removeVote(pollId, me);
        broker.convertAndSend("/topic/channels/" + hostChannelId,
                MessageEvent.pollVote(messageIdOf(pollId), hostChannelId, dto));
        return dto;
    }

    /**
     * Look up the host channel and enforce read access via the standard channel rules. Anyone
     * authenticated can read+vote in {@code PUBLIC} channels (matches existing reaction posture);
     * private channels require membership. The query join-fetches the host message and channel
     * so the lazy proxies don't blow up after the implicit-tx repo call returns.
     */
    private Long requireMembership(Long pollId, ai.intellistream.radiance.domain.User me) {
        var poll = pollRepository.findByIdWithOptions(pollId)
                .orElseThrow(() -> new ai.intellistream.radiance.security.ResourceNotFoundException("Poll not found: " + pollId));
        var channel = poll.getMessage().getChannel();
        channelService.requireMember(channel, me);
        return channel.getId();
    }

    private Long messageIdOf(Long pollId) {
        return pollRepository.findByIdWithOptions(pollId)
                .map(p -> p.getMessage().getId())
                .orElseThrow();
    }
}
