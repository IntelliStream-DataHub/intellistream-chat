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

package ai.intellistream.chat.i18n;

import ai.intellistream.chat.domain.DateStyle;
import ai.intellistream.chat.domain.HourCycle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * One viewer's timestamp conventions, resolved: a zone, a locale, an hour cycle and a date order,
 * with the formatters already built. Handed to Thymeleaf as {@code ${fmt}} so a template writes
 * {@code ${fmt.time(msg.createdAt)}} instead of {@code #temporals.format(msg.createdAt, 'h:mm a')}.
 *
 * <p>Replacing {@code #temporals} at every timestamp is the point, not a stylistic preference.
 * {@code #temporals} formats an {@code Instant} in the <em>server's</em> zone — there is nowhere
 * to tell it otherwise — and every pattern it was given here was a hard-coded English one. On a
 * container running UTC that rendered the whole scrollback in UTC and in US 12-hour form, directly
 * above the live messages that {@code chat-kit.js} had drawn in the reader's own zone and locale.
 *
 * <p>The same values reach the client through {@code fragments/time-prefs.html} as meta tags, and
 * {@code time-format.js} builds the equivalent {@code Intl} formatters from them, so both halves of
 * the feed agree by construction rather than by two implementations happening to match.
 *
 * <p>Immutable and cheap to build (four {@code DateTimeFormatter}s), but built once per rendered
 * page rather than per timestamp — a feed is hundreds of them.
 */
public final class TimeView {

    private final ZoneId zone;
    private final Locale locale;
    private final ZoneSource source;
    private final HourCycle hourCycle;
    private final DateStyle dateStyle;

    private final DateTimeFormatter timeFormatter;
    private final DateTimeFormatter dayFormatter;
    private final DateTimeFormatter dateFormatter;
    private final DateTimeFormatter dayKeyFormatter;
    private final DateTimeFormatter clockFormatter;

    TimeView(ZoneId zone, Locale locale, ZoneSource source, HourCycle hourCycle, DateStyle dateStyle) {
        this.zone = zone;
        this.locale = locale;
        this.source = source;
        this.hourCycle = hourCycle;
        this.dateStyle = dateStyle;
        this.timeFormatter = buildTime(zone, locale, hourCycle);
        this.dateFormatter = buildDate(zone, locale, dateStyle);
        this.dayFormatter = buildDay(zone, locale, dateStyle);
        // Not locale-anything: this is the key the day-divider walker compares two messages on,
        // and it only has to be stable and zone-correct. It must still be built with the zone,
        // because which calendar day an Instant falls on is exactly the question being asked.
        this.dayKeyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(zone);
        this.clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone);
    }

    // ---------- Formatting (called from templates) ----------

    /** A message's clock time: {@code 14:05} or {@code 2:05 PM}. */
    public String time(Instant instant) {
        return instant == null ? "" : timeFormatter.format(instant);
    }

    /** A day divider: {@code Tuesday, 3 February} or {@code Tuesday, February 3}. */
    public String day(Instant instant) {
        return instant == null ? "" : dayFormatter.format(instant);
    }

    /** A date on its own: {@code 3 Feb 2026}, {@code Feb 3, 2026} or {@code 2026-02-03}. */
    public String date(Instant instant) {
        return instant == null ? "" : dateFormatter.format(instant);
    }

    /** Date and clock time together, for tables and tooltips. */
    public String dateTime(Instant instant) {
        return instant == null ? "" : dateFormatter.format(instant) + ", " + timeFormatter.format(instant);
    }

    /**
     * A fixed {@code yyyy-MM-dd HH:mm} stamp in the viewer's zone, for the admin console's tables.
     *
     * <p>Deliberately not locale-formatted. Those tables are read as a log — scanned down a column,
     * compared against each other and against something in a terminal — and a sortable, unambiguous
     * shape serves that better than the reader's date convention. The zone still has to be theirs:
     * an audit row is only useful if "16:40" is 16:40 where the person reading it was sitting.
     */
    public String stamp(Instant instant) {
        return instant == null ? "" : dayKey(instant) + " " + clockFormatter.format(instant);
    }

    /**
     * The {@code yyyy-MM-dd} key a day divider groups on, in the viewer's zone.
     *
     * <p>Formatting this in the server's zone was the same bug as the clock times but harder to
     * see: the divider landed on the wrong message rather than showing the wrong text, so a feed
     * read by somebody far enough east or west had "Tuesday" starting partway through Monday
     * evening.
     */
    public String dayKey(Instant instant) {
        return instant == null ? "" : dayKeyFormatter.format(instant);
    }

    /** True when two instants fall on the same calendar day <em>for this viewer</em>. */
    public boolean sameDay(Instant a, Instant b) {
        if (a == null || b == null) return false;
        return dayKey(a).equals(dayKey(b));
    }

    // ---------- The resolved settings (meta tags, profile page copy) ----------

    public String zoneId() {
        return zone.getId();
    }

    public ZoneId zone() {
        return zone;
    }

    /** BCP 47 tag for the client's {@code Intl} constructors — {@code nb-NO}, {@code en-US}. */
    public String localeTag() {
        return locale.toLanguageTag();
    }

    public ZoneSource source() {
        return source;
    }

    public String sourceToken() {
        return source.token();
    }

    /** True when the zone on screen is a guess or a fallback rather than something confirmed. */
    public boolean unconfirmed() {
        return source.isUnconfirmed();
    }

    public HourCycle hourCycle() {
        return hourCycle;
    }

    public DateStyle dateStyle() {
        return dateStyle;
    }

    public String hourCycleToken() {
        return hourCycle.name().toLowerCase(Locale.ROOT);
    }

    public String dateStyleToken() {
        return dateStyle.name().toLowerCase(Locale.ROOT);
    }

    /** What {@code AUTO} resolved to, so the client can pass a concrete {@code hour12} to Intl. */
    public boolean twelveHour() {
        return hourCycle.isTwelveHour(locale);
    }

    /** The short zone label shown next to a "times are shown in…" line: {@code CET}, {@code JST}. */
    public String zoneAbbreviation() {
        return DateTimeFormatter.ofPattern("zzz", locale).withZone(zone).format(Instant.now());
    }

    // ---------- Formatter construction ----------

    private static DateTimeFormatter buildTime(ZoneId zone, Locale locale, HourCycle cycle) {
        var formatter = switch (cycle) {
            // SHORT is the locale's own clock — HH:mm in nb-NO, h:mm a in en-US — so AUTO needs no
            // table of which countries use which.
            case AUTO -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
            case H24 -> DateTimeFormatter.ofPattern("HH:mm");
            // Locale still applies to the marker itself, which is not "AM/PM" everywhere.
            case H12 -> DateTimeFormatter.ofPattern("h:mm a");
        };
        return formatter.withLocale(locale).withZone(zone);
    }

    private static DateTimeFormatter buildDate(ZoneId zone, Locale locale, DateStyle style) {
        var pattern = style.pattern();
        var formatter = pattern == null
                ? DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                : DateTimeFormatter.ofPattern(pattern);
        return formatter.withLocale(locale).withZone(zone);
    }

    /**
     * The day divider keeps the weekday and drops the year, which is what both renderers did
     * before and what makes the divider scannable. For {@link DateStyle#AUTO} the day/month order
     * comes from CLDR's medium pattern for the locale with the year field cut out of it, so a
     * Norwegian reads "tirsdag, 3. februar" and an American "Tuesday, February 3" without either
     * order being hard-coded. ISO is the exception and keeps its year: somebody who asked for ISO
     * asked for the unambiguous form.
     */
    private static DateTimeFormatter buildDay(ZoneId zone, Locale locale, DateStyle style) {
        var pattern = switch (style) {
            case AUTO -> "EEEE, " + withoutYear(localizedDatePattern(locale));
            case DMY -> "EEEE, d MMMM";
            case MDY -> "EEEE, MMMM d";
            case ISO -> "EEEE, yyyy-MM-dd";
        };
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone);
    }

    private static String localizedDatePattern(Locale locale) {
        return DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.LONG, null, IsoChronology.INSTANCE, locale);
    }

    /**
     * Strip the year field (and the separator it leaves behind) out of a CLDR date pattern.
     *
     * <p>Falls back to a spelled-out day and month if the result looks damaged — some locales
     * build patterns this simple edit cannot survive, and an odd-looking divider in one locale is
     * better than an exception on every page in it.
     */
    private static String withoutYear(String pattern) {
        var out = new StringBuilder();
        var inQuote = false;
        for (var i = 0; i < pattern.length(); i++) {
            var c = pattern.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                out.append(c);
            } else if (!inQuote && (c == 'y' || c == 'u' || c == 'G')) {
                continue;
            } else {
                out.append(c);
            }
        }
        var cleaned = trimEdges(out.toString());
        return cleaned.contains("d") && cleaned.contains("M") ? cleaned : "d MMMM";
    }

    /**
     * Peel separators and now-orphaned quoted literals off both ends of a pattern.
     *
     * <p>The literals are why this is a loop and not a regex. Spanish and Portuguese write a long
     * date as {@code d 'de' MMMM 'de' y}; deleting the year leaves a dangling "de" that formats as
     * "3 de febrero de", and the same shape recurs with different words in a dozen locales. Peeling
     * whole literals from the ends handles all of them without a list of words.
     */
    private static String trimEdges(String pattern) {
        var s = pattern.strip();
        var changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            var last = s.charAt(s.length() - 1);
            if (last == ',' || last == '.' || last == '/' || last == '-' || Character.isWhitespace(last)) {
                s = s.substring(0, s.length() - 1).strip();
                changed = true;
            } else if (last == '\'') {
                var open = s.lastIndexOf('\'', s.length() - 2);
                if (open >= 0) {
                    s = s.substring(0, open).strip();
                    changed = true;
                }
            }
            if (!s.isEmpty()) {
                var first = s.charAt(0);
                if (first == ',' || first == '.' || first == '/' || first == '-' || Character.isWhitespace(first)) {
                    s = s.substring(1).strip();
                    changed = true;
                }
            }
        }
        return s.replaceAll("\\s{2,}", " ");
    }
}
