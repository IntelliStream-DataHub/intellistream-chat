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

import ai.intellistream.chat.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findByDmKey(String dmKey);

    /** Insert a DIRECT conversation for the given dm_key if absent, ignore on conflict (N1). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "insert into conversations (type, dm_key, created_by) values ('DIRECT', :dmKey, :creatorId) on conflict (dm_key) do nothing", nativeQuery = true)
    void insertDirectIgnore(@org.springframework.data.repository.query.Param("dmKey") String dmKey, @org.springframework.data.repository.query.Param("creatorId") Long creatorId);
}
