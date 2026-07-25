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

package ai.intellistream.chat.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RawUploadTest {

    private static MockHttpServletRequest request(String contentType, byte[] body) {
        var req = new MockHttpServletRequest("POST", "/api/channels/1/attachments");
        if (contentType != null) req.setContentType(contentType);
        if (body != null) req.setContent(body);
        return req;
    }

    @Test
    void readsFilenameCaptionAndLengthFromHeaders() throws IOException {
        var body = "the file bytes".getBytes(StandardCharsets.UTF_8);
        var req = request("image/png", body);
        req.addHeader("X-Upload-Filename", "photo.png");
        req.addHeader("X-Upload-Caption", "a caption");

        var upload = RawUpload.from(req, true);

        assertThat(upload.filename()).isEqualTo("photo.png");
        assertThat(upload.caption()).isEqualTo("a caption");
        assertThat(upload.contentType()).isEqualTo("image/png");
        assertThat(upload.declaredLength()).isEqualTo(body.length);
        assertThat(upload.body().readAllBytes()).isEqualTo(body);
    }

    @Test
    void decodesPercentEncodedUtf8() throws IOException {
        var req = request("application/octet-stream", new byte[0]);
        req.addHeader("X-Upload-Filename", "h%C3%A5ndbok%20%F0%9F%93%98.pdf");
        req.addHeader("X-Upload-Caption", "fr%C3%A5%20turen%20%E2%9C%93");

        var upload = RawUpload.from(req, true);

        assertThat(upload.filename()).isEqualTo("håndbok 📘.pdf");
        assertThat(upload.caption()).isEqualTo("frå turen ✓");
    }

    @Test
    void keepsALiteralPlusInAFilename() throws IOException {
        // The reason this isn't URLDecoder: that implements form encoding, where '+' means a
        // space, and it would quietly rename "C++ notes.txt" to "C   notes.txt".
        var req = request("text/plain", new byte[0]);
        req.addHeader("X-Upload-Filename", "C++%20notes.txt");

        assertThat(RawUpload.from(req, true).filename()).isEqualTo("C++ notes.txt");
    }

    @Test
    void leavesAMalformedEscapeAloneRatherThanFailingTheUpload() throws IOException {
        var req = request("text/plain", new byte[0]);
        req.addHeader("X-Upload-Filename", "100% done.txt");

        // A filename is display metadata; rejecting a valid file over a stray '%' is the wrong
        // trade, so the sequence passes through untouched.
        assertThat(RawUpload.from(req, true).filename()).isEqualTo("100% done.txt");
    }

    @Test
    void rejectsAMissingFilenameWhenOneIsRequired() {
        var req = request("text/plain", new byte[0]);

        assertThatThrownBy(() -> RawUpload.from(req, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-Upload-Filename");
    }

    @Test
    void allowsAMissingFilenameWhenOneIsNotRequired() throws IOException {
        // The avatar endpoint: the stored file is keyed by user and the type is sniffed anyway.
        var req = request("image/png", new byte[0]);

        assertThat(RawUpload.from(req, false).filename()).isNull();
    }

    @Test
    void rejectsAMultipartBody() {
        var req = request("multipart/form-data; boundary=abc", new byte[0]);
        req.addHeader("X-Upload-Filename", "photo.png");

        // A client that forgot to switch off form encoding would otherwise have the multipart
        // wrapper stored as if it were the file.
        assertThatThrownBy(() -> RawUpload.from(req, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("raw request body");
    }

    @Test
    void rejectsAFormUrlencodedBody() {
        var req = request("application/x-www-form-urlencoded", new byte[0]);
        req.addHeader("X-Upload-Filename", "photo.png");

        assertThatThrownBy(() -> RawUpload.from(req, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnOverlongCaption() {
        var req = request("text/plain", new byte[0]);
        req.addHeader("X-Upload-Filename", "a.txt");
        req.addHeader("X-Upload-Caption", "x".repeat(9000));

        assertThatThrownBy(() -> RawUpload.from(req, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Caption too long");
    }

    @Test
    void toleratesAMissingContentType() throws IOException {
        var req = request(null, new byte[0]);
        req.addHeader("X-Upload-Filename", "a.bin");

        var upload = RawUpload.from(req, true);

        assertThat(upload.contentType()).isNull(); // the service falls back and sniffs the bytes
    }
}
