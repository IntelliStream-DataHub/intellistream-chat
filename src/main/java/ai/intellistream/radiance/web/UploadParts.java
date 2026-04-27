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

package ai.intellistream.radiance.web;

import org.apache.commons.fileupload2.core.FileItemInput;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Multipart-upload helpers shared by the channel and DM attachment endpoints.
 * Both endpoints use {@code commons-fileupload2} and need the same form-field
 * + content-type plumbing; one home for it avoids drift.
 */
public final class UploadParts {

    private static final int MAX_FORM_FIELD_BYTES = 8192;

    private UploadParts() {}

    /**
     * Read a small text form-field (e.g. a caption). Errors out if the field exceeds
     * {@value #MAX_FORM_FIELD_BYTES} bytes — these aren't binary uploads, so anything
     * larger is almost certainly a misconfigured client.
     */
    public static String readSmallField(FileItemInput item) throws IOException {
        var sb = new StringBuilder();
        var buf = new byte[1024];
        try (var in = item.getInputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (sb.length() > MAX_FORM_FIELD_BYTES) {
                    throw new IllegalArgumentException("Form field too long");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Parse a Content-Type string defensively; fall back to {@code application/octet-stream}
     * for anything malformed or null. Used on the response side when serving stored files
     * that may have a malformed type recorded.
     */
    public static MediaType parseMediaType(String value) {
        try {
            return value == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(value);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
