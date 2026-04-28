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
import ai.intellistream.radiance.domain.Message;
import ai.intellistream.radiance.domain.User;
import ai.intellistream.radiance.repository.ChannelMemberRepository;
import ai.intellistream.radiance.repository.MessageRepository;
import ai.intellistream.radiance.search.MessageIndexService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Channel- and user-scoped search over the embedded Lucene index.
 *
 * <p>Authorization rules:
 * <ul>
 *   <li>{@link #searchChannel} — requires read access to the channel (public or member).</li>
 *   <li>{@link #searchAllJoined} — limited to the viewer's joined channels.</li>
 *   <li>{@link #searchEverywhere} — every channel; admin only (Spring authority {@code ROLE_ADMIN}).</li>
 * </ul>
 */
@Service
public class SearchService {

    static final int MAX_RESULTS = 100;
    static final String ADMIN_ROLE = "ROLE_ADMIN";
    /** {@code @username} tokens at the start of a token boundary. Underscored / hyphenated /
     *  dotted usernames are honoured (matches {@link ai.intellistream.radiance.service.UserService#SAFE_USERNAME}). */
    private static final Pattern AT_USER = Pattern.compile("(?:^|\\s)@([A-Za-z0-9._-]+)");

    private final MessageRepository messageRepository;
    private final ChannelMemberRepository memberRepository;
    private final ChannelService channelService;
    private final MessageIndexService messageIndex;

    public SearchService(MessageRepository messageRepository,
                         ChannelMemberRepository memberRepository,
                         ChannelService channelService,
                         MessageIndexService messageIndex) {
        this.messageRepository = messageRepository;
        this.memberRepository = memberRepository;
        this.channelService = channelService;
        this.messageIndex = messageIndex;
    }

    @Transactional(readOnly = true)
    public List<Message> searchChannel(Channel channel, User viewer, String query, int limit) {
        channelService.requireMember(channel, viewer);
        var p = parsed(query);
        if (p == null) return List.of();
        var hits = messageIndex.searchInChannel(channel.getId(), p.body(), p.authors(), clamp(limit));
        return resolve(hits);
    }

    @Transactional(readOnly = true)
    public List<Message> searchAllJoined(User viewer, String query, int limit) {
        var p = parsed(query);
        if (p == null) return List.of();
        List<Long> channelIds = memberRepository.findChannelsForUser(viewer).stream()
                .map(Channel::getId)
                .toList();
        if (channelIds.isEmpty()) {
            return List.of();
        }
        var hits = messageIndex.searchInChannels(channelIds, p.body(), p.authors(), clamp(limit));
        return resolve(hits);
    }

    /**
     * Search every message in every channel — including ones the viewer hasn't joined.
     * Only available to platform admins (Keycloak realm role {@code chat-admin} → Spring authority {@code ROLE_ADMIN}).
     */
    @Transactional(readOnly = true)
    public List<Message> searchEverywhere(User viewer, String query, int limit) {
        if (!isPlatformAdmin()) {
            throw new AccessDeniedException("Admin role required for cross-channel search.");
        }
        var p = parsed(query);
        if (p == null) return List.of();
        var hits = messageIndex.searchEverywhere(p.body(), p.authors(), clamp(limit));
        return resolve(hits);
    }

    /**
     * Resolve Lucene hit IDs to {@link Message} entities while preserving Lucene's relevance order.
     * Drops any IDs the DB no longer has (defensive — should be rare).
     */
    private List<Message> resolve(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        // Join-fetch the author so MessageDto.from(...) in the controller doesn't trip
        // a LazyInitializationException once this @Transactional scope closes
        // (open-in-view is off).
        Map<Long, Message> byId = messageRepository.findAllByIdWithAuthor(orderedIds).stream()
                .collect(Collectors.toMap(Message::getId, Function.identity()));
        return orderedIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Body + author tokens parsed out of one user-supplied query. */
    record Parsed(String body, Set<String> authors) {}

    /**
     * Pull {@code @username} filter tokens out of the raw query and return what remains as the
     * body fuzzy-match. Returns {@code null} when the user supplied neither a body keyword
     * (≥2 chars) nor an author filter — caller can short-circuit with empty results.
     */
    static Parsed parsed(String raw) {
        if (raw == null) return null;
        var matcher = AT_USER.matcher(raw);
        Set<String> authors = new LinkedHashSet<>();
        while (matcher.find()) {
            authors.add(matcher.group(1).toLowerCase());
        }
        var body = matcher.replaceAll(" ").trim();
        var bodyOrNull = body.length() < 2 ? null : body;
        if (bodyOrNull == null && authors.isEmpty()) return null;
        return new Parsed(bodyOrNull == null ? "" : bodyOrNull, authors);
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_RESULTS);
    }

    private static boolean isPlatformAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_ROLE.equals(a.getAuthority()));
    }
}
