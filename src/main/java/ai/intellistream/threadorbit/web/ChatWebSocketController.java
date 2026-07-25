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

import ai.intellistream.threadorbit.metrics.WritePathMetrics;
import ai.intellistream.threadorbit.repository.MessageMentionRepository;
import ai.intellistream.threadorbit.security.CurrentUser;
import ai.intellistream.threadorbit.security.RateLimitExceededException;
import ai.intellistream.threadorbit.security.RateLimiter;
import ai.intellistream.threadorbit.service.ChannelService;
import ai.intellistream.threadorbit.service.MarkdownRenderer;
import ai.intellistream.threadorbit.service.MessageService;
import ai.intellistream.threadorbit.service.PollService;
import ai.intellistream.threadorbit.slash.SlashCommandService;
import ai.intellistream.threadorbit.web.dto.MessageDto;
import ai.intellistream.threadorbit.web.dto.MessageEvent;
import ai.intellistream.threadorbit.web.dto.SendMessageRequest;
import ai.intellistream.threadorbit.web.dto.TypingEvent;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;

@Controller
public class ChatWebSocketController {

    private final ChannelService channelService;
    private final MessageService messageService;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;
    private final MessageMentionRepository mentionRepository;
    private final SlashCommandService slashCommands;
    private final PollService pollService;
    private final WritePathMetrics metrics;

    public ChatWebSocketController(ChannelService channelService,
                                   MessageService messageService,
                                   MarkdownRenderer markdown,
                                   CurrentUser currentUser,
                                   SimpMessagingTemplate broker,
                                   RateLimiter rateLimiter,
                                   MessageMentionRepository mentionRepository,
                                   SlashCommandService slashCommands,
                                   PollService pollService,
                                   WritePathMetrics metrics) {
        this.metrics = metrics;
        this.channelService = channelService;
        this.messageService = messageService;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
        this.mentionRepository = mentionRepository;
        this.slashCommands = slashCommands;
        this.pollService = pollService;
    }

