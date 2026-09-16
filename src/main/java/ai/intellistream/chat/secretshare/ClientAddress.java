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

package ai.intellistream.chat.secretshare;

import java.net.InetAddress;

/**
 * The address to record for someone who opened a secret without an account.
 *
 * <p>{@code request.getRemoteAddr()} is not a trustworthy string. With
 * {@code server.forward-headers-strategy: framework} it is the leftmost {@code X-Forwarded-For}
 * entry, which is whatever the client put there unless the proxy overwrites the header (see
 * {@code frontend.md}). So the value is accepted only when it parses as an IPv4 or IPv6
 * <em>literal</em> — {@link InetAddress#ofLiteral} never touches DNS — and is re-rendered from the
 * parsed address, which is what makes it safe to print into a receipt's Markdown and a page. Anything
 * else is recorded as unknown rather than stored verbatim.
 */
public final class ClientAddress {

    private ClientAddress() {}

    /** Longest textual IPv6 form; the column is sized to it. */
    static final int MAX_LENGTH = 45;

    public static String literalOrNull(String raw) {
        if (raw == null) return null;
        var s = raw.strip();
        if (s.length() > 2 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']') {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty() || s.length() > MAX_LENGTH || s.indexOf('%') >= 0) {
            return null;
        }
        try {
            var text = InetAddress.ofLiteral(s).getHostAddress();
            return text.length() <= MAX_LENGTH ? text : null;
        } catch (IllegalArgumentException notALiteral) {
            return null;
        }
    }
}
