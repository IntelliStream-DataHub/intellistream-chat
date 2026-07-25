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

import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;


public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByMessageOrderByCreatedAtAsc(Message message);

    List<Attachment> findByMessageInOrderByCreatedAtAsc(Collection<Message> messages);

    /**
     * Every attachment in a channel, with its message and that message's author loaded — captured
     * before channel deletion so the files can be reaped and their bytes credited back.
     *
     * <p>Returns rows rather than storage keys because the key alone is not enough any more: the
     * uploader and the size are recorded nowhere but here, and once the channel is deleted there is
     * no way to work out who to credit. The author is join-fetched because destroying a busy
     * channel would otherwise emit one query per attachment just to learn its owner.
     */
    @org.springframework.data.jpa.repository.Query("""
            select a from Attachment a
            join fetch a.message m
            join fetch m.author
            where m.channel = :channel
            """)
    List<Attachment> findByChannelWithAuthor(ai.intellistream.chat.domain.Channel channel);

    /**
     * Attachments hanging off the given messages <b>or off any reply underneath them</b> — the set
     * a retention purge is about to destroy.
     *
     * <p>The reply half is not defensive: {@code messages.parent_id} is {@code on delete cascade},
     * so hard-deleting a thread parent takes its (already-removed) replies with it, and the purge
     * only ever sees the parents' ids. A query over the batch's own ids would leave every reply's
     * file on disk and every reply's bytes charged to its uploader forever. One level of expansion
     * is enough — a reply cannot itself be replied to (see {@code MessageService.replyInThread}).
     *
     * <p>The parent is reached through an explicit {@code left join} rather than a {@code m.parent.id}
     * path. A path dereference is the shorter spelling but leaves the null-parent case resting on
     * Hibernate choosing the FK column over an implicit inner join; if it ever chose the join, every
     * top-level message would drop out of the result and the first half of the predicate would
     * quietly stop matching anything.
     */
    @org.springframework.data.jpa.repository.Query("""
            select a from Attachment a
            join fetch a.message m
            join fetch m.author
            left join m.parent p
            where m.id in :messageIds or p.id in :messageIds
            """)
    List<Attachment> findByMessageIdsIncludingReplies(
            @org.springframework.data.repository.query.Param("messageIds") Collection<Long> messageIds);

    /** Every attachment storage key — the live set for the orphan-attachment sweep (CLEAN-1). */
    @org.springframework.data.jpa.repository.Query("select a.storageKey from Attachment a")
    java.util.List<String> findAllStorageKeys();
}
