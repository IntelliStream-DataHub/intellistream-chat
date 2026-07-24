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
import ai.intellistream.threadorbit.web.dto.ChannelSidebarDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final ReadStateService readStateService;

    public SidebarService(ChannelRepository channelRepository,
                          ChannelMemberRepository memberRepository,
                          ReadStateService readStateService) {
        this.channelRepository = channelRepository;
        this.memberRepository = memberRepository;
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
