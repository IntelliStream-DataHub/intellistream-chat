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

package ai.intellistream.chat.web;

import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationMessage;
import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.web.dto.SearchHitDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/search}. Four modes, picked by the request parameters:
 *
 * <ul>
 *   <li>{@code ?channelId=} — that one channel (public, or one the viewer is in).</li>
 *   <li>{@code ?conversationId=} — that one DM / group conversation; members only.</li>
 *   <li>{@code ?scope=all} — every channel including private ones the admin never joined, and no
 *       conversations; admins only.</li>
 *   <li>neither — everything the viewer can read: every public channel ∪ the private ones they
 *       joined ∪ their conversations, ranked as one list. This is the global search box.</li>
 * </ul>
 *
 * Access control lives in {@link SearchService} / the Lucene query, not here.
 */
@RestController
@RequestMapping("/api/search")
public class SearchRestController {

    private final SearchService searchService;
    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final CurrentUser currentUser;
    private final MarkdownRenderer markdown;
    private final MessageIndexService messageIndex;
    private final RateLimiter rateLimiter;

    public SearchRestController(SearchService searchService,
                                ChannelService channelService,
                                ConversationService conversationService,
                                CurrentUser currentUser,
                                MarkdownRenderer markdown,
                                MessageIndexService messageIndex,
                                RateLimiter rateLimiter) {
        this.searchService = searchService;
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.currentUser = currentUser;
        this.markdown = markdown;
        this.messageIndex = messageIndex;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<SearchHitDto> search(@RequestParam("q") String q,
                                     @RequestParam(value = "channelId", required = false) Long channelId,
                                     @RequestParam(value = "conversationId", required = false) Long conversationId,
                                     @RequestParam(value = "scope", required = false) String scope,
                                     @RequestParam(defaultValue = "50") int limit,
                                     Principal principal) {
        var me = currentUser.resolve(principal);
        // 30 searches/min/user. Lucene fuzzy queries on long terms are nontrivial CPU even
        // after the parser-level guards (no wildcards, edits capped at 2); a tight loop of
        // expensive queries used to be a free DoS vector, this caps the burst.
        if (!rateLimiter.tryAcquire(me.getUsername(), "search", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("search rate exceeded");
        }
        if (channelId != null) {
            var channel = channelService.requireById(channelId);
            return channelHits(searchService.searchChannel(channel, me, q, limit),
                    channelService.isMember(channel, me), q);
        }
        if (conversationId != null) {
            var conversation = conversationService.requireById(conversationId);
            return conversationHits(
                    searchService.searchConversation(conversation, me, q, limit), me, q);
        }
        if ("all".equalsIgnoreCase(scope)) {
            // Admin-only: every channel, including ones the viewer hasn't joined. Never DMs.
            // Membership is per channel here and this scope spans all of them, so the rows carry
            // the viewer's joined set rather than one flag for the page.
            var joined = searchService.joinedChannelIds(me);
            return searchService.searchEverywhere(me, q, limit).stream()
                    .map(m -> SearchHitDto.ofChannel(m, joined.contains(m.getChannel().getId()),
                            render(m.getBodyMarkdown()), snippet(q, m.getBodyMarkdown())))
                    .toList();
        }
        var hits = searchService.searchAccessible(me, q, limit);
        var labels = searchService.conversationLabels(me, conversationsIn(hits));
        return hits.stream()
                .map(hit -> switch (hit) {
                    case SearchService.SearchHit.ChannelHit c ->
                            SearchHitDto.ofChannel(c.message(), c.joined(),
                                    render(c.message().getBodyMarkdown()),
                                    snippet(q, c.message().getBodyMarkdown()));
                    case SearchService.SearchHit.ConversationHit c ->
                            SearchHitDto.ofConversation(c.message(),
                                    labels.get(c.message().getConversation().getId()),
                                    render(c.message().getBodyMarkdown()),
                                    snippet(q, c.message().getBodyMarkdown()));
                })
                .toList();
    }

    private List<SearchHitDto> channelHits(List<Message> rows, boolean joined, String q) {
        return rows.stream()
                .map(m -> SearchHitDto.ofChannel(m, joined, render(m.getBodyMarkdown()),
                        snippet(q, m.getBodyMarkdown())))
                .toList();
    }

    private List<SearchHitDto> conversationHits(List<ConversationMessage> rows,
                                                ai.intellistream.chat.domain.User me, String q) {
        var labels = searchService.conversationLabels(me, rows.stream()
                .map(ConversationMessage::getConversation)
                .collect(java.util.stream.Collectors.toMap(Conversation::getId, c -> c, (a, b) -> a,
                        LinkedHashMap::new))
                .values());
        return rows.stream()
                .map(m -> SearchHitDto.ofConversation(m, labels.get(m.getConversation().getId()),
                        render(m.getBodyMarkdown()), snippet(q, m.getBodyMarkdown())))
                .toList();
    }

    /** Distinct conversations on this result page, so their labels resolve in one query. */
    private static java.util.Collection<Conversation> conversationsIn(List<SearchService.SearchHit> hits) {
        Map<Long, Conversation> byId = new LinkedHashMap<>();
        for (var hit : hits) {
            if (hit instanceof SearchService.SearchHit.ConversationHit c) {
                byId.putIfAbsent(c.message().getConversation().getId(), c.message().getConversation());
            }
        }
        return byId.values();
    }

    private String render(String bodyMarkdown) {
        return markdown.render(bodyMarkdown);
    }

    /** Snippet is computed against the raw Markdown body (the same text the index sees), so the
     *  highlighter agrees with the search query's tokens. */
    private String snippet(String q, String bodyMarkdown) {
        return messageIndex.highlight(q, bodyMarkdown, 200);
    }
}
