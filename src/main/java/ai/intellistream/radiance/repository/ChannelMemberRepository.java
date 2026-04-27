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

import ai.intellistream.radiance.domain.Channel;
import ai.intellistream.radiance.domain.ChannelMember;
import ai.intellistream.radiance.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelMemberRepository extends JpaRepository<ChannelMember, UUID> {

    Optional<ChannelMember> findByChannelAndUser(Channel channel, User user);

    @Query("""
            select m from ChannelMember m
            join fetch m.user
            where m.channel = :channel
            order by m.joinedAt asc
            """)
    List<ChannelMember> findAllByChannelOrderByJoinedAtAsc(Channel channel);

    List<ChannelMember> findAllByUser(User user);

    @Query("""
            select m.channel from ChannelMember m
            where m.user = :user
            order by m.channel.name asc
            """)
    List<Channel> findChannelsForUser(User user);

    boolean existsByChannelAndUser(Channel channel, User user);

    long countByChannel(Channel channel);
}
