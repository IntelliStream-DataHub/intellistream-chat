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

import ai.intellistream.chat.domain.MessageSave;
import ai.intellistream.chat.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MessageSaveRepository extends JpaRepository<MessageSave, Long> {

    /**
     * One page of the viewer's saved items, newest save first.
     *
     * <p>Everything the list renders is join-fetched here, because open-in-view is off and the DTO
     * is built after the transaction closes: the channel message with its channel and author, the
     * conversation message with its conversation and author. Both are {@code left join fetch} —
     * exactly one of the two is present on any given row, so an inner join on either would return
     * nothing at all.
     */
    @Query("""
            select s from MessageSave s
            left join fetch s.message m
            left join fetch m.channel
            left join fetch m.author
            left join fetch s.conversationMessage cm
            left join fetch cm.conversation
            left join fetch cm.author
            where s.user = :user
            order by s.createdAt desc, s.id desc
            """)
    List<MessageSave> findPageForUser(@Param("user") User user, Pageable pageable);

    long countByUser(User user);

    /**
     * Which of {@code messageIds} the viewer has saved — one query for a whole rendered page rather
     * than one per message.
     */
    @Query("""
            select s.message.id from MessageSave s
            where s.user = :user and s.message.id in :messageIds
            """)
    List<Long> findSavedMessageIdsAmong(@Param("user") User user,
                                        @Param("messageIds") Collection<Long> messageIds);

    /** The viewer's saved message ids in one channel — what the channel page needs on first paint. */
    @Query("""
            select s.message.id from MessageSave s
            where s.user = :user and s.message.channel.id = :channelId
            """)
    List<Long> findSavedMessageIdsInChannel(@Param("user") User user,
                                            @Param("channelId") Long channelId);

    /** The same, for one conversation — what the DM page needs on first paint. */
    @Query("""
            select s.conversationMessage.id from MessageSave s
            where s.user = :user and s.conversationMessage.conversation.id = :conversationId
            """)
    List<Long> findSavedMessageIdsInConversation(@Param("user") User user,
                                                 @Param("conversationId") Long conversationId);

    /** Save if absent, ignore the duplicate — a double-click is one save, not an error. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into message_saves (user_id, message_id) values (:userId, :messageId)
            on conflict (user_id, message_id) where message_id is not null do nothing
            """, nativeQuery = true)
    void insertMessageSaveIgnore(@Param("userId") Long userId, @Param("messageId") Long messageId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into message_saves (user_id, conversation_message_id) values (:userId, :messageId)
            on conflict (user_id, conversation_message_id) where conversation_message_id is not null
            do nothing
            """, nativeQuery = true)
    void insertConversationSaveIgnore(@Param("userId") Long userId, @Param("messageId") Long messageId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MessageSave s where s.user = :user and s.message.id = :messageId")
    int deleteMessageSave(@Param("user") User user, @Param("messageId") Long messageId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MessageSave s where s.user = :user and s.conversationMessage.id = :messageId")
    int deleteConversationSave(@Param("user") User user, @Param("messageId") Long messageId);

    boolean existsByUserAndMessageId(User user, Long messageId);

    boolean existsByUserAndConversationMessageId(User user, Long messageId);
}
