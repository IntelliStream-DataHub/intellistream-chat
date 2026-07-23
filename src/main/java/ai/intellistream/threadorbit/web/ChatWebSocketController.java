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

    public ChatWebSocketController(ChannelService channelService,
                                   MessageService messageService,
                                   MarkdownRenderer markdown,
                                   CurrentUser currentUser,
                                   SimpMessagingTemplate broker,
                                   RateLimiter rateLimiter,
                                   MessageMentionRepository mentionRepository,
                                   SlashCommandService slashCommands,
                                   PollService pollService) {
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
        var user = currentUser.resolve(principal);
        // 30 messages per minute per user — comfortably above human-typed throughput.
        if (!rateLimiter.tryAcquire(user.getUsername(), "ws-send", 30, java.time.Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("send rate exceeded");
        }
        var channel = channelService.requireById(channelId);
        // Slash commands intercept the body BEFORE we treat it as a regular message — /poll
        // posts a synthetic markdown message, /remind queues a row + posts a confirmation,
        // unknown /typos fall through to the normal post path so users don't lose the text.
        ai.intellistream.threadorbit.slash.SlashCommandResult slashed;
        try {
            slashed = slashCommands.dispatch(channel, user, payload.body());
        } catch (IllegalArgumentException badArgs) {
            // Surface usage / validation errors only to the sender — they show as a transient
            // banner above their composer (chat.js subscribes to /user/queue/notices). Not
            // broadcast to the channel because nobody else cares about a typo.
            broker.convertAndSendToUser(user.getUsername(), "/queue/notices",
                    java.util.Map.of("level", "error", "text", badArgs.getMessage()));
            return;
        }
        ai.intellistream.threadorbit.domain.Message saved;
        if (slashed.handled()) {
            saved = slashed.message();
            if (saved == null) return; // command had no immediate output (rare; nothing to broadcast)
        } else {
            saved = messageService.post(channel, user, payload.body());
        }
        // MessageService.post syncs mention rows; we read them back to surface in the broadcast
        // so clients can fire @mention notifications without re-parsing the body.
        List<String> mentions = mentionRepository.usernamesByMessage(saved);
        // Poll-host messages (created by /poll) carry their freshly-attached PollDto so the
        // recipient's renderer can paint the vote widget on first sight without a follow-up
        // round-trip. Non-poll messages get null and the field is harmlessly elided.
        var poll = pollService.pollFor(saved, user);
        var dto = MessageDto.from(saved, markdown.render(saved.getBodyMarkdown()),
                List.of(), List.of(), 0L, mentions, poll);
        broker.convertAndSend("/topic/channels/" + channelId, MessageEvent.created(dto));
    }

    @MessageMapping("/channels/{channelId}/typing")
    public void typing(@DestinationVariable Long channelId, Principal principal) {
        var user = currentUser.resolve(principal);
        // Typing pings are throttled client-side to 1 per 2s; cap at 60/min server-side as a safety net.
        if (!rateLimiter.tryAcquire(user.getUsername(), "ws-typing", 60, java.time.Duration.ofMinutes(1))) {
            return; // silently drop excess typing pings
        }
        var channel = channelService.requireById(channelId);
        channelService.requireMember(channel, user);
        broker.convertAndSend("/topic/channels/" + channelId + "/typing",
                new TypingEvent(user.getUsername(), user.getDisplayName()));
    }
}
