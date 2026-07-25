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
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.ConversationType;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.repository.ChannelMemberRepository;
import ai.intellistream.chat.repository.ConversationMemberRepository;
import ai.intellistream.chat.repository.ConversationMessageRepository;
import ai.intellistream.chat.repository.MessageRepository;
import ai.intellistream.chat.search.MessageIndexService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Search over the embedded Lucene index, across both message stores: channels and conversations
 * (direct messages + group conversations).
 *
 * <p>Authorization rules:
 * <ul>
 *   <li>{@link #searchChannel} — requires read access to the channel (public or member).</li>
 *   <li>{@link #searchConversation} — requires membership of that conversation.</li>
 *   <li>{@link #searchAccessible} — everything the viewer can read: their joined channels plus
 *       the conversations they belong to, ranked as one list.</li>
 *   <li>{@link #searchEverywhere} — every channel and no conversations; admin only (Spring
 *       authority {@code ROLE_ADMIN}).</li>
 * </ul>
 *
 * <h2>Where access control happens</h2>
 * For the scoped searches it happens here, before the query runs. For {@link #searchAccessible}
 * it happens <em>inside</em> the Lucene query: the viewer's channel and conversation ids become a
 * required filter clause, so a document they may not read is never scored, never counted, never
 * ranked and never highlighted. Nothing in this class filters a result list after the fact, and
 * nothing should start to — see {@code MessageIndexService.searchAccessible} for why the
 * distinction is load-bearing rather than stylistic.
 */
@Service
public class SearchService {

    static final int MAX_RESULTS = 100;
    static final String ADMIN_ROLE = "ROLE_ADMIN";
    /** {@code @username} tokens at the start of a token boundary. Underscored / hyphenated /
     *  dotted usernames are honoured (matches {@link ai.intellistream.chat.service.UserService#SAFE_USERNAME}). */
    private static final Pattern AT_USER = Pattern.compile("(?:^|\\s)@([A-Za-z0-9._-]+)");

    private final MessageRepository messageRepository;
    private final ChannelMemberRepository memberRepository;
    private final ChannelService channelService;
    private final MessageIndexService messageIndex;
    private final ConversationMessageRepository conversationMessageRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final ConversationService conversationService;

    public SearchService(MessageRepository messageRepository,
                         ChannelMemberRepository memberRepository,
                         ChannelService channelService,
                         MessageIndexService messageIndex,
                         ConversationMessageRepository conversationMessageRepository,
                         ConversationMemberRepository conversationMemberRepository,
                         ConversationService conversationService) {
        this.messageRepository = messageRepository;
        this.memberRepository = memberRepository;
        this.channelService = channelService;
        this.messageIndex = messageIndex;
        this.conversationMessageRepository = conversationMessageRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.conversationService = conversationService;
    }

    /** One result, from whichever store it came from. */
    public sealed interface SearchHit {
        record ChannelHit(Message message) implements SearchHit {}
        record ConversationHit(ConversationMessage message) implements SearchHit {}
    }

    @Transactional(readOnly = true)
    public List<Message> searchChannel(Channel channel, User viewer, String query, int limit) {
        channelService.requireMember(channel, viewer);
        var p = parsed(query);
        if (p == null) return List.of();
        var hits = messageIndex.searchInChannel(channel.getId(), p.body(), p.authors(), clamp(limit));
        return resolve(hits);
    }

    /**
     * Search one conversation. Membership is required — unlike a PUBLIC channel there is no
     * "anyone may read" tier for a DM or a group conversation, so this is the only check and it
     * is unconditional.
     */
    @Transactional(readOnly = true)
    public List<ConversationMessage> searchConversation(Conversation conversation, User viewer,
                                                        String query, int limit) {
        conversationService.requireMember(conversation, viewer);
        var p = parsed(query);
        if (p == null) return List.of();
        var hits = messageIndex.searchInConversation(
                conversation.getId(), p.body(), p.authors(), clamp(limit));
        return resolveConversation(hits);
    }

    /**
     * Search everything the viewer has access to — joined channels and the conversations they are
     * a member of — as a single relevance-ranked list. This is what the global search box calls.
     *
     * <p>Both id sets are read from the database on every call rather than cached anywhere: they
     * are the ACL, and a search must reflect a membership that changed a second ago.
     */
    @Transactional(readOnly = true)
    public List<SearchHit> searchAccessible(User viewer, String query, int limit) {
        var p = parsed(query);
        if (p == null) return List.of();
        List<Long> channelIds = memberRepository.findChannelsForUser(viewer).stream()
                .map(Channel::getId)
                .toList();
        List<Long> conversationIds = conversationMemberRepository.findConversationIdsForUser(viewer);
        if (channelIds.isEmpty() && conversationIds.isEmpty()) {
            return List.of();
        }
        var hits = messageIndex.searchAccessible(
                channelIds, conversationIds, p.body(), p.authors(), clamp(limit));
        return resolveHits(hits);
    }

    /**
     * Search every message in every channel — including ones the viewer hasn't joined.
     * Only available to platform admins (Keycloak realm role {@code ichat-admin} → Spring authority {@code ROLE_ADMIN}).
     *
     * <p>Direct and group conversations are <b>not</b> included, at any role. The index-level query
     * excludes them structurally.
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
     * The display label for a conversation hit: the group's title, or for a DM the other
     * participant. Resolved for a whole result page in one query.
     *
     * <p>Takes conversations, not ids, and is only ever fed the ones a search already returned to
     * this viewer. It does no membership check of its own and must not become a general lookup —
     * called with arbitrary ids it would happily name the participants of a stranger's DM.
     *
     * @return conversation id → label, absent when nothing better than the id is available
     */
    @Transactional(readOnly = true)
    public Map<Long, String> conversationLabels(User viewer, Collection<Conversation> conversations) {
        if (conversations.isEmpty()) return Map.of();
        var labels = new java.util.HashMap<Long, String>();
        var directIds = new java.util.ArrayList<Long>();
        for (var c : conversations) {
            if (c.getType() == ConversationType.GROUP) {
                if (c.getTitle() != null) labels.put(c.getId(), c.getTitle());
            } else {
                directIds.add(c.getId());
            }
        }
        if (!directIds.isEmpty()) {
            for (var row : conversationMemberRepository.findCounterparts(directIds, viewer.getId())) {
                var convId = (Long) row[0];
                var username = (String) row[1];
                var displayName = (String) row[2];
                labels.putIfAbsent(convId, displayName == null || displayName.isBlank()
                        ? username : displayName);
            }
        }
        return labels;
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

    private List<ConversationMessage> resolveConversation(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ConversationMessage> byId =
                conversationMessageRepository.findAllByIdWithAuthor(orderedIds).stream()
                        .collect(Collectors.toMap(ConversationMessage::getId, Function.identity()));
        return orderedIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Hydrate a mixed result page: two batched lookups, then re-interleave in Lucene's order.
     *
     * <p>This is hydration only. It must never be given the job of deciding what the viewer may
     * see — by the time ids get here the filtering has already happened, in the query.
     */
    private List<SearchHit> resolveHits(List<MessageIndexService.Hit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        var channelIds = hits.stream()
                .filter(h -> h.scope() == MessageIndexService.Scope.CHANNEL)
                .map(MessageIndexService.Hit::id)
                .toList();
        var conversationIds = hits.stream()
                .filter(h -> h.scope() == MessageIndexService.Scope.CONVERSATION)
                .map(MessageIndexService.Hit::id)
                .toList();
        Map<Long, Message> channelRows = channelIds.isEmpty() ? Map.of()
                : messageRepository.findAllByIdWithAuthor(channelIds).stream()
                        .collect(Collectors.toMap(Message::getId, Function.identity()));
        Map<Long, ConversationMessage> conversationRows = conversationIds.isEmpty() ? Map.of()
                : conversationMessageRepository.findAllByIdWithAuthor(conversationIds).stream()
                        .collect(Collectors.toMap(ConversationMessage::getId, Function.identity()));
        var out = new java.util.ArrayList<SearchHit>(hits.size());
        for (var hit : hits) {
            if (hit.scope() == MessageIndexService.Scope.CHANNEL) {
                var row = channelRows.get(hit.id());
                if (row != null) out.add(new SearchHit.ChannelHit(row));
            } else {
                var row = conversationRows.get(hit.id());
                if (row != null) out.add(new SearchHit.ConversationHit(row));
            }
        }
        return List.copyOf(out);
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
