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

    /**
     * Live public channels, alphabetically — the public listing.
     *
     * <p>Archived channels are excluded here rather than filtered by callers, because "the channels
     * you can join" is what this question means and an archived one cannot be joined. A caller that
     * genuinely wants the archived ones asks for them by name.
     */
    List<Channel> findAllByTypeAndArchivedAtIsNullOrderByNameAsc(ChannelType type);

    /** Archived channels, most recently archived first — the admin console's unarchive/delete list. */
    List<Channel> findAllByArchivedAtIsNotNullOrderByArchivedAtDesc();

    /**
     * Ids of every channel of one type. Used by search to widen its scope to all PUBLIC channels
     * — which the viewer may read whether or not they joined — without materialising the entities.
     *
     * <p>Projecting ids rather than rows matters at scale here: the result feeds a Lucene
     * {@code TermInSetQuery}, so a workspace with thousands of public channels would otherwise
     * hydrate thousands of {@code Channel} entities per search to read one field off each.
     */
    @org.springframework.data.jpa.repository.Query("select c.id from Channel c where c.type = :type")
    List<Long> findIdsByType(@org.springframework.data.repository.query.Param("type") ChannelType type);

    /**
     * Exact (case-insensitive) lookup by either identifier, for search's {@code in:#channel}
     * modifier — a user types the name they see in the sidebar, which is the display name, while
     * every URL in the app carries the slug.
     *
     * <p>Returns at most one row and does <b>no</b> access check: the caller must apply the
     * channel read rules, and must not let "found but unreadable" and "not found" produce
     * different messages, or the modifier becomes a way to enumerate private channel names.
     */
    @org.springframework.data.jpa.repository.Query("""
            select c from Channel c
             where lower(c.slug) = lower(:q) or lower(c.name) = lower(:q)
             order by c.id asc
             limit 1
            """)
    Optional<Channel> findFirstBySlugOrNameIgnoreCase(
            @org.springframework.data.repository.query.Param("q") String q);

    /**
     * Channels matching {@code q} by name or slug that {@code user} is allowed to see: every
     * PUBLIC channel, plus the PRIVATE ones they belong to. The visibility rule lives in the query
     * rather than in a filter afterwards, so a private channel the user isn't in never leaves the
     * database — this endpoint would otherwise be a directory of every private channel's name.
     *
     * <p>Paged, because this replaced a sidebar that rendered every channel there was.
     *
     * <p>Archived channels never appear. Being out of the way is most of what archiving is for, and
     * a finished project turning up in type-ahead is the clutter the feature exists to remove.
     * Getting back to one is not through here: it is the message search (its content stays indexed),
     * the {@code /channels/{id}} route, or the admin console's archived list.
     */
    @org.springframework.data.jpa.repository.Query("""
            select c from Channel c
             where (lower(c.name) like lower(concat('%', :q, '%'))
                    or lower(c.slug) like lower(concat('%', :q, '%')))
               and c.archivedAt is null
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
     * Live PUBLIC channels, largest first: each row is {@code [Channel, Long memberCount]}.
     *
     * <p>Backs two things — the <em>Suggested for you</em> group a brand-new account sees in the
     * sidebar, and the Browse channels directory — and it is a population ranking on purpose, not
     * recency or activity: a person who has joined nothing has no history to rank by, and "where
     * everybody already is" is the one signal that answers "where should I start". PUBLIC only,
     * because a private channel's name is not something a non-member gets to read, and never
     * archived, for the same reason {@link #searchVisibleTo} leaves them out.
     *
     * <p>The count is a {@code left join} so a channel with nobody left in it still ranks, at
     * zero, rather than vanishing from the directory. Ties break on name then id, so the order is
     * total and a page never reshuffles between two loads.
     */
    @org.springframework.data.jpa.repository.Query("""
            select c, count(m.id)
              from Channel c
              left join ChannelMember m on m.channel = c
             where c.type = :publicType
               and c.archivedAt is null
             group by c
             order by count(m.id) desc, lower(c.name) asc, c.id asc
            """)
    List<Object[]> findLargestPublic(
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

    /**
     * Archive or unarchive, as one UPDATE. Same shape and the same reason as {@link #renameById}:
     * {@code Channel} has no setters and must not gain any, so the row is written directly.
     *
     * <p>Unarchiving passes nulls for all three, which clears the tombstone completely rather than
     * leaving a stale {@code archived_by} behind a null {@code archived_at} — a half-cleared record
     * is what makes a later "who archived this?" answer confidently wrong.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Channel c
               set c.archivedAt = :at, c.archivedBy = :by, c.archivedByUsername = :byUsername
             where c.id = :id
            """)
    int setArchivedById(@org.springframework.data.repository.query.Param("id") Long id,
                        @org.springframework.data.repository.query.Param("at") java.time.Instant at,
                        @org.springframework.data.repository.query.Param("by") ai.intellistream.chat.domain.User by,
                        @org.springframework.data.repository.query.Param("byUsername") String byUsername);
}
