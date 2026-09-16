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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.web.dto.ConversationDto;
import ai.intellistream.chat.web.dto.ConversationMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Delivers something the application itself has to tell one person, as a direct message: a fired
 * {@code /remind}, a receipt that somebody opened your one-time secret.
 *
 * <p>A conversation is the delivery mechanism rather than a notification system of our own because
 * it already carries everything such a notice needs and none of it is worth reimplementing: it
 * survives the recipient being offline, it badges unread, it pulses the favicon, it is searchable,
 * and it has a permalink. Addressed to yourself it lands in your one-member conversation ("You"),
 * where own-authored messages count as unread.
 *
 * <p>One implementation, because the reminder scheduler had the only copy and the secret receipts
 * would otherwise have been a second: the post, the rendered DTO, and the two announcements with
 * the exact keys the clients read.
 */
@Service
public class DirectNoticeService {

    private static final Logger log = LoggerFactory.getLogger(DirectNoticeService.class);

    /** Same toast-sized excerpt {@code ConversationAlertPublisher} uses for an ordinary DM. */
    private static final int PREVIEW_CHARS = 200;

    private final ConversationService conversations;
    private final MarkdownRenderer markdown;
    private final SimpMessagingTemplate broker;

    public DirectNoticeService(ConversationService conversations, MarkdownRenderer markdown,
                               SimpMessagingTemplate broker) {
        this.conversations = conversations;
        this.markdown = markdown;
        this.broker = broker;
    }

    /**
     * Posts {@code body}, authored by {@code author}, into the DIRECT conversation between
     * {@code author} and {@code recipient} — reusing the one they have, or the author's conversation
     * with themself when the two are the same person — and announces it once the surrounding
     * transaction commits.
     *
     * <p>{@code MANDATORY}: the caller's transaction is what makes the notice atomic with whatever it
     * reports (the reminder marked fired, the secret marked opened), and announcing before that
     * commit would show clients a message a later rollback then discarded (N30).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConversationMessage deliver(User author, User recipient, String body) {
        var conversation = conversations.directBetween(author, recipient);
        var saved = conversations.post(conversation, author, body);
        // Built inside the transaction, where the associations are loaded; sent after it commits.
        var dto = ConversationMessageDto.from(saved, markdown.renderInConversation(saved.getBodyMarkdown()));
        var title = recipient.getId().equals(author.getId())
                ? ConversationDto.SELF_TITLE
                : displayName(author);
        var conversationId = conversation.getId();
        var recipientUsername = recipient.getUsername();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(conversationId, dto, recipientUsername, title);
            }
        });
        return saved;
    }

    /**
     * Two sends, both on destinations that already exist. The topic reaches the conversation page if
     * the recipient happens to have it open; the user queue is what reaches them anywhere else.
     * {@code ConversationAlertPublisher} does the same job for interactive sends but skips the
     * author, which for a notice to yourself is the only person there is — so the payload is built
     * here instead, with the same keys the clients already read. The alert goes to exactly one
     * person: a notice has an addressee, unlike an ordinary message where everyone but the author is
     * notified.
     */
    private void publish(Long conversationId, ConversationMessageDto dto, String recipientUsername, String title) {
        try {
            broker.convertAndSend("/topic/conversations/" + conversationId, dto);
        } catch (RuntimeException e) {
            log.warn("Could not broadcast a direct notice to conversation {}", conversationId, e);
        }
        try {
            broker.convertAndSendToUser(recipientUsername, "/queue/conversation-alerts",
                    Map.of(
                            "conversationId", conversationId,
                            "type", "DIRECT",
                            "title", title,
                            "author", dto.authorDisplayName() == null
                                    ? dto.authorUsername()
                                    : dto.authorDisplayName(),
                            "authorUsername", dto.authorUsername(),
                            "messageId", dto.id(),
                            "preview", preview(dto.bodyMarkdown())));
        } catch (RuntimeException e) {
            // The message is stored and the badge will show on next load; a failed toast is not
            // worth failing anything over.
            log.warn("Could not alert {} about a direct notice", recipientUsername, e);
        }
    }

    private static String displayName(User u) {
        return u.getDisplayName() == null || u.getDisplayName().isBlank()
                ? u.getUsername() : u.getDisplayName();
    }

    private static String preview(String body) {
        if (body == null) return "";
        var oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= PREVIEW_CHARS ? oneLine : oneLine.substring(0, PREVIEW_CHARS - 1) + "…";
    }
}
