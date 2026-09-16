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

package ai.intellistream.chat.paste;

/**
 * Makes text safe to splice into a line of Markdown as literal text. Public so that anything
 * composing a message body from someone else's words — the paste converter, a secret's receipt —
 * uses this one escaper rather than a second copy that disagrees about which characters matter.
 */
public final class MarkdownEscape {

    private MarkdownEscape() {}

    /**
     * Escapes everything with an inline Markdown meaning, plus {@code <} and {@code &}:
     * the renderer passes raw inline HTML through to the sanitizer, so a pasted literal
     * "&lt;b&gt;" would otherwise come back as actual bold, and "&amp;copy;" as ©.
     */
    public static String inline(String s) {
        var sb = new StringBuilder(s.length() + 8);
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '\\', '*', '_', '`', '~', '|', '[', ']' -> sb.append('\\').append(c);
                case '<' -> sb.append("\\<");
                case '&' -> sb.append("\\&");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
