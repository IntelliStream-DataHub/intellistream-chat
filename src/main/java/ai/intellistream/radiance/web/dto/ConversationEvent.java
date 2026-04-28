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

package ai.intellistream.radiance.web.dto;



/**
 * Lightweight envelope broadcast on {@code /topic/conversations/{id}} for events
 * that aren't a new {@link ConversationMessageDto} — currently just member-added.
 * The conversation client distinguishes by the {@code type} discriminator.
 */
public record ConversationEvent(String type,
                                Long conversationId,
                                String username,
                                Long messageId,
                                ConversationMessageDto message) {
    public static ConversationEvent memberAdded(Long conversationId, String username) {
        return new ConversationEvent("member-added", conversationId, username, null, null);
    }

    public static ConversationEvent messageUpdated(ConversationMessageDto dto) {
        return new ConversationEvent("message-updated", dto.conversationId(), null, dto.id(), dto);
    }

    public static ConversationEvent messageDeleted(Long conversationId, Long messageId) {
        return new ConversationEvent("message-deleted", conversationId, null, messageId, null);
    }
}
