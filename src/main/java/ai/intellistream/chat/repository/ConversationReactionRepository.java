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

package ai.intellistream.chat.repository;

import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.ConversationReaction;
import ai.intellistream.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface ConversationReactionRepository extends JpaRepository<ConversationReaction, Long> {

    Optional<ConversationReaction> findByMessageAndUserAndEmoji(ConversationMessage message, User user, String emoji);

    // join fetch the reactor's user so collapse() doesn't N+1 per distinct reactor on render (N28).
    @org.springframework.data.jpa.repository.Query(
            "select r from ConversationReaction r join fetch r.user where r.message = :message order by r.createdAt asc")
    List<ConversationReaction> findByMessageOrderByCreatedAtAsc(ConversationMessage message);

    @org.springframework.data.jpa.repository.Query(
            "select r from ConversationReaction r join fetch r.user where r.message in :messages order by r.createdAt asc")
    List<ConversationReaction> findByMessageInOrderByCreatedAtAsc(Collection<ConversationMessage> messages);

    void deleteByMessageAndUserAndEmoji(ConversationMessage message, User user, String emoji);

    /** Insert the reaction if absent, ignore the duplicate (N1). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "insert into conversation_reactions (conversation_message_id, user_id, emoji) values (:messageId, :userId, :emoji) on conflict (conversation_message_id, user_id, emoji) do nothing", nativeQuery = true)
    void insertReactionIgnore(@org.springframework.data.repository.query.Param("messageId") Long messageId, @org.springframework.data.repository.query.Param("userId") Long userId, @org.springframework.data.repository.query.Param("emoji") String emoji);
}
