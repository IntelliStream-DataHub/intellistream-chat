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
import org.springframework.data.domain.Pageable;
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

    /**
     * The channel's other members, longest-standing first — page it to 1 to get the successor when
     * the last admin leaves.
     *
     * <p>Longest-standing rather than, say, most active: it needs no extra data, it is stable, and
     * it is explicable to the person it happens to ("you were here first"). The {@code id} tiebreak
     * matters because {@code joined_at} defaults to {@code now()} and everyone bulk-invited in one
     * statement shares a timestamp.
     */
    @Query("""
            select m from ChannelMember m
            join fetch m.user
            where m.channel = :channel and m.user <> :excluding
            order by m.joinedAt asc, m.id asc
            """)
    List<ChannelMember> findOthersOldestFirst(Channel channel, User excluding, Pageable pageable);

    /** Memberships with their channels eager-fetched — the sidebar render reads m.getChannel()
     *  per row, so the plain findAllByUser lazy-loaded one channel per membership (N28). */
    @Query("select m from ChannelMember m join fetch m.channel where m.user = :user")
    List<ChannelMember> findAllByUserFetchingChannel(User user);

    /**
     * The same, minus archived channels — what the sidebar renders.
     *
     * <p>A separate query rather than a filter over {@link #findAllByUserFetchingChannel}, because
     * the two callers want different sets and the difference is not cosmetic. The sidebar is "the
     * channels you are working in", and an archived one is by definition not one of those; the
     * membership is deliberately kept (unarchiving restores the row to the sidebar with its
     * favourite and notification settings intact, and its read marker where it was), it is only
     * hidden. The other caller annotates search results with whether the viewer has joined, and
     * there "you are a member" is true whatever the channel's state.
     *
     * <p>Filtered in the query, not after it: the sidebar's follow-up unread and mention counts are
     * driven off this list, and counting unread in channels the user cannot see or post to is work
     * done to produce a number nothing renders.
     */
    @Query("""
            select m from ChannelMember m
            join fetch m.channel c
            where m.user = :user and c.archivedAt is null
            """)
    List<ChannelMember> findLiveByUserFetchingChannel(User user);

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

    /** Insert a MEMBER row if absent, ignore if the (channel,user) row already exists (N1). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "insert into channel_members (channel_id, user_id, role) values (:channelId, :userId, 'MEMBER') on conflict (channel_id, user_id) do nothing", nativeQuery = true)
    void insertMemberIgnore(@org.springframework.data.repository.query.Param("channelId") Long channelId, @org.springframework.data.repository.query.Param("userId") Long userId);
}
