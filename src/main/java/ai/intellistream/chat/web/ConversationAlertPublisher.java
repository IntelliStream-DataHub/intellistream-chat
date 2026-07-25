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

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tells a conversation's members, individually, that a message arrived.
 *
 * <p>{@code /topic/conversations/{id}} already carries the message, but only to clients that
 * subscribed to that conversation — which means the page you are looking at. A direct message is
 * precisely the thing you need to hear about when you are looking at something else, so it needs
 * a destination the client is subscribed to wherever it is. That is
 * {@code /user/queue/conversation-alerts}: Spring resolves a user destination per session, so a
 * member receives only their own, on every tab they have open.
 *
 * <p><b>Not a second delivery path.</b> The alert is a notice, not the message: id, who, and a
 * short preview for the toast. The message itself still arrives once, on the conversation topic.
 * Sending the full DTO here would mean two sources of truth for the same message and a rendering
 * path that only runs when you happen to be elsewhere — the kind that rots unnoticed.
 *
 * <p>The author is skipped. Being notified of your own message is noise, and with a sound
 * attached it is worse than noise.
 *
 * <p>Failures are swallowed and logged. A broken notification must not fail the send that
 * triggered it; the message is already committed and broadcast by the time this runs, and
 * throwing here would surface to the sender as a failed send of a message that was in fact
 * delivered.
 */
@Component
public class ConversationAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(ConversationAlertPublisher.class);

    /** Long enough to recognise the message, short enough not to leak a wall of text into a toast. */
    private static final int PREVIEW_CHARS = 200;

    private final ConversationService conversations;
    private final SimpMessagingTemplate broker;

    public ConversationAlertPublisher(ConversationService conversations,
                                      SimpMessagingTemplate broker) {
        this.conversations = conversations;
        this.broker = broker;
    }

    public void alert(Conversation conversation, ConversationMessage message) {
        try {
            User author = message.getAuthor();
            String preview = preview(message.getBodyMarkdown());
            String authorName = author.getDisplayName() != null && !author.getDisplayName().isBlank()
                    ? author.getDisplayName()
                    : author.getUsername();

            for (var member : conversations.members(conversation)) {
                User recipient = member.getUser();
                if (recipient.getId().equals(author.getId())) continue;

                // A direct conversation has no title of its own — it is "the conversation with
                // that person", so from the recipient's side the sender's name is the title.
                String title = conversation.getType() == ConversationType.DIRECT
                        ? authorName
                        : conversation.getTitle();

                broker.convertAndSendToUser(recipient.getUsername(), "/queue/conversation-alerts",
                        Map.of(
                                "conversationId", conversation.getId(),
                                "type", conversation.getType().name(),
                                "title", title == null ? "" : title,
                                "author", authorName,
                                "authorUsername", author.getUsername(),
                                "messageId", message.getId(),
                                "preview", preview));
            }
        } catch (RuntimeException e) {
            log.warn("Could not publish conversation alerts for conversation {}",
                    conversation.getId(), e);
        }
    }

    private static String preview(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > PREVIEW_CHARS ? flat.substring(0, PREVIEW_CHARS - 1) + "…" : flat;
    }
}
