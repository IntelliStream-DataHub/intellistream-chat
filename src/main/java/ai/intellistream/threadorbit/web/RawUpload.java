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

package ai.intellistream.threadorbit.web;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A file upload sent as the <b>raw request body</b>, with its metadata in headers.
 *
 * <h2>Why not multipart</h2>
 *
 * {@code multipart/form-data} wraps the payload in delimiters, which means the server cannot just
 * copy bytes: it has to inspect every byte looking for the boundary, and it can never trust a
 * chunk until it has confirmed the boundary doesn't straddle it. That byte-at-a-time scan is the
 * upload path's throughput ceiling, and it is pure protocol overhead for a request that carries
 * exactly one file. Sending the file as the body instead means the bytes go
 * {@code request.getInputStream()} → {@code transferTo} → disk with nothing in the middle, so a
 * transfer runs at whatever the socket and the disk can do.
 *
 * <p>Browsers support this directly — {@code fetch(url, {method: 'POST', body: file})} streams a
 * {@code File} with no encoding step and no boundary. Metadata that multipart would have carried in
 * part headers moves to request headers:
 *
 * <pre>
 * POST /api/channels/42/attachments
 * Content-Type: image/png                 &lt;- declared type (still sniffed server-side)
 * Content-Length: 1048576                 &lt;- lets the size cap reject early, before reading
 * X-Upload-Filename: holiday%20snap.png   &lt;- percent-encoded UTF-8
 * X-Upload-Caption: from%20the%20trip     &lt;- percent-encoded UTF-8, optional
 * &lt;raw bytes&gt;
 * </pre>
 *
 * <p>The headers are percent-encoded because HTTP header values are ISO-8859-1 by specification;
 * a filename with an emoji or an umlaut in it would otherwise arrive mangled.
 */
public record RawUpload(String filename, String contentType, String caption, long declaredLength,
                        InputStream body) {

    static final String FILENAME_HEADER = "X-Upload-Filename";
    static final String CAPTION_HEADER = "X-Upload-Caption";

    /** Longest accepted caption header, in encoded characters — captions are chat text, not payloads. */
    private static final int MAX_CAPTION_CHARS = 8192;

    /**
     * Read the upload metadata and expose the body stream. Does not read the body: the caller
     * passes {@link #body()} to a service that copies it straight to disk.
     *
     * @param filenameRequired attachments need a filename to display; an avatar doesn't.
     */
    public static RawUpload from(HttpServletRequest request, boolean filenameRequired)
            throws IOException {
        var rawFilename = request.getHeader(FILENAME_HEADER);
        if ((rawFilename == null || rawFilename.isBlank()) && filenameRequired) {
            throw new IllegalArgumentException(
                    "Missing " + FILENAME_HEADER + " header; send the file as the request body");
        }
        var rawCaption = request.getHeader(CAPTION_HEADER);
        if (rawCaption != null && rawCaption.length() > MAX_CAPTION_CHARS) {
            throw new IllegalArgumentException("Caption too long");
        }
        var contentType = request.getContentType();
        // Guard against a client that forgot to switch off form encoding: a urlencoded or
        // multipart body is not the file, and silently storing the wrapper would be worse than
        // failing. (The servlet container may also have already consumed it as parameters.)
        if (contentType != null) {
            var lower = contentType.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("multipart/") || lower.startsWith("application/x-www-form-urlencoded")) {
                throw new IllegalArgumentException(
                        "Send the file as the raw request body, not as " + contentType);
            }
        }
        return new RawUpload(
                decode(rawFilename),
                contentType,
                decode(rawCaption),
                request.getContentLengthLong(),
                request.getInputStream());
    }

    /**
     * Percent-decode a header value as UTF-8.
     *
     * <p>Deliberately not {@code URLDecoder.decode}: that implements
     * {@code application/x-www-form-urlencoded}, where {@code '+'} means a space. This is a
     * filename, so a literal {@code +} in "C++ notes.txt" must survive as {@code +}. Invalid
     * escapes are left as written rather than throwing — a filename is display metadata, and
     * rejecting an otherwise valid upload over a stray {@code %} would be the wrong trade.
     */
    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? null : "";
        }
        if (value.indexOf('%') < 0) {
            return value;
        }
        var out = new java.io.ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            // Header values arrive as ISO-8859-1, so a raw char here is one byte by definition.
            out.write(c & 0xFF);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
