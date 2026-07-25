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

package ai.intellistream.chat.repository;

import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;


public interface ConversationAttachmentRepository extends JpaRepository<ConversationAttachment, Long> {

    List<ConversationAttachment> findByMessageOrderByCreatedAtAsc(ConversationMessage message);

    List<ConversationAttachment> findByMessageInOrderByCreatedAtAsc(Collection<ConversationMessage> messages);

    /**
     * A message's attachments with their uploader loaded — captured before a delete so the files
     * can be reaped and their bytes credited back. Rows, not storage keys: the key names the file
     * but says nothing about who is being charged for it or how much, and the delete cascade takes
     * that away with the row.
     */
    @org.springframework.data.jpa.repository.Query("""
            select a from ConversationAttachment a
            join fetch a.message m
            join fetch m.author
            where m.id = :messageId
            """)
    List<ConversationAttachment> findByMessageIdWithAuthor(Long messageId);

    /** Every DM attachment storage key — part of the live set for the orphan sweep (CLEAN-1). */
    @org.springframework.data.jpa.repository.Query("select a.storageKey from ConversationAttachment a")
    java.util.List<String> findAllStorageKeys();
}
