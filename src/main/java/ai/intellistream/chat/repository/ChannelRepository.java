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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface ChannelRepository extends JpaRepository<Channel, Long> {
    Optional<Channel> findBySlug(String slug);

    /**
     * Is this slug taken by some <em>other</em> channel? The rename collision check.
     *
     * <p>{@code create} asks {@link #findBySlug} instead, because at creation time there is no id
     * to exclude. Renaming a channel to a name that slugifies to the one it already has must be a
     * no-op, not a conflict — otherwise "Deploys" → "deploys" is refused for colliding with itself.
     */
    boolean existsBySlugAndIdNot(String slug, Long id);

    List<Channel> findAllByTypeOrderByNameAsc(ChannelType type);

    /**
     * Channels matching {@code q} by name or slug that {@code user} is allowed to see: every
     * PUBLIC channel, plus the PRIVATE ones they belong to. The visibility rule lives in the query
     * rather than in a filter afterwards, so a private channel the user isn't in never leaves the
     * database — this endpoint would otherwise be a directory of every private channel's name.
     *
     * <p>Paged, because this replaced a sidebar that rendered every channel there was.
     */
    @org.springframework.data.jpa.repository.Query("""
            select c from Channel c
             where (lower(c.name) like lower(concat('%', :q, '%'))
                    or lower(c.slug) like lower(concat('%', :q, '%')))
               and (c.type = :publicType
                    or exists (select 1 from ChannelMember m where m.channel = c and m.user = :user))
             order by c.name asc
            """)
    List<Channel> searchVisibleTo(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("user") ai.intellistream.chat.domain.User user,
            @org.springframework.data.repository.query.Param("publicType") ChannelType publicType,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Rename / re-describe a channel, as one UPDATE rather than through a setter.
     *
     * <p><b>This is the shape the whole feature turns on.</b> {@code Channel} exposes no mutators and
     * must not gain any: {@code ChannelAccessCache} hands cached instances to STOMP SUBSCRIBE
     * authorization, and {@code ChannelImmutabilityTest} fails the build if a setter reappears. A
     * bulk update writes the row without ever putting a mutable entity in anyone's hands, so the
     * invariant is preserved by construction instead of by everybody remembering it. The eviction
     * that keeps the cache honest lives in {@code ChannelService.rename}, which is the only caller.
     *
     * <p>{@code clearAutomatically} because a bulk update bypasses the persistence context: without
     * it, a {@code Channel} already loaded in this transaction would keep serving the old name from
     * the first-level cache, and the re-read that produces the broadcast payload would return stale
     * values. {@code flushAutomatically} so a pending insert (the channel created moments ago in the
     * same transaction, as the tests do it) is on the table before the UPDATE looks for it.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Channel c
               set c.slug = :slug, c.name = :name, c.description = :description
             where c.id = :id
            """)
    int renameById(@org.springframework.data.repository.query.Param("id") Long id,
                   @org.springframework.data.repository.query.Param("slug") String slug,
                   @org.springframework.data.repository.query.Param("name") String name,
                   @org.springframework.data.repository.query.Param("description") String description);
}
