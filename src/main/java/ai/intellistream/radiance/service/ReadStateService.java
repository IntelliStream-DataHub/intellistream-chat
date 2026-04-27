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

package ai.intellistream.radiance.service;

import ai.intellistream.radiance.domain.Channel;
import ai.intellistream.radiance.domain.ChannelRead;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.ChannelReadRepository;
import ai.intellistream.radiance.repository.MessageMentionRepository;
import ai.intellistream.radiance.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks per-user read markers per channel and answers unread / mention counts.
 */
@Service
public class ReadStateService {

    private final ChannelReadRepository readRepo;
    private final MessageRepository messageRepo;
    private final MessageMentionRepository mentionRepo;

    public ReadStateService(ChannelReadRepository readRepo,
                            MessageRepository messageRepo,
                            MessageMentionRepository mentionRepo) {
        this.readRepo = readRepo;
        this.messageRepo = messageRepo;
        this.mentionRepo = mentionRepo;
    }

    /** Mark all current messages in {@code channel} as read for {@code user}. */
    @Transactional
    public ChannelRead markRead(Channel channel, User user) {
        var existing = readRepo.findByChannelAndUser(channel, user);
        if (existing.isPresent()) {
            var row = existing.get();
            row.setLastReadAt(Instant.now());
            return row;
        }
        return readRepo.save(new ChannelRead(channel, user, Instant.now()));
    }

    /**
     * @return channelId -&gt; count of unread top-level messages (excluding the viewer's own).
     *         Channels with zero unread are not in the map.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> unreadCounts(User viewer, Collection<UUID> channelIds) {
        if (channelIds.isEmpty()) return Map.of();
        var result = new HashMap<UUID, Long>();
        for (var row : messageRepo.countUnreadPerChannel(viewer.getId(), channelIds)) {
            result.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    /**
     * @return channelId -&gt; count of unread mentions of {@code viewer}. Channels with zero
     *         mentions are not in the map.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> mentionCounts(User viewer, Collection<UUID> channelIds) {
        if (channelIds.isEmpty()) return Map.of();
        var result = new HashMap<UUID, Long>();
        for (var row : mentionRepo.countMentionsPerChannel(viewer.getId(), channelIds)) {
            result.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    /**
     * Advance {@code last_read_at} to now for every channel where the viewer has at least
     * one unread mention — clears the inbox in a single round-trip. Returns the number of
     * channel_reads rows touched.
     */
    @Transactional
    public int markAllMentionedChannelsRead(User viewer) {
        return readRepo.markAllChannelsWithUnreadMentionsRead(viewer.getId(), Instant.now());
    }
}
