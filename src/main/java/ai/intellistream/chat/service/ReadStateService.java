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
import ai.intellistream.chat.domain.ChannelRead;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelReadRepository;
import ai.intellistream.chat.repository.MessageMentionRepository;
import ai.intellistream.chat.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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
        // Single-statement upsert (N1): ON CONFLICT DO UPDATE handles both the first read and the
        // concurrent-first-read race atomically. The old saveAndFlush + catch-and-reread could not
        // recover on Postgres — the failed INSERT aborts the transaction, so the re-read threw and
        // the loser still 500'd. markRead fires on every live message, so this race is realistic.
        readRepo.upsertLastReadAt(channel.getId(), user.getId(), Instant.now());
        return readRepo.findByChannelAndUser(channel, user).orElseThrow();
    }

    /**
     * @return channelId -&gt; count of unread top-level messages (excluding the viewer's own).
     *         Channels with zero unread are not in the map.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> unreadCounts(User viewer, Collection<Long> channelIds) {
        if (channelIds.isEmpty()) return Map.of();
        var result = new HashMap<Long, Long>();
        for (var row : messageRepo.countUnreadPerChannel(viewer.getId(), channelIds)) {
            result.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    /**
     * @return channelId -&gt; count of unread mentions of {@code viewer}. Channels with zero
     *         mentions are not in the map.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> mentionCounts(User viewer, Collection<Long> channelIds) {
        if (channelIds.isEmpty()) return Map.of();
        var result = new HashMap<Long, Long>();
        for (var row : mentionRepo.countMentionsPerChannel(viewer.getId(), channelIds)) {
            result.put((Long) row[0], ((Number) row[1]).longValue());
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
