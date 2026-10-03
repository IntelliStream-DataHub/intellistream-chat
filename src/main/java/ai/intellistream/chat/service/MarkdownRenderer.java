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

package ai.intellistream.chat.service;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
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
        // Strikethrough is not optional decoration: the composer toolbar has always had an S
        // button that wraps the selection in ~~, so without the extension the app shipped a
        // control that produced literal tildes in the rendered message.
        var extensions = List.of(TablesExtension.create(), AutolinkExtension.create(),
                StrikethroughExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).build();
        // NB: `span` is deliberately stripped (removeTags — Safelist.basic() allows it by default).
        // Mention spans are added by decorateMentions() AFTER this clean, so a user can't hand-write
        // <span class="mention" data-username="admin"> in raw markdown to forge a mention of someone
        // they didn't actually @-mention (N29).
        this.safelist = Safelist.basic()
                .removeTags("span")
                // `del` carries the strikethrough extension's output. Safelist.basic() allows
                // `strike` but not `del`, so without this the sanitizer would quietly remove
                // the very markup the extension was added to produce.
                .addTags("h1", "h2", "h3", "h4", "h5", "h6", "pre", "table", "thead", "tbody", "tr", "th", "td", "del")
                .addAttributes("a", "rel", "target")
                .addAttributes("code", "class")
                .addAttributes("pre", "class");
    }

    /**
     * Which room the rendered message is in. It changes exactly one thing — the hover text on a
     * broadcast mention pill, which has to name the audience it actually reached — and it is an
     * argument rather than a second renderer because everything else about rendering is identical
     * and a second implementation of Markdown-plus-sanitiser is not something this codebase should
     * have two of.
     */
    public enum Room {
        /** A channel: {@code @channel} reaches its members via {@code message_mentions}. */
        CHANNEL,
        /** A direct or group conversation: {@code @channel} reaches everyone in it. */
        CONVERSATION
    }

    public String render(String markdown) {
        return render(markdown, Room.CHANNEL);
    }

    /** As {@link #render(String)}, for a message in a direct or group conversation. */
    public String renderInConversation(String markdown) {
        return render(markdown, Room.CONVERSATION);
    }

    public String render(String markdown, Room room) {
        var hardened = toSafeHtml(markdown);
        if (hardened.isEmpty()) return hardened;
        return decorateMentions(hardened, mentionService.resolvedUsernames(markdown), room);
    }

    /**
     * As {@link #render(String)}, for markdown that is <b>not</b> a message — today the rendered
     * preview of an uploaded {@code .md} file (see
     * {@link ai.intellistream.chat.attachments.MarkdownAttachments}).
     *
     * <p>Same parse, same safelist, same hardened anchors; what it leaves out is the mention pass,
     * for two reasons. An {@code @name} in a file notified nobody — nothing wrote a
     * {@code message_mentions} row for it — so painting it as a mention pill would be the UI
     * claiming something untrue. And the pass costs one user lookup per distinct handle in the
     * text, which is a fine price for a chat message and a bad one for a document that may hold
     * thousands.
     */
    public String renderDocument(String markdown) {
        return toSafeHtml(markdown);
    }

    /** CommonMark, then the safelist, then hardened anchors. Everything the two paths share. */
    private String toSafeHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        var node = parser.parse(markdown);
        var rawHtml = renderer.render(node);
        var clean = Jsoup.clean(rawHtml, safelist);
        return hardenAnchors(clean);
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

    /*
     * A video link used to get an <iframe> injected here, right after its anchor. It doesn't any
     * more: the player is now a click-to-play facade built by the client from
     * LinkPreviewDto.video (see linkpreview/VideoLinks for the whole reasoning). Two things that
     * were true of the old arrangement are worth keeping in mind before anyone re-adds it — the
     * embed contacted YouTube on render, which is exactly what the link-preview card copies
     * pictures server-side to avoid; and an <iframe> in the body makes the body impossible to run
     * through the browser's own sanitizer, since Element.setHTML() drops iframes unconditionally.
     */

    /**
     * After CommonMark + jsoup-clean, walk the text nodes and wrap any {@code @username} that
     * resolves to a known user in a {@code <span class="mention" data-username="x">} tag, and any
     * broadcast handle ({@code @channel} / {@code @here} / {@code @everyone}) in a
     * {@code <span class="mention mention-broadcast" data-mention="…">}. We don't touch text inside
     * {@code <code>} / {@code <pre>} so code samples stay untouched.
     *
     * <p>Runs after the safelist clean, which strips {@code span} outright — so these tags are ours
     * by construction and a hand-written mention span in raw markdown cannot survive to forge one
     * (N29). Nothing sanitizes after this point, hence the explicit escaping below.
     *
     * <p>A handle that is neither a known user nor a broadcast is left as bare text, which is the
     * visible difference between a mention that will notify somebody and one that will not.
     */
    private String decorateMentions(String html, Set<String> knownUsernames, Room room) {
        // Every mention starts with '@'. Note the second check is no longer just "are there known
        // usernames": a body whose only mention is @channel has none and still has work to do. It
        // stays a string scan rather than becoming an unconditional walk, because this runs on the
        // send path for every message and the common miss is an email address — one linear pass to
        // rule that out, instead of a jsoup parse plus a full tree walk to find nothing.
        if (html == null || html.indexOf('@') < 0) return html;
        if (knownUsernames.isEmpty() && !BROADCAST_WORD.matcher(html).find()) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        decorateRecursively(doc.body(), knownUsernames, room);
        return doc.body().html();
    }

    private void decorateRecursively(Element parent, Set<String> known, Room room) {
        var children = parent.childNodes();
        for (int i = 0; i < children.size(); i++) {
            var node = children.get(i);
            if (node instanceof TextNode tn) {
                var replaced = decorateText(tn.getWholeText(), known, room);
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
                    decorateRecursively(el, known, room);
                }
            }
        }
    }

    private String decorateText(String text, Set<String> known, Room room) {
        Matcher m = MentionService.MENTION.matcher(text);
        if (!m.find()) return null;
        m.reset();
        var sb = new StringBuilder();
        int last = 0;
        boolean any = false;
        while (m.find()) {
            var handle = m.group(1);
            var lc = handle.toLowerCase();
            var broadcast = MentionService.broadcastFor(lc);
            if (broadcast == null && !known.contains(lc)) continue;
            sb.append(escape(text, last, m.start()));
            if (broadcast != null) {
                // data-mention carries the *audience*, not the word typed, so a client reading it
                // sees that @everyone reached the channel. The title says the same thing in prose,
                // because "@everyone behaves as @channel here" is a decision the reader of the
                // message is entitled to see rather than a convention they have to know.
                sb.append("<span class=\"mention mention-broadcast\" data-mention=\"")
                  .append(broadcast.audience().handle()).append("\" title=\"")
                  .append(escapeAttr(broadcastTitle(broadcast, room))).append("\">@")
                  .append(escapeText(handle)).append("</span>");
            } else {
                sb.append("<span class=\"mention\" data-username=\"").append(escapeAttr(handle)).append("\">@")
                  .append(escapeText(handle)).append("</span>");
            }
            last = m.end();
            any = true;
        }
        if (!any) return null;
        sb.append(escape(text, last, text.length()));
        return sb.toString();
    }

    /**
     * Whether the rendered HTML could contain a broadcast handle at all. Deliberately loose — it can
     * match inside a code span, which the tree walk then correctly declines to decorate (N21) — its
     * only job is to keep a body with an '@' and no mention from paying for a parse.
     */
    private static final Pattern BROADCAST_WORD =
            Pattern.compile("(?i)@(?:channel|here|everyone)\\b");

    /**
     * Hover text for a broadcast pill: what this handle, in this room, actually reached.
     *
     * <p>It used to say "channel" everywhere, and in a direct message that was two lies at once —
     * there is no channel, and nothing was notified, because {@code message_mentions} is
     * channel-only. The second half is no longer true: a conversation needs no fan-out table to
     * answer "who is everyone here", since {@code ConversationAlertPublisher} is already iterating
     * exactly that set, so {@code @channel} in a group DM reaches everyone in it. The wording had to
     * follow, because a pill that names the wrong room is the first thing a reader will disbelieve.
     */
    private static String broadcastTitle(MentionService.Broadcast broadcast, Room room) {
        if (room == Room.CONVERSATION) {
            return switch (broadcast) {
                case CHANNEL -> "Notifies everyone in this conversation";
                case HERE -> "Notifies the people here who are online right now";
                case EVERYONE -> "@everyone works like @channel here: it notifies everyone in this conversation";
            };
        }
        return switch (broadcast) {
            case CHANNEL -> "Notifies every member of this channel";
            case HERE -> "Notifies the members who are online right now";
            case EVERYONE -> "@everyone works like @channel here: it notifies every member of this channel";
        };
    }

    private static String escape(String s, int from, int to) { return escapeText(s.substring(from, to)); }
    private static String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String escapeAttr(String s) {
        return escapeText(s).replace("\"", "&quot;");
    }
}
