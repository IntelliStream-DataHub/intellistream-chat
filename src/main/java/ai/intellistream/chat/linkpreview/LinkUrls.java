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

package ai.intellistream.chat.linkpreview;

import ai.intellistream.chat.service.MarkdownRenderer;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which URL in a message body gets the card, and how it is keyed.
 *
 * <p>The first {@code http(s)} URL in the Markdown, skipping code (a URL inside backticks is being
 * quoted, not shared) and skipping anything {@link MarkdownRenderer#embedsVideo} already turns into
 * a player. One per message, deliberately: a message that is a list of ten links is a list, and ten
 * cards under it would bury it. Trailing punctuation that prose puts after a link — the full stop,
 * the closing bracket of "(see https://…)" — is not part of the URL.
 *
 * <p>Pure functions; the tests are {@code LinkUrlsTest}.
 */
public final class LinkUrls {

    private LinkUrls() {}

    /** Loose on purpose: {@link URI} decides what is really a URL, this only finds candidates. */
    private static final Pattern CANDIDATE = Pattern.compile("https?://[^\\s<>\"'`\\u00A0]+", Pattern.CASE_INSENSITIVE);
    /** Fenced blocks first (they may contain backticks), then inline code. */
    private static final Pattern FENCED = Pattern.compile("(?s)```.*?```|~~~.*?~~~");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    private static final String TRAILING = ".,;:!?)]}'\"";

    public static final int MAX_URL_LENGTH = 2000;

    /** The URL that would get a card, if any. */
    public static Optional<String> firstPreviewable(String bodyMarkdown) {
        if (bodyMarkdown == null || bodyMarkdown.indexOf("http") < 0) {
            return Optional.empty();
        }
        var text = INLINE_CODE.matcher(FENCED.matcher(bodyMarkdown).replaceAll(" ")).replaceAll(" ");
        var m = CANDIDATE.matcher(text);
        while (m.find()) {
            var url = trimTrailing(m.group());
            if (url.length() > MAX_URL_LENGTH || !isHttpUrl(url) || MarkdownRenderer.embedsVideo(url)) {
                continue;
            }
            return Optional.of(url);
        }
        return Optional.empty();
    }

    /**
     * Strip the punctuation prose leaves after a link. A closing bracket is only stripped when it
     * is unbalanced — {@code https://en.wikipedia.org/wiki/Foo_(bar)} keeps its parenthesis.
     */
    static String trimTrailing(String url) {
        var s = url;
        while (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            if (TRAILING.indexOf(last) < 0) break;
            if (last == ')' && count(s, '(') >= count(s, ')')) break;
            if (last == ']' && count(s, '[') >= count(s, ']')) break;
            if (last == '}' && count(s, '{') >= count(s, '}')) break;
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    static boolean isHttpUrl(String url) {
        try {
            var uri = new URI(url);
            var scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The lookup key: SHA-256 hex of the URL exactly as posted. */
    public static String hash(String url) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from the JRE", e);
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
