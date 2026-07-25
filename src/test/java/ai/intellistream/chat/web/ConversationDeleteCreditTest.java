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
import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.moderation.StorageQuotaService;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ConversationAttachmentService;
import ai.intellistream.chat.service.ConversationReactionService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The DM half of the credit-back. A direct message has no soft delete — removing one destroys the
 * row and, through the cascade, its attachment rows — so the bytes really are freed and the
 * uploader is owed them back.
 *
 * <p>The controller is the right place for it here, and only here: {@code ConversationService.deleteMessage}
 * owns the transaction and has committed by the time it returns, so this is the genuine after-commit
 * point. Doing it before the call would credit a delete that may still be refused for authorization.
 */
class ConversationDeleteCreditTest {

    private ConversationService conversations;
    private ConversationAttachmentService attachments;
    private StorageQuotaService quotas;
    private ConversationRestController controller;

    private final User alice = user(1L, "alice");
    private final Principal principal = mock(Principal.class);

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        attachments = mock(ConversationAttachmentService.class);
        quotas = mock(StorageQuotaService.class);
        var currentUser = mock(CurrentUser.class);
        when(currentUser.resolve(principal)).thenReturn(alice);
        controller = new ConversationRestController(conversations, mock(UserService.class),
                currentUser, mock(MarkdownRenderer.class), attachments,
                mock(ConversationReactionService.class), mock(SimpMessagingTemplate.class),
                new RateLimiter(), quotas,
                mock(ai.intellistream.chat.web.ConversationAlertPublisher.class));
    }

    @Test
    void deletingADirectMessageReapsItsFilesAndCreditsItsUploader() {
        dmWithAttachments(300L, 200L);

        controller.deleteMessage(5L, principal);

        verify(attachments).deleteFiles(List.of("key-0", "key-1"));
        verify(quotas).releaseAll(Map.of(1L, 500L));
        // Same read for both: a second query could see a different set if an upload landed between
        // them, leaving a file either reaped uncredited or credited unreaped.
        var ordered = inOrder(attachments, conversations, quotas);
        ordered.verify(attachments).forMessage(5L);
        ordered.verify(conversations).deleteMessage(5L, alice);
        ordered.verify(quotas).releaseAll(any());
    }

    @Test
    void aRefusedDeleteCreditsNothingAndTouchesNoFiles() {
        dmWithAttachments(300L);
        when(conversations.deleteMessage(5L, alice))
                .thenThrow(new AccessDeniedException("You can only delete your own messages."));

        assertThatThrownBy(() -> controller.deleteMessage(5L, principal))
                .isInstanceOf(AccessDeniedException.class);

        // The rows are still there and the bytes are still stored; handing them back would let a
        // failed delete raise the account's headroom for free.
        verify(quotas, never()).releaseAll(any());
        verify(attachments, never()).deleteFiles(any());
    }

    // ------------------------------------------------------------------ helpers

    private void dmWithAttachments(long... sizes) {
        var conversation = new Conversation(ConversationType.DIRECT, null, "1:2", alice);
        ReflectionTestUtils.setField(conversation, "id", 9L);
        var message = new ConversationMessage(conversation, alice, "here you go");
        ReflectionTestUtils.setField(message, "id", 5L);
        var rows = new java.util.ArrayList<ConversationAttachment>();
        for (int i = 0; i < sizes.length; i++) {
            rows.add(new ConversationAttachment(message, "f" + i + ".bin",
                    "application/octet-stream", sizes[i], "key-" + i));
        }
        when(attachments.forMessage(5L)).thenReturn(rows);
        when(conversations.deleteMessage(5L, alice)).thenReturn(message);
    }

    private static User user(long id, String username) {
        var user = new User("sub-" + username, username, username + "@example.test", username);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
