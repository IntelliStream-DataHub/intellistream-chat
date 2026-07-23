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
import ai.intellistream.threadorbit.service.ConversationService;
import ai.intellistream.threadorbit.service.MarkdownRenderer;
import ai.intellistream.threadorbit.web.dto.ConversationMessageDto;
import ai.intellistream.threadorbit.web.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Duration;

/**
 * STOMP send for direct messages. Mirrors {@link ChatWebSocketController#send} but
 * routes to {@code /topic/conversations/{id}}. Subscribe-side authorization is
 * enforced in {@code StompAuthorizationConfig}.
 */
@Controller
public class ConversationWebSocketController {

    private final ConversationService conversations;
    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final SimpMessagingTemplate broker;
    private final RateLimiter rateLimiter;

    public ConversationWebSocketController(ConversationService conversations,
                                           MarkdownRenderer markdown,
                                           CurrentUser currentUser,
                                           SimpMessagingTemplate broker,
                                           RateLimiter rateLimiter) {
        this.conversations = conversations;
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
    }

    @MessageMapping("/conversations/{conversationId}/send")
    public void send(@DestinationVariable Long conversationId,
                     @Valid SendMessageRequest payload,
                     Principal principal) {
        var user = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(user.getUsername(), "ws-conv-send", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("send rate exceeded");
        }
        var conversation = conversations.requireById(conversationId);
        var saved = conversations.post(conversation, user, payload.body());
        var dto = ConversationMessageDto.from(saved, markdown.render(saved.getBodyMarkdown()));
        broker.convertAndSend("/topic/conversations/" + conversationId, dto);
    }
}
