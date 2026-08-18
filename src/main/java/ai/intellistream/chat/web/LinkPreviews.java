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

import ai.intellistream.chat.linkpreview.LinkPreviewService;
import ai.intellistream.chat.web.dto.ConversationEvent;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
import ai.intellistream.chat.web.dto.MessageDto;
import ai.intellistream.chat.web.dto.MessageEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The web layer's two verbs for link previews, so every controller says the same thing.
 *
 * <p>{@link #decorate} is for reads: given DTOs about to be returned or rendered, attach the card
 * each one already has (one query for the list). {@link #unfurl} is for writes: once a message is
 * durable and broadcast, ask for its card and, when it arrives, publish a {@code link-preview}
 * event on the room's topic. A create broadcast is <em>not</em> decorated — the hot path stays
 * query-free — because the event that follows fills the card in whether it was cached or fetched.
 *
 * <p>Both are no-ops when {@code ichat.link-previews.enabled=false}.
 */
@Component
public class LinkPreviews {

    private final LinkPreviewService service;
    private final SimpMessagingTemplate broker;

    public LinkPreviews(LinkPreviewService service, SimpMessagingTemplate broker) {
        this.service = service;
        this.broker = broker;
    }

    public List<MessageDto> decorate(List<MessageDto> messages) {
        if (messages.isEmpty() || !service.isEnabled()) return messages;
        var previews = service.previewsFor(messages.stream().map(MessageDto::bodyMarkdown).toList());
        var out = new ArrayList<MessageDto>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            var p = previews.get(i);
            out.add(p == null ? messages.get(i) : messages.get(i).withLinkPreview(p));
        }
        return out;
    }

    public MessageDto decorate(MessageDto message) {
        if (!service.isEnabled()) return message;
        var p = service.previewFor(message.bodyMarkdown());
        return p == null ? message : message.withLinkPreview(p);
    }

    public List<ConversationMessageDto> decorateConversation(List<ConversationMessageDto> messages) {
        if (messages.isEmpty() || !service.isEnabled()) return messages;
        var previews = service.previewsFor(messages.stream().map(ConversationMessageDto::bodyMarkdown).toList());
        var out = new ArrayList<ConversationMessageDto>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            var p = previews.get(i);
            out.add(p == null ? messages.get(i) : messages.get(i).withLinkPreview(p));
        }
        return out;
    }

    public ConversationMessageDto decorate(ConversationMessageDto message) {
        if (!service.isEnabled()) return message;
        var p = service.previewFor(message.bodyMarkdown());
        return p == null ? message : message.withLinkPreview(p);
    }

    /** After a channel message is durable and broadcast. Returns at once. */
    public void unfurl(MessageDto message) {
        service.request(message.bodyMarkdown(), preview -> broker.convertAndSend(
                "/topic/channels/" + message.channelId(),
                MessageEvent.linkPreview(message.id(), message.channelId(), preview)));
    }

    /** After a conversation message is durable and broadcast. Returns at once. */
    public void unfurl(ConversationMessageDto message) {
        service.request(message.bodyMarkdown(), preview -> broker.convertAndSend(
                "/topic/conversations/" + message.conversationId(),
                ConversationEvent.linkPreview(message.conversationId(), message.id(), preview)));
    }
}
