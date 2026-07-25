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

import ai.intellistream.chat.domain.Message;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.web.dto.MessageDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchRestController {

    private final SearchService searchService;
    private final ChannelService channelService;
    private final CurrentUser currentUser;
    private final MarkdownRenderer markdown;
    private final MessageIndexService messageIndex;
    private final RateLimiter rateLimiter;

    public SearchRestController(SearchService searchService,
                                ChannelService channelService,
                                CurrentUser currentUser,
                                MarkdownRenderer markdown,
                                MessageIndexService messageIndex,
                                RateLimiter rateLimiter) {
        this.searchService = searchService;
        this.channelService = channelService;
        this.currentUser = currentUser;
        this.markdown = markdown;
        this.messageIndex = messageIndex;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<MessageDto> search(@RequestParam("q") String q,
                                   @RequestParam(value = "channelId", required = false) Long channelId,
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
        List<Message> rows;
        if (channelId != null) {
            rows = searchService.searchChannel(channelService.requireById(channelId), me, q, limit);
        } else if ("all".equalsIgnoreCase(scope)) {
            // Admin-only: searches every channel, including ones the viewer hasn't joined.
            rows = searchService.searchEverywhere(me, q, limit);
        } else {
            rows = searchService.searchAllJoined(me, q, limit);
        }
        return rows.stream()
                .map(m -> {
                    var html = markdown.render(m.getBodyMarkdown());
                    // Snippet is computed against the raw Markdown body (the same text the
                    // index sees), so the highlighter agrees with the search query's tokens.
                    var snippet = messageIndex.highlight(q, m.getBodyMarkdown(), 200);
                    return MessageDto.fromSearchHit(m, html, snippet);
                })
                .toList();
    }
}
