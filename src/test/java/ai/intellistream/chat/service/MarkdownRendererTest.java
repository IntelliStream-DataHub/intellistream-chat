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
    void aDocumentIsSanitisedAndHardenedButNotMentionDecorated() {
        // renderDocument is the path an uploaded .md file takes. It must sanitise exactly like a
        // message body — the file is somebody's upload — but leave handles alone: nothing wrote a
        // mention row for a word in a file, so a mention pill there would promise a notification
        // that never happened. It also keeps the pass off a document that may hold thousands of
        // handles, each one a user lookup.
        Mockito.when(mentionService.resolvedUsernames(Mockito.anyString())).thenReturn(Set.of("alice"));

        var html = renderer.renderDocument("hello @alice <script>alert(1)</script> [x](http://e.com)");

        assertThat(html).doesNotContain("<script").doesNotContain("class=\"mention\"");
        assertThat(html).contains("@alice");
        assertThat(html).contains("rel=\"noopener noreferrer nofollow\"");
        Mockito.verify(mentionService, Mockito.never()).resolvedUsernames(Mockito.anyString());
    }

    @Test
    void aMessageStillGetsItsMentionsDecorated() {
        // The other half: renderDocument must not have quietly become the only renderer.
        Mockito.when(mentionService.resolvedUsernames(Mockito.anyString())).thenReturn(Set.of("alice"));
        assertThat(renderer.render("hello @alice")).contains("class=\"mention\"");
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
    void doesNotPutAPlayerInTheBody() {
        // The renderer used to inject an <iframe> after a video link. It doesn't any more: the
        // player is a click-to-play facade on the link-preview card (VideoLinksTest covers which
        // URLs get one). Two reasons, both in linkpreview/VideoLinks — an iframe called YouTube on
        // every render, and an iframe in the body makes the body impossible to run through the
        // browser's own sanitizer, since Element.setHTML() drops iframes unconditionally.
        for (var url : new String[] {
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ",
                "https://www.youtube.com/shorts/phwq5hZZwDU",
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://vimeo.com/76979871" }) {
            var html = renderer.render("look " + url);
            assertThat(html)
                    .as("body for %s", url)
                    .doesNotContain("<iframe")
                    .doesNotContain("video-embed")
                    .doesNotContain("data-orientation");
            // The link itself is still a link, which is what the card hangs off.
            assertThat(html).contains("href=\"" + url + "\"");
        }
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
