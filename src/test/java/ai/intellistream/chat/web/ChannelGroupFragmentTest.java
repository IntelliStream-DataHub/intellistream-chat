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

import static ai.intellistream.chat.domain.NotificationLevel.DEFAULT;
import static ai.intellistream.chat.domain.NotificationLevel.MENTIONS;
import static ai.intellistream.chat.domain.NotificationLevel.NONE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders {@code fragments/channel-group.html} for real.
 *
 * <p>Two reasons this exists rather than being left to a manual look at the page. The unread cue is
 * decided in {@code ChannelSidebarDto} and rendered <em>twice</em> — here in Thymeleaf and again in
 * {@code index.js} for live messages — and if the two disagree, reloading the page silently changes
 * what the user is looking at. And a template is the one place where a wrong expression fails at
 * request time with no compiler to catch it, so the attributes the CSS and the JS both key on
 * (data-unread-cue, data-muted, data-unread, data-mentions) are worth asserting.
 *
 * <p>No Spring context and no database: a {@code SpringTemplateEngine} over a mock servlet
 * exchange, which is all the fragment needs — a SpEL evaluator and something for {@code @{...}} to
 * resolve a context path against.
 */
class ChannelGroupFragmentTest {

    private static final StaticApplicationContext APP_CONTEXT = appContext();
    private static final SpringTemplateEngine ENGINE = engine();

    private static StaticApplicationContext appContext() {
        var ctx = new StaticApplicationContext();
        ctx.refresh();
        return ctx;
    }

    /**
     * Two resolvers: the real fragment off the classpath, plus a string resolver for the one-line
     * host template below. The host is what makes this a faithful test — the fragment is invoked
     * through a parameterised {@code th:replace}, exactly as sidebar.html invokes it, rather than
     * through a selector that would bypass the signature.
     */
    private static SpringTemplateEngine engine() {
        var files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setResolvablePatterns(java.util.Set.of("fragments/*"));
        files.setOrder(1);
        var strings = new StringTemplateResolver();
        strings.setOrder(2);
        var engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
        engine.addTemplateResolver(strings);
        return engine;
    }

    private static final String HOST = """
            <ul th:replace="~{fragments/channel-group ::             group(${channels}, ${activeChannelId}, ${notifyDefault}, 'sidebar-channel-list')}"></ul>""";

    /** {@code @{...}} link expressions need a web exchange; the mock servlet stack supplies one
     *  with an empty context path, so hrefs come out as the plain paths the sidebar uses. */
    private static org.thymeleaf.web.IWebExchange webExchange() {
        var servletContext = new MockServletContext();
        return JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse());
    }

    private static ChannelSidebarDto row(String name, long unread, long mentions,
                                         NotificationLevel level) {
        return new ChannelSidebarDto(42L, name, name, ChannelType.PUBLIC, true, false,
                unread, mentions, level);
    }

    private static String render(ChannelSidebarDto row, NotificationLevel accountDefault) {
        var context = new WebContext(webExchange());
        // Normally supplied by Spring's view resolver; the SpEL evaluator needs it to exist.
        context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(APP_CONTEXT, null));
        context.setVariable("channels", List.of(row));
        context.setVariable("activeChannelId", null);
        context.setVariable("notifyDefault", accountDefault);
        return ENGINE.process(HOST, context);
    }

    @Test
    void aQuietChannelIsPlain() {
        var html = render(row("general", 0, 0, DEFAULT), MENTIONS);

        assertThat(html).contains("data-unread-cue=\"none\"");
        assertThat(html).contains("data-muted=\"false\"");
        assertThat(html).doesNotContain("unread-badge");
        assertThat(html).doesNotContain("channel-muted-marker");
    }

    @Test
    void ordinaryUnreadIsBoldWithNoNumber() {
        var html = render(row("deploys", 12, 0, DEFAULT), MENTIONS);

        assertThat(html).contains("data-unread-cue=\"bold\"");
        assertThat(html).contains("data-unread=\"12\"");
        assertThat(html)
                .describedAs("a count on every busy channel is the noise this change removes")
                .doesNotContain("unread-badge");
        // has-unread stays truthful regardless of how loud the row is; index.js relies on it too.
        assertThat(html).contains("has-unread");
    }

    @Test
    void aMentionRendersItsCount() {
        var html = render(row("deploys", 12, 3, MENTIONS), MENTIONS);

        assertThat(html).contains("data-unread-cue=\"count\"");
        assertThat(html).contains("data-mentions=\"3\"");
        assertThat(html).contains("unread-badge mention");
        assertThat(html).doesNotContain("mention muted");
        assertThat(html).contains(">3<");
    }

    @Test
    void aMutedChannelIsDimmedMarkedAndSilentButStillCounts() {
        var html = render(row("noisy", 240, 0, NONE), MENTIONS);

        assertThat(html).contains("data-muted=\"true\"");
        assertThat(html).contains("data-unread-cue=\"none\"");
        assertThat(html)
                .describedAs("the count is kept on the row — muting is not amnesia")
                .contains("data-unread=\"240\"");
        assertThat(html).doesNotContain("unread-badge");
        assertThat(html).contains("channel-muted-marker");
    }

    @Test
    void aMentionInAMutedChannelGetsAQuietBadge() {
        var html = render(row("noisy", 240, 2, NONE), MENTIONS);

        assertThat(html).contains("data-muted=\"true\"");
        assertThat(html).contains("data-unread-cue=\"count\"");
        assertThat(html)
                .describedAs("kept so it is findable, dimmed so it is not an interruption")
                .contains("mention muted");
    }

    @Test
    void aMuteInheritedFromTheAccountDefaultCountsAsMuted() {
        var html = render(row("anything", 5, 0, DEFAULT), NONE);

        assertThat(html).contains("data-muted=\"true\"");
        assertThat(html).contains("data-unread-cue=\"none\"");
    }
}
