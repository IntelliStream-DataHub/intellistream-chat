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
import ai.intellistream.chat.domain.ChannelMember;
import ai.intellistream.chat.domain.ChannelRole;
import ai.intellistream.chat.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;


public interface ChannelMemberRepository extends JpaRepository<ChannelMember, Long> {

    Optional<ChannelMember> findByChannelAndUser(Channel channel, User user);

    /** Lock a channel's rows of a given role (SELECT … FOR UPDATE) so concurrent role changes on
     *  the channel serialize — used by demote to enforce the last-admin invariant race-free. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ChannelMember m join fetch m.user where m.channel = :channel and m.role = :role")
    List<ChannelMember> findByChannelAndRoleForUpdate(Channel channel, ChannelRole role);

    @Query("""
            select m from ChannelMember m
            join fetch m.user
            where m.channel = :channel
            order by m.joinedAt asc
            """)
    List<ChannelMember> findAllByChannelOrderByJoinedAtAsc(Channel channel);

    List<ChannelMember> findAllByUser(User user);

    /** Memberships with their channels eager-fetched — the sidebar render reads m.getChannel()
     *  per row, so the plain findAllByUser lazy-loaded one channel per membership (N28). */
    @Query("select m from ChannelMember m join fetch m.channel where m.user = :user")
    List<ChannelMember> findAllByUserFetchingChannel(User user);

    @Query("""
            select m.channel from ChannelMember m
            where m.user = :user
            order by m.channel.name asc
            """)
    List<Channel> findChannelsForUser(User user);

    boolean existsByChannelAndUser(Channel channel, User user);

    /** Of the given user ids, which are members of {@code channel} — used to keep private-channel
     *  mentions from reaching non-members (N2), in one query rather than N membership checks. */
    @Query("select m.user.id from ChannelMember m where m.channel = :channel and m.user.id in :userIds")
    List<Long> findMemberUserIds(Channel channel, java.util.Collection<Long> userIds);

    long countByChannel(Channel channel);

    /**
     * {@code (channelId, memberCount)} for every channel {@code user} belongs to, in one query.
     * Feeds the sidebar's "largest channels" group — counting members per channel individually
     * would be a query per row of a list that is rendered on every page load.
     */
    @Query("""
           select m.channel.id, count(other.id)
             from ChannelMember m
             join ChannelMember other on other.channel = m.channel
            where m.user = :user
            group by m.channel.id
           """)
    List<Object[]> memberCountsForChannelsOf(@Param("user") User user);

    /** Insert a MEMBER row if absent, ignore if the (channel,user) row already exists (N1). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "insert into channel_members (channel_id, user_id, role) values (:channelId, :userId, 'MEMBER') on conflict (channel_id, user_id) do nothing", nativeQuery = true)
    void insertMemberIgnore(@org.springframework.data.repository.query.Param("channelId") Long channelId, @org.springframework.data.repository.query.Param("userId") Long userId);
}
