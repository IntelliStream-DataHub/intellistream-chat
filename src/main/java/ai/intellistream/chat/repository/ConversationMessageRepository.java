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
import ai.intellistream.chat.domain.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    /**
     * The conversation feed: top-level messages only, newest first.
     *
     * <p>{@code m.parent is null} is what keeps a thread's replies out of the feed, mirroring
     * {@code MessageRepository.findByChannelAndParentIsNullOrderByCreatedAtDesc}. Without it a busy
     * thread would push the conversation it belongs to off its own page.
     */
    @Query("""
            select m from ConversationMessage m
            join fetch m.author
            join fetch m.conversation
            where m.conversation = :conversation and m.parent is null
            order by m.createdAt desc
            """)
    List<ConversationMessage> findByConversationOrderByCreatedAtDesc(Conversation conversation, Pageable pageable);

    /** Forward page after a timestamp, oldest-first — the DM reconnect backfill (N4/BUG-3). */
    @Query("""
            select m from ConversationMessage m
            join fetch m.author
            join fetch m.conversation
            where m.conversation = :conversation and m.parent is null and m.createdAt > :after
            order by m.createdAt asc, m.id asc
            """)
    List<ConversationMessage> findByConversationAndCreatedAtAfterOrderByCreatedAtAsc(
            Conversation conversation, Instant after, Pageable pageable);

    /** One thread's replies, oldest first — what the thread panel renders under the parent. */
    @Query("""
            select m from ConversationMessage m
            join fetch m.author
            join fetch m.conversation
            where m.parent = :parent
            order by m.createdAt asc, m.id asc
            """)
    List<ConversationMessage> findByParentOrderByCreatedAtAsc(ConversationMessage parent);

    long countByParent(ConversationMessage parent);

    /**
     * Reply counts for a page of parents in one query — parents with no replies are absent from the
     * result rather than present with a zero, so the caller's {@code getOrDefault(id, 0L)} is the
     * only place the default is written.
     */
    @Query("select m.parent.id, count(m) from ConversationMessage m where m.parent.id in :parentIds group by m.parent.id")
    List<Object[]> countRepliesByParentIds(@Param("parentIds") Collection<Long> parentIds);

    /**
     * Ids of a message's replies. Read before a delete so the caller can reap their attachments and
     * their Lucene documents: the {@code on delete cascade} takes the rows, and nothing else.
     */
    @Query("select m.id from ConversationMessage m where m.parent.id = :parentId")
    List<Long> findReplyIds(@Param("parentId") Long parentId);

    /**
     * The distinct authors of a thread — the parent's author plus everyone who has replied — as
     * {@code [userId, username]} rows, ordered so the parent's author comes first.
     *
     * <p>Derived from the messages rather than a follow table, exactly as the channel side does it:
     * having written in a thread is what being in it means, and a table saying the same thing can
     * only ever disagree with the messages.
     */
    @Query("""
            select distinct m.author.id, m.author.username from ConversationMessage m
            where m.id = :parentId or m.parent.id = :parentId
            """)
    List<Object[]> findThreadParticipants(@Param("parentId") Long parentId);

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
            left join fetch m.parent
            where m.id = :id
            """)
    Optional<ConversationMessage> findByIdWithAuthor(Long id);

    /**
     * Batch hydration of search hits: author and conversation are join-fetched so the caller can
     * build DTOs after the read transaction closes (open-in-view is off).
     *
     * <p>Note what this method is <b>not</b>: it does no access control. By the time ids reach it
     * they have already been produced by a Lucene query carrying the viewer's membership filter,
     * and that is the only place the check belongs — see {@code MessageIndexService.searchAccessible}.
     */
    @Query("""
            select m from ConversationMessage m
            join fetch m.author
            join fetch m.conversation
            where m.id in :ids
            """)
    List<ConversationMessage> findAllByIdWithAuthor(@Param("ids") Collection<Long> ids);

    /** Every conversation-message id — the DB side of the Lucene↔DB reconcile. There is no soft
     *  delete on conversation messages, so this is simply every live row. */
    @Query("select m.id from ConversationMessage m")
    List<Long> findAllMessageIds();

    /** Flat {@code (id, conversationId, authorUsername, bodyMarkdown)} rows for a set of ids —
     *  used to (re)build index documents without materialising entities. */
    @Query("select m.id, m.conversation.id, m.author.username, m.bodyMarkdown from ConversationMessage m "
           + "where m.id in :ids")
    List<Object[]> findIndexRowsByIds(@Param("ids") Collection<Long> ids);

    /** Keyset-paged flat index projection — lets the startup backfill stream the whole table
     *  without holding it in memory (mirrors {@code MessageRepository.findIndexRowsAfter}). */
    @Query("select m.id, m.conversation.id, m.author.username, m.bodyMarkdown from ConversationMessage m "
           + "where m.id > :afterId order by m.id asc")
    List<Object[]> findIndexRowsAfter(Long afterId, Pageable pageable);

    /** Flat index rows for one author — used to reindex their conversation messages when their
     *  username changes, so {@code @handle} search stays correct in DMs too (N23). */
    @Query("select m.id, m.conversation.id, m.author.username, m.bodyMarkdown from ConversationMessage m "
           + "where m.author.id = :authorId")
    List<Object[]> findIndexRowsByAuthor(Long authorId);
}
