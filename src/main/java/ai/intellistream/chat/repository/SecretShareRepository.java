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

import ai.intellistream.chat.domain.SecretShare;
import ai.intellistream.chat.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;


public interface SecretShareRepository extends JpaRepository<SecretShare, Long> {

    /**
     * The secret behind a link, locked ({@code SELECT … FOR UPDATE}) for the rest of the transaction.
     *
     * <p>The lock is what makes "opens exactly once" true under concurrency: two requests racing to
     * open the same link serialize here, and the second sees the first one's consumed row. It must be
     * called from a read-write transaction — a {@code readOnly} one is routed to the replica when one
     * is configured, and a hot standby refuses row locks. No {@code join fetch} of the creator, so the
     * lock stays on this row and does not also hold the creator's {@code users} row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SecretShare s where s.publicId = :publicId")
    Optional<SecretShare> findByPublicIdForUpdate(@Param("publicId") String publicId);

    /** The secret behind a link, without a lock — for looking, not for opening. */
    Optional<SecretShare> findByPublicId(String publicId);

    /** The creator's own secrets, newest first. */
    Page<SecretShare> findByCreatorOrderByCreatedAtDesc(User creator, Pageable pageable);

    /** Secrets of this creator that can still be opened — the per-user cap counts these. */
    @Query("""
            select count(s) from SecretShare s
             where s.creator = :creator
               and s.payload is not null
               and s.openedAt is null
               and s.revokedAt is null
               and s.expiresAt > :now
            """)
    long countWaiting(@Param("creator") User creator, @Param("now") Instant now);

    /** Forget the ciphertext of every secret whose link has expired unopened. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SecretShare s set s.payload = null where s.payload is not null and s.expiresAt <= :now")
    int clearExpiredPayloads(@Param("now") Instant now);

    /**
     * Delete secrets that ended — opened, revoked or expired — before {@code cutoff}, and with them
     * the receipt details, including an anonymous opener's address.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SecretShare s where coalesce(s.openedAt, s.revokedAt, s.expiresAt) < :cutoff")
    int deleteEndedBefore(@Param("cutoff") Instant cutoff);
}
