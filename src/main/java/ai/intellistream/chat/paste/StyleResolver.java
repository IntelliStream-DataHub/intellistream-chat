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

import org.jsoup.nodes.Element;

import java.util.regex.Pattern;

/**
 * Computes the effective inline formatting of an element from its tag, its {@code style}
 * attribute, and what it inherited. This single resolver is why Google Docs, Apple Notes
 * and Word Online need no per-source handling: Docs says bold as {@code font-weight:700}
 * on a {@code <span>}, Notes as {@code font-weight: bold}, Word Online as {@code <strong>}
 * — all three land on the same {@link Style}.
 *
 * <p>The cancellation rules matter as much as the positive ones. An explicit
 * {@code font-weight:normal} clears bold even when a {@code <b>} tag set it, which is
 * exactly what neutralizes Google Docs' {@code <b id="docs-internal-guid-…"
 * style="font-weight:normal">} wrapper around the whole payload without a special case.
 *
 * <p>Underline is deliberately absent: the dialect has no syntax for it, and emitting raw
 * {@code <u>} is off the table (the converter never emits HTML). Colour, size and
 * background are noise — read by nobody, cancelled by nothing.
 */
final class StyleResolver {

    record Style(boolean bold, boolean italic, boolean strike, boolean mono) {
        static final Style PLAIN = new Style(false, false, false, false);
    }

    private StyleResolver() {
    }

    private static final Pattern MONO_FAMILY = Pattern.compile(
            "monospace|consolas|courier|menlo|monaco|roboto mono|fira code|sf mono"
                    + "|source code|jetbrains mono|ubuntu mono|liberation mono|dejavu sans mono");

    static Style effective(Element el, Style inherited) {
        var bold = inherited.bold();
        var italic = inherited.italic();
        var strike = inherited.strike();
        var mono = inherited.mono();

        switch (el.normalName()) {
            case "b", "strong" -> bold = true;
            case "i", "em" -> italic = true;
            case "s", "strike", "del" -> strike = true;
            case "code", "kbd", "samp", "tt" -> mono = true;
            default -> { }
        }

        var style = el.attr("style");
        if (!style.isEmpty()) {
            for (var decl : style.split(";")) {
                var colon = decl.indexOf(':');
                if (colon < 0) continue;
                var prop = decl.substring(0, colon).strip().toLowerCase();
                var value = decl.substring(colon + 1).strip().toLowerCase();
                switch (prop) {
                    case "font-weight" -> {
                        var numeric = parseWeight(value);
                        if (value.startsWith("bold") || numeric >= 600) {
                            bold = true;
                        } else if (value.equals("normal") || (numeric >= 1 && numeric < 600)) {
                            bold = false;
                        }
                    }
                    case "font-style" -> {
                        if (value.startsWith("italic") || value.startsWith("oblique")) {
                            italic = true;
                        } else if (value.equals("normal")) {
                            italic = false;
                        }
                    }
                    case "text-decoration", "text-decoration-line" -> {
                        if (value.contains("line-through")) {
                            strike = true;
                        } else if (value.contains("none")) {
                            strike = false;
                        }
                    }
                    case "font-family" -> {
                        if (MONO_FAMILY.matcher(value).find()) {
                            mono = true;
                        }
                    }
                    default -> { }
                }
            }
        }
        return new Style(bold, italic, strike, mono);
    }

    private static int parseWeight(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
