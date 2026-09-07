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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard for the one code path a rendered message body takes to the DOM.
 *
 * <p>The bug this pins: showing a message is "drop the server's sanitized {@code bodyHtml} in,
 * then highlight the fenced code in it", and that pair used to be copied at eight call sites —
 * the channel feed, its edit re-render, its thread replies, its pins panel, the DM feed and its
 * two re-renders, and {@code /saved}. Four carried the highlight line and four didn't, so the
 * same code block rendered coloured in the channel feed and plain in a refreshed DM, the pins
 * panel and saved items. Nothing threw and nothing was logged; it was found by a person
 * refreshing a conversation. The pair now lives once, in
 * {@code ChatKit.renderMessageBody} / {@code buildMessageBodyEl}.
 *
 * <p>The project deliberately runs no JS in tests (no Node, no headless browser — see AGENTS.md),
 * so like {@link ChannelMembersClientGuardTest} this is a string scan over the sources. It is
 * fragile by design: it exists to make the ninth copy fail the build rather than ship silently.
 */
class MessageBodyRenderGuardTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** chat-kit.js is the implementation of the seam, so it is the one file allowed to render a body. */
    private static final String SEAM_FILE = "chat-kit.js";

    /**
     * The page scripts that build message bodies, and the page each one belongs to. Every entry's
     * template has to load the highlighter — a body built without one renders plain, which is the
     * whole bug. Adding a fourth renderer means adding it here *and* giving its page the script;
     * {@link #everyBodyRenderingScriptIsAccountedFor()} fails until you do.
     */
    private static final List<String[]> BODY_RENDERERS = List.of(
            new String[] { "chat/index.js", "channels.html" },
            new String[] { "conversation.js", "conversation.html" },
            new String[] { "saved.js", "saved.html" });

    private static final Pattern SEAM_CALL =
            Pattern.compile("\\b(buildMessageBodyEl|renderMessageBody)\\s*\\(");

    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    /** The bundle a template pulls in, from its {@code ~{fragments/assets :: js('name')}} include. */
    private static String bundleOf(String template) {
        var m = Pattern.compile("assets\\s*::\\s*js\\('([^']+)'\\)").matcher(template);
        return m.find() ? m.group(1) : null;
    }

    private static List<Path> jsSources() throws IOException {
        try (Stream<Path> walk = Files.walk(JS)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".js"))
                    // Vendored libraries are third-party; the smoke runner is dev-only.
                    .filter(p -> !p.toString().contains("/vendor/"))
                    .filter(p -> !p.toString().contains("/test/"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void chatKitOwnsTheOnlyBodyRenderer() throws Exception {
        var src = read(JS.resolve(SEAM_FILE));
        assertThat(src)
                .as("the seam must exist and be exported")
                .contains("const renderMessageBody = (el, html)")
                .contains("const buildMessageBodyEl = (html, extraClass)")
                .contains("    renderMessageBody,")
                .contains("    buildMessageBodyEl,");

        // The point of the seam is that rendering and highlighting cannot be separated.
        int render = src.indexOf("const renderMessageBody");
        int end = src.indexOf("};", render);
        assertThat(src.substring(render, end))
                .as("renderMessageBody must highlight what it renders — that pairing is the fix")
                .contains("el.innerHTML = html")
                .contains("highlightCode(el)");
    }

    @Test
    void noPageScriptRendersABodyItself() throws Exception {
        // `.innerHTML = <anything>.bodyHtml` or `.html` is the shape of the old copies. Outside
        // chat-kit.js it must not reappear: it is a body rendered without the highlight step.
        var raw = Pattern.compile("innerHTML\\s*=\\s*\\w+\\.(bodyHtml|html)\\b");
        for (Path p : jsSources()) {
            if (p.getFileName().toString().equals(SEAM_FILE)) continue;
            assertThat(raw.matcher(read(p)).find())
                    .as("%s renders a message body directly — use ChatKit.buildMessageBodyEl "
                            + "/ renderMessageBody so the highlight step cannot be forgotten", p)
                    .isFalse();
        }
    }

    @Test
    void everyBodyRenderingScriptIsAccountedFor() throws Exception {
        var known = BODY_RENDERERS.stream().map(r -> r[0]).collect(java.util.stream.Collectors.toSet());
        for (Path p : jsSources()) {
            String rel = JS.relativize(p).toString();
            if (rel.equals(SEAM_FILE)) continue;
            if (!SEAM_CALL.matcher(read(p)).find()) continue;
            assertThat(known)
                    .as("%s builds message bodies but isn't in BODY_RENDERERS — add it with its "
                            + "page, and make sure that page loads vendor/highlight.min.js", rel)
                    .contains(rel);
        }
    }

    @Test
    void everyPageThatShowsBodiesLoadsTheHighlighter() throws Exception {
        for (String[] pair : BODY_RENDERERS) {
            var template = read(TEMPLATES.resolve(pair[1]));
            assertThat(template)
                    .as("%s shows message bodies (%s) so it must load highlight.js — without it "
                            + "every fenced block on the page renders plain", pair[1], pair[0])
                    .contains("/js/vendor/highlight.min.js");
        }
    }

    @Test
    void everyPageThatShowsBodiesPicksAThemeStylesheet() throws Exception {
        // theme-loader.js injects the light/dark hljs stylesheet. Without it the highlighting is
        // applied but unstyled — classes on the tokens, no colours — which reads as "not working".
        for (String[] pair : BODY_RENDERERS) {
            String bundle = bundleOf(read(TEMPLATES.resolve(pair[1])));
            assertThat(bundle).as("%s must include a JS bundle", pair[1]).isNotNull();
            assertThat(read(JS.resolve(bundle + ".manifest.js")))
                    .as("the '%s' bundle must carry theme-loader.js so %s gets an hljs stylesheet",
                            bundle, pair[1])
                    .contains("//= require theme-loader.js");
        }
    }

    @Test
    void serverRenderedBodiesAreSweptOnLoad() throws Exception {
        // Thymeleaf draws the history you land on; nothing in the page scripts touches it. chat-kit
        // sweeps it once, for every page at once — this is the half of the bug that made a
        // refreshed DM show plain code while an edited message showed coloured code.
        var src = read(JS.resolve(SEAM_FILE));
        assertThat(src)
                .as("chat-kit must highlight the server-rendered history on load")
                .contains("highlightCode(document)")
                .contains("DOMContentLoaded");

        // And the two templates that actually server-render bodies must be able to benefit.
        for (String page : Set.of("channels.html", "conversation.html")) {
            assertThat(read(TEMPLATES.resolve(page)))
                    .as("%s server-renders message bodies", page)
                    .contains("class=\"message-body\" th:utext=\"${msg.bodyHtml}\"");
        }
    }

    @Test
    void highlighterStaysQuietOnPagesWithNoCode() throws Exception {
        // chat-kit is on /files and the file manager too, and now sweeps every page on load. A
        // warning there would fire on pages that legitimately have no code, training people to
        // ignore the one case that matters: a page with code blocks and no highlighter.
        var src = read(JS.resolve(SEAM_FILE));
        int start = src.indexOf("const highlightCode = (root)");
        assertThat(start).as("highlightCode block").isGreaterThan(-1);
        var block = src.substring(start, src.indexOf("// ---------- Rendered message bodies", start));
        assertThat(block.indexOf("querySelectorAll('pre code')"))
                .as("the empty-page check must come before the missing-hljs warning")
                .isLessThan(block.indexOf("highlight.js not loaded"));
    }
}
