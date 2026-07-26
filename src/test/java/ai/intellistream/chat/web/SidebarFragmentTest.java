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

import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.NotificationLevel;
import ai.intellistream.chat.web.dto.ChannelSidebarDto;
import ai.intellistream.chat.web.dto.SidebarView;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders {@code fragments/sidebar.html} — the whole thing, both groups.
 *
 * <p>Worth its own test because the grouping is reached through <em>derived</em> accessors:
 * {@code SidebarView.favourites()} and {@code unstarred()} are methods on the record, not record
 * components, and the template asks for them as properties. If SpEL stopped resolving them — a
 * rename, or the record becoming a class — nothing would fail until a page was requested. The same
 * goes for {@code sidebar.channelIds()}, which the channel page's subscription meta reads.
 *
 * <p>It is also the only test that sees the Favourites group appear and disappear, which is a
 * template-level decision (the group is not rendered empty) that the client-side regroup() in
 * chrome.js has to mirror.
 */
class SidebarFragmentTest {

    private static final SpringTemplateEngine ENGINE = engine();

    private static SpringTemplateEngine engine() {
        var files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setResolvablePatterns(Set.of("fragments/*"));
        files.setOrder(1);
        var strings = new StringTemplateResolver();
        strings.setOrder(2);
        var engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
        engine.addTemplateResolver(strings);
        return engine;
    }

    private static final String HOST =
            "<div th:replace=\"~{fragments/sidebar :: sidebar}\"></div>";

    private static ChannelSidebarDto row(long id, String name, boolean favourite) {
        return new ChannelSidebarDto(id, name, name, ChannelType.PUBLIC, true, favourite,
                0, 0, NotificationLevel.DEFAULT);
    }

    private static String render(List<ChannelSidebarDto> channels) {
        var appContext = new StaticApplicationContext();
        appContext.refresh();
        var servletContext = new MockServletContext();
        var exchange = JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse());
        var context = new WebContext(exchange);
        context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(appContext, null));
        context.setVariable("sidebar", new SidebarView(channels, NotificationLevel.MENTIONS));
        // HomeController sets both ids on every page that renders this, the inactive one to null.
        context.setVariable("activeChannelId", null);
        context.setVariable("activeConversationId", null);
        context.setVariable("conversations", List.of());
        return ENGINE.process(HOST, context);
    }

    @Test
    void everyJoinedChannelIsListedInOneGroupOrTheOther() {
        var html = render(List.of(row(1, "alfa", true), row(2, "bravo", false), row(3, "charlie", true)));

        assertThat(html).contains("id=\"sidebar-favourite-list\"");
        assertThat(html).contains("id=\"sidebar-channel-list\"");
        // Nothing is dropped by the split, and nothing appears twice.
        assertThat(html.split("data-channel-id=\"1\"", -1)).hasSize(2);
        assertThat(html.split("data-channel-id=\"2\"", -1)).hasSize(2);
        assertThat(html.split("data-channel-id=\"3\"", -1)).hasSize(2);
        // Favourites sits above Channels — the group order is the point of it.
        assertThat(html.indexOf("Favourites")).isLessThan(html.indexOf("id=\"sidebar-channel-list\""));
    }

    @Test
    void theFavouritesGroupIsAbsentUntilSomethingIsStarred() {
        var html = render(List.of(row(1, "alfa", false), row(2, "bravo", false)));

        // A heading over an empty list teaches nobody what the star does. chrome.js creates the
        // group on the first star for exactly this reason.
        assertThat(html).doesNotContain("sidebar-favourite-list");
        assertThat(html).doesNotContain("Favourites");
        assertThat(html).contains("id=\"sidebar-channel-list\"");
    }

    @Test
    void anEmptySidebarExplainsItself() {
        var html = render(List.of());

        assertThat(html).contains("not in any channels yet");
        // The filter's two jobs are labelled whether or not there is anything to filter.
        assertThat(html).contains("id=\"sidebar-filter\"");
        assertThat(html).contains("id=\"sidebar-search-hint\"");
        assertThat(html).contains("id=\"sidebar-no-match\"");
    }
}
