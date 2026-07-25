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

    /** Storage keys of every attachment in a channel — captured before channel deletion to reap files. */
    @org.springframework.data.jpa.repository.Query("select a.storageKey from Attachment a where a.message.channel = :channel")
    java.util.List<String> findStorageKeysByChannel(ai.intellistream.chat.domain.Channel channel);

    /** Every attachment storage key — the live set for the orphan-attachment sweep (CLEAN-1). */
    @org.springframework.data.jpa.repository.Query("select a.storageKey from Attachment a")
    java.util.List<String> findAllStorageKeys();
}
