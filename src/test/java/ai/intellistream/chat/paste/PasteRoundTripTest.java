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

package ai.intellistream.chat.paste;

import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MentionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dialect-compatibility lock: every fixture's converted Markdown is fed through the
 * real {@link MarkdownRenderer} (CommonMark + the jsoup safelist), asserting that what the
 * converter emits actually survives to rendered HTML. If someone teaches the emitter a
 * construct the safelist deletes — or narrows the safelist under the emitter — this is the
 * test that says so.
 */
class PasteRoundTripTest {

    private final MentionService mentionService = Mockito.mock(MentionService.class);
    {
        Mockito.when(mentionService.resolvedUsernames(Mockito.anyString())).thenReturn(Set.of());
    }
    private final MarkdownRenderer renderer = new MarkdownRenderer(mentionService);
    private final HtmlToMarkdownConverter converter = new HtmlToMarkdownConverter();

    private String roundTrip(String fixtureName) {
        String html;
        try (var in = PasteRoundTripTest.class.getResourceAsStream("/paste/" + fixtureName)) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var result = converter.convert(html);
        assertThat(result.convertible()).as("%s should convert", fixtureName).isTrue();
        return renderer.render(result.markdown());
    }

    @Test
    void googleDocsInlineSurvivesTheSanitizer() {
        var rendered = roundTrip("gdocs-inline.html");
        assertThat(rendered).contains("<strong>Bold words</strong>")
                .contains("<em>italic bit</em>")
                .contains("<del>struck words</del>");
    }

    @Test
    void listsSurviveTheSanitizer() {
        var rendered = roundTrip("gdocs-lists-nested.html");
        assertThat(rendered).contains("<ul>").contains("<ol>")
                .contains("<li>First item</li>")
                .contains("Nested item")
                .contains("<li>Step one</li>");
    }

    @Test
    void headingLinkAndTableSurviveTheSanitizer() {
        var rendered = roundTrip("gdocs-heading-link-table.html");
        assertThat(rendered).contains("<h2>Project plan</h2>")
                .contains("href=\"https://example.com/spec\"")
                .contains("<table>")
                .contains("<td>alpha</td>");
    }

    @Test
    void wordDesktopListsSurviveTheSanitizer() {
        var rendered = roundTrip("word-desktop-lists.html");
        assertThat(rendered).contains("<li>First bullet</li>")
                .contains("Nested bullet")
                .contains("<ol>").contains("<li>Numbered item</li>");
    }

    @Test
    void everyConvertibleFixtureRendersWithoutDeletedMarkup() {
        for (var name : HtmlToMarkdownConverterTest.convertibleFixtures()) {
            var rendered = roundTrip(name);
            assertThat(rendered).as(name)
                    .doesNotContain("<img").doesNotContain("<hr")
                    // Escaped literals must come back as text, not as stray markers.
                    .doesNotContain("~~").doesNotContain("\\*");
        }
    }
}
