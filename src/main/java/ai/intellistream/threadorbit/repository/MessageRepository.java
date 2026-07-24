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

import ai.intellistream.threadorbit.domain.Channel;
import ai.intellistream.threadorbit.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("""
            select m from Message m
            join fetch m.author
            where m.id = :id
            """)
    Optional<Message> findByIdWithAuthor(Long id);

    @Query("""
            select m from Message m
            join fetch m.author
            join fetch m.channel
            where m.id = :id
            """)
    Optional<Message> findByIdWithChannelAndAuthor(Long id);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.id in :ids
            """)
    List<Message> findAllByIdWithAuthor(@Param("ids") Collection<Long> ids);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null
            order by m.createdAt desc, m.id desc
            """)
    List<Message> findByChannelAndParentIsNullOrderByCreatedAtDesc(Channel channel, Pageable pageable);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null and m.createdAt < :before
            order by m.createdAt desc, m.id desc
            """)
    List<Message> findByChannelAndParentIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(
            Channel channel, Instant before, Pageable pageable);

    /** Mirror of {@link #findByChannelAndParentIsNullAndCreatedAtBeforeOrderByCreatedAtDesc} but
     * paging FORWARD from a timestamp. */
    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null and m.createdAt > :after
            order by m.createdAt asc, m.id asc
            """)
    List<Message> findByChannelAndParentIsNullAndCreatedAtAfterOrderByCreatedAtAsc(
            Channel channel, Instant after, Pageable pageable);

    /** Composite-keyset "before the anchor": messages strictly before (createdAt, id), so a
     *  message sharing the anchor's exact timestamp is ordered by id rather than dropped. Used by
     *  {@code around()} — see BUG-20. */
    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null
              and (m.createdAt < :ts or (m.createdAt = :ts and m.id < :id))
            order by m.createdAt desc, m.id desc
            """)
    List<Message> findTopLevelBeforeKeyset(Channel channel, Instant ts, Long id, Pageable pageable);

    /** Composite-keyset "after the anchor": mirror of {@link #findTopLevelBeforeKeyset}. */
    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null
              and (m.createdAt > :ts or (m.createdAt = :ts and m.id > :id))
            order by m.createdAt asc, m.id asc
            """)
    List<Message> findTopLevelAfterKeyset(Channel channel, Instant ts, Long id, Pageable pageable);

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

    long countByChannelAndParentIsNull(Channel channel);
    /** All message ids in a channel — captured before channel deletion to purge the Lucene index. */
    @org.springframework.data.jpa.repository.Query("select m.id from Message m where m.channel = :channel")
    java.util.List<Long> findIdsByChannel(Channel channel);

    /** Flat (id, channelId, authorUsername, bodyMarkdown) projection, keyset-paged by id — used by
     *  the Lucene bootstrap to stream the whole table without materialising entities (BUG-24). */
    @org.springframework.data.jpa.repository.Query(
            "select m.id, m.channel.id, m.author.username, m.bodyMarkdown from Message m "
            + "where m.id > :afterId order by m.id asc")
    java.util.List<Object[]> findIndexRowsAfter(Long afterId, Pageable pageable);

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
    List<Object[]> countRepliesByParentIds(@Param("parentIds") Collection<Long> parentIds);

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
    List<Object[]> countUnreadPerChannel(@Param("userId") Long userId,
                                         @Param("channelIds") Collection<Long> channelIds);

    /** Every message id — the DB side of the Lucene↔DB reconcile (CLEAN-3). */
    @org.springframework.data.jpa.repository.Query("select m.id from Message m")
    java.util.List<Long> findAllMessageIds();

    /** Flat (id, channelId, authorUsername, bodyMarkdown) rows for one author — used to reindex
     *  their messages when their username changes so search-by-author stays correct (N23). */
    @org.springframework.data.jpa.repository.Query(
            "select m.id, m.channel.id, m.author.username, m.bodyMarkdown from Message m where m.author.id = :authorId")
    java.util.List<Object[]> findIndexRowsByAuthor(Long authorId);
}
