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

import ai.intellistream.threadorbit.security.CurrentUser;
import ai.intellistream.threadorbit.security.RateLimitExceededException;
import ai.intellistream.threadorbit.security.RateLimiter;
import ai.intellistream.threadorbit.service.MarkdownRenderer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;
import java.util.Map;

/**
 * Renders a markdown body into the same sanitised HTML the channel will display, so the
 * composer can show a live preview that exactly matches what gets posted.
 */
@RestController
@RequestMapping("/api/preview")
public class PreviewRestController {

    private final MarkdownRenderer markdown;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public PreviewRestController(MarkdownRenderer markdown, CurrentUser currentUser, RateLimiter rateLimiter) {
        this.markdown = markdown;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public Map<String, String> preview(@RequestBody PreviewRequest body, Principal principal) {
        // The render pipeline (CommonMark parse + several jsoup passes + mention DB lookups) is
        // non-trivial per call; cap it so a scripted loop can't spin CPU/DB. 60/min per user is
        // well above live-preview typing cadence.
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "preview", 60, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("preview rate exceeded");
        }
        var text = body == null || body.body() == null ? "" : body.body();
        if (text.length() > 8000) {
            throw new IllegalArgumentException("Body too long (max 8000 chars)");
        }
        return Map.of("html", markdown.render(text));
    }

    public record PreviewRequest(String body) {}
}
