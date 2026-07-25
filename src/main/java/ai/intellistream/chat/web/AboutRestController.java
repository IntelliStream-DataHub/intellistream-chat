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

import ai.intellistream.chat.service.AboutService;
import ai.intellistream.chat.web.dto.AboutDto;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backs the About dialog in the avatar dropdown.
 *
 * <p>Authenticated users get the name, version and build time. The server and component inventory
 * is attached only for {@code ROLE_ADMIN} — see {@link AboutDto} for why. The decision is made
 * here rather than with a second endpoint so there is exactly one URL to reason about, and it is
 * made from the live authorities rather than from anything the client sends.
 */
@RestController
@RequestMapping("/api/about")
public class AboutRestController {

    private final AboutService aboutService;

    public AboutRestController(AboutService aboutService) {
        this.aboutService = aboutService;
    }

    @GetMapping
    public AboutDto about(Authentication authentication) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return aboutService.about(admin);
    }
}
