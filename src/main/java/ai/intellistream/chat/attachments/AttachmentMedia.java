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

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a stored attachment is <em>as media</em>: whether the browser should be offered it as a
 * video, and what it may be served as. Channel and DM attachments both go through here — the
 * DTOs (so both feeds decide the same way) and both download endpoints (so the bytes arrive with
 * the type the player was told to expect).
 *
 * <h2>Decided at read time, from the stored row</h2>
 * Nothing about this is recorded at upload. A video uploaded before the player existed is
 * offered one exactly like a video uploaded today, with no migration and no backfill — the
 * answer is derived from {@code content_type} and the filename every time a message is shown.
 *
 * <h2>Why the filename is consulted at all</h2>
 * Upload trusts Tika's sniff over the browser's claim, and Tika names some containers by what it
 * can prove rather than by what they hold: WebM and Matroska share the EBML magic, so without a
 * {@code .webm} it says {@code application/x-matroska}; an Ogg stream with no {@code .ogv} is
 * {@code application/ogg}; and a client that sent no type at all, which the upload path files as
 * {@code application/octet-stream}, leaves nothing to go on but the name. For exactly those
 * container-level types a video extension is allowed to settle it. A real {@code video/*} sniff
 * always wins, and nothing <em>outside</em> that short list is ever reinterpreted — a PDF named
 * {@code clip.mp4} stays a PDF.
 *
 * <h2>What "video" promises</h2>
 * Only that the browser should be <em>asked</em>. Whether it can play the format is the browser's
 * question ({@code canPlayType}, then the element's own {@code error}), answered in
 * {@code chat-kit.js}; an AVI is a video here and a download card on every browser.
 */
public final class AttachmentMedia {

    /**
     * Types that name a container without saying whether it carries pictures. Only these may be
     * upgraded to a video type by the filename; see the class comment.
     */
    private static final Set<String> AMBIGUOUS_CONTAINERS = Set.of(
            "application/octet-stream",
            "application/x-matroska",
            "application/ogg",
            "application/mp4");

    /** Extensions that make an ambiguous container a video, and the video type they mean. */
    private static final Map<String, String> VIDEO_EXTENSIONS = Map.of(
            "mp4", "video/mp4",
            "m4v", "video/x-m4v",
            "mov", "video/quicktime",
            "webm", "video/webm",
            "mkv", "video/x-matroska",
            "ogv", "video/ogg",
            "3gp", "video/3gpp");

    private AttachmentMedia() {}

    /**
     * The type to offer the browser's video player, or {@code null} when this isn't a video.
     * Lower-case, parameters stripped, so it can be handed to {@code canPlayType} as-is.
     */
    public static String videoType(String contentType, String filename) {
        var type = essence(contentType);
        if (type.startsWith("video/")) {
            return type;
        }
        if (type.isEmpty() || AMBIGUOUS_CONTAINERS.contains(type)) {
            return VIDEO_EXTENSIONS.get(extension(filename));
        }
        return null;
    }

    /**
     * The Content-Type to serve the bytes with: the video type when there is one, so a player
     * that was offered {@code video/webm} is not then handed {@code application/x-matroska}
     * (Firefox refuses a media response by its header), and the stored type otherwise.
     */
    public static String servedType(String contentType, String filename) {
        var video = videoType(contentType, filename);
        return video != null ? video : contentType;
    }

    /**
     * Whether a response of this type may be rendered by the browser rather than downloaded —
     * that is, whether {@code ?disposition=inline} is honoured.
     *
     * <p>Images, minus SVG: an SVG is a document that can carry script, and rendering a user's
     * upload as one on this origin is stored XSS whatever {@code nosniff} says. Video, because a
     * player is the only thing a browser does with it — "Open video in new tab" from the player
     * would otherwise download the file instead of playing it. Everything else is a download.
     */
    public static boolean inlineSafe(String servedType) {
        var type = essence(servedType);
        return (type.startsWith("image/") && !type.equals("image/svg+xml"))
                || type.startsWith("video/");
    }

    /** {@code "Video/MP4; codecs=avc1"} → {@code "video/mp4"}; null → {@code ""}. */
    private static String essence(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        var bare = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return bare.strip().toLowerCase(Locale.ROOT);
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
