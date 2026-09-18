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

package ai.intellistream.chat.service;

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.ChannelMember;
import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ChannelRepository;
import ai.intellistream.chat.web.dto.ChannelBrowseDto;
import ai.intellistream.chat.web.dto.ChannelSidebarDto;
import ai.intellistream.chat.web.dto.SidebarView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ai.intellistream.chat.domain.ChannelRole.ADMIN;
import static ai.intellistream.chat.domain.ChannelType.PUBLIC;

/**
 * Builds the left-sidebar channel list: every channel the viewer is a member of, and — separately,
 * through {@link #search} and {@link #browse} — the channels they could join but haven't.
 */
@Service
public class SidebarService {

    /** How many channels the first-login <em>Suggested for you</em> group offers. */
    public static final int SUGGESTION_COUNT = 5;

    /**
     * How many rows the Browse channels directory returns. A page, not a sidebar, so it can be
     * generous; a workspace with more live public channels than this has a search box for the tail.
     */
    public static final int BROWSE_LIMIT = 200;

    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository memberRepository;
    private final ReadStateService readStateService;

    public SidebarService(ChannelRepository channelRepository,
                          ChannelMemberRepository memberRepository,
                          ReadStateService readStateService) {
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
        this.readStateService = readStateService;
    }

    /**
     * Every channel {@code user} belongs to, alphabetically, with unread and mention counts.
     *
     * <p>No ranking, no cap, nothing hidden. See {@link SidebarView} for why the previous curated
     * shortlist was a mistake; the short version is that a sidebar is spatial memory, and a list
     * that reorders itself when someone else joins a channel destroys it. The column scrolls, which
     * is what makes "all of them" work at sixty channels as well as at six.
     *
     * <p>There is deliberately no {@code activeChannelId} parameter any more. The old signature
     * took one so the channel being read could be force-included against the cap — it would
     * otherwise vanish from under the user the moment they opened it from search. With nothing
     * falling off the list there is nothing to force-include, and the template highlights the
     * active row from the model attribute it already has.
     */
    @Transactional(readOnly = true)
    public SidebarView joinedFor(User user) {
        // The account-wide notification default rides along on every sidebar render: each row
        // carries its raw per-channel level, and DEFAULT only means something next to this.
        var notifyDefault = accountDefaultOf(user);
        // Live channels only — archived ones keep their membership row (unarchiving restores the
        // sidebar entry with its star, notification level and read marker intact) but are out of the
        // list, which is most of what archiving is for. Filtered in the query rather than here so the
        // unread and mention counts below are not computed for rows nothing renders.
        var memberships = memberRepository.findLiveByUserFetchingChannel(user); // avoid channel N+1 (N28)
        if (memberships.isEmpty()) {
            // Nothing to list, so the column shows where everybody else is instead. Only here:
            // once there is a list, it is alphabetical and nothing ranks anything (see SidebarView).
            // The viewer belongs to nothing, so there is no membership to subtract from the result.
            return new SidebarView(List.of(), largestPublic(Set.of(), SUGGESTION_COUNT),
                    notifyDefault, notifyDmDefault(user));
        }

        var rows = new ArrayList<ChannelSidebarDto>(memberships.size());
        var ids = new ArrayList<Long>(memberships.size());
        for (var m : memberships) {
            var c = m.getChannel();
            rows.add(ChannelSidebarDto.joined(c, m));
            ids.add(c.getId());
        }

        var unread = readStateService.unreadCounts(user, ids);
        var mentions = readStateService.mentionCounts(user, ids);
        rows.replaceAll(d -> d.withCounts(
                unread.getOrDefault(d.id(), 0L), mentions.getOrDefault(d.id(), 0L)));
        rows.sort(ChannelSidebarDto.BY_NAME);
        return new SidebarView(List.copyOf(rows), List.of(), notifyDefault, notifyDmDefault(user));
    }

    /**
     * The channel directory: every live public channel, most populated first, each marked with
     * whether {@code user} is already in it. Private channels are not listed even for their own
     * members — they are in the sidebar already, and a directory is for finding what you are not in.
     */
    @Transactional(readOnly = true)
    public List<ChannelBrowseDto> browse(User user) {
        var joined = new HashSet<Long>();
        for (var m : memberRepository.findAllByUser(user)) {
            joined.add(m.getChannel().getId());
        }
        return largestPublic(joined, BROWSE_LIMIT);
    }

    private List<ChannelBrowseDto> largestPublic(Set<Long> joinedIds, int limit) {
        var rows = channelRepository.findLargestPublic(PUBLIC, PageRequest.of(0, limit));
        var out = new ArrayList<ChannelBrowseDto>(rows.size());
        for (var row : rows) {
            var channel = (Channel) row[0];
            var members = ((Number) row[1]).longValue();
            out.add(ChannelBrowseDto.of(channel, members, joinedIds.contains(channel.getId())));
        }
        return List.copyOf(out);
    }

    /** The viewer's account-wide conversation default, tolerating a row written before V13. */
    private static NotificationLevel notifyDmDefault(User user) {
        var stored = user.getNotifyDmDefault();
        return stored == null ? NotificationLevel.ALL : stored;
    }

    /** The viewer's account-wide notification default, tolerating a row written before V7. */
    private static NotificationLevel accountDefaultOf(User user) {
        var stored = user.getNotifyDefault();
        return stored == null ? NotificationLevel.ACCOUNT_FALLBACK : stored;
    }

    /**
     * Name/slug search across the channels a user may see, annotated with whether they've joined.
     *
     * <p>This is the half of channel navigation the sidebar cannot do. The sidebar lists what you
     * are in; this finds what you are not in yet, which is why its results render in the main
     * content area — there is room there for a description and a Join button, and none in a 260px
     * column.
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
                        m -> m.getChannel().getId(), m -> m, (a, b) -> a));
        return matches.stream()
                .map(c -> {
                    var membership = membershipByChannelId.get(c.getId());
                    return membership == null
                            ? ChannelSidebarDto.notJoined(c)
                            : ChannelSidebarDto.joined(c, membership);
                })
                .toList();
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
