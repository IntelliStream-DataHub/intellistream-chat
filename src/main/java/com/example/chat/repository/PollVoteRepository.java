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

import com.example.chat.domain.Poll;
import com.example.chat.domain.PollVote;
import com.example.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PollVoteRepository extends JpaRepository<PollVote, UUID> {

    Optional<PollVote> findByPollAndVoter(Poll poll, User voter);

    @Modifying
    @Transactional
    void deleteByPollAndVoter(Poll poll, User voter);

    /**
     * Per-option vote counts for the supplied polls in a single round-trip.
     * Returns rows of {@code (pollId, optionId, count)}.
     */
    @Query("""
            select v.poll.id, v.option.id, count(v.id)
              from PollVote v
             where v.poll.id in (:pollIds)
             group by v.poll.id, v.option.id
            """)
    List<Object[]> tallyByPollIds(@Param("pollIds") Collection<UUID> pollIds);

    /** Resolve "which option did THIS user vote for" for a list of polls. */
    @Query("""
            select v.poll.id, v.option.id
              from PollVote v
             where v.voter = :voter and v.poll.id in (:pollIds)
            """)
    List<Object[]> myVotesByPollIds(@Param("voter") User voter,
                                    @Param("pollIds") Collection<UUID> pollIds);
}
