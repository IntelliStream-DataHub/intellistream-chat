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

package ai.intellistream.chat.web.dto;



/**
 * Lightweight envelope broadcast on {@code /topic/conversations/{id}} for events that aren't a new
 * {@link ConversationMessageDto} — member added or gone, and message updated or deleted. The
 * conversation client distinguishes by the {@code type} discriminator; a frame with no {@code type}
 * at all is a message.
 *
 * <p>{@code parentId} rides on a delete so the client can decrement the right thread indicator. It
 * cannot work that out afterwards: by the time the frame arrives the row is gone, and the reply may
 * never have been on screen.
 */
public record ConversationEvent(String type,
                                Long conversationId,
                                String username,
                                Long messageId,
                                Long parentId,
                                ConversationMessageDto message) {
    public static ConversationEvent memberAdded(Long conversationId, String username) {
        return new ConversationEvent("member-added", conversationId, username, null, null, null);
    }

    public static ConversationEvent messageUpdated(ConversationMessageDto dto) {
        return new ConversationEvent("message-updated", dto.conversationId(), null, dto.id(),
                dto.parentId(), dto);
    }

    public static ConversationEvent messageDeleted(Long conversationId, Long messageId, Long parentId) {
        return new ConversationEvent("message-deleted", conversationId, null, messageId, parentId, null);
    }
}
