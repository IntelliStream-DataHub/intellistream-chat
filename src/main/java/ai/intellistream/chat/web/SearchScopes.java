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
import ai.intellistream.chat.service.SearchService.ScopeKind;

/**
 * The wire spelling of a search scope, and how a request with no explicit one is read.
 *
 * <p>One place for both, because the JSON endpoint and the results page must agree: a URL copied
 * out of the address bar and a URL the dropdown builds have to mean the same search, or the "see
 * all results" link lands on a different result set than the dropdown it came from.
 */
final class SearchScopes {

    /** Everything the viewer can read. The default, and the value the page's control submits. */
    static final String ACCESSIBLE = "accessible";
    /** One channel. */
    static final String CHANNEL = "channel";
    /** One direct/group conversation. */
    static final String CONVERSATION = "conversation";
    /** Every channel, private ones included. Admin only. Spelled {@code all} since before the
     *  results page existed; kept so old links and the REST contract still work. */
    static final String EVERYWHERE = "all";

    private SearchScopes() {}

    /**
     * Read the requested scope, defaulting to whatever the viewer was looking at.
     *
     * <p>The default is the point: someone who searches while reading #general almost always means
     * "in here", and making them pick a scope they had already expressed by being on the page is
     * the kind of small friction that stops people using search at all. An explicit {@code scope}
     * always wins, so widening is one click and never a guess.
     */
    static ScopeKind resolve(String requested, Channel channel, Conversation conversation) {
        if (requested != null && !requested.isBlank()) {
            return switch (requested.toLowerCase(java.util.Locale.ROOT)) {
                case CHANNEL -> channel == null ? ScopeKind.ACCESSIBLE : ScopeKind.CHANNEL;
                case CONVERSATION -> conversation == null ? ScopeKind.ACCESSIBLE : ScopeKind.CONVERSATION;
                case EVERYWHERE -> ScopeKind.EVERYWHERE;
                default -> ScopeKind.ACCESSIBLE;
            };
        }
        if (channel != null) return ScopeKind.CHANNEL;
        if (conversation != null) return ScopeKind.CONVERSATION;
        return ScopeKind.ACCESSIBLE;
    }

    /** The wire value for a resolved scope, so the page can echo it back into its own form. */
    static String wireName(ScopeKind kind) {
        return switch (kind) {
            case CHANNEL -> CHANNEL;
            case CONVERSATION -> CONVERSATION;
            case EVERYWHERE -> EVERYWHERE;
            case ACCESSIBLE -> ACCESSIBLE;
        };
    }
}
