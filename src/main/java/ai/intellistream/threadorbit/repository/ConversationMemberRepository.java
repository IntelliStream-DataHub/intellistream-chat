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

package ai.intellistream.threadorbit.repository;

import ai.intellistream.threadorbit.domain.Conversation;
import ai.intellistream.threadorbit.domain.ConversationMember;
import ai.intellistream.threadorbit.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface ConversationMemberRepository extends JpaRepository<ConversationMember, Long> {

    Optional<ConversationMember> findByConversationAndUser(Conversation conversation, User user);

    boolean existsByConversationAndUser(Conversation conversation, User user);

    @Query("""
            select m from ConversationMember m
            join fetch m.user
            where m.conversation = :conversation
            order by m.joinedAt asc
            """)
    List<ConversationMember> findAllByConversationOrderByJoinedAtAsc(Conversation conversation);

    @Query("""
            select m.conversation from ConversationMember m
            where m.user = :user
            order by m.joinedAt desc
            """)
    List<Conversation> findConversationsForUser(User user);

    /**
     * For each conversation in {@code convIds}, count messages newer than the viewer's
     * last_read_at marker (treated as "all unread" when null) and authored by someone else.
     * Returns rows of {@code [conversationId, count]}; conversations with zero unread are
     * absent from the result.
     */
    @Query(value = """
            select cm.message_conv_id, count(*)
              from (
                select msg.conversation_id as message_conv_id, msg.author_id, msg.created_at,
                       cmem.last_read_at
                  from conversation_messages msg
                  join conversation_members cmem
                       on cmem.conversation_id = msg.conversation_id and cmem.user_id = :userId
                 where msg.conversation_id in (:convIds)
              ) cm
             where cm.author_id <> :userId
               and (cm.last_read_at is null or cm.created_at > cm.last_read_at)
             group by cm.message_conv_id
            """, nativeQuery = true)
    List<Object[]> countUnreadPerConversation(@Param("userId") Long userId,
                                              @Param("convIds") Collection<Long> convIds);
}
