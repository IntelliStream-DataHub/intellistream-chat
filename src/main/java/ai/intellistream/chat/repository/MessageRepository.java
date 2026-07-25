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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


/**
 * <h2>Soft delete</h2>
 *
 * A message with {@code deletedAt} set is invisible, not gone: the row survives until the
 * retention purge so a mistaken "clear everything this account wrote" can be undone. That only
 * holds if <b>every</b> reading query filters it out, and the filter lives here rather than in the
 * services because the readers are spread across {@code MessageService}, {@code SearchService},
 * {@code ReactionService}, {@code ReadStateService}, {@code SidebarService} and the Lucene
 * bootstrap — one missed call site and a purged message reappears somewhere.
 *
 * <p>The methods that deliberately <b>include</b> deleted rows say so in their name
 * ({@code ...IncludingDeleted}) or in their javadoc; treat that as the exception list. The bare
 * {@link JpaRepository#findById} inherited from Spring Data is also unfiltered — it is used by
 * moderation and by cascade cleanup, so callers on a read path must use one of the fetching
 * finders below instead.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("""
            select m from Message m
            join fetch m.author
            where m.id = :id and m.deletedAt is null
            """)
    Optional<Message> findByIdWithAuthor(Long id);

    @Query("""
            select m from Message m
            join fetch m.author
            join fetch m.channel
            where m.id = :id and m.deletedAt is null
            """)
    Optional<Message> findByIdWithChannelAndAuthor(Long id);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.id in :ids and m.deletedAt is null
            """)
    List<Message> findAllByIdWithAuthor(@Param("ids") Collection<Long> ids);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null and m.deletedAt is null
            order by m.createdAt desc, m.id desc
            """)
    List<Message> findByChannelAndParentIsNullOrderByCreatedAtDesc(Channel channel, Pageable pageable);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null and m.deletedAt is null
              and m.createdAt < :before
            order by m.createdAt desc, m.id desc
            """)
    List<Message> findByChannelAndParentIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(
            Channel channel, Instant before, Pageable pageable);

    /** Mirror of {@link #findByChannelAndParentIsNullAndCreatedAtBeforeOrderByCreatedAtDesc} but
     * paging FORWARD from a timestamp. */
    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null and m.deletedAt is null
              and m.createdAt > :after
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
            where m.channel = :channel and m.parent is null and m.deletedAt is null
              and (m.createdAt < :ts or (m.createdAt = :ts and m.id < :id))
            order by m.createdAt desc, m.id desc
            """)
    List<Message> findTopLevelBeforeKeyset(Channel channel, Instant ts, Long id, Pageable pageable);

    /** Composite-keyset "after the anchor": mirror of {@link #findTopLevelBeforeKeyset}. */
    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.parent is null and m.deletedAt is null
              and (m.createdAt > :ts or (m.createdAt = :ts and m.id > :id))
            order by m.createdAt asc, m.id asc
            """)
    List<Message> findTopLevelAfterKeyset(Channel channel, Instant ts, Long id, Pageable pageable);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.channel = :channel and m.pinnedAt is not null and m.deletedAt is null
            order by m.pinnedAt desc
            """)
    List<Message> findByChannelAndPinnedAtIsNotNullOrderByPinnedAtDesc(Channel channel);

    @Query("""
            select m from Message m
            join fetch m.author
            where m.parent = :parent and m.deletedAt is null
            order by m.createdAt asc
            """)
    List<Message> findByParentOrderByCreatedAtAsc(Message parent);

    /**
     * Every reply to {@code parent}, <b>including soft-deleted ones</b> — the hard-delete path.
     * {@code messages.parent_id} is {@code on delete cascade}, so removing the parent takes the
     * replies with it whether or not the application saw them; enumerating them first is what
     * lets their attachment files and index documents be cleaned up rather than orphaned.
     */
    @Query("""
            select m from Message m
            join fetch m.author
            where m.parent = :parent
            order by m.createdAt asc
            """)
    List<Message> findRepliesIncludingDeleted(Message parent);

    /** Live replies only — the "N replies" indicator must not count removed ones. */
    @Query("select count(m) from Message m where m.parent = :parent and m.deletedAt is null")
    long countByParent(Message parent);

    /**
     * Live top-level messages in a channel. Annotated rather than left as a derived query so the
     * soft-delete filter applies: the admin screen's per-channel message count is a "what is in
     * this channel" figure, and it should drop when an admin clears an account's messages.
     */
    @Query("""
            select count(m) from Message m
            where m.channel = :channel and m.parent is null and m.deletedAt is null
            """)
    long countByChannelAndParentIsNull(Channel channel);

    /**
     * All message ids in a channel, <b>including soft-deleted ones</b> — captured before channel
     * deletion to purge the Lucene index. Unfiltered on purpose: the channel and every row in it
     * are about to be hard-deleted, so anything left in the index would be a permanently stale doc.
     */
    @Query("select m.id from Message m where m.channel = :channel")
    List<Long> findIdsByChannel(Channel channel);

    /** Flat (id, channelId, authorUsername, bodyMarkdown) projection, keyset-paged by id — used by
     *  the Lucene bootstrap to stream the whole table without materialising entities (BUG-24).
     *  Skips soft-deleted rows so a rebuild can't resurrect them into search. */
    @Query("select m.id, m.channel.id, m.author.username, m.bodyMarkdown from Message m "
           + "where m.id > :afterId and m.deletedAt is null order by m.id asc")
    List<Object[]> findIndexRowsAfter(Long afterId, Pageable pageable);

    /**
     * For each given parent id, count its top-level replies. Parents with zero replies are
     * absent from the result. Used to render the "N replies" thread indicator on a feed
     * of top-level messages without N+1 queries.
     */
    @Query("""
            select m.parent.id, count(m)
            from Message m
            where m.parent.id in :parentIds and m.deletedAt is null
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
               and msg.deleted_at is null
               and msg.author_id <> :userId
               and (cr.last_read_at is null or msg.created_at > cr.last_read_at)
             group by msg.channel_id
            """, nativeQuery = true)
    List<Object[]> countUnreadPerChannel(@Param("userId") Long userId,
                                         @Param("channelIds") Collection<Long> channelIds);

    /**
     * Every <b>live</b> message id — the DB side of the Lucene↔DB reconcile (CLEAN-3).
     *
     * <p>Filtering here is what makes the reconcile the backstop for soft-delete rather than its
     * enemy. A soft-deleted message keeps its row; if this returned it, the reconcile would see a
     * DB row with no index doc, classify it "missing" and re-index it — putting removed messages
     * back into search an hour after an admin removed them. Excluded, the same sweep classifies a
     * leftover doc as "stale" and drops it, healing any post-commit index delete that was lost.
     */
    @Query("select m.id from Message m where m.deletedAt is null")
    List<Long> findAllMessageIds();

    /** Flat (id, channelId, authorUsername, bodyMarkdown) rows for one author — used to reindex
     *  their messages when their username changes so search-by-author stays correct (N23).
     *  Live rows only: reindexing a removed message would put it back into search. */
    @Query("select m.id, m.channel.id, m.author.username, m.bodyMarkdown from Message m "
           + "where m.author.id = :authorId and m.deletedAt is null")
    List<Object[]> findIndexRowsByAuthor(Long authorId);

    /**
     * {@code (channelId, messageCount)} since a cutoff, for the given channels — how the sidebar
     * decides which of a user's channels are "most active". Native and grouped so it rides the
     * {@code ix_messages_channel_created} index rather than counting rows per channel.
     */
    @Query(value = """
            select m.channel_id, count(*)
              from messages m
             where m.channel_id in (:channelIds)
               and m.created_at >= :since
               and m.deleted_at is null
             group by m.channel_id
            """, nativeQuery = true)
    List<Object[]> countRecentByChannel(@Param("channelIds") Collection<Long> channelIds,
                                        @Param("since") Instant since);

    // ------------------------------------------------------------------ moderation ----
    // Everything below deliberately sees deleted rows: it is the code that sets, clears and
    // finally reaps the flag the read paths above filter on.

    /**
     * Load a message whether or not it has been removed — the only supported way to reach a
     * soft-deleted row by id. Author and channel are join-fetched because the moderation paths
     * need both (audit target, index document) after the transaction closes.
     */
    @Query("""
            select m from Message m
            join fetch m.author
            join fetch m.channel
            where m.id = :id
            """)
    Optional<Message> findByIdIncludingDeleted(Long id);

    /**
     * One keyset page of an author's <b>live</b> message ids. Keyset rather than offset so the
     * caller's delete-as-it-goes loop can't skip rows: each page starts strictly after the last id
     * of the previous one, and rows the loop already flagged simply stop matching.
     */
    @Query("select m.id from Message m "
           + "where m.author = :author and m.deletedAt is null and m.id > :afterId order by m.id asc")
    List<Long> findLiveIdsByAuthorAfter(User author, Long afterId, Pageable pageable);

    /** Keyset page of an author's soft-deleted ids — the undo direction of
     *  {@link #findLiveIdsByAuthorAfter}. */
    @Query("select m.id from Message m "
           + "where m.author = :author and m.deletedAt is not null and m.id > :afterId order by m.id asc")
    List<Long> findDeletedIdsByAuthorAfter(User author, Long afterId, Pageable pageable);

    /**
     * Flag a batch of messages as removed in one statement.
     *
     * <p>{@code and m.deletedAt is null} makes it idempotent and preserves the original removal
     * time: re-running a purge must not push every message's {@code deletedAt} forward, because
     * that timestamp is what the retention window is measured from — refreshing it would keep
     * resetting the clock and the rows would never be reaped.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Message m set m.deletedAt = :at, m.deletedBy = :by "
           + "where m.id in :ids and m.deletedAt is null")
    int softDeleteByIds(@Param("ids") Collection<Long> ids,
                        @Param("at") Instant at,
                        @Param("by") User by);

    /** Clear the removal flag on a batch. Idempotent for the same reason as
     *  {@link #softDeleteByIds}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Message m set m.deletedAt = null, m.deletedBy = null "
           + "where m.id in :ids and m.deletedAt is not null")
    int restoreByIds(@Param("ids") Collection<Long> ids);

    /** Flat index rows for a set of ids, live only — used to put restored messages back into
     *  Lucene without a second pass over the entities. */
    @Query("select m.id, m.channel.id, m.author.username, m.bodyMarkdown from Message m "
           + "where m.id in :ids and m.deletedAt is null")
    List<Object[]> findIndexRowsByIds(@Param("ids") Collection<Long> ids);

    /**
     * One bounded batch of messages the retention purge may hard-delete: removed before
     * {@code cutoff}, oldest removal first.
     *
     * <p>The {@code not exists} guard is not an optimisation, it is a correctness requirement.
     * {@code messages.parent_id} is {@code on delete cascade}, so deleting a thread parent deletes
     * its replies — and an admin who cleared one account's messages will have left that account's
     * thread parents removed while other people's replies underneath them are still live. Purging
     * such a parent would silently destroy messages nobody asked to remove, and no soft-delete row
     * would remain to undo it. Those parents stay until their live replies are gone too.
     *
     * <p>Native because JPQL bulk statements can't take a {@code limit}, and an unbounded purge is
     * exactly the long-running transaction this is designed to avoid.
     */
    @Query(value = """
            select m.id
              from messages m
             where m.deleted_at is not null
               and m.deleted_at < :cutoff
               and not exists (select 1 from messages r
                                where r.parent_id = m.id and r.deleted_at is null)
             order by m.deleted_at
             limit :limit
            """, nativeQuery = true)
    List<Long> findPurgeableIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Hard-delete a batch by id. Dependent rows (attachments, reactions, mentions, polls and
     *  already-removed replies) go with them via the schema's {@code on delete cascade}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Message m where m.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);
}
