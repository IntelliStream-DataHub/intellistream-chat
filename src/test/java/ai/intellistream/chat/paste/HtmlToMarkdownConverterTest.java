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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixtures under {@code src/test/resources/paste/} are fabricated from the documented
 * clipboard shapes of each source (Docs' guid-wrapped span soup, Word desktop's mso list
 * paragraphs, Notes' div-per-line) — captured shapes, not captured bytes, so they stay
 * readable. {@code PasteRoundTripTest} feeds the same conversions through the real
 * renderer to pin that everything emitted here actually survives the sanitizer.
 */
class HtmlToMarkdownConverterTest {

    private final HtmlToMarkdownConverter converter = new HtmlToMarkdownConverter();

    private static String fixture(String name) {
        try (var in = HtmlToMarkdownConverterTest.class.getResourceAsStream("/paste/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> convertibleFixtures() {
        return List.of("gdocs-inline.html", "gdocs-lists-nested.html",
                "gdocs-heading-link-table.html", "word-desktop-lists.html",
                "word-desktop-inline.html", "word-online-basic.html", "apple-notes-basic.html");
    }

    private String convert(String fixtureName) {
        var result = converter.convert(fixture(fixtureName));
        assertThat(result.convertible()).as("%s should convert", fixtureName).isTrue();
        return result.markdown();
    }

    // ---------- Google Docs ----------

    @Test
    void googleDocsInlineFormatting() {
        var md = convert("gdocs-inline.html");
        assertThat(md).contains("**Bold words** then plain then _italic bit_ and ~~struck words~~.");
        // The guid <b> wrapper carries font-weight:normal and must not bold the whole paste.
        assertThat(md).doesNotStartWith("**Bold words** then plain then _italic bit_ and ~~struck words~~.**");
        assertThat(md.lines().filter(l -> l.startsWith("**") && l.endsWith("**")).count()).isZero();
    }

    @Test
    void adjacentSpansWithTheSameStyleMergeIntoOneRun() {
        // Docs splits single words across spans; without the merge this is **Hel****lo**.
        assertThat(convert("gdocs-inline.html")).contains("**Hello** split across spans.");
    }

    @Test
    void googleDocsNestedListsAndRenumbering() {
        assertThat(convert("gdocs-lists-nested.html")).isEqualTo("""
                - First item
                - Second item
                  - Nested item

                1. Step one
                2. Step two""");
    }

    @Test
    void googleDocsHeadingLinkAndTable() {
        var md = convert("gdocs-heading-link-table.html");
        assertThat(md).contains("## Project plan");
        // The Docs redirector is unwrapped so the reader gets the real URL.
        assertThat(md).contains("See [the spec](https://example.com/spec) for details.");
        assertThat(md).doesNotContain("google.com/url");
        assertThat(md).contains("| **Name** | **Value** |")
                .contains("| --- | --- |")
                .contains("| alpha | 1 |");
    }

    // ---------- Word desktop ----------

    @Test
    void wordDesktopFakeListsAreReconstructed() {
        var md = convert("word-desktop-lists.html");
        assertThat(md).isEqualTo("""
                - First bullet
                - Second bullet
                  - Nested bullet

                1. Numbered item""");
        // No glyph or o:p residue — the fixture covers both conditional-comment variants,
        // <!--[if !supportLists]--> and the downlevel <![if !supportLists]>.
        assertThat(md).doesNotContain("·").doesNotContain("supportLists").doesNotContain("o:p");
    }

    @Test
    void wordDesktopInlineFormatting() {
        var md = convert("word-desktop-inline.html");
        assertThat(md).contains("Plain start **bold part** and _italic part_ end.");
        assertThat(md).contains("Second paragraph here.");
    }

    // ---------- Word Online / Apple Notes: the generic pass suffices ----------

    @Test
    void wordOnlineSemanticHtml() {
        var md = convert("word-online-basic.html");
        assertThat(md).contains("Regular and **bold online** plus _italic online_");
        assertThat(md).contains("- Item one\n- Item two");
    }

    @Test
    void appleNotesDivPerLine() {
        var md = convert("apple-notes-basic.html");
        assertThat(md).isEqualTo("""
                **Shopping list**

                Milk and **eggs**

                Cheap bread""");
    }

    // ---------- the never-emit rules ----------

    @Test
    void convertibleFixturesNeverEmitWhatTheSanitizerDeletes() {
        for (var name : convertibleFixtures()) {
            var md = convert(name);
            assertThat(md).as(name).doesNotContain("![").doesNotContain("<img");
            assertThat(md.lines().map(String::strip)
                    .anyMatch(l -> l.matches("(-{3,}|\\*{3,}|_{3,})"))).as(name).isFalse();
            assertThat(md.lines().anyMatch(l -> l.matches("<[a-zA-Z][^>]*>.*"))).as(name).isFalse();
        }
    }

    @Test
    void imagesAndRulesAreSkippedNotEmitted() {
        var result = converter.convert(
                "<p><b>keep</b></p><hr><p><img src=\"https://example.com/pic.png\" alt=\"pic\"></p>");
        assertThat(result.convertible()).isTrue();
        assertThat(result.markdown()).contains("**keep**")
                .doesNotContain("![").doesNotContain("---").doesNotContain("pic");
    }

    @Test
    void orderedListsAlwaysRestartAtOne() {
        // The sanitizer strips ol[start], so emitting Word's continuation numbers would
        // render as 1, 2 anyway — the emitter renumbers so composer and rendering agree.
        var result = converter.convert("<ol start=\"7\"><li>alpha</li><li>beta</li></ol>");
        assertThat(result.markdown()).isEqualTo("1. alpha\n2. beta");
    }

    // ---------- escaping ----------

    @Test
    void markdownSyntaxInPastedTextIsEscaped() {
        var result = converter.convert(
                "<p><b>bold</b> literal *stars* and _under_ and `tick` and [bracket] and pipe|bar</p>");
        assertThat(result.convertible()).isTrue();
        assertThat(result.markdown()).isEqualTo(
                "**bold** literal \\*stars\\* and \\_under\\_ and \\`tick\\` and \\[bracket\\] and pipe\\|bar");
    }

    @Test
    void rawHtmlInTextCannotSmuggleThroughTheRenderer() {
        // The renderer passes raw inline HTML to the sanitizer, so a literal "<b>" in
        // pasted text would come back as real bold unless the emitter escapes it.
        var result = converter.convert("<p><i>x</i> a literal &lt;b&gt;tag&lt;/b&gt; here</p>");
        assertThat(result.markdown()).contains("\\<b>tag\\</b> here");
    }

    @Test
    void lineStartsThatWouldFormBlocksAreEscaped() {
        var result = converter.convert(
                "<p><b>b</b><br># not a heading<br>- not a list<br>1. not ordered<br>&gt; not a quote</p>");
        var md = result.markdown();
        assertThat(md).contains("\\# not a heading");
        assertThat(md).contains("\\- not a list");
        assertThat(md).contains("1\\. not ordered");
        assertThat(md).contains("\\> not a quote");
    }

    // ---------- structure ----------

    @Test
    void lineBreaksBecomeHardBreaks() {
        var result = converter.convert("<p>line one<br>line <b>two</b></p>");
        assertThat(result.markdown()).isEqualTo("line one\\\nline **two**");
    }

    @Test
    void preBecomesAFencedBlockWithLanguage() {
        var result = converter.convert(
                "<pre><code class=\"language-java\">int x = 1;\nint y = 2;</code></pre>");
        assertThat(result.convertible()).isTrue();
        assertThat(result.markdown()).isEqualTo("```java\nint x = 1;\nint y = 2;\n```");
    }

    @Test
    void blockquotesArePrefixed() {
        var result = converter.convert("<blockquote><p>quoted <i>words</i></p></blockquote>");
        assertThat(result.markdown()).isEqualTo("> quoted _words_");
    }

    @Test
    void nestedListIndentFollowsTheParentMarkerWidth() {
        var result = converter.convert("<ol><li>one<ul><li>sub</li></ul></li></ol>");
        assertThat(result.markdown()).isEqualTo("1. one\n   - sub");
    }

    @Test
    void bareUrlLinksAreLeftToTheAutolinker() {
        var result = converter.convert(
                "<p><b>x</b> see <a href=\"https://example.com/a\">https://example.com/a</a></p>");
        assertThat(result.markdown()).isEqualTo("**x** see https://example.com/a");
    }

    // ---------- not-convertible guards ----------

    @Test
    void vsCodeStyleAllMonospacePasteIsNotConverted() {
        // A copy that is monospace in its entirety, styled only with colour, is code — the
        // person expects their plain text, not a wall of backtick spans.
        assertThat(converter.convert(fixture("vscode-snippet.html")).convertible()).isFalse();
    }

    @Test
    void plainTextWrappedInClipboardHtmlIsNotConverted() {
        assertThat(converter.convert(fixture("plain-wrapped.html")).convertible()).isFalse();
    }

    @Test
    void blankNullAndOversizeInputsAreNotConverted() {
        assertThat(converter.convert(null).convertible()).isFalse();
        assertThat(converter.convert("   ").convertible()).isFalse();
        assertThat(converter.convert("<p><b>x</b></p>"
                + "y".repeat(HtmlToMarkdownConverter.MAX_HTML_CHARS)).convertible()).isFalse();
    }

    @Test
    void colourOnlyStylingIsNotASignal() {
        var result = converter.convert(
                "<p><span style=\"color:#ff0000;background-color:#eee;font-size:20px\">loud text</span></p>");
        assertThat(result.convertible()).isFalse();
    }

    // ---------- output cap ----------

    @Test
    void outputIsCappedAtTheMessageLimitOnALineBoundary() {
        var para = "<p><b>" + "x".repeat(200) + "</b></p>";
        var result = converter.convert(para.repeat(100));
        assertThat(result.convertible()).isTrue();
        var md = result.markdown();
        assertThat(md.length()).isLessThanOrEqualTo(HtmlToMarkdownConverter.MAX_MARKDOWN_CHARS);
        // Cut at a newline: every surviving line is a complete **xxx…x** run.
        assertThat(md.lines().filter(l -> !l.isBlank())
                .allMatch(l -> l.equals("**" + "x".repeat(200) + "**"))).isTrue();
    }
}
