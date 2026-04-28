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

import ai.intellistream.radiance.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findBySubject(String subject);

    Optional<User> findByUsernameIgnoreCase(String username);

    /**
     * Batch lookup used by {@code PresenceService} so the auto-away computation can
     * read {@code lastActiveAt} for every requested user in a single query, without
     * resorting to N+1 lazy fetches off {@code UserPresence.user}.
     */
    @Query("select u from User u where lower(u.username) in :usernames")
    List<User> findAllByUsernameLowerIn(@Param("usernames") Collection<String> lowercaseUsernames);
}
