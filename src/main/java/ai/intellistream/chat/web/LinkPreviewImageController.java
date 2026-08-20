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

import ai.intellistream.chat.linkpreview.LinkPreviewService;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.security.Principal;
import java.time.Duration;

/**
 * Serves the server's copy of a link preview's picture. The key is a random UUID the client was
 * handed on the DTO — unguessable, so knowing the URL is knowing the picture, and nothing else
 * about who posted it or where. Authenticated like everything under {@code /api}; a preview is
 * public web content, so there is no per-room check to make, but there is nothing here for
 * somebody who is not signed in either.
 *
 * <p>Long cache: the bytes behind a key never change (a re-fetch gets a new key), so the browser
 * can keep them for a day and a scroll through history costs no image requests.
 */
@RestController
public class LinkPreviewImageController {

    private final LinkPreviewService linkPreviews;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public LinkPreviewImageController(LinkPreviewService linkPreviews, CurrentUser currentUser, RateLimiter rateLimiter) {
        this.linkPreviews = linkPreviews;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/link-previews/images/{key}")
    public ResponseEntity<Resource> image(@PathVariable String key, Principal principal) throws IOException {
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "link-preview-image", 600, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("link preview image rate exceeded");
        }
        var stored = linkPreviews.image(key).orElse(null);
        if (stored == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .contentLength(Files.size(stored.path()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(stored.path()));
    }
}
