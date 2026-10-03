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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link UploadParts#fileResponse}, the response both attachment download endpoints send, driven
 * through real Spring MVC — the Range half of it is the framework's behaviour for a
 * {@link Resource} body, and a Boot upgrade is exactly what could move it.
 *
 * <p>Range is what a video player lives on: every seek is a {@code Range} request, Safari won't
 * start playback without a {@code 206}, and a moov box at the end of an MP4 is read with one
 * before the first frame. The download endpoints used to fix {@code Content-Length} to the whole
 * file, which a single-range answer overrides but a multi-range one does not — hence the last test.
 */
class AttachmentFileResponseTest {

    private static final String BODY = "0123456789abcdefghij"; // 20 bytes

    @TempDir
    static Path dir;

    private MockMvc mvc;

    /** Stands in for either download endpoint after its access checks have passed. */
    @RestController
    static class FileController {
        @GetMapping("/file")
        ResponseEntity<Resource> file(@RequestParam String name,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String disposition) {
            return UploadParts.fileResponse(new FileSystemResource(dir.resolve("blob")), name, type, disposition);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("blob"), BODY, StandardCharsets.US_ASCII);
        mvc = MockMvcBuilders.standaloneSetup(new FileController()).build();
    }

    @Test
    void aVideoIsServedInlineWhenAskedWithRangesAdvertised() throws Exception {
        mvc.perform(get("/file").param("name", "clip.mp4").param("type", "video/mp4")
                        .param("disposition", "inline"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Disposition", "inline; filename*=UTF-8''clip.mp4"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Length", "20"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(BODY));
    }

    @Test
    void withoutTheParameterItIsStillADownload() throws Exception {
        mvc.perform(get("/file").param("name", "clip.mp4").param("type", "video/mp4"))
                .andExpect(header().string("Content-Disposition", "attachment; filename*=UTF-8''clip.mp4"));
    }

    @Test
    void aContainerTypeIsServedAsTheVideoTypeThePlayerWasOffered() throws Exception {
        // Firefox refuses a media response by its header, so the bytes have to arrive as what the
        // DTO said they were — not as the application/x-matroska Tika stored for an unhinted WebM.
        mvc.perform(get("/file").param("name", "screen recording.webm")
                        .param("type", "application/x-matroska").param("disposition", "inline"))
                .andExpect(header().string("Content-Type", "video/webm"))
                .andExpect(header().string("Content-Disposition",
                        "inline; filename*=UTF-8''screen%20recording.webm"));
    }

    @Test
    void svgAndHtmlNeverRenderInlineWhateverTheParameterSays() throws Exception {
        mvc.perform(get("/file").param("name", "x.svg").param("type", "image/svg+xml")
                        .param("disposition", "inline"))
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(header().string("Content-Disposition", "attachment; filename*=UTF-8''x.svg"));
        mvc.perform(get("/file").param("name", "x.html").param("type", "text/html")
                        .param("disposition", "inline"))
                .andExpect(header().string("Content-Disposition", "attachment; filename*=UTF-8''x.html"));
    }

    @Test
    void imagesStillRenderInline() throws Exception {
        mvc.perform(get("/file").param("name", "p.png").param("type", "image/png")
                        .param("disposition", "inline"))
                .andExpect(header().string("Content-Disposition", "inline; filename*=UTF-8''p.png"));
    }

    @Test
    void aMalformedStoredTypeFallsBackToOctetStream() throws Exception {
        mvc.perform(get("/file").param("name", "x.bin").param("type", "not a type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"));
    }

    @Test
    void aRangeIsAnsweredWithJustThoseBytes() throws Exception {
        mvc.perform(get("/file").param("name", "clip.mp4").param("type", "video/mp4")
                        .header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/20"))
                .andExpect(header().string("Content-Length", "4"))
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(content().string("2345"));
    }

    @Test
    void anOpenEndedRangeRunsToTheEnd() throws Exception {
        // What a player sends first, and again after every seek.
        mvc.perform(get("/file").param("name", "clip.mp4").param("type", "video/mp4")
                        .header("Range", "bytes=15-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 15-19/20"))
                .andExpect(content().string("fghij"));
    }

    @Test
    void anUnsatisfiableRangeIs416() throws Exception {
        mvc.perform(get("/file").param("name", "clip.mp4").param("type", "video/mp4")
                        .header("Range", "bytes=50-60"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */20"));
    }

    @Test
    void severalRangesDoNotClaimTheWholeFileAsTheirLength() throws Exception {
        var response = mvc.perform(get("/file").param("name", "clip.mp4").param("type", "video/mp4")
                        .header("Range", "bytes=0-1,4-5"))
                .andExpect(status().isPartialContent())
                .andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("multipart/byteranges");
        var body = response.getContentAsString();
        assertThat(body).contains("Content-Range: bytes 0-1/20").contains("Content-Range: bytes 4-5/20");
        // A whole-file Content-Length on a multipart body truncates it or hangs the client.
        var length = response.getHeader("Content-Length");
        if (length != null) {
            assertThat(Long.parseLong(length)).isEqualTo(response.getContentAsByteArray().length);
        }
    }
}
