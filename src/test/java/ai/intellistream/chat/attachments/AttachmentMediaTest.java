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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentMediaTest {

    /**
     * What Tika stores for each container when it has the filename hint every upload passes —
     * measured against real files, not assumed. A stored {@code video/*} is offered as itself.
     */
    @ParameterizedTest
    @CsvSource({
            "video/mp4,        clip.mp4,  video/mp4",
            "video/webm,       clip.webm, video/webm",
            "video/quicktime,  IMG_0001.MOV, video/quicktime",
            "video/x-matroska, clip.mkv,  video/x-matroska",
            "video/ogg,        clip.ogv,  video/ogg",
            "video/x-m4v,      clip.m4v,  video/x-m4v",
            "video/3gpp,       clip.3gp,  video/3gpp",
            // A sniffed video type wins over whatever the name says.
            "video/mp4,        notes.txt, video/mp4",
            // Case and parameters are normalised, so the value can go straight to canPlayType.
            "'Video/MP4; codecs=\"avc1.42E01E\"', clip.mp4, video/mp4",
    })
    void storedVideoTypesAreOfferedAsThemselves(String stored, String filename, String expected) {
        assertThat(AttachmentMedia.videoType(stored, filename)).isEqualTo(expected);
    }

    /**
     * The rows that upload can leave without a video type even though they are one: Tika names
     * the container when it can't see more (WebM/Matroska share EBML magic, Ogg is Ogg), and a
     * client that sent no type at all is filed as octet-stream. These are already in databases,
     * which is why it is decided on read rather than fixed at upload.
     */
    @ParameterizedTest
    @CsvSource({
            "application/x-matroska,   clip.webm, video/webm",
            "application/x-matroska,   clip.mkv,  video/x-matroska",
            "application/ogg,          clip.ogv,  video/ogg",
            "application/mp4,          clip.mp4,  video/mp4",
            "application/octet-stream, clip.MOV,  video/quicktime",
            "application/octet-stream, a.b.webm,  video/webm",
            "'',                       clip.mp4,  video/mp4",
    })
    void ambiguousContainersAreSettledByAVideoExtension(String stored, String filename, String expected) {
        assertThat(AttachmentMedia.videoType(stored, filename)).isEqualTo(expected);
        assertThat(AttachmentMedia.servedType(stored, filename)).isEqualTo(expected);
    }

    @Test
    void aMissingContentTypeIsTreatedLikeAnEmptyOne() {
        assertThat(AttachmentMedia.videoType(null, "clip.webm")).isEqualTo("video/webm");
    }

    @ParameterizedTest
    @CsvSource({
            // Only the container-level types are ever reinterpreted: a PDF named like a video is a PDF.
            "application/pdf,          clip.mp4",
            "text/html,                clip.webm",
            "image/png,                clip.mov",
            // An ambiguous container without a video extension stays what it is — .ogg is usually audio.
            "application/ogg,          song.ogg",
            "application/x-matroska,   recording",
            "application/octet-stream, archive.bin",
            "application/octet-stream, ",
            "audio/mpeg,               song.mp3",
            "video,                    clip.mp4",
    })
    void everythingElseIsNotAVideo(String stored, String filename) {
        assertThat(AttachmentMedia.videoType(stored, filename)).isNull();
        assertThat(AttachmentMedia.servedType(stored, filename)).isEqualTo(stored);
    }

    @ParameterizedTest
    @CsvSource({
            "image/png,      true",
            "image/jpeg,     true",
            "IMAGE/WEBP,     true",
            "video/mp4,      true",
            "video/webm,     true",
            // An SVG is a document that can run script on this origin; never inline.
            "image/svg+xml,  false",
            "IMAGE/SVG+XML; charset=utf-8, false",
            "text/html,      false",
            "text/plain,     false",
            "application/pdf, false",
            "application/octet-stream, false",
    })
    void onlyImagesAndVideosMayRenderInline(String servedType, boolean expected) {
        assertThat(AttachmentMedia.inlineSafe(servedType)).isEqualTo(expected);
    }

    @Test
    void aNullTypeIsNeverInline() {
        assertThat(AttachmentMedia.inlineSafe(null)).isFalse();
    }
}
