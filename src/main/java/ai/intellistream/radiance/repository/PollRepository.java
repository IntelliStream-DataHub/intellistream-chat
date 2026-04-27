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

package ai.intellistream.radiance.repository;

import ai.intellistream.radiance.domain.Poll;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PollRepository extends JpaRepository<Poll, UUID> {

    /**
     * Single-poll lookup with options + host message + channel eagerly fetched. The vote
     * path needs all three: options to validate the chosen one, message + channel to enforce
     * membership in the controller before we touch the DB.
     */
    @Query("""
            select p from Poll p
              left join fetch p.options
              left join fetch p.message m
              left join fetch m.channel
             where p.id = :id
            """)
    Optional<Poll> findByIdWithOptions(@Param("id") UUID id);

    /** Single-poll lookup by host message id. */
    @Query("""
            select p from Poll p
              left join fetch p.options
              left join fetch p.message m
              left join fetch m.channel
             where m.id = :messageId
            """)
    Optional<Poll> findByMessageIdWithOptions(@Param("messageId") UUID messageId);

    /** Batch fetch for rendering a page of channel messages — one round-trip, options included. */
    @Query("select p from Poll p left join fetch p.options where p.message.id in (:messageIds)")
    List<Poll> findByMessageIdsWithOptions(@Param("messageIds") Collection<UUID> messageIds);
}
