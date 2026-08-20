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
 * Whether a clock time reads {@code 14:05} or {@code 2:05 PM}.
 *
 * <p>{@link #AUTO} is the shipping value and defers to the viewer's locale, which is the right
 * answer often enough to be the default and wrong often enough to need an override: the locale a
 * browser advertises is a *language* preference, and a great many people read an application in
 * English from a country that has never used a 12-hour clock. Locale gets you the common case for
 * free; the explicit values are how somebody says the common case is not theirs.
 *
 * <p>Deliberately not modelled as a nullable Boolean. "Follow my locale" is a third state, not the
 * absence of the other two — it keeps tracking the locale as the locale changes, where a resolved
 * copy would freeze whatever was true on the day it was written.
 */
public enum HourCycle {

    /** Follow the viewer's locale — CLDR's short-time pattern for it. */
    AUTO,
    /** 12-hour clock with an AM/PM marker, whatever the locale says. */
    H12,
    /** 24-hour clock, whatever the locale says. */
    H24;

    /**
     * Resolve against a locale: {@code true} for a 12-hour clock, {@code false} for 24-hour.
     *
     * <p>{@link #AUTO} answers by asking CLDR — via the JDK's own locale data — for the locale's
     * short time pattern and looking for a half-day field in it. That is the same table the
     * browser's {@code Intl} consults, so the server and the client agree without either of them
     * carrying a list of which countries use which clock.
     */
    public boolean isTwelveHour(Locale locale) {
        return switch (this) {
            case H12 -> true;
            case H24 -> false;
            case AUTO -> localeUsesTwelveHour(locale);
        };
    }

    private static boolean localeUsesTwelveHour(Locale locale) {
        var pattern = java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                null, java.time.format.FormatStyle.SHORT,
                java.time.chrono.IsoChronology.INSTANCE,
                locale == null ? Locale.getDefault() : locale);
        // 'a' (am/pm), 'b' (day period), 'B' (flexible day period) and the 'h'/'K' hour fields all
        // mean a half-day clock. Quoted literals are skipped so a pattern like 'kl'. HH:mm — where
        // the letters are ornamental text, not fields — is not misread as 12-hour.
        var inQuote = false;
        for (var i = 0; i < pattern.length(); i++) {
            var c = pattern.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && (c == 'h' || c == 'K' || c == 'a' || c == 'b' || c == 'B')) {
                return true;
            }
        }
        return false;
    }

    /** Parse a form value, falling back to {@link #AUTO} rather than throwing on junk. */
    public static HourCycle parse(String raw) {
        if (raw == null || raw.isBlank()) return AUTO;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return AUTO;
        }
    }
}
