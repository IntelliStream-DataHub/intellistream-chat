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

import org.springframework.http.MediaType;

/**
 * Download-side helpers shared by the channel and DM attachment endpoints.
 *
 * <p>The upload side used to live here too, back when both endpoints parsed
 * {@code multipart/form-data} with {@code commons-fileupload2}. Uploads now arrive as a raw
 * request body — see {@link RawUpload} for why — so all that remains is serving stored files back.
 */
public final class UploadParts {

    private UploadParts() {}

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
