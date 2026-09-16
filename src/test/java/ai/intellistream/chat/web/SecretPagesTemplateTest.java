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
 * Renders {@code secrets.html} and {@code secret-view.html} for real, as {@link SearchPageTemplateTest}
 * does for search: a template has no compiler behind it, and the integration tests have no view
 * resolver. The assertions after each render are the contracts the pages' scripts and the security
 * rules depend on.
 */
class SecretPagesTemplateTest {

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
        applicationContext.getBeanFactory().registerSingleton("assetService", new SearchPageTemplateTest.StubAssets());
        applicationContext.refresh();

        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    private String render(String template, Map<String, Object> model) {
        var servletContext = new MockServletContext();
        var request = new MockHttpServletRequest(servletContext);
        var response = new MockHttpServletResponse();
        var exchange = JakartaServletWebApplication.buildApplication(servletContext).buildExchange(request, response);

        var variables = new HashMap<>(model);
        variables.put(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(applicationContext, null));
        variables.putIfAbsent("appTitle", "Acme Chat");
        variables.putIfAbsent("appFaviconUrl", "/favicon.ico");
        variables.putIfAbsent("fmt", new ai.intellistream.chat.i18n.TimeFormats("UTC").forUser(null, Locale.ENGLISH));
        variables.putIfAbsent("askForZone", false);
        return engine.process(template, new WebContext(exchange, Locale.ENGLISH, variables));
    }

    private Map<String, Object> secretsModel(boolean allowPublic) {
        var model = new HashMap<String, Object>();
        model.put("me", new User("kc-alice", "alice", "alice@example.com", "Alice A"));
        model.put("allowPublicSecrets", allowPublic);
        model.put("lifetimes", List.of(
                new SecretSharePageController.LifetimeOption(3600, "1 hour", false),
                new SecretSharePageController.LifetimeOption(86400, "1 day", true),
                new SecretSharePageController.LifetimeOption(604800, "7 days", false)));
        model.put("maxPlaintextBytes", 16384);
        model.put("displayMinutes", 5);
        return model;
    }

    // ---------------------------------------------------------------- /s/{id} ----

    @Test
    void theLinkPageRendersSignedOutAndGivesNothingAway() {
        // No "me" in the model at all: the page must render for a visitor without an account.
        var html = render("secret-view", Map.of());

        assertThat(html).contains("<title>Shared secret · Acme Chat</title>");
        assertThat(html).contains("<meta name=\"robots\" content=\"noindex, nofollow\"/>");
        assertThat(html).contains("<meta name=\"referrer\" content=\"no-referrer\"/>");
        // No CSRF token: rendering one would rotate the SameSite=Strict cookie under other tabs.
        assertThat(html).doesNotContain("_csrf");
        // Nothing for an unfurler: no description, no Open Graph.
        assertThat(html).doesNotContain("og:").doesNotContain("name=\"description\"");
        assertThat(html).contains("js/secret-view.bundle.min.js");
    }

    @Test
    void theLinkPageCarriesEveryHookItsScriptQueriesFor() {
        var html = render("secret-view", Map.of());
        for (var id : List.of("secret-view", "sv-loading", "sv-unsupported", "sv-key-prompt", "sv-key-form", "sv-key",
                "sv-key-error", "sv-ready", "sv-creator", "sv-minutes", "sv-expiry", "sv-reveal", "sv-signin",
                "sv-signin-btn", "sv-revealed", "sv-warning", "sv-why", "sv-secret", "sv-countdown", "sv-timer-fill",
                "sv-copy", "sv-hide", "sv-copy-note", "sv-wiped", "sv-gone", "sv-gone-text", "sv-notfound", "sv-error",
                "sv-error-text", "sv-why-dialog", "sv-why-close", "sv-why-ok")) {
            assertThat(html).as(id).contains("id=\"" + id + "\"");
        }
        // Both icons the page uses exist in the sprite it includes.
        assertThat(html).contains("<symbol id=\"icon-key\"").contains("<symbol id=\"icon-camera\"");
    }

    @Test
    void theScreenshotWarningComesWithTheSecretAndExplainsItself() {
        var html = render("secret-view", Map.of());
        var revealed = html.substring(html.indexOf("id=\"sv-revealed\""), html.indexOf("id=\"sv-wiped\""));
        // The banner is inside the revealed panel, ahead of the secret itself.
        assertThat(revealed.indexOf("id=\"sv-warning\"")).isLessThan(revealed.indexOf("id=\"sv-secret\""));
        assertThat(revealed).contains("Don't screenshot or photograph this.").contains("Why this matters");
        // The dialog is a real modal and says why, in depth.
        var dialog = html.substring(html.indexOf("id=\"sv-why-dialog\""));
        assertThat(dialog).contains("role=\"dialog\"").contains("aria-modal=\"true\"")
                .contains("backed up and synced").contains("searchable").contains("What to do instead");
        // Starts closed.
        assertThat(html).containsPattern("id=\"sv-why-dialog\"\\s+hidden");
    }

    // ---------------------------------------------------------------- /secrets ----

    @Test
    void theCreatePageOffersAnyoneWithTheLinkOnlyWhileTheWorkspaceAllowsIt() {
        assertThat(render("secrets", secretsModel(true))).contains("id=\"secret-anyone\"");
        assertThat(render("secrets", secretsModel(false))).doesNotContain("id=\"secret-anyone\"");
    }

    @Test
    void theCreatePageCarriesItsHooksLifetimesAndLimits() {
        var html = render("secrets", secretsModel(true));
        for (var id : List.of("secret-form", "secret-text", "secret-label", "secret-lifetime", "secret-separate-key",
                "secret-size", "secret-error", "secret-submit", "secret-result", "secret-link", "secret-copy-link",
                "secret-key-row", "secret-key", "secret-copy-key", "secret-another", "secret-list",
                "secret-list-error", "secret-more", "secret-unsupported")) {
            assertThat(html).as(id).contains("id=\"" + id + "\"");
        }
        assertThat(html).contains("data-max-bytes=\"16384\"");
        assertThat(html).containsPattern("<option value=\"86400\"\\s+selected=\"selected\">1 day</option>");
        assertThat(html).contains("<option value=\"3600\">1 hour</option>");
        assertThat(html).contains("js/secrets.bundle.min.js");
        assertThat(html).contains("Not encrypted, and shown only to you");
    }

    @Test
    void lifetimeLabelsReadNaturally() {
        assertThat(SecretSharePageController.label(java.time.Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(SecretSharePageController.label(java.time.Duration.ofHours(12))).isEqualTo("12 hours");
        assertThat(SecretSharePageController.label(java.time.Duration.ofDays(1))).isEqualTo("1 day");
        assertThat(SecretSharePageController.label(java.time.Duration.ofDays(7))).isEqualTo("7 days");
        assertThat(SecretSharePageController.label(java.time.Duration.ofMinutes(30))).isEqualTo("30 minutes");
    }
}
