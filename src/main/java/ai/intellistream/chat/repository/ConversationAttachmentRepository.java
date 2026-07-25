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

import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;


public interface ConversationAttachmentRepository extends JpaRepository<ConversationAttachment, Long> {

    List<ConversationAttachment> findByMessageOrderByCreatedAtAsc(ConversationMessage message);

    List<ConversationAttachment> findByMessageInOrderByCreatedAtAsc(Collection<ConversationMessage> messages);

    /**
     * A message's attachments with their uploader loaded — captured before a delete so the files
     * can be reaped and their bytes credited back. Rows, not storage keys: the key names the file
     * but says nothing about who is being charged for it or how much, and the delete cascade takes
     * that away with the row.
     */
    @org.springframework.data.jpa.repository.Query("""
            select a from ConversationAttachment a
            join fetch a.message m
            join fetch m.author
            where m.id = :messageId
            """)
    List<ConversationAttachment> findByMessageIdWithAuthor(Long messageId);

    /** Every DM attachment storage key — part of the live set for the orphan sweep (CLEAN-1). */
    @org.springframework.data.jpa.repository.Query("select a.storageKey from ConversationAttachment a")
    java.util.List<String> findAllStorageKeys();

    // ------------------------------------------------------------------ file manager (GET /files)

    /**
     * The DM half of {@code AttachmentRepository.findUploadedBy} — one page of the conversation
     * files uploaded by {@code owner}, newest first, optionally narrowed by a filename pattern.
     * Same reasoning throughout: ownership is only expressible through the carrying message, so the
     * predicate on it <em>is</em> the authorization, and no client-supplied id appears in the query.
     *
     * <p>Needs {@code ix_conv_messages_author} (V5) to stay off a sequential scan of every DM in the
     * workspace.
     */
    @org.springframework.data.jpa.repository.Query("""
            select a from ConversationAttachment a
            join fetch a.message m
            join fetch m.conversation
            where m.author = :owner
              and lower(a.filename) like :pattern escape '!'
            order by a.createdAt desc, a.id desc
            """)
    List<ConversationAttachment> findUploadedBy(
            @org.springframework.data.repository.query.Param("owner") ai.intellistream.chat.domain.User owner,
            @org.springframework.data.repository.query.Param("pattern") String pattern,
            org.springframework.data.domain.Pageable pageable);

    /** Row count behind {@link #findUploadedBy}, for the file manager's paging footer. */
    @org.springframework.data.jpa.repository.Query("""
            select count(a) from ConversationAttachment a
            join a.message m
            where m.author = :owner
              and lower(a.filename) like :pattern escape '!'
            """)
    long countUploadedBy(
            @org.springframework.data.repository.query.Param("owner") ai.intellistream.chat.domain.User owner,
            @org.springframework.data.repository.query.Param("pattern") String pattern);

    /** Total bytes an account's DM uploads still occupy — the "you are storing N" line. */
    @org.springframework.data.jpa.repository.Query("""
            select coalesce(sum(a.sizeBytes), 0) from ConversationAttachment a
            join a.message m
            where m.author = :owner
            """)
    long sumBytesUploadedBy(
            @org.springframework.data.repository.query.Param("owner") ai.intellistream.chat.domain.User owner);
}
