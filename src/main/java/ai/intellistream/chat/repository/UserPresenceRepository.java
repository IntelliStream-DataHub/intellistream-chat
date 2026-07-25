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

import ai.intellistream.chat.domain.UserPresence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;


public interface UserPresenceRepository extends JpaRepository<UserPresence, Long> {

    @Query("select p from UserPresence p join fetch p.user u where lower(u.username) in (:usernames)")
    List<UserPresence> findByUsernames(@Param("usernames") Collection<String> lowercaseUsernames);

    /** Ensure a presence row exists for the user, race-free (N1); other columns default/null. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "insert into user_presence (user_id) values (:userId) on conflict (user_id) do nothing", nativeQuery = true)
    void insertRowIgnore(@org.springframework.data.repository.query.Param("userId") Long userId);
}
