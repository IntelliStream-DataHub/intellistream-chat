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

package com.example.chat.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTest {

    private final MentionService mentionService = Mockito.mock(MentionService.class);
    {
        Mockito.when(mentionService.resolvedUsernames(Mockito.anyString())).thenReturn(Set.of());
    }
    private final MarkdownRenderer renderer = new MarkdownRenderer(mentionService);

    @Test
    void rendersBasicMarkdown() {
        var html = renderer.render("Hello **world**");
        assertThat(html).contains("<strong>world</strong>");
    }

    @Test
    void rendersFencedCodeBlock() {
        var html = renderer.render("""
                ```
                int x = 1;
                ```
                """);
        assertThat(html).contains("<pre>").contains("int x = 1;");
    }

    @Test
    void rendersTables() {
        var html = renderer.render("""
                | a | b |
                | - | - |
                | 1 | 2 |
                """);
        assertThat(html).contains("<table>").contains("<td>1</td>");
    }

    @Test
    void autolinksUrls() {
        var html = renderer.render("see https://example.com here");
        assertThat(html).contains("href=\"https://example.com\"");
    }

    @Test
    void embedsYouTubeWatchUrls() {
        var html = renderer.render("look https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(html).contains("class=\"video-embed-wrapper\"");
        assertThat(html).contains("src=\"https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ\"");
    }

    @Test
    void embedsYouTubeShortUrls() {
        var html = renderer.render("look https://youtu.be/dQw4w9WgXcQ");
        assertThat(html).contains("src=\"https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ\"");
    }

    @Test
    void embedsVimeoUrls() {
        var html = renderer.render("watch https://vimeo.com/76979871");
        assertThat(html).contains("class=\"video-embed-wrapper\"");
        assertThat(html).contains("src=\"https://player.vimeo.com/video/76979871\"");
    }

    @Test
    void doesNotEmbedNonVideoLinks() {
        var html = renderer.render("see https://example.com here");
        assertThat(html).doesNotContain("video-embed");
    }

    @Test
    void stripsScriptTags() {
        var html = renderer.render("hi <script>alert(1)</script> bye");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("alert(1)");
    }

    @Test
    void blankInputReturnsEmpty() {
        assertThat(renderer.render("")).isEmpty();
        assertThat(renderer.render(null)).isEmpty();
    }
}
