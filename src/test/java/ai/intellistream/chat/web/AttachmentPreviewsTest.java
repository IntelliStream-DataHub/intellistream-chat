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

import ai.intellistream.chat.attachments.PreviewableAttachments;
import ai.intellistream.chat.security.PublicBadRequestException;
import ai.intellistream.chat.security.ResourceNotFoundException;
import ai.intellistream.chat.service.MarkdownRenderer;
import ai.intellistream.chat.service.MentionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The step both preview endpoints share, once their own authorisation has passed: is this file
 * that kind, read it capped, hand it over. Everything the two controllers could disagree about
 * lives here on purpose.
 */
class AttachmentPreviewsTest {

    @TempDir
    Path dir;

    private final MentionService mentions = Mockito.mock(MentionService.class);
    private final MarkdownRenderer markdown = new MarkdownRenderer(mentions);

    {
        Mockito.when(mentions.resolvedUsernames(Mockito.anyString())).thenReturn(Set.of("alice"));
    }

    @Test
    void markdownComesBackSanitisedAndWithoutMentionPills() throws IOException {
        var file = write("notes.md", "# Title\n\nhi @alice <script>alert(1)</script>\n");

        var dto = AttachmentPreviews.markdown("notes.md", "text/markdown", file, markdown);

        assertThat(dto.filename()).isEqualTo("notes.md");
        assertThat(dto.html()).contains("<h1>Title</h1>").doesNotContain("<script");
        // A word in a file notified nobody; a mention pill there would say otherwise.
        assertThat(dto.html()).contains("@alice").doesNotContain("class=\"mention\"");
        assertThat(dto.truncated()).isFalse();
    }

    @Test
    void htmlComesBackExactlyAsUploaded() throws IOException {
        // Unsanitised on purpose — an HTML file with its <style> stripped is not a preview of that
        // file. What makes it safe is the sandboxed srcdoc frame it lands in, not a cleaning pass
        // here; see HtmlPreviewDto.
        var source = "<html><head><style>body{color:red}</style></head><body><p>hi</p></body></html>";
        var file = write("page.html", source);

        var dto = AttachmentPreviews.html("page.html", "text/html", file);

        assertThat(dto.source()).isEqualTo(source);
        assertThat(dto.truncated()).isFalse();
    }

    @Test
    void aLongFileIsCutAndFlagged() throws IOException {
        var file = write("big.md", "x".repeat(PreviewableAttachments.MAX_MARKDOWN_CHARS + 500));

        var dto = AttachmentPreviews.markdown("big.md", "text/markdown", file, markdown);

        assertThat(dto.truncated()).isTrue();
    }

    @Test
    void askingTheWrongReaderForAFileIsRefused() throws IOException {
        // Only a hand-made request gets here — the client calls the URL the DTO gave it — but the
        // endpoint still has to refuse rather than frame a PDF.
        var file = write("page.html", "<p>hi</p>");
        assertThatThrownBy(() -> AttachmentPreviews.markdown("page.html", "text/html", file, markdown))
                .isInstanceOf(PublicBadRequestException.class);
        assertThatThrownBy(() -> AttachmentPreviews.html("notes.md", "text/markdown", file))
                .isInstanceOf(PublicBadRequestException.class);
    }

    @Test
    void aFileWhoseBytesAreGoneIs404NotAnEmptyDocument() {
        // The row can outlive its file (a restored database, a wiped volume). An empty preview
        // would read as "this document is blank", which is a different and wrong answer.
        assertThatThrownBy(() ->
                AttachmentPreviews.markdown("notes.md", "text/markdown", dir.resolve("missing.md"), markdown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Path write(String name, String text) throws IOException {
        var file = dir.resolve(name);
        Files.writeString(file, text);
        return file;
    }
}
