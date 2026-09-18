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

import java.util.regex.Pattern;

/**
 * Recognises a YouTube or Vimeo URL and says what it takes to play it.
 *
 * <p>This used to live in {@code MarkdownRenderer}, which injected a ready-made {@code <iframe>}
 * into the rendered body right after the anchor. Two things were wrong with that, and neither was
 * a bug in the usual sense:
 *
 * <ul>
 *   <li><b>Privacy.</b> The iframe contacted YouTube the moment a message was rendered, so every
 *       reader's address went to Google for scrolling past someone else's link. The link-preview
 *       code deliberately does the opposite — it copies a page's picture server-side and serves it
 *       from this origin, because {@code img-src 'self'} exists so that reading a message tells
 *       nobody anything. The player quietly broke the principle the card was built around.</li>
 *   <li><b>The body stopped being sanitizable.</b> {@code Element.setHTML()} removes
 *       {@code <iframe>} unconditionally, so no client could apply the browser's own sanitizer to
 *       a body as a second lock under the server's jsoup pass without deleting every embed.</li>
 * </ul>
 *
 * <p>So the body now carries the plain link and nothing else, and the descriptor here rides on the
 * card ({@code LinkPreviewDto.video}). The client draws the poster it already copied, with a play
 * button, and builds the iframe only when somebody asks for it — one deliberate click, instead of
 * a request per rendered message.
 *
 * <p>Detection is pure string work and does no I/O, which is the point: it must not depend on the
 * unfurl having succeeded. A YouTube link whose page could not be fetched still gets a play button
 * (without a poster behind it); it just has nothing to say about the video.
 */
public final class VideoLinks {

    /** What the client needs to build a player, and nothing more. */
    public record VideoRef(String provider, String embedUrl, String orientation) {
        /** Shorts are 9:16; everything else is 16:9. The stylesheet keys on this. */
        public boolean isVertical() {
            return "vertical".equals(orientation);
        }
    }

    // Subdomain is optional and may be www / m (mobile share URLs) / music — all cover the same
    // video catalogue. The id captured by group(1) is what goes into the embed URL, regardless of
    // which entry path was pasted.
    private static final String YT_HOST = "(?:www\\.|m\\.|music\\.)?youtube\\.com";
    private static final Pattern YT_WATCH = Pattern.compile(
            "^https?://" + YT_HOST + "/watch\\?(?:[^#]*&)?v=([A-Za-z0-9_-]{6,20})");
    /** Short-domain links — youtu.be/ID — usually the result of the YouTube share button. */
    private static final Pattern YT_BE = Pattern.compile(
            "^https?://(?:www\\.)?youtu\\.be/([A-Za-z0-9_-]{6,20})");
    private static final Pattern YT_EMBED = Pattern.compile(
            "^https?://" + YT_HOST + "/embed/([A-Za-z0-9_-]{6,20})");
    /** Vertical-format Shorts: a different URL path from /watch, but the same embed endpoint. */
    private static final Pattern YT_SHORTS = Pattern.compile(
            "^https?://" + YT_HOST + "/shorts/([A-Za-z0-9_-]{6,20})");
    private static final Pattern VIMEO = Pattern.compile(
            "^https?://(?:www\\.)?vimeo\\.com/(?:video/)?(\\d{6,12})");

    private VideoLinks() {
    }

    /**
     * The player descriptor for {@code url}, or null if it is not a video link.
     *
     * <p>The embed URL is built here rather than by the client: the id is matched by a regex that
     * admits only {@code [A-Za-z0-9_-]}, so the result cannot be steered anywhere but the two
     * embed origins the CSP's {@code frame-src} allows. A client assembling a third-party URL out
     * of parts is how that guarantee gets lost.
     */
    public static VideoRef of(String url) {
        if (url == null || url.isEmpty()) return null;

        // Shorts first: a /shorts/ URL also has to not be read as a /watch one.
        var shorts = match(url, YT_SHORTS);
        if (shorts != null) {
            return new VideoRef("YouTube", "https://www.youtube-nocookie.com/embed/" + shorts, "vertical");
        }
        var yt = match(url, YT_WATCH, YT_BE, YT_EMBED);
        if (yt != null) {
            return new VideoRef("YouTube", "https://www.youtube-nocookie.com/embed/" + yt, null);
        }
        var vimeo = match(url, VIMEO);
        if (vimeo != null) {
            return new VideoRef("Vimeo", "https://player.vimeo.com/video/" + vimeo, null);
        }
        return null;
    }

    /** Whether {@code url} names a video this app can play. */
    public static boolean isVideo(String url) {
        return of(url) != null;
    }

    private static String match(String url, Pattern... patterns) {
        for (var p : patterns) {
            var m = p.matcher(url);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}
