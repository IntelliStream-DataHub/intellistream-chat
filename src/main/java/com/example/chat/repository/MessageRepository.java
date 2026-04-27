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

package com.example.chat.repository;

import com.example.chat.domain.Channel;
import com.example.chat.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("""
            select m from Message m
            join fetch m.author
            where m.id = :id
            """)
    Optional<Message> findByIdWithAuthor(UUID id);

    @Query("""
            select m from Message m
            join fetch m.author
            join fetch m.channel
            where m.id = :id
            """)
    Optional<Message> findByIdWithChannelAndAuthor(UUID id);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.id in :ids
            """)
    List<Message> findAllByIdWithAuthor(@Param("ids") Collection<UUID> ids);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null
            order by m.createdAt desc
            """)
    List<Message> findByChannelAndParentIsNullOrderByCreatedAtDesc(Channel channel, Pageable pageable);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null and m.createdAt < :before
            order by m.createdAt desc
            """)
    List<Message> findByChannelAndParentIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(
            Channel channel, Instant before, Pageable pageable);

    /** Mirror of {@link #findByChannelAndParentIsNullAndCreatedAtBeforeOrderByCreatedAtDesc} but
     * paging FORWARD from a timestamp; used by {@code around()} to pull the N messages immediately
     * after the anchor message. */
    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null and m.createdAt > :after
            order by m.createdAt asc
            """)
    List<Message> findByChannelAndParentIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
            Channel channel, Instant after, Pageable pageable);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.pinnedAt is not null
            order by m.pinnedAt desc
            """)
    List<Message> findByChannelAndPinnedAtIsNotNullOrderByPinnedAtDesc(Channel channel);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.parent = :parent
            order by m.createdAt asc
            """)
    List<Message> findByParentOrderByCreatedAtAsc(Message parent);

    long countByParent(Message parent);

    /**
     * For each given parent id, count its top-level replies. Parents with zero replies are
     * absent from the result. Used to render the "N replies" thread indicator on a feed
     * of top-level messages without N+1 queries.
     */
    @Query("""
            select m.parent.id, count(m)
            from Message m
            where m.parent.id in :parentIds
            group by m.parent.id
            """)
    List<Object[]> countRepliesByParentIds(@Param("parentIds") Collection<UUID> parentIds);

    /**
     * For each channel id, count top-level messages that arrived after the viewer's last_read_at
     * marker (no marker counts as "all unread"). Excludes the viewer's own messages — you don't
     * generate unread counts for yourself.
     */
    @Query(value = """
            select msg.channel_id, count(*)
              from messages msg
              left join channel_reads cr
                     on cr.channel_id = msg.channel_id and cr.user_id = :userId
             where msg.channel_id in (:channelIds)
               and msg.parent_id is null
               and msg.author_id <> :userId
               and (cr.last_read_at is null or msg.created_at > cr.last_read_at)
             group by msg.channel_id
            """, nativeQuery = true)
    List<Object[]> countUnreadPerChannel(@Param("userId") UUID userId,
                                         @Param("channelIds") Collection<UUID> channelIds);
}
