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

import ai.intellistream.chat.attachments.PreviewableAttachments.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What may be shown in the page, and what the reader is handed when it is.
 *
 * <p>The rule under test is the one that keeps "preview" from meaning "render whatever somebody
 * uploaded": a name is a claim, the sniffed content type is a fact, and the two have to agree
 * before anything is shown.
 */
class PreviewableAttachmentsTest {

    @TempDir
    Path dir;

    @Test
    void markdownIsRecognisedByTypeOrByNameOverText() {
        assertThat(PreviewableAttachments.kindOf("notes.md", "text/markdown")).isEqualTo(Kind.MARKDOWN);
        // Tika's answer for plain text depends on the magic it matched, so the ordinary upload of
        // a .md file arrives as text/plain and the name is what settles it.
        assertThat(PreviewableAttachments.kindOf("notes.md", "text/plain")).isEqualTo(Kind.MARKDOWN);
        assertThat(PreviewableAttachments.kindOf("NOTES.MD", "text/plain")).isEqualTo(Kind.MARKDOWN);
        assertThat(PreviewableAttachments.kindOf("notes.markdown", "text/plain; charset=utf-8"))
                .isEqualTo(Kind.MARKDOWN);
        // Rows predating MIME sniffing carry no type at all; the name is all there is.
        assertThat(PreviewableAttachments.kindOf("notes.md", null)).isEqualTo(Kind.MARKDOWN);
    }

    @Test
    void htmlIsRecognisedByTypeOrByNameOverText() {
        assertThat(PreviewableAttachments.kindOf("page.html", "text/html")).isEqualTo(Kind.HTML);
        assertThat(PreviewableAttachments.kindOf("page.html", "text/html; charset=utf-8")).isEqualTo(Kind.HTML);
        assertThat(PreviewableAttachments.kindOf("page.htm", "text/plain")).isEqualTo(Kind.HTML);
        assertThat(PreviewableAttachments.kindOf("doc.xhtml", "application/xhtml+xml")).isEqualTo(Kind.HTML);
    }

    @Test
    void aLyingNameDoesNotEarnAPreview() {
        // This is the whole point of consulting the sniffed type: report.md holding a ZIP is a ZIP,
        // and rendering its bytes as a document would be showing binary as prose at best.
        assertThat(PreviewableAttachments.kindOf("report.md", "application/zip")).isNull();
        assertThat(PreviewableAttachments.kindOf("page.html", "application/pdf")).isNull();
    }

    @Test
    void everythingElseHasNoPreview() {
        assertThat(PreviewableAttachments.kindOf("notes.txt", "text/plain")).isNull();
        assertThat(PreviewableAttachments.kindOf("photo.png", "image/png")).isNull();
        // Images have their own viewer, and an SVG is a script container the download endpoint
        // deliberately refuses to serve inline — neither belongs in the document reader.
        assertThat(PreviewableAttachments.kindOf("logo.svg", "image/svg+xml")).isNull();
        assertThat(PreviewableAttachments.kindOf(null, null)).isNull();
    }

    @Test
    void eachKindNamesItsOwnEndpointAndCap() {
        // slug() is the last path segment of the endpoint AND the DTO's previewKind AND the word
        // the client switches on, so a typo here is a button that opens nothing.
        assertThat(Kind.MARKDOWN.slug()).isEqualTo("markdown");
        assertThat(Kind.HTML.slug()).isEqualTo("html");
        assertThat(Kind.MARKDOWN.maxChars()).isEqualTo(PreviewableAttachments.MAX_MARKDOWN_CHARS);
        assertThat(Kind.HTML.maxChars()).isEqualTo(PreviewableAttachments.MAX_HTML_CHARS);
    }

    @Test
    void readsTheWholeFileWhenItFits() throws IOException {
        var file = write("# Title\n\nbody\n");
        var source = PreviewableAttachments.read(file, 1024);
        assertThat(source.text()).isEqualTo("# Title\n\nbody\n");
        assertThat(source.truncated()).isFalse();
    }

    @Test
    void aFileExactlyAtTheCapIsNotTruncated() throws IOException {
        var file = write("x".repeat(64));
        var source = PreviewableAttachments.read(file, 64);
        assertThat(source.text()).hasSize(64);
        assertThat(source.truncated()).isFalse();
    }

    @Test
    void anOversizeFileIsCutAndSaysSo() throws IOException {
        var file = write("y".repeat(500));
        var source = PreviewableAttachments.read(file, 100);
        assertThat(source.text()).hasSize(100);
        assertThat(source.truncated()).isTrue();
    }

    @Test
    void theCutNeverSplitsASurrogatePair() throws IOException {
        // "😀" is two UTF-16 code units. Cutting between them leaves a lone high surrogate, which
        // the browser draws as a replacement glyph at the end of every truncated preview.
        var file = write("ab" + "😀" + "cd");
        var source = PreviewableAttachments.read(file, 3);
        assertThat(source.text()).isEqualTo("ab");
        assertThat(source.truncated()).isTrue();
    }

    @Test
    void theByteOrderMarkIsDropped() throws IOException {
        var file = write("﻿# Title");
        assertThat(PreviewableAttachments.read(file, 1024).text()).isEqualTo("# Title");
    }

    @Test
    void bytesThatAreNotUtf8AreReplacedRatherThanThrownOn() throws IOException {
        var file = dir.resolve("latin1.md");
        Files.write(file, new byte[] { 'a', (byte) 0xC3, (byte) 0x28, 'b' });
        var source = PreviewableAttachments.read(file, 1024);
        assertThat(source.text()).startsWith("a").endsWith("b");
    }

    private Path write(String text) throws IOException {
        var file = dir.resolve("doc.md");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
