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

import ai.intellistream.threadorbit.domain.Message;
import ai.intellistream.threadorbit.domain.MessageMention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;



public interface MessageMentionRepository extends JpaRepository<MessageMention, Long> {

    void deleteAllByMessage(Message message);

    @Query("select mn.user.username from MessageMention mn where mn.message = :message")
    List<String> usernamesByMessage(@Param("message") Message message);

    /**
     * For each channel id, count messages in that channel that mention {@code userId} and were created
     * after the user's last_read_at marker (no marker counts as "all unread").
     */
    @Query(value = """
            select msg.channel_id, count(*)
              from message_mentions mn
              join messages msg on msg.id = mn.message_id
              left join channel_reads cr
                     on cr.channel_id = msg.channel_id and cr.user_id = mn.user_id
             where mn.user_id = :userId
               and msg.channel_id in (:channelIds)
               and (cr.last_read_at is null or msg.created_at > cr.last_read_at)
             group by msg.channel_id
            """, nativeQuery = true)
    List<Object[]> countMentionsPerChannel(@Param("userId") Long userId,
                                           @Param("channelIds") Collection<Long> channelIds);

    /**
     * Total unread mentions across every channel — drives the topbar bell badge.
     * "Unread" matches {@link #countMentionsPerChannel}: the message was created after the
     * viewer's {@code channel_reads.last_read_at} for that channel (or the viewer has no
     * read marker for it yet).
     */
    @Query(value = """
            select count(*)
              from message_mentions mn
              join messages msg on msg.id = mn.message_id
              left join channel_reads cr
                     on cr.channel_id = msg.channel_id and cr.user_id = mn.user_id
             where mn.user_id = :userId
               and (cr.last_read_at is null or msg.created_at > cr.last_read_at)
            """, nativeQuery = true)
    long countUnreadFor(@Param("userId") Long userId);

    /**
     * Recent unread-mention rows joined with channel + author for the inbox dropdown.
     * Returned columns (in order): message_id, channel_id, channel_slug, channel_name,
     * author_username, author_display_name, body_markdown, created_at. Newest first.
     */
    @Query(value = """
            select msg.id, ch.id, ch.slug, ch.name,
                   author.username, author.display_name,
                   msg.body_markdown, msg.created_at
              from message_mentions mn
              join messages msg on msg.id = mn.message_id
              join channels ch on ch.id = msg.channel_id
              join users author on author.id = msg.author_id
              left join channel_reads cr
                     on cr.channel_id = msg.channel_id and cr.user_id = mn.user_id
             where mn.user_id = :userId
               and (cr.last_read_at is null or msg.created_at > cr.last_read_at)
             order by msg.created_at desc
             limit :limit
            """, nativeQuery = true)
    List<Object[]> findUnreadInbox(@Param("userId") Long userId, @Param("limit") int limit);
}
