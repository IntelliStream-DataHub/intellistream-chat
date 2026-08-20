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

package ai.intellistream.chat.domain;

import java.util.Locale;

/**
 * Whether a date reads {@code 3 Feb 2026}, {@code Feb 3, 2026} or {@code 2026-02-03}.
 *
 * <p>The three explicit orders exist because day-first and month-first are genuinely ambiguous
 * when written numerically — {@code 03/02/2026} is two different days depending on who is reading
 * — and because the people most bothered by that ambiguity are exactly the people working across
 * both conventions. ISO is offered for them.
 *
 * <p>Like {@link HourCycle}, {@link #AUTO} follows the viewer's locale and is the shipping value.
 * Unlike it, the explicit orders are rendered with an abbreviated month name rather than digits:
 * the point of choosing an order is to stop guessing, and a spelled month cannot be misread.
 */
public enum DateStyle {

    /** Follow the viewer's locale — CLDR's medium date pattern for it. */
    AUTO,
    /** Day first: {@code 3 Feb 2026}. */
    DMY,
    /** Month first: {@code Feb 3, 2026}. */
    MDY,
    /** ISO 8601: {@code 2026-02-03}. */
    ISO;

    /**
     * The {@link java.time.format.DateTimeFormatter} pattern for this style, or {@code null} for
     * {@link #AUTO} — whose formatter is built from {@code FormatStyle.MEDIUM} against the locale
     * rather than from a pattern string, so it picks up locale quirks a pattern cannot express.
     */
    public String pattern() {
        return switch (this) {
            case AUTO -> null;
            case DMY -> "d MMM yyyy";
            case MDY -> "MMM d, yyyy";
            case ISO -> "yyyy-MM-dd";
        };
    }

    /** Parse a form value, falling back to {@link #AUTO} rather than throwing on junk. */
    public static DateStyle parse(String raw) {
        if (raw == null || raw.isBlank()) return AUTO;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return AUTO;
        }
    }
}
