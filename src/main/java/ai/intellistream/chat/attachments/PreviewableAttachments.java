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

package ai.intellistream.chat.attachments;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Which uploaded files can be shown in the page, and how their text is read.
 *
 * <p>An attachment is otherwise opaque: {@code AttachmentRestController.download} refuses
 * {@code inline} disposition for everything but images, precisely so user-supplied bytes never
 * render as a document in this origin. The two kinds here are the exceptions, and each earns it
 * differently — a markdown file is rendered to sanitised HTML server-side and never reaches the
 * browser as a file at all; an HTML file is shown as itself, inside a sandboxed iframe with no
 * script and no origin. Adding a third kind means answering the same question: what stops the
 * uploader's bytes from acting as this application?
 *
 * <p><b>The name alone is not enough.</b> {@code report.md} holding a ZIP is a ZIP — Tika sniffs
 * the bytes at upload (see {@link AttachmentBytes#sniffContentType}), so the stored content type
 * is a fact about the content while the filename is only a claim about it. A kind is recognised
 * when the type says so outright, or when the type says "some kind of text" <em>and</em> the name
 * agrees; that second case is the ordinary one for markdown, since Tika's answer for plain text
 * depends on which magic it matched.
 */
public final class PreviewableAttachments {

    /**
     * How much of the file is shown. A preview is a look at a document, not a document viewer:
     * past the cap the reader gets a truncation notice and the download button, which is a better
     * answer than a tab spending a second on a megabyte of CommonMark every time it is opened.
     */
    public static final int MAX_MARKDOWN_CHARS = 128 * 1024;

    /** HTML is transported, not parsed, so it can afford a larger slice than markdown. */
    public static final int MAX_HTML_CHARS = 512 * 1024;

    /** Types Tika reports for markdown. {@code text/x-web-markdown} is its older spelling. */
    private static final Set<String> MARKDOWN_TYPES =
            Set.of("text/markdown", "text/x-markdown", "text/x-web-markdown");

    private static final Set<String> MARKDOWN_SUFFIXES = Set.of(".md", ".markdown");

    private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");

    private static final Set<String> HTML_SUFFIXES = Set.of(".html", ".htm", ".xhtml");

    private PreviewableAttachments() {}

    /**
     * What a previewable attachment is, and — through {@link #slug()} — both the endpoint that
     * serves it and the word the client switches on. One enum drives all three so a new kind
     * cannot be half-added: a server that offers {@code previewKind} the client doesn't know
     * shows a button that opens nothing.
     */
    public enum Kind {
        /** Rendered server-side to sanitised HTML, exactly like a message body. */
        MARKDOWN("markdown", MAX_MARKDOWN_CHARS),
        /** Shown as authored, inside a sandboxed iframe — see {@code HtmlPreviewDto}. */
        HTML("html", MAX_HTML_CHARS);

        private final String slug;
        private final int maxChars;

        Kind(String slug, int maxChars) {
            this.slug = slug;
            this.maxChars = maxChars;
        }

        /** Last path segment of the preview endpoint, and the DTO's {@code previewKind}. */
        public String slug() {
            return slug;
        }

        public int maxChars() {
            return maxChars;
        }
    }

    /**
     * How this attachment can be shown, or null when it can't be.
     *
     * @param filename    the uploader's filename, may be null
     * @param contentType the <em>sniffed</em> content type stored on the row, may be null for rows
     *                    predating sniffing — treated as text, so the filename decides
     */
    public static Kind kindOf(String filename, String contentType) {
        var type = bareType(contentType);
        if (MARKDOWN_TYPES.contains(type)) return Kind.MARKDOWN;
        if (HTML_TYPES.contains(type)) return Kind.HTML;
        var textish = type.isEmpty() || type.startsWith("text/");
        if (!textish) return null;
        if (hasSuffix(filename, MARKDOWN_SUFFIXES)) return Kind.MARKDOWN;
        if (hasSuffix(filename, HTML_SUFFIXES)) return Kind.HTML;
        return null;
    }

    private static String bareType(String contentType) {
        if (contentType == null) return "";
        var semi = contentType.indexOf(';');
        var bare = semi < 0 ? contentType : contentType.substring(0, semi);
        return bare.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasSuffix(String filename, Set<String> suffixes) {
        if (filename == null) return false;
        var name = filename.trim().toLowerCase(Locale.ROOT);
        return suffixes.stream().anyMatch(name::endsWith);
    }

    /**
     * Read at most {@code maxChars} characters of UTF-8 text from {@code path}.
     *
     * <p>Malformed bytes are replaced rather than thrown on: the file is whatever somebody
     * uploaded, and a preview that shows one mangled character is more use than a 500. The BOM
     * some editors write is dropped — CommonMark would otherwise render it as text at the top of
     * the document, and it is noise at the head of an HTML file too.
     */
    public static Source read(Path path, int maxChars) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        var text = new StringBuilder();
        try (var reader = new InputStreamReader(new BufferedInputStream(Files.newInputStream(path)), decoder)) {
            var buf = new char[8 * 1024];
            int n;
            // One char past the cap is enough to know the file didn't fit.
            while (text.length() <= maxChars && (n = reader.read(buf)) != -1) {
                text.append(buf, 0, n);
            }
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text.deleteCharAt(0);
        var truncated = text.length() > maxChars;
        if (truncated) {
            text.setLength(maxChars);
            // Never end on half a surrogate pair — that is a lone code unit the browser would
            // draw as a replacement glyph.
            if (Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                text.setLength(text.length() - 1);
            }
        }
        return new Source(text.toString(), truncated);
    }

    /**
     * The text handed to the renderer, or to the iframe.
     *
     * @param text      up to the kind's character cap
     * @param truncated true when the file had more; the client says so above the preview, so a
     *                  reader is never quietly shown a document that stops mid-sentence
     */
    public record Source(String text, boolean truncated) {}
}
