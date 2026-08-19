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

import ai.intellistream.chat.domain.User;
import org.springframework.data.domain.Pageable;
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

    /**
     * Every account with this email, case-insensitively. Used once per never-seen subject at login,
     * to find the account it may be the new key for — see {@code UserService.upsert}. Returns a list
     * rather than an Optional because two rows sharing an email is exactly the state that linking
     * exists to prevent, and if it has already happened the caller must not guess between them.
     */
    @Query("select u from User u where u.email is not null and lower(u.email) = lower(:email)")
    List<User> findAllByEmailIgnoreCase(@Param("email") String email);

    /**
     * Users not already a member of {@code channelId}, matching a wildcard username pattern and
     * an email-domain-prefix pattern (both pre-built {@code LIKE} patterns, escaped with
     * {@code !} — see {@code UserService.usernamePattern}/{@code emailDomainPattern}), ordered by
     * whatever {@code pageable}'s sort says. Backs the channel settings "Find user" browser
     * (see {@code UserService.searchInviteCandidates}); the {@code not exists} keeps someone
     * already in the channel from showing up as a candidate to add.
     *
     * <p>An empty {@code emailDomainPattern} skips that predicate entirely rather than requiring
     * a non-null email — most accounts have one, but the filter should not silently exclude the
     * ones that don't when nobody asked to filter by domain.
     */
    @Query("""
            select u from User u
            where lower(u.username) like lower(:usernamePattern) escape '!'
              and (:emailDomainPattern = '' or lower(u.email) like lower(:emailDomainPattern) escape '!')
              and not exists (
                  select 1 from ChannelMember m where m.channel.id = :channelId and m.user = u
              )
            """)
    List<User> searchNotInChannel(@Param("channelId") Long channelId,
                                   @Param("usernamePattern") String usernamePattern,
                                   @Param("emailDomainPattern") String emailDomainPattern,
                                   Pageable pageable);

    /**
     * The whole-directory sibling of {@link #searchNotInChannel}: same wildcard username pattern
     * and email-domain-prefix pattern, no channel exclusion. Backs the "Find user" browser on the
     * new-conversation form (see {@code UserService.searchDirectory}) — a DM has no channel to
     * scope against, which is the whole point of starting one.
     */
    @Query("""
            select u from User u
            where lower(u.username) like lower(:usernamePattern) escape '!'
              and (:emailDomainPattern = '' or lower(u.email) like lower(:emailDomainPattern) escape '!')
            """)
    List<User> searchDirectory(@Param("usernamePattern") String usernamePattern,
                               @Param("emailDomainPattern") String emailDomainPattern,
                               Pageable pageable);

    /** Every non-null avatar storage key — the live set for the orphan-avatar sweep (CLEAN-2). */
    @org.springframework.data.jpa.repository.Query("select u.avatarStorageKey from User u where u.avatarStorageKey is not null")
    java.util.List<String> findAllAvatarStorageKeys();

    /** Insert a freshly-provisioned user if the subject is new, ignore on the subject conflict
     *  (N1: concurrent first login). Username uniqueness is disambiguated before this call. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "insert into users (subject, username, email, display_name, admin) values (:subject, :username, :email, :displayName, :admin) on conflict (subject) do nothing", nativeQuery = true)
    void insertIgnore(@org.springframework.data.repository.query.Param("subject") String subject, @org.springframework.data.repository.query.Param("username") String username, @org.springframework.data.repository.query.Param("email") String email, @org.springframework.data.repository.query.Param("displayName") String displayName, @org.springframework.data.repository.query.Param("admin") boolean admin);
}
