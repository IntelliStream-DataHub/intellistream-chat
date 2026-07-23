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

import ai.intellistream.threadorbit.service.AppSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Public read-only endpoint that serves the active logo. Uploads/changes are routed through
 * {@link AdminController} which is gated on {@code ROLE_ADMIN}; the URL here is anonymous so
 * the landing page (pre-login) and favicon both pick up the custom logo when one is set.
 */
@RestController
public class BrandingRestController {

    private final AppSettingsService settings;
    private final Path brandingDir;

    public BrandingRestController(AppSettingsService settings,
                                  @Value("${threadorbit.branding.dir}") String brandingDirPath) {
        this.settings = settings;
        this.brandingDir = Path.of(brandingDirPath);
    }

    @GetMapping("/branding/logo")
    public ResponseEntity<Resource> logo() throws IOException {
        var s = settings.current();
        if (!s.hasCustomLogo()) {
            // No custom upload — redirect to the bundled default. Lets the browser cache the
            // static asset normally; the admin page rewrites the redirect target after upload.
            return ResponseEntity.status(302)
                    .header(HttpHeaders.LOCATION, "/img/logo.svg")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .build();
        }
        var path = brandingDir.resolve(s.getLogoPath()).normalize();
        if (!path.startsWith(brandingDir.normalize()) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        var declared = s.getLogoContentType() == null ? "" : s.getLogoContentType().toLowerCase();
        // Defence-in-depth: SVG uploads are now rejected at the admin form, but a row from
        // before the policy change might still point at one. Force-attach so the browser
        // won't render scripts even if a stale SVG is on disk.
        var isSvg = declared.equals("image/svg+xml");
        var media = declared.isEmpty() ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(declared);
        var resp = ResponseEntity.ok()
                .contentType(media)
                .contentLength(Files.size(path))
                // Cache-bust via the version query string the templates inject (?v=epochMillis),
                // so we can serve fresh after upload but let the browser cache between changes.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff");
        if (isSvg) {
            resp.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"logo.svg\"");
        }
        return resp.body(new FileSystemResource(path));
    }
}
