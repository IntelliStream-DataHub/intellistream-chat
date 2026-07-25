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

package ai.intellistream.chat.repository;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;


public interface ChannelRepository extends JpaRepository<Channel, Long> {
    Optional<Channel> findBySlug(String slug);

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
}
