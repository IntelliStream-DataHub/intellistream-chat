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

package ai.intellistream.chat.web.dto;

import ai.intellistream.chat.domain.Attachment;
import ai.intellistream.chat.domain.Conversation;
import ai.intellistream.chat.domain.ConversationAttachment;
import ai.intellistream.chat.domain.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server decides whether a file can be shown and how — the client only reads
 * {@code previewUrl} / {@code previewKind}. That is what keeps the four places that draw an
 * attachment chip (two feeds, two Thymeleaf histories) from each growing their own opinion about
 * what a markdown file looks like.
 */
class AttachmentPreviewUrlTest {

    @Test
    void aMarkdownAttachmentGetsAMarkdownPreview() {
        var dto = AttachmentDto.from(new Attachment(null, "notes.md", "text/markdown", 12, "key"));
        assertThat(dto.previewKind()).isEqualTo("markdown");
        assertThat(dto.previewUrl()).endsWith("/markdown");
    }

    @Test
    void anHtmlAttachmentGetsAnHtmlPreview() {
        var dto = AttachmentDto.from(new Attachment(null, "page.html", "text/html", 12, "key"));
        assertThat(dto.previewKind()).isEqualTo("html");
        assertThat(dto.previewUrl()).endsWith("/html");
    }

    @Test
    void anOrdinaryFileGetsNoPreviewButton() {
        var dto = AttachmentDto.from(new Attachment(null, "report.pdf", "application/pdf", 12, "key"));
        assertThat(dto.previewKind()).isNull();
        assertThat(dto.previewUrl()).isNull();
    }

    @Test
    void aTombstoneOffersNothingAtAll() {
        // Same reason the download link goes: the bytes are gone, so a preview would 404 — and a
        // button that fails is worse than no button.
        var attachment = new Attachment(null, "notes.md", "text/markdown", 12, "key");
        attachment.softDelete(null);
        var dto = AttachmentDto.from(attachment);
        assertThat(dto.downloadUrl()).isNull();
        assertThat(dto.previewUrl()).isNull();
        assertThat(dto.previewKind()).isNull();
    }

    @Test
    void aConversationAttachmentIsNamespacedUnderItsConversation() {
        var conversation = Mockito.mock(Conversation.class);
        Mockito.when(conversation.getId()).thenReturn(7L);
        var message = Mockito.mock(ConversationMessage.class);
        Mockito.when(message.getConversation()).thenReturn(conversation);

        var dto = ConversationAttachmentDto.from(
                new ConversationAttachment(message, "notes.md", "text/markdown", 12, "key"));
        assertThat(dto.previewKind()).isEqualTo("markdown");
        // The membership check lives on the conversation-scoped route, so the preview has to use
        // it too — a preview URL outside it would be read authorisation the DM never granted.
        assertThat(dto.previewUrl()).startsWith("/api/conversations/7/attachments/").endsWith("/markdown");
    }
}
