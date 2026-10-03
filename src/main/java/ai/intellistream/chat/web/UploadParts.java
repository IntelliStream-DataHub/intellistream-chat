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

import ai.intellistream.chat.attachments.AttachmentMedia;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

    /**
     * The response both download endpoints send once they have decided the caller may have the
     * file. One copy, because the two used to carry the same disposition rule pasted side by side,
     * and a rule about what may render on this origin is the last thing that should drift.
     *
     * <ul>
     *   <li><b>Type</b> — {@link AttachmentMedia#servedType}: the stored type, except that a video
     *       is served as the video type the player was offered.</li>
     *   <li><b>Disposition</b> — {@code attachment} unless the caller asked for {@code inline}
     *       <em>and</em> {@link AttachmentMedia#inlineSafe} agrees. {@code nosniff} on every
     *       response, so the browser cannot promote the bytes to something riskier.</li>
     *   <li><b>Range</b> — handled by Spring MVC for a {@link Resource} body: {@code Accept-Ranges}
     *       on every response, {@code 206} with {@code Content-Range} when asked. A video player
     *       cannot seek without it, and Safari will not start one at all. No explicit
     *       {@code Content-Length} is set for the same reason: the converter writes the right one
     *       for whichever of the whole file, one range or a multipart set of ranges goes out, and
     *       a whole-file length fixed here would be wrong for the last of those.</li>
     * </ul>
     */
    public static ResponseEntity<Resource> fileResponse(Resource body, String filename, String contentType,
                                                        String dispositionParam) {
        var served = AttachmentMedia.servedType(contentType, filename);
        var inline = "inline".equalsIgnoreCase(dispositionParam) && AttachmentMedia.inlineSafe(served);
        var encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        var disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .contentType(parseMediaType(served))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
