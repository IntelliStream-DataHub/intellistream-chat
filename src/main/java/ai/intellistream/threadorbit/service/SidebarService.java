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

package ai.intellistream.threadorbit.service;

import ai.intellistream.threadorbit.domain.Channel;
import ai.intellistream.threadorbit.domain.ChannelMember;
import ai.intellistream.threadorbit.domain.User;
import ai.intellistream.threadorbit.repository.ChannelMemberRepository;
import ai.intellistream.threadorbit.repository.ChannelRepository;
import ai.intellistream.threadorbit.repository.MessageRepository;
import ai.intellistream.threadorbit.web.dto.ChannelSidebarDto;
import ai.intellistream.threadorbit.web.dto.SidebarView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static ai.intellistream.threadorbit.domain.ChannelRole.ADMIN;
import static ai.intellistream.threadorbit.domain.ChannelType.PUBLIC;

/**
 * Builds the left-sidebar channel list: public channels + private channels the user belongs to,
 * each annotated with whether the viewer has joined and whether they are an admin.
 */
@Service
public class SidebarService {

    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final ReadStateService readStateService;

    public SidebarService(ChannelRepository channelRepository,
                          ChannelMemberRepository memberRepository,
                          MessageRepository messageRepository,
                          ReadStateService readStateService) {
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.readStateService = readStateService;
    }

    @Transactional(readOnly = true)
    public List<ChannelSidebarDto> sidebarFor(User user) {
        var publicChannels = channelRepository.findAllByTypeOrderByNameAsc(PUBLIC);
        var memberships = memberRepository.findAllByUserFetchingChannel(user); // avoid per-row channel N+1 (N28)

        LinkedHashMap<Long, ChannelSidebarDto> byId = new LinkedHashMap<>();
        for (var m : memberships) {
            var c = m.getChannel();
            byId.put(c.getId(), ChannelSidebarDto.of(c, true, m.getRole() == ADMIN));
        }
        for (var c : publicChannels) {
            byId.computeIfAbsent(c.getId(), k -> ChannelSidebarDto.of(c, false, false));
        }

        // Unread + mention counts only meaningful for joined channels — others are background catalog.
        var joinedIds = byId.values().stream()
                .filter(ChannelSidebarDto::joined)
                .map(ChannelSidebarDto::id)
                .toList();
        var unread = readStateService.unreadCounts(user, joinedIds);
        var mentions = readStateService.mentionCounts(user, joinedIds);
        if (!unread.isEmpty() || !mentions.isEmpty()) {
            byId.replaceAll((id, dto) -> dto.joined()
                    ? dto.withCounts(unread.getOrDefault(id, 0L), mentions.getOrDefault(id, 0L))
                    : dto);
        }

        var list = new ArrayList<>(byId.values());
        list.sort(Comparator
                .comparing(ChannelSidebarDto::joined).reversed()
                .thenComparing(d -> d.name().toLowerCase()));
        return list;
    }

    /** How many channels each sidebar group shows. */
    static final int GROUP_SIZE = 5;
    /** How far back "most active" looks. Long enough to survive a quiet weekend. */
    static final Duration ACTIVITY_WINDOW = Duration.ofDays(7);

