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

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the {@code text/html} clipboard flavor of a rich-text paste (Google Docs, Apple
 * Notes, Word Online, Word desktop) into the Markdown dialect this app actually renders.
 *
 * <p>The dialect is deliberately the one the composer toolbar produces and nothing more,
 * because {@code MarkdownRenderer}'s jsoup safelist silently deletes everything else at
 * display time: no image syntax ({@code <img>} is not safelisted, so {@code ![…]} would
 * render as an empty hole), no {@code ---} thematic breaks, no {@code ol} start offsets,
 * no table alignment or cell merging, and no raw HTML. Emitting only what survives is the
 * whole contract — {@code PasteRoundTripTest} pins it against the real renderer.
 *
 * <p>{@link #convert} never throws. Any failure, oversize input, or content with no
 * formatting worth keeping returns {@link Result#notConvertible()}, and the client then
 * pastes the plain-text flavor it already holds — a paste can be degraded, never lost.
 */
@Component
public class HtmlToMarkdownConverter {

    private static final Logger log = LoggerFactory.getLogger(HtmlToMarkdownConverter.class);

    /**
     * How much clipboard HTML we are willing to parse. Word and Docs emit span-per-word
     * markup, so a few pages of real content measures a few hundred KB. Not tied to
     * {@code ichat.link-previews.max-html-bytes} — that one answers a different question (how far
     * into a fetched page the {@code <head>} might be, which on YouTube is ~700 KB in) and the two
     * moved apart when it was raised.
     */
    public static final int MAX_HTML_CHARS = 524_288;

    /** The message body cap ({@code SendMessageRequest} and friends enforce the same 8000). */
    public static final int MAX_MARKDOWN_CHARS = 8_000;

    public record Result(boolean convertible, String markdown) {
        public static Result notConvertible() {
            return new Result(false, null);
        }
    }

    public Result convert(String html) {
        try {
            if (html == null || html.isBlank() || html.length() > MAX_HTML_CHARS) {
                return Result.notConvertible();
            }
            // Full-document parse, not a fragment: the Windows clipboard wraps the payload
            // in <html><body><!--StartFragment-->…<!--EndFragment--> and Word ships a whole
            // <head> with a <style> block.
            var body = Jsoup.parse(html).body();
            if (looksLikeWordDesktop(html)) {
                WordDesktopNormalizer.normalize(body);
            }
            var out = new MarkdownEmitter().emit(body);
            // signals == 0 is the authoritative "not worth converting" answer: nothing in
            // the HTML maps to a dialect construct. This is what keeps a plain-text-in-a-div
            // paste, or a VS Code copy whose only styling is per-token colour, untouched.
            if (out.signals() == 0) {
                return Result.notConvertible();
            }
            var md = tidy(out.markdown());
            if (md.isBlank()) {
                return Result.notConvertible();
            }
            return new Result(true, md);
        } catch (Exception e) {
            // The server half of the "any failure → plain text" guarantee.
            log.debug("Paste conversion failed; client will fall back to plain text", e);
            return Result.notConvertible();
        }
    }

    /**
     * Word desktop is the one source whose HTML has to be rewritten before the generic
     * emitter can read it (fake lists, conditional comments, o:p elements). The markers are
     * unambiguous and the normalizer is harmless on anything else, so a rare false positive
     * costs nothing.
     */
    private static boolean looksLikeWordDesktop(String html) {
        return html.contains("urn:schemas-microsoft-com")
                || html.contains("mso-")
                || html.contains("supportLists");
    }

    private static String tidy(String markdown) {
        var md = markdown.replaceAll("\n{3,}", "\n\n").strip();
        if (md.length() > MAX_MARKDOWN_CHARS) {
            // Cut at a line boundary so we never leave half a table row or list marker;
            // a single line longer than the cap falls back to a hard cut.
            var cut = md.lastIndexOf('\n', MAX_MARKDOWN_CHARS);
            md = (cut > 0 ? md.substring(0, cut) : md.substring(0, MAX_MARKDOWN_CHARS)).strip();
        }
        return md;
    }
}
