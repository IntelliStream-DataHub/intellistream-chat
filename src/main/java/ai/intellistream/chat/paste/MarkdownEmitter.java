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

import ai.intellistream.chat.paste.StyleResolver.Style;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Walks a cleaned-up DOM and emits the app's Markdown dialect — exactly the constructs the
 * composer toolbar produces and {@code MarkdownRenderer}'s safelist keeps, nothing else.
 * See {@link HtmlToMarkdownConverter} for the never-emit list and why it exists.
 *
 * <p>Alongside the text it counts <em>signals</em>: dialect constructs actually produced
 * (a bold run, a heading, a list, a table, a labelled link). Zero signals means the paste
 * had no formatting worth keeping and the caller should let the plain-text flavor through.
 * Monospace runs are counted apart and only folded in when the document also contains
 * non-mono text — a copy that is monospace in its entirety (VS Code, a terminal) styled
 * only with colour is somebody pasting code, and turning it into a wall of inline
 * code-spans would be worse than the plain text they expected.
 *
 * <p>One instance per conversion; not thread-safe, holds the signal counters.
 */
final class MarkdownEmitter {

    record Output(String markdown, int signals) {
    }

    private int signals = 0;
    private int monoSignals = 0;
    private boolean sawPlainText = false;

    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "pre",
            "table", "section", "article", "header", "footer", "main", "aside", "figure",
            "figcaption", "dl", "dt", "dd", "hr", "address", "fieldset", "details", "summary");

    /** Elements whose content must never reach the output. */
    private static final Set<String> SKIP_TAGS = Set.of(
            "script", "style", "head", "meta", "link", "title", "noscript", "iframe",
            "object", "embed", "svg", "canvas", "video", "audio", "source", "track",
            "button", "input", "select", "textarea", "template", "map", "area");

    private static final String BLOCK_SELECTOR =
            "p, div, h1, h2, h3, h4, h5, h6, ul, ol, blockquote, pre, table, section, "
                    + "article, header, footer, main, aside, figure, dl, hr, address";

    Output emit(Element body) {
        var blocks = blocksOf(body, Style.PLAIN);
        var rendered = new ArrayList<String>(blocks.size());
        for (var block : blocks) {
            if (!block.isEmpty()) {
                rendered.add(String.join("\n", block));
            }
        }
        var markdown = String.join("\n\n", rendered);
        return new Output(markdown, signals + (sawPlainText ? monoSignals : 0));
    }

    // ---------- block level ----------

    /** Each block is a list of lines; blocks are separated by a blank line when joined. */
    private List<List<String>> blocksOf(Element container, Style inherited) {
        var blocks = new ArrayList<List<String>>();
        var inline = new ArrayList<Node>();
        for (var node : container.childNodes()) {
            if (node instanceof Element el
                    && (BLOCK_TAGS.contains(el.normalName()) || hasBlockChild(el))) {
                flushInline(inline, inherited, blocks);
                if (BLOCK_TAGS.contains(el.normalName())) {
                    emitBlock(el, inherited, blocks);
                } else {
                    // An inline-tagged element wrapping block content. Google Docs wraps the
                    // whole payload in <b id="docs-internal-guid-…" style="font-weight:normal">,
                    // so this branch is what keeps a Docs paste from collapsing into one
                    // paragraph — recurse as a container, carrying the element's style.
                    blocks.addAll(blocksOf(el, StyleResolver.effective(el, inherited)));
                }
            } else {
                inline.add(node);
            }
        }
        flushInline(inline, inherited, blocks);
        return blocks;
    }

    private void emitBlock(Element el, Style inherited, List<List<String>> blocks) {
        switch (el.normalName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> emitHeading(el, inherited, blocks);
            case "ul", "ol" -> {
                var lines = new ArrayList<String>();
                emitList(el, inherited, lines, "");
                if (!lines.isEmpty()) {
                    blocks.add(lines);
                    signals++;
                }
            }
            case "blockquote" -> emitBlockquote(el, inherited, blocks);
            case "pre" -> emitFence(el, blocks);
            case "table" -> emitTable(el, inherited, blocks);
            // <hr> is not safelisted, so a --- would be deleted at render time; a Docs page
            // break or Word rule simply doesn't survive the trip. Skipped, never emitted.
            case "hr" -> { }
            default -> {
                // p, div, section, … — a container that holds either blocks or a paragraph.
                var style = StyleResolver.effective(el, inherited);
                if (hasBlockChild(el)) {
                    blocks.addAll(blocksOf(el, style));
                } else {
                    addParagraph(renderInline(el, style), blocks);
                }
            }
        }
    }

    private static boolean hasBlockChild(Element el) {
        return el.selectFirst(BLOCK_SELECTOR) != null;
    }

    private void flushInline(List<Node> inline, Style inherited, List<List<String>> blocks) {
        if (inline.isEmpty()) return;
        var runs = new ArrayList<Run>();
        for (var node : inline) {
            flatten(node, inherited, runs);
        }
        inline.clear();
        addParagraph(renderRuns(runs), blocks);
    }

    private void addParagraph(String text, List<List<String>> blocks) {
        if (text.isBlank()) return;
        var lines = new ArrayList<String>();
        for (var line : text.split("\n", -1)) {
            lines.add(escapeLineStart(line.strip()));
        }
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        if (!lines.isEmpty()) {
            blocks.add(lines);
        }
    }

    private void emitHeading(Element el, Style inherited, List<List<String>> blocks) {
        var text = renderInline(el, StyleResolver.effective(el, inherited))
                .replace("\\\n", " ").replace("\n", " ").strip();
        if (text.isEmpty()) return;
        var level = el.normalName().charAt(1) - '0';
        blocks.add(List.of("#".repeat(level) + " " + text));
        signals++;
    }

    private void emitBlockquote(Element el, Style inherited, List<List<String>> blocks) {
        var inner = blocksOf(el, StyleResolver.effective(el, inherited));
        if (inner.isEmpty()) return;
        var lines = new ArrayList<String>();
        for (var i = 0; i < inner.size(); i++) {
            if (i > 0) lines.add(">");
            for (var line : inner.get(i)) {
                lines.add(line.isEmpty() ? ">" : "> " + line);
            }
        }
        blocks.add(lines);
        signals++;
    }

    private void emitFence(Element el, List<List<String>> blocks) {
        var code = el.wholeText()
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        while (code.startsWith("\n")) code = code.substring(1);
        code = code.stripTrailing();
        if (code.isBlank()) return;
        var fence = "`".repeat(Math.max(3, longestBacktickRun(code) + 1));
        var lines = new ArrayList<String>();
        var lang = fenceLanguage(el);
        lines.add(fence + (lang == null ? "" : lang));
        for (var line : code.split("\n", -1)) {
            lines.add(line);
        }
        lines.add(fence);
        blocks.add(lines);
        signals++;
    }

    private static String fenceLanguage(Element pre) {
        var code = pre.selectFirst("code[class]");
        if (code == null) return null;
        for (var cls : code.classNames()) {
            if (cls.startsWith("language-")) {
                var lang = cls.substring("language-".length()).toLowerCase();
                if (lang.matches("[a-z0-9+#-]{1,30}")) return lang;
            }
        }
        return null;
    }

    // ---------- lists ----------

    private void emitList(Element listEl, Style inherited, List<String> out, String indent) {
        var ordered = listEl.normalName().equals("ol");
        var style = StyleResolver.effective(listEl, inherited);
        var n = 0;
        var lastMarkerWidth = 2;
        for (var child : listEl.children()) {
            switch (child.normalName()) {
                case "li" -> {
                    n++;
                    var marker = ordered ? n + ". " : "- ";
                    lastMarkerWidth = marker.length();
                    emitListItem(child, style, marker, out, indent);
                }
                // A sublist as a direct child of a list (Docs emits </li><ul>…) belongs
                // under the item before it — indent by that item's marker width.
                case "ul", "ol" -> emitList(child, style, out, indent + " ".repeat(lastMarkerWidth));
                default -> { }
            }
        }
    }

    private void emitListItem(Element li, Style inherited, String marker, List<String> out, String indent) {
        var blocks = blocksOf(li, StyleResolver.effective(li, inherited));
        var lines = new ArrayList<String>();
        for (var block : blocks) {
            lines.addAll(block);
        }
        if (lines.isEmpty()) return;
        var cont = indent + " ".repeat(marker.length());
        out.add(indent + marker + lines.get(0));
        for (var i = 1; i < lines.size(); i++) {
            out.add(lines.get(i).isEmpty() ? "" : cont + lines.get(i));
        }
    }

    // ---------- tables ----------

    private void emitTable(Element table, Style inherited, List<List<String>> blocks) {
        var style = StyleResolver.effective(table, inherited);
        var rows = new ArrayList<List<String>>();
        var headerFromTh = false;
        for (var tr : table.select("tr")) {
            // A nested table's rows also match the select; flatten them into their cell's
            // text instead of splicing foreign rows into this table.
            if (closestTable(tr) != table) continue;
            var cells = new ArrayList<String>();
            var anyTh = false;
            for (var cell : tr.children()) {
                var tag = cell.normalName();
                if (!tag.equals("td") && !tag.equals("th")) continue;
                anyTh |= tag.equals("th");
                cells.add(renderInline(cell, StyleResolver.effective(cell, style))
                        .replace("\\\n", " ").replace("\n", " ").strip());
            }
            if (cells.isEmpty()) continue;
            if (rows.isEmpty()) headerFromTh = anyTh;
            rows.add(cells);
        }
        if (rows.isEmpty()) return;
        var cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0) return;

        var lines = new ArrayList<String>();
        // Markdown requires a header row; when the table has none, the first data row is
        // promoted — the alternative (an injected empty header) wastes a line saying nothing.
        lines.add(tableRow(rows.get(0), cols));
        lines.add("| " + "--- | ".repeat(cols).strip());
        for (var i = 1; i < rows.size(); i++) {
            lines.add(tableRow(rows.get(i), cols));
        }
        // headerFromTh only documents why the first row was chosen; either way it leads.
        blocks.add(lines);
        signals++;
    }

    private static Element closestTable(Element el) {
        for (var p = el.parent(); p != null; p = p.parent()) {
            if (p.normalName().equals("table")) return p;
        }
        return null;
    }

    private static String tableRow(List<String> cells, int cols) {
        var sb = new StringBuilder("|");
        for (var i = 0; i < cols; i++) {
            var cell = i < cells.size() ? cells.get(i) : "";
            sb.append(' ').append(cell.isEmpty() ? " " : cell).append(" |");
        }
        return sb.toString();
    }

    // ---------- inline level ----------

    private sealed interface Run permits TextRun, RawRun, BreakRun {
    }

    private record TextRun(String text, Style style) implements Run {
    }

    /** An already-rendered Markdown fragment (links) — appended verbatim, never re-escaped. */
    private record RawRun(String markdown) implements Run {
    }

    private record BreakRun() implements Run {
    }

    private String renderInline(Element el, Style style) {
        var runs = new ArrayList<Run>();
        for (var node : el.childNodes()) {
            flatten(node, style, runs);
        }
        return renderRuns(runs);
    }

    private void flatten(Node node, Style style, List<Run> out) {
        if (node instanceof TextNode tn) {
            var text = tn.getWholeText();
            if (!text.isBlank() && !style.mono()) {
                sawPlainText = true;
            }
            out.add(new TextRun(text, style));
        } else if (node instanceof Element el) {
            var tag = el.normalName();
            if (SKIP_TAGS.contains(tag)) return;
            switch (tag) {
                case "br" -> out.add(new BreakRun());
                // <img> is not safelisted — ![…] would render as literal text after the
                // sanitizer deletes the tag, so images are dropped, not linked.
                case "img", "picture" -> { }
                case "a" -> flattenLink(el, style, out);
                default -> {
                    var s = StyleResolver.effective(el, style);
                    for (var child : el.childNodes()) {
                        flatten(child, s, out);
                    }
                }
            }
        }
    }

    private void flattenLink(Element a, Style style, List<Run> out) {
        var href = cleanHref(a.attr("href"));
        var inner = new ArrayList<Run>();
        var innerStyle = StyleResolver.effective(a, style);
        for (var child : a.childNodes()) {
            flatten(child, innerStyle, inner);
        }
        if (href == null) {
            // Not a usable http(s) link — keep the text, drop the wrapper.
            out.addAll(inner);
            return;
        }
        var text = renderRuns(inner).replace("\\\n", " ").replace("\n", " ").strip();
        if (text.isEmpty()) return;
        if (bareUrlEquals(text, href)) {
            // The autolink extension turns a bare URL into a link on its own; a [x](x)
            // wrapper would only add noise. Not a signal — plain text carries it equally.
            out.add(new RawRun(href));
            return;
        }
        out.add(new RawRun("[" + text + "](" + href + ")"));
        signals++;
    }

    private static boolean bareUrlEquals(String text, String href) {
        return text.equals(href)
                || (href.endsWith("/") && text.equals(href.substring(0, href.length() - 1)))
                || (text.endsWith("/") && href.equals(text.substring(0, text.length() - 1)));
    }

    private static String cleanHref(String href) {
        if (href == null) return null;
        href = href.strip();
        var lower = href.toLowerCase();
        if (lower.startsWith("https://www.google.com/url?")) {
            // Google Docs wraps every link in its redirector; the reader deserves the real URL.
            var unwrapped = queryParam(href, "q");
            if (unwrapped == null) unwrapped = queryParam(href, "url");
            if (unwrapped != null) {
                href = unwrapped;
                lower = href.toLowerCase();
            }
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;
        // Characters that would end the (...) destination early or break a table row.
        return href.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
                .replace("|", "%7C").replace("<", "%3C").replace(">", "%3E");
    }

    private static String queryParam(String url, String name) {
        var q = url.indexOf('?');
        if (q < 0) return null;
        for (var pair : url.substring(q + 1).split("&")) {
            var eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                var value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                var vl = value.toLowerCase();
                if (vl.startsWith("http://") || vl.startsWith("https://")) return value;
            }
        }
        return null;
    }

    private String renderRuns(List<Run> runs) {
        // Merge adjacent equal-style text runs first: Docs splits a single bold word across
        // spans, and without the merge "Hel" + "lo" would come out as **Hel****lo**.
        var merged = new ArrayList<Run>(runs.size());
        for (var run : runs) {
            if (run instanceof TextRun t && !merged.isEmpty()
                    && merged.get(merged.size() - 1) instanceof TextRun prev
                    && prev.style().equals(t.style())) {
                merged.set(merged.size() - 1, new TextRun(prev.text() + t.text(), t.style()));
            } else {
                merged.add(run);
            }
        }

        var sb = new StringBuilder();
        for (var run : merged) {
            if (run instanceof BreakRun) {
                while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ' ') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                if (!sb.isEmpty()) {
                    // A bare newline renders as a space, so a line break has to be the
                    // hard-break form: backslash + newline.
                    sb.append("\\\n");
                }
            } else if (run instanceof RawRun raw) {
                sb.append(raw.markdown());
            } else if (run instanceof TextRun t) {
                appendTextRun(sb, t);
            }
        }
        return sb.toString();
    }

    private void appendTextRun(StringBuilder sb, TextRun run) {
        var text = run.text().replace('\u00A0', ' ').replaceAll("[ \\t\\r\\n\\f]+", " ");
        if (text.isBlank()) {
            if (!sb.isEmpty() && !endsWithWhitespace(sb)) sb.append(' ');
            return;
        }
        // Emphasis delimiters must hug the text — "** bold **" is not emphasis in
        // CommonMark — so edge whitespace moves outside the markers.
        var lead = text.startsWith(" ") && !sb.isEmpty() && !endsWithWhitespace(sb) ? " " : "";
        var trail = text.endsWith(" ") ? " " : "";
        var core = text.strip();
        var s = run.style();

        String wrapped;
        if (s.mono()) {
            wrapped = codeSpan(core);
            monoSignals++;
        } else {
            wrapped = escapeInline(core);
            if (s.bold() || s.italic() || s.strike()) {
                signals++;
            }
        }
        if (s.strike()) wrapped = "~~" + wrapped + "~~";
        if (s.italic()) wrapped = "_" + wrapped + "_";
        if (s.bold()) wrapped = "**" + wrapped + "**";

        sb.append(lead).append(wrapped).append(trail);
    }

    private static boolean endsWithWhitespace(StringBuilder sb) {
        var c = sb.charAt(sb.length() - 1);
        return c == ' ' || c == '\n';
    }

    private static String codeSpan(String core) {
        var delim = "`".repeat(longestBacktickRun(core) + 1);
        var pad = core.startsWith("`") || core.endsWith("`") ? " " : "";
        return delim + pad + core + pad + delim;
    }

    private static int longestBacktickRun(String s) {
        var longest = 0;
        var current = 0;
        for (var i = 0; i < s.length(); i++) {
            current = s.charAt(i) == '`' ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    // ---------- escaping ----------

    /**
     * Escapes everything with an inline Markdown meaning, plus {@code <} and {@code &}:
     * the renderer passes raw inline HTML through to the sanitizer, so a pasted literal
     * "&lt;b&gt;" would otherwise come back as actual bold, and "&amp;copy;" as ©.
     */
    private static String escapeInline(String s) {
        var sb = new StringBuilder(s.length() + 8);
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '\\', '*', '_', '`', '~', '|', '[', ']' -> sb.append('\\').append(c);
                case '<' -> sb.append("\\<");
                case '&' -> sb.append("\\&");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final Pattern ORDERED_START = Pattern.compile("^(\\d{1,9})([.)])(\\s.*|$)");

    /**
     * A line of plain prose must not accidentally start a block construct: "#include",
     * "> /dev/null", "- item", "1. point", or a dash run that would setext-ify the line
     * above. Applied after inline escaping, before any list/quote prefix is added.
     */
    private static String escapeLineStart(String line) {
        if (line.isEmpty()) return line;
        var c = line.charAt(0);
        if (c == '#' || c == '>') {
            return "\\" + line;
        }
        if (c == '-' || c == '+') {
            if (line.length() == 1 || line.charAt(1) == ' '
                    || line.chars().allMatch(ch -> ch == c)) {
                return "\\" + line;
            }
            return line;
        }
        if (c == '=' && line.chars().allMatch(ch -> ch == '=')) {
            return "\\" + line;
        }
        var m = ORDERED_START.matcher(line);
        if (m.matches()) {
            return m.group(1) + "\\" + m.group(2) + m.group(3);
        }
        return line;
    }
}
