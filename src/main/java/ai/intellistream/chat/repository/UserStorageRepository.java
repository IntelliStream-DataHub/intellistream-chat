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

import ai.intellistream.chat.domain.UserStorage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserStorageRepository extends JpaRepository<UserStorage, Long> {

    /**
     * Add (or subtract) bytes atomically, creating the row if this user has never uploaded.
     *
     * <p>Native upsert rather than read-modify-write because two concurrent uploads from the same
     * account would otherwise interleave and lose one of the increments, which is exactly the
     * situation a quota is supposed to catch. The CHECK constraint keeps a buggy decrement from
     * driving the total negative.
     */
    @Modifying
    @Query(value = """
            insert into user_storage (user_id, bytes_used, updated_at)
            values (:userId, greatest(:delta, 0), now())
            on conflict (user_id) do update
              set bytes_used = greatest(user_storage.bytes_used + :delta, 0),
                  updated_at = now()
            """, nativeQuery = true)
    void addBytes(@Param("userId") long userId, @Param("delta") long delta);

    @Query(value = "select coalesce(sum(bytes_used), 0) from user_storage", nativeQuery = true)
    long totalBytesUsed();
}
