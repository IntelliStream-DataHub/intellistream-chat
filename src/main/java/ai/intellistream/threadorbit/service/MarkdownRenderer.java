/*
 * Copyright 2026 Olav Gjerde
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

package ai.intellistream.threadorbit.service;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final Safelist safelist;
    private final MentionService mentionService;

    @Autowired
    public MarkdownRenderer(MentionService mentionService) {
        this.mentionService = mentionService;
        var extensions = List.of(TablesExtension.create(), AutolinkExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).build();
        this.safelist = Safelist.basic()
                .addTags("h1", "h2", "h3", "h4", "h5", "h6", "pre", "table", "thead", "tbody", "tr", "th", "td", "span")
                .addAttributes("a", "rel", "target")
                .addAttributes("code", "class")
                .addAttributes("pre", "class")
                .addAttributes("span", "class", "data-username");
    }

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        var node = parser.parse(markdown);
        var rawHtml = renderer.render(node);
        var clean = Jsoup.clean(rawHtml, safelist);
        var hardened = hardenAnchors(clean);
        var embedded = embedVideos(hardened);
        return decorateMentions(embedded, mentionService.resolvedUsernames(markdown));
    }

    /**
     * Force {@code rel="noopener noreferrer"} and {@code target="_blank"} on every rendered link
     * so a user-pasted URL can't grab {@code window.opener} or leak the referrer.
     */
    private static String hardenAnchors(String html) {
        if (!html.contains("<a ")) return html;
        var doc = Jsoup.parseBodyFragment(html);
        for (var a : doc.select("a[href]")) {
            a.attr("rel", "noopener noreferrer nofollow");
            a.attr("target", "_blank");
        }
        return doc.body().html();
    }

    // Subdomain is optional and may be www / m (mobile share URLs) / music — all cover the
    // same video catalogue. The video id captured by group(1) is fed into the
    // youtube-nocookie embed URL regardless of which entry path the user pasted.
    private static final String YT_HOST = "(?:www\\.|m\\.|music\\.)?youtube\\.com";
    private static final Pattern YT_WATCH = Pattern.compile(
            "^https?://" + YT_HOST + "/watch\\?(?:[^#]*&)?v=([A-Za-z0-9_-]{6,20})");
    /** Short-domain links — youtu.be/ID — usually the result of the YouTube share button. */
    private static final Pattern YT_BE = Pattern.compile(
            "^https?://(?:www\\.)?youtu\\.be/([A-Za-z0-9_-]{6,20})");
    private static final Pattern YT_EMBED = Pattern.compile(
            "^https?://" + YT_HOST + "/embed/([A-Za-z0-9_-]{6,20})");
    /** Vertical-format Shorts (different URL path from /watch but the same embed endpoint). */
    private static final Pattern YT_SHORTS = Pattern.compile(
            "^https?://" + YT_HOST + "/shorts/([A-Za-z0-9_-]{6,20})");
    private static final Pattern VIMEO = Pattern.compile(
            "^https?://(?:www\\.)?vimeo\\.com/(?:video/)?(\\d{6,12})");

    /**
     * Append a responsive iframe embed after any anchor whose href matches a YouTube or Vimeo
     * URL. Runs after the safelist clean (the inserted iframe is trusted markup we generate
     * here, not user input) and before mention decoration. CSP {@code frame-src} on the web
     * filter chain explicitly allows the YouTube and Vimeo embed origins.
     */
    private static String embedVideos(String html) {
        if (html == null || html.isEmpty() || !html.contains("href=")) return html;
        var doc = Jsoup.parseBodyFragment(html);
        for (var a : doc.select("a[href]")) {
            // Don't double-embed if this anchor is already inside (or right next to) one of our wrappers.
            var next = a.nextElementSibling();
            if (next != null && next.hasClass("video-embed-wrapper")) continue;

            var href = a.attr("href");
            // Shorts get their own branch so the wrapper can carry data-orientation="vertical",
            // which the stylesheet uses to render a 9:16 aspect-ratio frame instead of the
            // landscape default. Regular /watch, /embed, and youtu.be URLs all funnel through
            // matchFirst below and stay landscape.
            var shortsId = matchFirst(href, YT_SHORTS);
            if (shortsId != null) {
                a.after(buildEmbed("https://www.youtube-nocookie.com/embed/" + shortsId,
                        "YouTube Short", "vertical"));
                continue;
            }
            var ytId = matchFirst(href, YT_WATCH, YT_BE, YT_EMBED);
            if (ytId != null) {
                a.after(buildEmbed("https://www.youtube-nocookie.com/embed/" + ytId, "YouTube video", null));
                continue;
            }
            var vmId = matchFirst(href, VIMEO);
            if (vmId != null) {
                a.after(buildEmbed("https://player.vimeo.com/video/" + vmId, "Vimeo video", null));
            }
        }
        return doc.body().html();
    }

    private static String matchFirst(String url, Pattern... patterns) {
        if (url == null) return null;
        for (var p : patterns) {
            var m = p.matcher(url);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String buildEmbed(String src, String title, String orientation) {
        // src and title are constructed from a regex-matched id and a fixed string, so no
        // untrusted content. orientation is one of {null, "vertical"} — the stylesheet
        // selects on data-orientation="vertical" for the 9:16 Shorts frame.
        var dataAttr = orientation == null ? "" : " data-orientation=\"" + orientation + "\"";
        return "<div class=\"video-embed-wrapper\"" + dataAttr + ">"
                + "<iframe class=\"video-embed\" src=\"" + src + "\" "
                + "title=\"" + title + "\" loading=\"lazy\" allowfullscreen "
                + "allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture\""
                + "></iframe></div>";
    }

    /**
     * After CommonMark + jsoup-clean, walk the text nodes and wrap any {@code @username} that
     * resolves to a known user in a {@code <span class="mention" data-username="x">} tag. We
     * don't touch text inside {@code <code>} / {@code <pre>} so code samples stay untouched.
     */
    private String decorateMentions(String html, Set<String> knownUsernames) {
        if (knownUsernames.isEmpty()) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        decorateRecursively(doc.body(), knownUsernames);
        return doc.body().html();
    }

    private void decorateRecursively(Element parent, Set<String> known) {
        var children = parent.childNodes();
        for (int i = 0; i < children.size(); i++) {
            var node = children.get(i);
            if (node instanceof TextNode tn) {
                var replaced = decorateText(tn.getWholeText(), known);
                if (replaced != null) {
                    var fragment = Jsoup.parseBodyFragment(replaced).body();
                    var newNodes = new java.util.ArrayList<>(fragment.childNodes());
                    tn.remove();
                    for (int j = 0; j < newNodes.size(); j++) {
                        parent.insertChildren(i + j, newNodes.get(j));
                    }
                    children = parent.childNodes();
                    i += newNodes.size() - 1;
                }
            } else if (node instanceof Element el) {
                var tag = el.tagName().toLowerCase();
                if (!tag.equals("code") && !tag.equals("pre") && !tag.equals("a") && !tag.equals("span")) {
                    decorateRecursively(el, known);
                }
            }
        }
    }

    private String decorateText(String text, Set<String> known) {
        Matcher m = MentionService.MENTION.matcher(text);
        if (!m.find()) return null;
        m.reset();
        var sb = new StringBuilder();
        int last = 0;
        boolean any = false;
        while (m.find()) {
            var handle = m.group(1);
            var lc = handle.toLowerCase();
            if (!known.contains(lc)) continue;
            sb.append(escape(text, last, m.start()));
            sb.append("<span class=\"mention\" data-username=\"").append(escapeAttr(handle)).append("\">@")
              .append(escapeText(handle)).append("</span>");
            last = m.end();
            any = true;
        }
        if (!any) return null;
        sb.append(escape(text, last, text.length()));
        return sb.toString();
    }

    private static String escape(String s, int from, int to) { return escapeText(s.substring(from, to)); }
    private static String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String escapeAttr(String s) {
        return escapeText(s).replace("\"", "&quot;");
    }
}
