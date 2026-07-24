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

package ai.intellistream.threadorbit.repository;

import ai.intellistream.threadorbit.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findBySubject(String subject);

    /** Explicit {@code lower(username)} predicate so the V2 functional unique index is used —
     *  Spring's derived {@code IgnoreCase} generates {@code UPPER(username)=UPPER(?)}, which can't
     *  serve that index and forced a seq scan on every mention/login/lookup (N27). */
    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    /**
     * Batch lookup used by {@code PresenceService} so the auto-away computation can
     * read {@code lastActiveAt} for every requested user in a single query, without
     * resorting to N+1 lazy fetches off {@code UserPresence.user}.
     */
    @Query("select u from User u where lower(u.username) in :usernames")
    List<User> findAllByUsernameLowerIn(@Param("usernames") Collection<String> lowercaseUsernames);

    /** Every non-null avatar storage key — the live set for the orphan-avatar sweep (CLEAN-2). */
    @org.springframework.data.jpa.repository.Query("select u.avatarStorageKey from User u where u.avatarStorageKey is not null")
    java.util.List<String> findAllAvatarStorageKeys();

    /** Insert a freshly-provisioned user if the subject is new, ignore on the subject conflict
     *  (N1: concurrent first login). Username uniqueness is disambiguated before this call. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "insert into users (subject, username, email, display_name, admin) values (:subject, :username, :email, :displayName, :admin) on conflict (subject) do nothing", nativeQuery = true)
    void insertIgnore(@org.springframework.data.repository.query.Param("subject") String subject, @org.springframework.data.repository.query.Param("username") String username, @org.springframework.data.repository.query.Param("email") String email, @org.springframework.data.repository.query.Param("displayName") String displayName, @org.springframework.data.repository.query.Param("admin") boolean admin);
}