    /**
     * The curated sidebar: the user's largest channels and their most active ones, rather than
     * every channel in the workspace.
     *
     * <p>Ranking is only half the job; the other half is making sure the sidebar never hides
     * something the user needs to act on. Two channels are force-included regardless of rank:
     * one with unread messages (an unread badge the user can't see does nothing), and the channel
     * they're currently reading (which would otherwise vanish from under them the moment they
     * opened it from search). Both displace the weakest entry in the active group rather than
     * growing the list, so the sidebar stays a fixed, scannable size.
     */
    @Transactional(readOnly = true)
    public SidebarView curatedFor(User user, Long activeChannelId) {
        var memberships = memberRepository.findAllByUserFetchingChannel(user);
        if (memberships.isEmpty()) {
            return new SidebarView(List.of(), List.of(), 0);
        }

        var joined = new LinkedHashMap<Long, ChannelSidebarDto>();
        for (var m : memberships) {
            var c = m.getChannel();
            joined.put(c.getId(), ChannelSidebarDto.of(c, true, m.getRole() == ADMIN));
        }
        var joinedIds = List.copyOf(joined.keySet());

        var unread = readStateService.unreadCounts(user, joinedIds);
        var mentions = readStateService.mentionCounts(user, joinedIds);
        joined.replaceAll((id, dto) ->
                dto.withCounts(unread.getOrDefault(id, 0L), mentions.getOrDefault(id, 0L)));

        var memberCounts = toCountMap(memberRepository.memberCountsForChannelsOf(user));
        var recentMessages = toCountMap(
                messageRepository.countRecentByChannel(joinedIds, Instant.now().minus(ACTIVITY_WINDOW)));

        // Largest: most members, ties broken by name so the order is stable between page loads.
        var largest = joined.values().stream()
                .sorted(Comparator
                        .comparingLong((ChannelSidebarDto d) -> memberCounts.getOrDefault(d.id(), 0L)).reversed()
                        .thenComparing(d -> d.name().toLowerCase()))
                .limit(GROUP_SIZE)
                .toList();
        var shown = new LinkedHashSet<Long>();
        largest.forEach(d -> shown.add(d.id()));

        // Most active: the busiest of what's left. Unread counts as traffic, which is what makes
        // a quiet channel that just pinged you climb into view.
        var active = new ArrayList<>(joined.values().stream()
                .filter(d -> !shown.contains(d.id()))
                .sorted(Comparator
                        .comparingLong((ChannelSidebarDto d) ->
                                recentMessages.getOrDefault(d.id(), 0L) + d.unreadCount()).reversed()
                        .thenComparing(d -> d.name().toLowerCase()))
                .limit(GROUP_SIZE)
                .toList());
        active.forEach(d -> shown.add(d.id()));

        // Force-include what the user must not lose sight of, weakest-first displacement.
        for (var dto : joined.values()) {
            boolean needsAttention = dto.unreadCount() > 0 || dto.mentionCount() > 0;
            boolean isBeingRead = dto.id().equals(activeChannelId);
            if ((needsAttention || isBeingRead) && !shown.contains(dto.id())) {
                if (active.size() >= GROUP_SIZE) {
                    shown.remove(active.remove(active.size() - 1).id());
                }
                active.add(dto);
                shown.add(dto.id());
            }
        }
        // Re-sort after promotion so mentions sit above plain unread above quiet channels.
        active.sort(Comparator
                .comparingLong((ChannelSidebarDto d) -> d.mentionCount()).reversed()
                .thenComparing(Comparator.comparingLong((ChannelSidebarDto d) -> d.unreadCount()).reversed())
                .thenComparing(Comparator.comparingLong(
                        (ChannelSidebarDto d) -> recentMessages.getOrDefault(d.id(), 0L)).reversed())
                .thenComparing(d -> d.name().toLowerCase()));

        return new SidebarView(largest, List.copyOf(active), joined.size() - shown.size());
    }

    /**
     * Name/slug search across the channels a user may see, annotated with whether they've joined.
     * This is the other half of the curated sidebar: the shortlist covers what you use daily,
     * search covers everything else.
     */
    @Transactional(readOnly = true)
    public List<ChannelSidebarDto> search(User user, String query, int limit) {
        var trimmed = query == null ? "" : query.trim();
        if (trimmed.length() < 2) {
            // One character matches most of a workspace; make the caller be specific.
            return List.of();
        }
        var capped = Math.min(Math.max(limit, 1), 50);
        var matches = channelRepository.searchVisibleTo(trimmed, user, PUBLIC,
                org.springframework.data.domain.PageRequest.of(0, capped));
        if (matches.isEmpty()) {
            return List.of();
        }
        var membershipByChannelId = memberRepository.findAllByUserFetchingChannel(user).stream()
                .collect(java.util.stream.Collectors.toMap(
                        m -> m.getChannel().getId(), m -> m.getRole() == ADMIN, (a, b) -> a));
        return matches.stream()
                .map(c -> ChannelSidebarDto.of(c, membershipByChannelId.containsKey(c.getId()),
                        Boolean.TRUE.equals(membershipByChannelId.get(c.getId()))))
                .toList();
    }

    /** Collapse {@code (id, count)} rows into a map; both queries return Object[] pairs. */
    private static java.util.Map<Long, Long> toCountMap(List<Object[]> rows) {
        var out = new java.util.HashMap<Long, Long>(rows.size());
        for (var row : rows) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public boolean isAdminForAny(User user) {
        return memberRepository.findAllByUser(user).stream()
                .map(ChannelMember::getRole)
                .anyMatch(r -> r == ADMIN);
    }

    /** Tiny helper used by templates to highlight the active channel. */
    public Channel resolve(Long id) {
        return channelRepository.findById(id).orElse(null);
    }
}
