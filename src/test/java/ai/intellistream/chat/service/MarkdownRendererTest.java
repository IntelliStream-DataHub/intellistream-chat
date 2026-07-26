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
    void userProvidedMentionSpanIsStripped() {
        // N29: a hand-written mention span in raw markdown must not survive sanitization —
        // otherwise a user could forge a styled/clickable mention of anyone.
        var html = renderer.render("<span class=\"mention\" data-username=\"admin\">@admin</span>");
        assertThat(html).doesNotContain("<span").doesNotContain("data-username");
    }

    /**
     * A handle nobody owns stays bare text. It is half of the "silent mention" fix: the stylesheet
     * tints {@code .mention}, so leaving an unresolved handle undecorated is what makes the failure
     * visible — the text no longer looks identical to a mention that will actually notify someone.
     */
    @Test
    void unresolvedHandleIsNotDecorated() {
        var html = renderer.render("ping @nobody-here about it");
        assertThat(html).contains("@nobody-here")
                .doesNotContain("class=\"mention\"")
                .doesNotContain("data-username");
    }

    /**
     * A broadcast handle is highlighted with no help from the username lookup — the mock resolves
     * nobody here, which is exactly the state a real @channel is in. Before this, @channel rendered
     * as plain text: it notified nobody and said nothing about it.
     */
    @Test
    void broadcastHandlesAreDecorated() {
        var html = renderer.render("heads up @channel and @here");
        assertThat(html).contains("class=\"mention mention-broadcast\" data-mention=\"channel\"")
                .contains(">@channel</span>")
                .contains("class=\"mention mention-broadcast\" data-mention=\"here\"")
                .contains(">@here</span>");
    }

    /**
     * The @everyone decision has to be visible in the message, not just in the code: it carries
     * @channel's audience marker and says so in the title.
     */
    @Test
    void everyoneIsRenderedAsAChannelBroadcast() {
        var html = renderer.render("@everyone please read");
        assertThat(html).contains("data-mention=\"channel\"")
                .contains(">@everyone</span>")
                .contains("works like @channel");
    }

    /**
     * The pill has to name the room it is actually in. In a direct or group conversation the old
     * wording said "this channel", which was wrong twice over — there is no channel, and until
     * conversations gained their own fan-out the handle notified nobody. The second half is fixed;
     * this is the first.
     */
    @Test
    void aBroadcastPillInAConversationTalksAboutTheConversation() {
        var html = renderer.renderInConversation("heads up @channel");
        assertThat(html).contains("Notifies everyone in this conversation")
                .doesNotContain("member of this channel");

        assertThat(renderer.renderInConversation("@everyone please read"))
                .contains("everyone in this conversation");
        assertThat(renderer.renderInConversation("@here quick one"))
                .contains("the people here who are online right now");
    }

    /** And a channel keeps the wording it had — this is additive, not a rename. */
    @Test
    void aBroadcastPillInAChannelIsUnchanged() {
        assertThat(renderer.render("heads up @channel"))
                .contains("Notifies every member of this channel");
    }

    /** N21: a broadcast inside code is documentation, not an announcement. */
    @Test
    void broadcastInsideCodeIsNotDecorated() {
        var html = renderer.render("write `@channel` to notify the room");
        assertThat(html).contains("@channel").doesNotContain("mention-broadcast");
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
    void embedsYouTubeShortDomainUrls() {
        // youtu.be — share-button shortlink, not the /shorts/ path (different feature).
        var html = renderer.render("look https://youtu.be/dQw4w9WgXcQ");
        assertThat(html).contains("src=\"https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ\"");
    }

    @Test
    void embedsYouTubeShortsPath() {
        // /shorts/ID — vertical-format videos. Same embed endpoint, but the wrapper carries
        // data-orientation="vertical" so the stylesheet renders a 9:16 phone-shaped frame.
        var html = renderer.render("look https://www.youtube.com/shorts/phwq5hZZwDU");
        assertThat(html).contains("class=\"video-embed-wrapper\"");
        assertThat(html).contains("data-orientation=\"vertical\"");
        assertThat(html).contains("src=\"https://www.youtube-nocookie.com/embed/phwq5hZZwDU\"");
    }

    @Test
    void landscapeYouTubeDoesNotSetVerticalOrientation() {
        // Sanity: /watch URLs stay in the landscape default; data-orientation is shorts-only.
        var html = renderer.render("look https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(html).doesNotContain("data-orientation");
    }

    @Test
    void embedsYouTubeMobileSubdomain() {
        // m.youtube.com is what the YouTube mobile app emits when sharing via "Copy link".
        var html = renderer.render("look https://m.youtube.com/watch?v=dQw4w9WgXcQ");
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

    @Test
    void strikethroughRendersInsteadOfLeavingLiteralTildes() {
        // Regression: the composer toolbar has always had an S button that wraps the selection in
        // ~~, but the renderer registered only Tables and Autolink, so the tildes reached the user
        // verbatim. Two things have to be right for this to work — the CommonMark extension has to
        // produce <del>, and the jsoup safelist has to let <del> through, since Safelist.basic()
        // allows <strike> but not <del>.
        var html = renderer.render("this is ~~struck~~ text");
        assertThat(html).contains("<del>struck</del>");
        assertThat(html).doesNotContain("~~");
    }

    @Test
    void strikethroughIsStillSanitised() {
        var html = renderer.render("~~<img src=x onerror=alert(1)>~~");
        assertThat(html).doesNotContain("onerror");
    }
}
