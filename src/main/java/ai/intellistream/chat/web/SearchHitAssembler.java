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
import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.search.MessageIndexService;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.web.dto.SearchHitDto;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@link SearchService.SearchHit}s into {@link SearchHitDto} rows: rendered body, highlighted
 * snippet, and a conversation label where one is needed.
 *
 * <p>Its own class because two surfaces need the identical rows — the JSON the dropdown fetches and
 * the server-rendered results page — and a hit is fiddly enough to assemble (a union with a
 * per-conversation label resolved in one batched query) that two copies would drift. The snippet in
 * particular has a non-obvious rule attached to it, and a second implementation is where that rule
 * gets forgotten.
 */
@Component
class SearchHitAssembler {

    /** How much of a message the highlighted excerpt aims for, in characters. */
    private static final int SNIPPET_LENGTH = 200;

    private final SearchService searchService;
    private final MarkdownRenderer markdown;
    private final MessageIndexService messageIndex;

    SearchHitAssembler(SearchService searchService, MarkdownRenderer markdown,
                       MessageIndexService messageIndex) {
        this.searchService = searchService;
        this.markdown = markdown;
        this.messageIndex = messageIndex;
    }

    List<SearchHitDto> assemble(User viewer, List<SearchService.SearchHit> hits, String rawQuery) {
        if (hits.isEmpty()) return List.of();
        // Modifiers stripped: the highlighter marks what the user searched for, not how they
        // scoped it.
        var query = SearchService.highlightableBody(rawQuery);
        var labels = searchService.conversationLabels(viewer, conversationsIn(hits));
        var filenames = searchService.attachmentFilenames(hits);
        return hits.stream()
                .map(hit -> switch (hit) {
                    case SearchService.SearchHit.ChannelHit c ->
                            SearchHitDto.ofChannel(c.message(), c.joined(),
                                    render(c.message().getBodyMarkdown()),
                                    snippet(query, c.message().getBodyMarkdown()),
                                    matchedFilenames(query, filenames.of(hit)));
                    case SearchService.SearchHit.ConversationHit c ->
                            SearchHitDto.ofConversation(c.message(),
                                    labels.get(c.message().getConversation().getId()),
                                    render(c.message().getBodyMarkdown()),
                                    snippet(query, c.message().getBodyMarkdown()),
                                    matchedFilenames(query, filenames.of(hit)));
                })
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
    private String snippet(String query, String bodyMarkdown) {
        return messageIndex.highlight(query, bodyMarkdown, SNIPPET_LENGTH);
    }

    /**
     * Which of this message's files the query actually matched, each with the matching part marked.
     *
     * <p>Decided by running the highlighter over each name rather than by asking Lucene to explain
     * the hit: the highlighter already answers "did this text match, and where" with the same
     * parsed query the search used, and an {@code explain()} per result is a second scoring pass
     * over every row on the page to learn something one string comparison can tell us.
     *
     * <p>Names that did not match are dropped. A message can carry a dozen files and listing all of
     * them under every hit would bury the one that is the reason the row is there.
     */
    private List<String> matchedFilenames(String query, List<String> filenames) {
        if (filenames.isEmpty() || query.isBlank()) return List.of();
        var matched = new java.util.ArrayList<String>(filenames.size());
        for (var filename : filenames) {
            var highlighted = messageIndex.highlightFilename(query, filename);
            if (highlighted != null) matched.add(highlighted);
        }
        return List.copyOf(matched);
    }
}
