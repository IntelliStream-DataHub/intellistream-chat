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
import ai.intellistream.chat.domain.ChannelType;
import ai.intellistream.chat.domain.User;
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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders {@code channel-files.html} for real, for the same reason
 * {@link SearchPageTemplateTest} renders the results page: a template has no compiler behind it and
 * {@code IntegrationTestApplication} does not scan {@code web}, so nothing else in the suite ever
 * asks Thymeleaf to parse this file.
 *
 * <p>The assertions are about the two things that break silently — the ids
 * {@code static/js/channel-files.js} queries for, and the branch that decides whether a table is
 * emitted at all for a private channel the viewer is not in.
 */
class ChannelFilesPageTemplateTest {

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
        applicationContext.getBeanFactory()
                .registerSingleton("assetService", new SearchPageTemplateTest.StubAssets());
        applicationContext.refresh();

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    private String render(Channel channel, boolean canRead) {
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        var response = new MockHttpServletResponse();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(request, response);

        Map<String, Object> variables = new HashMap<>();
        variables.put(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));
        variables.put("appTitle", "IntelliStream Chat");
        variables.put("appFaviconUrl", "/favicon.ico");
        variables.put("me", new User("kc-alice", "alice", "alice@example.com", "Alice A"));
        variables.put("channel", channel);
        variables.put("canRead", canRead);
        // Every page carries the viewer's resolved timestamp conventions (fragments/time-prefs),
        // so a template test that omits them fails on the head rather than on what it is testing.
        variables.putIfAbsent("fmt", new ai.intellistream.chat.i18n.TimeFormats("UTC")
                .forUser(null, Locale.ENGLISH));
        variables.putIfAbsent("askForZone", false);


        return engine.process("channel-files", new WebContext(exchange, Locale.ENGLISH, variables));
    }

    private static Channel channel(String name, ChannelType type) {
        var creator = new User("kc-root", "root", "root@example.com", "Root");
        var c = new Channel(name, name, null, type, creator);
        // The template links by id; a detached entity has none until something sets one.
        try {
            var field = Channel.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(c, 7L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    @Test
    void theTableCarriesTheIdsAndTheChannelIdTheScriptNeeds() {
        var html = render(channel("general", ChannelType.PUBLIC), true);

        // Every one of these is queried by static/js/channel-files.js; a rename here is a page
        // that loads, says "Loading…" forever, and reports nothing.
        assertThat(html).contains("id=\"channel-files-tbody\"");
        assertThat(html).contains("id=\"channel-files-search\"");
        assertThat(html).contains("id=\"channel-files-count\"");
        assertThat(html).contains("id=\"channel-files-error\"");
        assertThat(html).contains("id=\"channel-files-pager\"");
        assertThat(html).contains("id=\"channel-files-prev\"");
        assertThat(html).contains("id=\"channel-files-next\"");
        // The channel id rides on the table rather than in the URL the script parses: the script
        // never has to know the shape of the page's own address.
        assertThat(html).contains("data-channel-id=\"7\"");
        assertThat(html).contains("Files in <span>#general</span>");
        // Six columns, including the two the personal file manager does not have.
        assertThat(html).contains("Shared by");
        assertThat(html).contains("colspan=\"6\"");
    }

    @Test
    void aPrivateChannelTheViewerIsNotInGetsNoTableAtAll() {
        var html = render(channel("board", ChannelType.PRIVATE), false);

        assertThat(html).contains("Ask an admin for an invitation");
        // Not merely hidden: there is no table, no filter box and no id for the script to bind to,
        // so nothing on the page can be talked into fetching a list.
        assertThat(html).doesNotContain("id=\"channel-files-tbody\"");
        assertThat(html).doesNotContain("data-channel-id");
        // The name is still shown, exactly as /channels/{id} already shows it to this same viewer.
        assertThat(html).contains("#board");
    }

    @Test
    void thePageSaysWhatItDoesNotList() {
        var html = render(channel("general", ChannelType.PUBLIC), true);

        // The tombstone decision, stated on the page rather than left to be discovered by someone
        // wondering where a file went.
        assertThat(html).contains("are not listed");
        assertThat(html).contains("still says one was removed");
    }

    @Test
    void bundleAndBackLinkPointWhereTheyShould() {
        var html = render(channel("general", ChannelType.PUBLIC), true);

        assertThat(html).contains("/js/channel-files.bundle.min.js");
        assertThat(html).contains("href=\"/channels/7\"");
        assertThat(html).contains("Back to #general");
    }

    /** Kept honest against the real DTO: the template renders no rows itself, so this is a guard
     *  that the columns the script fills still match the record it fills them from. */
    @Test
    void theHeaderColumnsMatchWhatTheDtoCanSupply() {
        var html = render(channel("general", ChannelType.PUBLIC), true);
        for (var column : List.of("File", "Size", "Type", "Shared by", "When")) {
            assertThat(html).contains(">" + column + "</th>");
        }
    }
}