    @MessageMapping("/channels/{channelId}/send")
    public void send(@DestinationVariable Long channelId,
                     @Valid SendMessageRequest payload,
                     Principal principal) {
        var lap = metrics.lap();
        var user = sessionUser(principal);
        // 30 messages per minute per user — comfortably above human-typed throughput.
        if (!rateLimiter.tryAcquire(user.getUsername(), "ws-send", 30, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("send rate exceeded");
        }
        lap.mark(metrics.resolveUser);
        var channel = channelService.requireByIdForMessaging(channelId);
        lap.mark(metrics.loadChannel);
        // Slash commands intercept the body BEFORE we treat it as a regular message — /poll
        // posts a synthetic markdown message, /remind queues a row + posts a confirmation,
        // unknown /typos fall through to the normal post path so users don't lose the text.
        ai.intellistream.threadorbit.slash.SlashCommandResult slashed;
        try {
            slashed = slashCommands.dispatch(channel, user, payload.body());
        } catch (IllegalArgumentException badArgs) {
            // Surface usage / validation errors only to the sender — they show as a transient
            // banner above their composer (chat.js subscribes to /user/queue/notices). Not
            // broadcast to the channel because nobody else cares about a typo. Route by
            // principal.getName() (the key Spring's user-destination registry uses), not the
            // sanitized domain username — they differ for email-style or collision-suffixed
            // usernames, and mismatching one silently delivers nothing (N19).
            broker.convertAndSendToUser(principal.getName(), "/queue/notices",
                    java.util.Map.of("level", "error", "text", badArgs.getMessage()));
            return;
        }
        lap.mark(metrics.slashDispatch);
        ai.intellistream.threadorbit.domain.Message saved;
        List<String> mentions;
        // Holds the broadcast back until the message is durably stored. On the batched write path
        // that is a few milliseconds later; on the transactional path it has already happened.
        ai.intellistream.threadorbit.service.Durability durability;
        // Poll-host messages (created by /poll) carry their freshly-attached PollDto so the
        // recipient's renderer can paint the vote widget on first sight without a follow-up
        // round-trip. An ordinary post was created microseconds ago and provably has no poll and
        // no mention rows beyond the ones post() just wrote, so both lookups are skipped —
        // on the hot path they were two round trips per message that could only return nothing.
        ai.intellistream.threadorbit.web.dto.PollDto poll = null;
        if (slashed.handled()) {
            saved = slashed.message();
            if (saved == null) return; // command had no immediate output (rare; nothing to broadcast)
            // Slash commands run their own transactions, which have committed by now.
            durability = ai.intellistream.threadorbit.service.Durability.alreadyCommitted();
            lap.mark(metrics.persist);
            mentions = mentionRepository.usernamesByMessage(saved);
            lap.mark(metrics.mentionReadback);
            poll = pollService.pollFor(saved, user);
            lap.mark(metrics.pollLookup);
        } else {
            var posted = messageService.postBuffered(channel, user, payload.body());
            saved = posted.message();
            mentions = posted.mentionedUsernames();
            durability = posted.durability();
            lap.mark(metrics.persist);
        }
        var bodyHtml = markdown.render(saved.getBodyMarkdown());
        lap.mark(metrics.render);
        var dto = MessageDto.from(saved, bodyHtml, List.of(), List.of(), 0L, mentions, poll);
        // Only tell the channel about it once it is durably stored — a message shown to everyone
        // and then lost to a failed INSERT is worse than a message that appears a few milliseconds
        // later. Whichever thread completes the durability handle does the send.
        durability.whenDurable(() -> broker.convertAndSend("/topic/channels/" + channelId,
                MessageEvent.created(dto, payload.clientId())));
        lap.mark(metrics.broadcast);
        lap.finish(metrics.total);
    }

    /**
     * The domain {@link ai.intellistream.threadorbit.domain.User} for this STOMP session.
     *
     * <p>{@code StompAuthorizationConfig} resolves it once on CONNECT and caches it on the session
     * attributes precisely so per-frame handling doesn't have to. Going through
     * {@code CurrentUser.resolve} on every frame runs a transactional upsert against {@code users}
     * — a full round trip per message to re-derive something fixed for the life of the connection.
     *
     * <p>Read off the thread-bound {@code SimpAttributes} rather than through an extra handler
     * parameter, so the mapping's signature stays what every caller (and test) already expects.
     * Falls back to resolving whenever the attribute isn't there — a session that connected before
     * this interceptor existed, or a direct call with no STOMP attributes bound — so the handler
     * never depends on the cache being present.
     */
    private ai.intellistream.threadorbit.domain.User sessionUser(Principal principal) {
        var attributes = org.springframework.messaging.simp.SimpAttributesContextHolder.getAttributes();
        if (attributes != null
                && attributes.getAttribute(
                        ai.intellistream.threadorbit.config.StompAuthorizationConfig.SESSION_USER_KEY)
                                instanceof ai.intellistream.threadorbit.domain.User cached) {
            return cached;
        }
        return currentUser.resolve(principal);
    }

    @MessageMapping("/channels/{channelId}/typing")
    public void typing(@DestinationVariable Long channelId, Principal principal) {
        var user = currentUser.resolve(principal);
        // Typing pings are throttled client-side to 1 per 2s; cap at 60/min server-side as a safety net.
        if (!rateLimiter.tryAcquire(user.getUsername(), "ws-typing", 60, java.time.Duration.ofMinutes(1))) {
            return; // silently drop excess typing pings
        }
        var channel = channelService.requireByIdForMessaging(channelId);
        // Broadcasting "X is typing" is a write — use the write check, not the read check that
        // short-circuits true for PUBLIC channels, so a non-member can't inject typing pings into
        // a channel they never joined (N15).
        channelService.requireWriteAccess(channel, user);
        broker.convertAndSend("/topic/channels/" + channelId + "/typing",
                new TypingEvent(user.getUsername(), user.getDisplayName()));
    }
}
