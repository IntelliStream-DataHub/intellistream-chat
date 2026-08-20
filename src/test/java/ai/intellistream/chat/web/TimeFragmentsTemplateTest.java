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
import ai.intellistream.chat.i18n.TimeFormats;
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
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two fragments the timestamp work added, rendered against the real template files.
 *
 * <p>Both are otherwise untested by construction. {@code time-prefs} is a block of meta tags that
 * no assertion on a page's visible content would ever notice going missing — and if it does go
 * missing the client silently falls back to the browser's raw defaults, which is the exact split
 * between server and client rendering this whole change exists to close. {@code zone-prompt} is
 * worse: it renders only when the zone could not be worked out at all, which is a state a developer
 * never sees, so a Thymeleaf error in it would ship and only ever break for the users who hit it.
 */
class TimeFragmentsTemplateTest {

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
        applicationContext.refresh();

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    private static User viewer() {
        return new User("kc-alice", "alice", "alice@example.com", "Alice A");
    }

    private String render(String template, Map<String, Object> model) {
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        var response = new MockHttpServletResponse();
        var application = JakartaServletWebApplication.buildApplication(servletContext);
        var exchange = application.buildExchange(request, response);

        var variables = new HashMap<>(model);
        variables.put(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));
        return engine.process(template, new WebContext(exchange, Locale.ENGLISH, variables));
    }

    // ---------- time-prefs ----------

    @Test
    void thePrefsFragmentPublishesEveryValueTheClientRebuildsItsFormattersFrom() {
        var me = viewer();
        me.chooseZone("Europe/Oslo");
        var view = new TimeFormats("UTC").forUser(me, Locale.forLanguageTag("nb-NO"));

        var html = render("fragments/time-prefs", Map.of("fmt", view));

        // Every one of these is read by name in time-format.js. A rename on either side leaves the
        // client on the browser's defaults with nothing to say so.
        assertThat(html).contains("name=\"me-zone\" content=\"Europe/Oslo\"");
        assertThat(html).contains("name=\"me-locale\" content=\"nb-NO\"");
        assertThat(html).contains("name=\"me-hour-cycle\" content=\"auto\"");
        assertThat(html).contains("name=\"me-twelve-hour\" content=\"false\"");
        assertThat(html).contains("name=\"me-date-style\" content=\"auto\"");
        assertThat(html).contains("name=\"me-zone-source\" content=\"chosen\"");
    }

    @Test
    void theResolvedClockIsPublishedNotTheRawSetting() {
        // AUTO is resolved server-side so the client passes a concrete hour12 to Intl instead of
        // reimplementing the CLDR lookup that decided it — the one place the two could disagree.
        var me = viewer();
        me.chooseZone("America/New_York");
        var html = render("fragments/time-prefs",
                Map.of("fmt", new TimeFormats("UTC").forUser(me, Locale.US)));

        assertThat(html).contains("name=\"me-hour-cycle\" content=\"auto\"");
        assertThat(html).contains("name=\"me-twelve-hour\" content=\"true\"");
    }

    // ---------- zone-prompt ----------

    @Test
    void theBannerRendersWithItsLinkAndItsDismissButtonWhenTheZoneIsUnknown() {
        var view = new TimeFormats("UTC").forUser(viewer(), Locale.ENGLISH);

        var html = render("fragments/zone-prompt", Map.of("fmt", view, "askForZone", true));

        assertThat(html).contains("id=\"zone-prompt\"");
        // Named, because "times may be wrong" without saying which zone they are in is not
        // actionable — and it is what the user checks the banner against.
        assertThat(html).contains("UTC");
        // Straight to the section rather than the top of a six-section page.
        assertThat(html).contains("href=\"/profile#timezone-section\"");
        // time-format.js wires the dismissal by this id, and the icon comes from the sprite.
        assertThat(html).contains("id=\"zone-prompt-dismiss\"");
        assertThat(html).contains("#icon-clock");
    }

    @Test
    void theBannerRendersNothingWhenTheZoneIsKnownOrTheUserWavedItAway() {
        var view = new TimeFormats("UTC").forUser(viewer(), Locale.forLanguageTag("nb-NO"));

        var html = render("fragments/zone-prompt", Map.of("fmt", view, "askForZone", false));

        assertThat(html).doesNotContain("id=\"zone-prompt\"");
    }
}
