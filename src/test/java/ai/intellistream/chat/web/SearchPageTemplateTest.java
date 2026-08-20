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

import ai.intellistream.chat.domain.User;
import ai.intellistream.chat.service.SearchService;
import ai.intellistream.chat.web.dto.SearchHitDto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders {@code search.html} for real.
 *
 * <p>A template is the one part of a Spring MVC feature with no compiler behind it: a mistyped
 * expression, a fragment that moved, an accessor that stopped existing on a record are all green
 * builds and a 500 the first time somebody opens the page. The integration tests cannot reach it —
 * {@code IntegrationTestApplication} deliberately does not scan {@code web}, so there is no view
 * resolver in any of them — so the engine is stood up here instead, against the real template files
 * on the classpath.
 *
 * <p>The assertions that follow the render are about the contract two other things depend on: the
 * hidden origin fields that make the way-back link work, and the ids and data attributes
 * {@code chat/search-page.js} queries for. Both are the kind of thing a rename breaks silently.
 */
class SearchPageTemplateTest {

    private static SpringTemplateEngine engine;
    private static GenericApplicationContext applicationContext;

    @BeforeAll
    static void engine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        applicationContext = new GenericApplicationContext();
        // fragments/assets resolves its <script>/<link> URLs through @assetService. A stub keeps
        // this test about search.html rather than about the asset pipeline, which has its own.
        applicationContext.getBeanFactory().registerSingleton("assetService", new StubAssets());
        applicationContext.refresh();

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    /** Stands in for {@code AssetService}; only the one method the fragment calls is needed. */
    public static final class StubAssets {
        public List<String> urls(String bundle) {
            return List.of("/js/" + bundle + ".bundle.min.js");
        }
    }

    private static User viewer() {
        var user = new User("kc-alice", "alice", "alice@example.com", "Alice A");
        return user;
    }

    private static SearchHitDto channelHit(long id, String channelName, boolean joined) {
        return channelHit(id, channelName, joined, List.of());
    }

    private static SearchHitDto channelHit(long id, String channelName, boolean joined,
                                           List<String> matchedFilenames) {
        return new SearchHitDto(id, "channel", 7L, channelName, joined,
                null, null, null,
                "/channels/7?m=" + id + "#m=" + id,
                "bob", "Bob B", false, 0L,
                "the deploy failed again",
                "<p>the deploy failed again</p>",
                "the <mark>deploy</mark> failed again",
                matchedFilenames,
                Instant.parse("2026-03-04T10:15:30Z"), null);
    }

    private String render(Map<String, Object> model) {
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        var response = new MockHttpServletResponse();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(request, response);

        var variables = new HashMap<>(model);
        // Normally set by Spring's ThymeleafView; the Spring dialect's SpEL evaluation needs it.
        variables.put(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));
        variables.putIfAbsent("appTitle", "IntelliStream Chat");
        variables.putIfAbsent("appFaviconUrl", "/favicon.ico");
        variables.putIfAbsent("me", viewer());
        variables.putIfAbsent("originChannel", null);
        variables.putIfAbsent("originConversation", null);
        variables.putIfAbsent("activeChannelId", null);
        variables.putIfAbsent("activeConversationId", null);
        variables.putIfAbsent("canSearchEverywhere", false);
        variables.putIfAbsent("error", null);
        // Every page carries the viewer's resolved timestamp conventions (fragments/time-prefs),
        // so a template test that omits them fails on the head rather than on what it is testing.
        variables.putIfAbsent("fmt", new ai.intellistream.chat.i18n.TimeFormats("UTC")
                .forUser(null, Locale.ENGLISH));
        variables.putIfAbsent("askForZone", false);

