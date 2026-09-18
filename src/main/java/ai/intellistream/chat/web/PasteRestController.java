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

import ai.intellistream.chat.paste.HtmlToMarkdownConverter;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.security.RateLimitExceededException;
import ai.intellistream.chat.security.RateLimiter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Duration;

/**
 * Converts the {@code text/html} clipboard flavor of a rich-text paste into the Markdown
 * dialect the composer speaks. The composer's paste handler calls this and inserts the
 * result; on {@code convert:false} — or any error at all — it inserts the plain-text
 * flavor it already holds, so this endpoint can only ever upgrade a paste, never break one.
 * Oversize input is therefore a 200 {@code convert:false}, not an error: to the client it
 * means the same thing, "paste the plain text".
 */
@RestController
@RequestMapping("/api/paste")
public class PasteRestController {

    private final HtmlToMarkdownConverter converter;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    public PasteRestController(HtmlToMarkdownConverter converter, CurrentUser currentUser,
                               RateLimiter rateLimiter) {
        this.converter = converter;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/markdown")
    public PasteMarkdownResponse convert(@RequestBody PasteMarkdownRequest body, Principal principal) {
        // Parsing up to half a megabyte of foreign HTML is real CPU; 30/min is far above
        // any human paste cadence but caps a scripted loop. A limited-out client quietly
        // degrades to plain-text pastes (the 429 takes the same fallback as any failure).
        var me = currentUser.resolve(principal);
        if (!rateLimiter.tryAcquire(me.getUsername(), "paste-convert", 30, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("paste conversion rate exceeded");
        }
        var html = body == null ? null : body.html();
        if (html == null || html.length() > HtmlToMarkdownConverter.MAX_HTML_CHARS) {
            return PasteMarkdownResponse.plain();
        }
        var result = converter.convert(html);
        return result.convertible()
                ? new PasteMarkdownResponse(true, result.markdown())
                : PasteMarkdownResponse.plain();
    }

    public record PasteMarkdownRequest(String html) {
    }

    public record PasteMarkdownResponse(boolean convert, String markdown) {
        static PasteMarkdownResponse plain() {
            return new PasteMarkdownResponse(false, null);
        }
    }
}
