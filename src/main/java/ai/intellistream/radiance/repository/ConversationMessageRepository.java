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

package ai.intellistream.radiance.repository;

import ai.intellistream.radiance.domain.Conversation;
import ai.intellistream.radiance.domain.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    @Query("""
            select m from ConversationMessage m
            join fetch m.author
            join fetch m.conversation
            where m.conversation = :conversation
            order by m.createdAt desc
            """)
    List<ConversationMessage> findByConversationOrderByCreatedAtDesc(Conversation conversation, Pageable pageable);

    /**
     * Eager fetch for read-then-render paths (edit/delete/react endpoints) that build a
     * {@code ConversationMessageDto} after the @Transactional boundary closes — without
     * the joins, {@code m.getAuthor()} / {@code m.getConversation()} hit
     * LazyInitializationException.
     */
    @Query("""
            select m from ConversationMessage m
            join fetch m.author
            join fetch m.conversation
            where m.id = :id
            """)
    Optional<ConversationMessage> findByIdWithAuthor(UUID id);
}