        return engine.process("search", new WebContext(exchange, Locale.ENGLISH, variables));
    }

    @Test
    void theEmptyStateRendersAndCarriesTheSyntaxHelp() {
        var html = render(Map.of(
                "query", "", "searchQuery", "", "scope", "accessible",
                "results", SearchService.ResultPage.empty(0, 20),
                "hits", List.of()));

        // The syntax is documented nowhere else in the product, so this is load-bearing content
        // rather than decoration.
        assertThat(html).contains("from:someone");
        assertThat(html).contains("@someone");
        assertThat(html).contains("in:#channel");
        assertThat(html).contains("&quot;deploy failed&quot;");
        // The out-of-scope modifiers are named, so nobody has to discover by experiment that they
        // are searched for as words.
        assertThat(html).contains("<code>before:</code>");
        // No results and no query means no count line claiming zero of something.
        assertThat(html).doesNotContain("class=\"search-count\"");
    }

    @Test
    void aResultPageRendersCountsRowsAndTheHighlightedSnippet() {
        var page = new SearchService.ResultPage(List.of(), 240L, false, 0, 20);
        var html = render(Map.of(
                "query", "deploy", "searchQuery", "deploy", "scope", "accessible",
                "results", page,
                "hits", List.of(channelHit(42L, "general", true))));

        assertThat(html).contains("240");
        assertThat(html).contains("#general");
        assertThat(html).contains("/channels/7?m=42#m=42");
        // The Lucene <mark> reaches the page as markup, not as escaped text — the whole point of
        // rendering the snippet with utext.
        assertThat(html).contains("<mark>deploy</mark>");
        assertThat(html).doesNotContain("&lt;mark&gt;");
    }

    @Test
    void anApproximateTotalIsPresentedAsAFloorRatherThanANumber() {
        var page = new SearchService.ResultPage(List.of(), 1000L, true, 0, 20);
        var html = render(Map.of(
                "query", "the", "searchQuery", "the", "scope", "accessible",
                "results", page,
                "hits", List.of(channelHit(1L, "general", true))));

        assertThat(html).contains("More than");
        assertThat(html).contains("1,000");
    }

    @Test
    void aHitThatMatchedOnAFilenameSaysWhichFile() {
        // Without this the row is a mystery: a file posted with no caption has an empty body, so
        // the snippet shows nothing the user typed and the result reads as a bug in search.
        var page = new SearchService.ResultPage(List.of(), 1L, false, 0, 20);
        var html = render(Map.of(
                "query", "quarterly", "searchQuery", "quarterly", "scope", "accessible",
                "results", page,
                "hits", List.of(channelHit(42L, "general", true,
                        List.of("<mark>quarterly</mark>-report.pdf")))));

        assertThat(html).contains("search-result-files");
        // The highlighter's <mark> arrives as markup, like the snippet's, and the filename is
        // already HTML-escaped by the time it gets here.
        assertThat(html).contains("<mark>quarterly</mark>-report.pdf");
        assertThat(html).doesNotContain("&lt;mark&gt;");
    }

    @Test
    void aHitWithNoFilenameMatchDrawsNoFileRow() {
        var page = new SearchService.ResultPage(List.of(), 1L, false, 0, 20);
        var html = render(Map.of(
                "query", "deploy", "searchQuery", "deploy", "scope", "accessible",
                "results", page,
                "hits", List.of(channelHit(42L, "general", true))));

        assertThat(html).doesNotContain("search-result-files");
    }

    @Test
    void aResultFromAChannelTheViewerHasNotJoinedIsTagged() {
        var page = new SearchService.ResultPage(List.of(), 1L, false, 0, 20);
        var html = render(Map.of(
                "query", "deploy", "searchQuery", "deploy", "scope", "accessible",
                "results", page,
                "hits", List.of(channelHit(9L, "incidents", false))));

        assertThat(html).contains("not joined");
    }

    @Test
    void anErrorFromTheQueryIsShownOnThePageRatherThanSwallowed() {
        var model = new HashMap<String, Object>(Map.of(
                "query", "in:#nope hello", "searchQuery", "in:#nope hello", "scope", "accessible",
                "results", SearchService.ResultPage.empty(0, 20),
                "hits", List.of()));
        model.put("error", "No channel called #nope that you can read.");

        var html = render(model);

        assertThat(html).contains("No channel called #nope that you can read.");
    }

    @Test
    void theAdminScopeOptionIsOfferedOnlyToAdminsAndSaysWhatItDoes() {
        var base = new HashMap<String, Object>(Map.of(
                "query", "x", "searchQuery", "x", "scope", "accessible",
                "results", SearchService.ResultPage.empty(0, 20),
                "hits", List.of()));

        assertThat(render(base)).doesNotContain("value=\"all\"");

        base.put("canSearchEverywhere", true);
        var asAdmin = render(base);
        assertThat(asAdmin).contains("value=\"all\"");
        // Named for what it does. Public channels are already in the default scope, so this option
        // adds private channels only, and the label has to say so.
        assertThat(asAdmin).contains("not invited to");
    }

    @Test
    void thePagerAndTheOriginSurviveTogether() {
        // The way-back link and the pager both hang off the origin channel, and the pager links
        // have to carry it forward or page 2 loses the way back.
        var channel = new ai.intellistream.chat.domain.Channel(
                "general", "general", null, ai.intellistream.chat.domain.ChannelType.PUBLIC, viewer());
        var page = new SearchService.ResultPage(List.of(), 100L, false, 1, 20);
        var model = new HashMap<String, Object>(Map.of(
                "query", "deploy", "searchQuery", "deploy", "scope", "channel",
                "results", page,
                "hits", List.of(channelHit(3L, "general", true))));
        model.put("originChannel", channel);
        model.put("activeChannelId", 5L);

        var html = render(model);

        assertThat(html).contains("Back to #general");
        assertThat(html).contains("page=0"); // previous
        assertThat(html).contains("page=2"); // next
        // The top bar's hidden origin field, which is what makes a second search from this page
        // point back to the same channel.
        assertThat(html).contains("name=\"channelId\"");
    }

    @Test
    void theTopBarBoxNamesTheScopeItWillActuallySearch() {
        // The one box is pre-scoped to the room you are reading, which is a convenience only as
        // long as it is visible. An unmarked box that silently searches one channel is the same
        // class of bug as a token that silently means the opposite of what you typed.
        var channel = new ai.intellistream.chat.domain.Channel(
                "general", "general", null, ai.intellistream.chat.domain.ChannelType.PUBLIC, viewer());
        var scoped = new HashMap<String, Object>(Map.of(
                "query", "", "searchQuery", "", "scope", "channel",
                "results", SearchService.ResultPage.empty(0, 20),
                "hits", List.of()));
        scoped.put("originChannel", channel);
        scoped.put("activeChannel", channel);
        scoped.put("activeChannelId", 5L);

        assertThat(render(scoped)).contains("Search #general…");

        // Widened: same box, and it says so.
        var wide = new HashMap<String, Object>(scoped);
        wide.put("activeChannel", null);
        wide.put("activeChannelId", null);
        assertThat(render(wide)).contains("Search messages…");
    }

    @Test
    void theHooksTheSearchPageScriptQueriesForAreAllPresent() {
        // chat/search-page.js selects on these three. A rename here is a silently dead control.
        var html = render(Map.of(
                "query", "", "searchQuery", "", "scope", "accessible",
                "results", SearchService.ResultPage.empty(0, 20),
                "hits", List.of()));

        assertThat(html).contains("id=\"search-scope-select\"");
        assertThat(html).contains("class=\"search-help-example\"");
        assertThat(html).contains("data-example=");
        assertThat(html).contains("id=\"global-search-input\"");
    }
}
