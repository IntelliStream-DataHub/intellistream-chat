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

package ai.intellistream.chat.linkpreview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which URLs are playable, and what the client is told to load. These cases came from
 * {@code MarkdownRendererTest} when the player stopped being an {@code <iframe>} injected into the
 * message body; the URL shapes are the same ones users actually paste.
 */
class VideoLinksTest {

    @Test
    void youTubeWatchUrls() {
        var v = VideoLinks.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(v).isNotNull();
        assertThat(v.embedUrl()).isEqualTo("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ");
        assertThat(v.provider()).isEqualTo("YouTube");
        assertThat(v.isVertical()).isFalse();
    }

    @Test
    void youTubeShareLinksAndMobileAndMusicSubdomains() {
        // youtu.be is the share button; m. is the mobile app's "Copy link"; music. is the same
        // catalogue again. All three have to reach the same embed.
        for (var url : new String[] {
                "https://youtu.be/dQw4w9WgXcQ",
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://www.youtube.com/embed/dQw4w9WgXcQ" }) {
            assertThat(VideoLinks.of(url))
                    .as(url)
                    .isNotNull()
                    .extracting(VideoLinks.VideoRef::embedUrl)
                    .isEqualTo("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ");
        }
    }

    @Test
    void shortsAreVerticalAndEverythingElseIsNot() {
        // /shorts/ has to be tested before /watch, or a Shorts URL renders in a landscape frame.
        var shorts = VideoLinks.of("https://www.youtube.com/shorts/phwq5hZZwDU");
        assertThat(shorts).isNotNull();
        assertThat(shorts.isVertical()).isTrue();
        assertThat(shorts.embedUrl()).isEqualTo("https://www.youtube-nocookie.com/embed/phwq5hZZwDU");

        assertThat(VideoLinks.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ").orientation()).isNull();
    }

    @Test
    void vimeoUrls() {
        var v = VideoLinks.of("https://vimeo.com/76979871");
        assertThat(v).isNotNull();
        assertThat(v.provider()).isEqualTo("Vimeo");
        assertThat(v.embedUrl()).isEqualTo("https://player.vimeo.com/video/76979871");

        assertThat(VideoLinks.of("https://vimeo.com/video/76979871")).isNotNull();
    }

    @Test
    void nonVideoLinksAreNotPlayable() {
        assertThat(VideoLinks.of("https://example.com/watch?v=dQw4w9WgXcQ")).isNull();
        assertThat(VideoLinks.of("https://example.com")).isNull();
        assertThat(VideoLinks.of(null)).isNull();
        assertThat(VideoLinks.isVideo("https://youtu.be/dQw4w9WgXcQ")).isTrue();
    }

    @Test
    void theEmbedUrlCannotBeSteeredAwayFromTheEmbedOrigins() {
        // The whole reason the server builds the embed URL: the id pattern admits only
        // [A-Za-z0-9_-], so nothing a user pastes can add a host, a query, or a quote to it. A
        // client assembling this from parts is how frame-src stops meaning anything.
        for (var url : new String[] {
                "https://www.youtube.com/watch?v=abc123\"><script>",
                "https://youtu.be/abc123/../../evil",
                "https://youtu.be/abc123?x=https://evil.example" }) {
            var v = VideoLinks.of(url);
            if (v != null) {
                assertThat(v.embedUrl())
                        .as(url)
                        .startsWith("https://www.youtube-nocookie.com/embed/")
                        .matches("https://www\\.youtube-nocookie\\.com/embed/[A-Za-z0-9_-]{6,20}");
            }
        }
    }
}
