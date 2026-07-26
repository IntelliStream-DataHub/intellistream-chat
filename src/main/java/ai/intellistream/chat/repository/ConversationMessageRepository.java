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

    @Query("""
            select m from ConversationMessage m
            join fetch m.author
            join fetch m.conversation
            where m.conversation = :conversation
            order by m.createdAt desc
            """)
    List<ConversationMessage> findByConversationOrderByCreatedAtDesc(Conversation conversation, Pageable pageable);

    /** Forward page after a timestamp, oldest-first — the DM reconnect backfill (N4/BUG-3). */
    @Query("""
            select m from ConversationMessage m
            join fetch m.author
            join fetch m.conversation
            where m.conversation = :conversation and m.createdAt > :after
            order by m.createdAt asc, m.id asc
            """)
    List<ConversationMessage> findByConversationAndCreatedAtAfterOrderByCreatedAtAsc(
            Conversation conversation, Instant after, Pageable pageable);

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

    /**
     * Flat {@code (messageId, filename)} rows for the live attachments on these messages — the
     * filename half of the index document. See {@code MessageRepository.findIndexFilenamesByIds}
     * for why it is a second query rather than a join, and why it lives here.
     */
    @Query("select a.message.id, a.filename from ConversationAttachment a "
           + "where a.message.id in :ids and a.deletedAt is null "
           + "order by a.createdAt asc, a.id asc")
    List<Object[]> findIndexFilenamesByIds(@Param("ids") Collection<Long> ids);

    /** {@link #findIndexFilenamesByIds} for a single message — the write path's edit/attach hooks. */
    @Query("select a.filename from ConversationAttachment a "
           + "where a.message.id = :messageId and a.deletedAt is null "
           + "order by a.createdAt asc, a.id asc")
    List<String> findIndexFilenames(@Param("messageId") Long messageId);
}
