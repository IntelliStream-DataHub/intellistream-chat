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

import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.XmlDeclaration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rewrites Word desktop's legacy Office HTML into clean semantic DOM the generic
 * {@link MarkdownEmitter} can read. The one structural lie it has to undo is lists:
 * Word's clipboard has no {@code <ul>}/{@code <ol>} at all — a bulleted list is a run of
 * flat {@code <p class=MsoListParagraph…>} paragraphs where the bullet is a literal glyph
 * ({@code ·} in Symbol font, or {@code 1.}) inside an {@code mso-list:Ignore} span framed
 * by conditional comments, and nesting exists only as {@code mso-list:l0 level2} in the
 * style attribute. Left alone, that pastes as paragraphs starting with a stray {@code ·}.
 *
 * <p>Everything here mutates the parsed body in place; inline formatting
 * ({@code <b>}, {@code font-style:italic} spans) is left for {@link StyleResolver}.
 */
final class WordDesktopNormalizer {

    private WordDesktopNormalizer() {
    }

    private static final Pattern MSO_LIST_LEVEL =
            Pattern.compile("mso-list:\\s*l(\\d+)\\s+level(\\d+)", Pattern.CASE_INSENSITIVE);

    /** A numbered glyph: "1." / "a)" / "(iv)" — anything else ("·", "o", "§", "-") is a bullet. */
    private static final Pattern ORDERED_GLYPH =
            Pattern.compile("^\\s*\\(?([0-9]{1,4}|[a-zA-Z]|[ivxlcdm]{1,7}|[IVXLCDM]{1,7})[.)]");

    static void normalize(Element body) {
        // Conditional comments first. Word emits both the comment form <!--[if !supportLists]-->
        // and the downlevel-revealed form <![if !supportLists]>; jsoup parses the former as a
        // Comment and the latter as a bogus comment/XmlDeclaration — remove every flavour.
        removeCommentNodes(body);
        // <o:p> is Word's paragraph-mark element; jsoup keeps it as an element named "o:p"
        // with either no content or a single &nbsp;.
        body.getElementsByTag("o:p").remove();
        rebuildLists(body);
        // Word separates blocks with paragraphs holding only a &nbsp; — noise, not spacing
        // the Markdown needs (blocks already get a blank line between them).
        for (var p : new ArrayList<>(body.select("p[class^=Mso]"))) {
            if (p.text().replace('\u00A0', ' ').isBlank() && p.select("img").isEmpty()) {
                p.remove();
            }
        }
    }

    private static void removeCommentNodes(Node node) {
        for (var i = node.childNodeSize() - 1; i >= 0; i--) {
            var child = node.childNode(i);
            if (child instanceof Comment || child instanceof XmlDeclaration) {
                child.remove();
            } else {
                removeCommentNodes(child);
            }
        }
    }

    // ---------- fake-list reconstruction ----------

    private record ListPara(Element p, int listId, int level, boolean ordered) {
    }

    private record OpenList(int level, Element list) {
    }

    private static void rebuildLists(Element body) {
        var paras = new ArrayList<ListPara>();
        for (var p : body.select("p")) {
            var m = MSO_LIST_LEVEL.matcher(p.attr("style"));
            if (!m.find()) continue;
            paras.add(new ListPara(p, Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), hasOrderedGlyph(p)));
        }
        // Group maximal runs of sibling-adjacent paragraphs of the same Word list (the lN
        // id): a bulleted list directly followed by a numbered one is two adjacent runs,
        // and merging them would turn the numbers into bullets.
        var i = 0;
        while (i < paras.size()) {
            var j = i;
            while (j + 1 < paras.size()
                    && paras.get(j).p().nextElementSibling() == paras.get(j + 1).p()
                    && paras.get(j).listId() == paras.get(j + 1).listId()) {
                j++;
            }
            buildRun(paras.subList(i, j + 1));
            i = j + 1;
        }
    }

    private static boolean hasOrderedGlyph(Element p) {
        var glyph = findGlyph(p);
        return glyph != null && ORDERED_GLYPH.matcher(glyph.text()).find();
    }

    /** The {@code mso-list:Ignore} span holding the visible bullet/number and its spacer. */
    private static Element findGlyph(Element p) {
        for (var el : p.select("[style*=mso-list]")) {
            if (el.attr("style").toLowerCase().contains("ignore")) {
                return el;
            }
        }
        return null;
    }

    private static void buildRun(List<ListPara> run) {
        var first = run.get(0);
        var root = new Element(first.ordered() ? "ol" : "ul");
        first.p().before(root);

        var stack = new ArrayDeque<OpenList>();
        stack.push(new OpenList(first.level(), root));
        for (var item : run) {
            while (stack.size() > 1 && stack.peek().level() > item.level()) {
                stack.pop();
            }
            if (stack.peek().level() < item.level()) {
                // Deeper level: open a sublist inside the last item of the current list
                // (or the list itself when it has no items yet — a run that starts deep).
                var parent = stack.peek().list();
                var lastLi = parent.children().isEmpty()
                        ? parent
                        : parent.child(parent.childrenSize() - 1);
                var sub = new Element(item.ordered() ? "ol" : "ul");
                lastLi.appendChild(sub);
                stack.push(new OpenList(item.level(), sub));
            }
            var glyph = findGlyph(item.p());
            if (glyph != null) {
                glyph.remove();
            }
            var li = new Element("li");
            for (var child : new ArrayList<>(item.p().childNodes())) {
                li.appendChild(child);
            }
            stack.peek().list().appendChild(li);
            item.p().remove();
        }
    }
}
