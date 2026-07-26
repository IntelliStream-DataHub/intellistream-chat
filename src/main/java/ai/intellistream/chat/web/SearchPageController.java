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

import ai.intellistream.chat.domain.Channel;
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import ai.intellistream.chat.service.ChannelService;
import ai.intellistream.chat.service.ConversationService;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.service.SearchService.ResultPage;
import ai.intellistream.chat.service.SearchService.ScopeKind;
import ai.intellistream.chat.web.dto.SearchHitDto;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.time.Duration;
import java.util.List;

/**
 * {@code GET /search} — the reviewable results page.
 *
 * <h2>A page, not a panel</h2>
 * Slack puts results beside the conversation and Mattermost in a right-hand panel, and a panel is
 * the better answer to "I lost the channel I was reading". Both were considered and both lose here,
 * for reasons specific to this application rather than out of preference:
 *
 * <ul>
 *   <li><b>The rendering has to stay in Thymeleaf.</b> A panel is opened without a navigation, so
 *       its contents are either built in JavaScript — which puts a second, divergent renderer for
 *       search results in the codebase — or fetched as an HTML fragment and injected, a pattern
 *       that exists nowhere else here and that every future contributor would have to learn from
 *       this one instance. Neither is worth a panel.</li>
 *   <li><b>Results are a destination now.</b> The thing being fixed is that there was nowhere to
 *       <em>review</em> a result set: no count, no second page, no way to change scope. A count, a
 *       pager and a scope control fit a page; in a 320px panel they crowd out the snippets, which
 *       are the part you actually read.</li>
 *   <li><b>The dropdown already covers the panel's job.</b> "Jump straight to the thing I
 *       remember" is answered in place, without leaving the channel, by the live dropdown — which
 *       is what a panel would mostly be used for.</li>
 * </ul>
 *
 * <p>What a page owes in exchange is a cheap way back, and that is the whole of {@code channelId} /
 * {@code conversationId} being carried through every link and form on it: they say where the search
 * started, independently of what it currently searches, so the "Back to #general" link survives
 * changing the scope and paging forward. They are ids rather than a return URL on purpose — a
 * {@code from=} parameter echoed into an {@code href} is an open redirect, and there is no version
 * of that worth the convenience.
 */
@Controller
public class SearchPageController {

    private final SearchService searchService;
    private final ChannelService channelService;
    private final ConversationService conversationService;
    private final SearchHitAssembler assembler;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public SearchPageController(SearchService searchService,
                                ChannelService channelService,
                                ConversationService conversationService,
                                SearchHitAssembler assembler,
                                CurrentUser currentUser,
                                RateLimiter rateLimiter) {
        this.searchService = searchService;
        this.channelService = channelService;
        this.conversationService = conversationService;
        this.assembler = assembler;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/search")
    public String search(@RequestParam(value = "q", required = false) String q,
                         @RequestParam(value = "scope", required = false) String scope,
                         @RequestParam(value = "channelId", required = false) Long channelId,
                         @RequestParam(value = "conversationId", required = false) Long conversationId,
                         @RequestParam(value = "page", defaultValue = "0") int page,
                         Principal principal, Model model) {
        var me = currentUser.resolve(principal);
        // A separate budget from the dropdown's. The dropdown fires on keystrokes and 30/min is
        // already tight for it; a page load that comes back 429 is a broken page rather than a
        // missing suggestion list, and paging through results is a handful of requests a minute.
        if (!rateLimiter.tryAcquire(me.getUsername(), "search-page", 60, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("search rate exceeded");
        }

        Channel channel = channelId == null ? null : readableChannelOrNull(channelId, me);
        Conversation conversation = conversationId == null ? null : readableConversationOrNull(conversationId, me);
        var kind = SearchScopes.resolve(scope, channel, conversation);

        model.addAttribute("me", me);
        // Two names for the same string on purpose: the shared top-bar fragment reads
        // `searchQuery` (it is absent on every other page, which is what makes the box empty
        // there), and this page's own form reads `query`.
        model.addAttribute("query", q == null ? "" : q);
        model.addAttribute("searchQuery", q == null ? "" : q);
        // What the top-bar box searches, which is the scope this page is currently showing — not
        // where the search started. The two differ once the viewer widens the scope, and the box
        // has to describe itself honestly: its placeholder is built from these. The way-back link
        // uses originChannel/originConversation below and is unaffected.
        model.addAttribute("activeChannel", kind == ScopeKind.CHANNEL ? channel : null);
        model.addAttribute("activeChannelId",
                kind == ScopeKind.CHANNEL && channel != null ? channel.getId() : null);
        model.addAttribute("activeConversationId",
                kind == ScopeKind.CONVERSATION && conversation != null ? conversation.getId() : null);
        model.addAttribute("scope", SearchScopes.wireName(kind));
        model.addAttribute("originChannel", channel);
        model.addAttribute("originConversation", conversation);
        model.addAttribute("canSearchEverywhere", isPlatformAdmin());

        var trimmed = q == null ? "" : q.trim();
        if (trimmed.isEmpty()) {
            // No query at all is not an error — it is how the page looks before you have typed
            // anything, and the empty state is where the syntax is documented.
            return render(model, ResultPage.empty(0, SearchService.DEFAULT_PAGE_SIZE), List.of(), null);
        }
        try {
            var results = searchService.searchPage(me, trimmed, kind, channel, conversation,
                    page, SearchService.DEFAULT_PAGE_SIZE);
            return render(model, results, assembler.assemble(me, results.hits(), trimmed), null);
        } catch (PublicBadRequestException e) {
            // An in: that named a channel the viewer cannot read. The message is written for them,
            // so it goes on the page instead of becoming a 400 they cannot act on.
            return render(model, ResultPage.empty(0, SearchService.DEFAULT_PAGE_SIZE), List.of(),
                    e.getMessage());
        } catch (AccessDeniedException e) {
            // Hand-edited ?scope=all without the admin role. Same treatment: the page still works,
            // it just tells them which scopes are theirs.
            return render(model, ResultPage.empty(0, SearchService.DEFAULT_PAGE_SIZE), List.of(),
                    "That scope is only available to workspace admins.");
        }
    }

    private String render(Model model, ResultPage results, List<SearchHitDto> hits, String error) {
        model.addAttribute("results", results);
        model.addAttribute("hits", hits);
        model.addAttribute("error", error);
        return "search";
    }

    /**
     * The channel the search started from, or null if it is no longer readable.
     *
     * <p>Null rather than an error: this parameter only decides which back link and which scope
     * option to draw. A channel that has been deleted, or made private since the link was made,
     * should cost the viewer a back link — not a 403 on a search that would otherwise have worked.
     */
    private Channel readableChannelOrNull(Long id, ai.intellistream.chat.domain.User me) {
        try {
            var channel = channelService.requireById(id);
            channelService.requireMember(channel, me);
            return channel;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Conversation readableConversationOrNull(Long id, ai.intellistream.chat.domain.User me) {
        try {
            var conversation = conversationService.requireById(id);
            conversationService.requireMember(conversation, me);
            return conversation;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isPlatformAdmin() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
