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

/** Which URL in a body gets the card. Pure; every rule in {@link LinkUrls} is one case here. */
class LinkUrlsTest {

    @Test
    void theFirstHttpUrlWins() {
        assertThat(LinkUrls.firstPreviewable("see https://example.com/a and https://example.com/b"))
                .contains("https://example.com/a");
    }

    @Test
    void nothingToPreviewIsEmpty() {
        assertThat(LinkUrls.firstPreviewable("no links here")).isEmpty();
        assertThat(LinkUrls.firstPreviewable("")).isEmpty();
        assertThat(LinkUrls.firstPreviewable(null)).isEmpty();
        assertThat(LinkUrls.firstPreviewable("ftp://files.example.com/x")).isEmpty();
        assertThat(LinkUrls.firstPreviewable("mailto:someone@example.com")).isEmpty();
    }

    @Test
    void trailingProsePunctuationIsNotPartOfTheUrl() {
        assertThat(LinkUrls.firstPreviewable("Read https://example.com/post.")).contains("https://example.com/post");
        assertThat(LinkUrls.firstPreviewable("(see https://example.com/post)")).contains("https://example.com/post");
        assertThat(LinkUrls.firstPreviewable("wow: https://example.com/post!?")).contains("https://example.com/post");
        assertThat(LinkUrls.firstPreviewable("\"https://example.com/post\"")).contains("https://example.com/post");
    }

    @Test
    void balancedBracketsStayBecauseWikipediaUsesThem() {
        assertThat(LinkUrls.firstPreviewable("https://en.wikipedia.org/wiki/Foo_(bar)"))
                .contains("https://en.wikipedia.org/wiki/Foo_(bar)");
        assertThat(LinkUrls.firstPreviewable("(https://en.wikipedia.org/wiki/Foo_(bar))"))
                .contains("https://en.wikipedia.org/wiki/Foo_(bar)");
    }

    @Test
    void markdownLinkSyntaxYieldsTheHref() {
        assertThat(LinkUrls.firstPreviewable("[the docs](https://example.com/docs) are good"))
                .contains("https://example.com/docs");
    }

    @Test
    void urlsInsideCodeAreQuotedNotShared() {
        assertThat(LinkUrls.firstPreviewable("run `curl https://example.com/api`")).isEmpty();
        assertThat(LinkUrls.firstPreviewable("```\nGET https://example.com/api\n```")).isEmpty();
        assertThat(LinkUrls.firstPreviewable("```\nhttps://example.com/in-code\n```\nand https://example.com/out"))
                .contains("https://example.com/out");
    }

    @Test
    void videoLinksGetACardBecauseTheCardIsNowThePlayer() {
        // These used to be skipped: MarkdownRenderer injected an <iframe> after the link, so a
        // card underneath would have been a second embed for one URL. The body carries no player
        // any more — the card *is* the player (poster + play button) — so the unfurl is what
        // supplies its poster and title, and skipping it would leave a play button over a blank.
        assertThat(LinkUrls.firstPreviewable("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .contains("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(LinkUrls.firstPreviewable("https://youtu.be/dQw4w9WgXcQ"))
                .contains("https://youtu.be/dQw4w9WgXcQ");
        assertThat(LinkUrls.firstPreviewable("https://vimeo.com/123456789"))
                .contains("https://vimeo.com/123456789");
        // First link wins, video or not — one card per message is still the rule.
        assertThat(LinkUrls.firstPreviewable("https://youtu.be/dQw4w9WgXcQ and https://example.com/article"))
                .contains("https://youtu.be/dQw4w9WgXcQ");
    }

    @Test
    void anAbsurdlyLongUrlIsSkipped() {
        var url = "https://example.com/" + "a".repeat(LinkUrls.MAX_URL_LENGTH);
        assertThat(LinkUrls.firstPreviewable(url + " https://example.com/short")).contains("https://example.com/short");
    }

    @Test
    void theHashIsStableAndHex() {
        assertThat(LinkUrls.hash("https://example.com/")).hasSize(64).matches("[0-9a-f]+")
                .isEqualTo(LinkUrls.hash("https://example.com/"));
        assertThat(LinkUrls.hash("https://example.com/a")).isNotEqualTo(LinkUrls.hash("https://example.com/b"));
    }
}
