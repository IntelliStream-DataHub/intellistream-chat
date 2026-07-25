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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelRead;
import ai.intellistream.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


public interface ChannelReadRepository extends JpaRepository<ChannelRead, Long> {

    Optional<ChannelRead> findByChannelAndUser(Channel channel, User user);

    List<ChannelRead> findAllByUser(User user);

    /**
     * Single-statement upsert that advances {@code last_read_at} to {@code now} for every
     * channel where {@code userId} has at least one unread mention — i.e. exactly the
     * channels the mention inbox lists. The SELECT is the same predicate as
     * {@code MessageMentionRepository#findUnreadInbox} so the two stay in lockstep.
     *
     * <p>Returns the number of channel_reads rows touched.
     */
    @Modifying
    @Query(value = """
            insert into channel_reads (channel_id, user_id, last_read_at)
            select distinct msg.channel_id, :userId, cast(:now as timestamptz)
              from message_mentions mn
              join messages msg on msg.id = mn.message_id
              left join channel_reads cr on cr.channel_id = msg.channel_id and cr.user_id = :userId
             where mn.user_id = :userId
               and msg.deleted_at is null
               and (cr.last_read_at is null or msg.created_at > cr.last_read_at)
            on conflict (channel_id, user_id)
              do update set last_read_at = excluded.last_read_at
            """, nativeQuery = true)
    int markAllChannelsWithUnreadMentionsRead(@Param("userId") Long userId,
                                              @Param("now") Instant now);

    /** Race-free read-marker upsert (N1): ON CONFLICT keeps the tx usable, unlike the old
     *  saveAndFlush + catch-and-reread which aborts the tx on Postgres so the re-read threw. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = """
            insert into channel_reads (channel_id, user_id, last_read_at)
            values (:channelId, :userId, cast(:now as timestamptz))
            on conflict (channel_id, user_id) do update set last_read_at = excluded.last_read_at
            """, nativeQuery = true)
    void upsertLastReadAt(@org.springframework.data.repository.query.Param("channelId") Long channelId,
                          @org.springframework.data.repository.query.Param("userId") Long userId,
                          @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
