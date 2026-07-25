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

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMember;
import ai.intellistream.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface ConversationMemberRepository extends JpaRepository<ConversationMember, Long> {

    Optional<ConversationMember> findByConversationAndUser(Conversation conversation, User user);

    /** Same lookup but eager-fetching the member's user, so the caller can build a DTO after the
     *  transaction closes (open-in-view is off) — used by addToGroup's return (N1). */
    @Query("select m from ConversationMember m join fetch m.user where m.conversation = :conversation and m.user = :user")
    Optional<ConversationMember> findByConversationAndUserFetchingUser(Conversation conversation, User user);

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
     * Just the ids — this is the search ACL, read fresh on every query so a membership change
     * takes effect immediately. Ids only (not {@link #findConversationsForUser}) because the
     * search path needs nothing but the filter terms, and a user with thousands of group
     * conversations should not hydrate thousands of entities to run one query.
     */
    @Query("select m.conversation.id from ConversationMember m where m.user = :user")
    List<Long> findConversationIdsForUser(User user);

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

    /**
     * The other participants of the given conversations — rows of
     * {@code [conversationId, username, displayName]}, excluding {@code excludeUserId}.
     *
     * <p>The file manager needs a name for the place each DM file was posted, and a DIRECT
     * conversation has no title: it is identified by whoever is on the other end. One query for the
     * whole page rather than a lookup per row.
     */
    @Query("""
            select cm.conversation.id, u.username, u.displayName
            from ConversationMember cm
            join cm.user u
            where cm.conversation.id in :conversationIds and u.id <> :excludeUserId
            """)
    List<Object[]> findCounterparts(
            @org.springframework.data.repository.query.Param("conversationIds") Collection<Long> conversationIds,
            @org.springframework.data.repository.query.Param("excludeUserId") Long excludeUserId);

    /** Insert a membership if absent, ignore on the (conversation,user) conflict (N1). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "insert into conversation_members (conversation_id, user_id) values (:conversationId, :userId) on conflict (conversation_id, user_id) do nothing", nativeQuery = true)
    void insertMemberIgnore(@org.springframework.data.repository.query.Param("conversationId") Long conversationId, @org.springframework.data.repository.query.Param("userId") Long userId);
}
