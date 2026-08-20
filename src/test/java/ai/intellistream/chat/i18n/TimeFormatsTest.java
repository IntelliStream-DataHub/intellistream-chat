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
import ai.intellistream.chat.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bug this whole area exists for: message times rendered in the server's zone, in a hard-coded
 * US 12-hour pattern, sitting directly above live messages the browser had drawn in the reader's own
 * zone and locale.
 *
 * <p>These pin both halves — which zone wins, and what the text looks like once it has — with a
 * server default of UTC throughout, because UTC in a container is exactly the configuration that
 * produced the report.
 */
class TimeFormatsTest {

    /** 2026-02-03T23:30:00Z — still Tuesday in Oslo, already Wednesday in Tokyo. */
    private static final Instant EVENING = Instant.parse("2026-02-03T23:30:00Z");

    /** 2026-02-03T13:05:00Z — 14:05 in Oslo, 08:05 in New York. */
    private static final Instant AFTERNOON = Instant.parse("2026-02-03T13:05:00Z");

    private static final Locale NB = Locale.forLanguageTag("nb-NO");

    private final TimeFormats formats = new TimeFormats("UTC");

    private static User user() {
        return new User("sub-1", "alice", "alice@example.com", "Alice");
    }

    // ---------- Which zone wins ----------

    @Test
    void anExplicitChoiceOutranksEverything() {
        var me = user();
        me.noteOidcZone("America/New_York");
        me.noteDetectedZone("Asia/Tokyo");
        me.chooseZone("Europe/Oslo");

        assertThat(formats.zoneFor(me, NB)).isEqualTo(ZoneId.of("Europe/Oslo"));
        assertThat(formats.sourceFor(me, NB)).isEqualTo(ZoneSource.CHOSEN);
    }

    @Test
    void theBrowserOutranksTheIdentityProvider() {
        // zoneinfo is an account attribute somebody set once; the browser is saying where this
        // person is sitting today, and "wherever they log in from" is the behaviour asked for.
        var me = user();
        me.noteOidcZone("America/New_York");
        me.noteDetectedZone("Asia/Tokyo");

        assertThat(formats.zoneFor(me, NB)).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(formats.sourceFor(me, NB)).isEqualTo(ZoneSource.DETECTED);
    }

    @Test
    void theIdentityProviderOutranksTheLocaleGuess() {
        var me = user();
        me.noteOidcZone("America/New_York");

        assertThat(formats.zoneFor(me, NB)).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(formats.sourceFor(me, NB)).isEqualTo(ZoneSource.ACCOUNT);
    }

    @Test
    void acceptLanguageIsTheLastGuessBeforeGivingUp() {
        var me = user();

        assertThat(formats.zoneFor(me, NB)).isEqualTo(ZoneId.of("Europe/Oslo"));
        assertThat(formats.sourceFor(me, NB)).isEqualTo(ZoneSource.LOCALE);
        assertThat(formats.forUser(me, NB).unconfirmed()).isTrue();
        // But not *unknown*: we produced a real answer, so there is nothing to ask the user, and
        // the pick-your-zone banner stays off. Nagging somebody about a guess that is right, over a
        // screen of timestamps that are also right, is worse than saying nothing.
        assertThat(ZoneSource.LOCALE.isUnknown()).isFalse();
    }

    @Test
    void anUnguessableLocaleFallsBackToTheConfiguredDefaultAndSaysSo() {
        var me = user();

        // Bare `en` implies the United States, which keeps six clocks — so nothing is guessed and
        // the page has to admit it rather than render UTC as if it were a fact.
        assertThat(formats.zoneFor(me, Locale.ENGLISH)).isEqualTo(ZoneId.of("UTC"));
        assertThat(formats.sourceFor(me, Locale.ENGLISH)).isEqualTo(ZoneSource.DEFAULT);
        assertThat(formats.forUser(me, Locale.ENGLISH).unconfirmed()).isTrue();
        // This is the one that raises the banner.
        assertThat(ZoneSource.DEFAULT.isUnknown()).isTrue();
    }

    @Test
    void aConfirmedZoneIsNotUnconfirmed() {
        var me = user();
        me.noteDetectedZone("Asia/Tokyo");
        assertThat(formats.forUser(me, Locale.ENGLISH).unconfirmed()).isFalse();
    }

    // ---------- What the text looks like ----------

    @Test
    void clockFollowsTheLocaleByDefault() {
        var me = user();
        me.chooseZone("Europe/Oslo");

        assertThat(formats.forUser(me, NB).time(AFTERNOON)).isEqualTo("14:05");
        // Same instant, same zone, US locale: half-day clock, and the marker is whatever CLDR says.
        assertThat(formats.forUser(me, Locale.US).time(AFTERNOON)).startsWith("2:05");
        assertThat(formats.forUser(me, Locale.US).time(AFTERNOON)).isNotEqualTo("14:05");
    }

    @Test
    void anExplicitClockOverridesTheLocale() {
        // The reason the setting exists: reading the interface in English does not mean wanting a
        // 12-hour clock, and until now there was no way to say so.
        var me = user();
        me.chooseZone("Europe/Oslo");
        me.chooseHourCycle(HourCycle.H24);
        assertThat(formats.forUser(me, Locale.US).time(AFTERNOON)).isEqualTo("14:05");

        me.chooseHourCycle(HourCycle.H12);
        assertThat(formats.forUser(me, NB).time(AFTERNOON)).startsWith("2:05");
    }

    @Test
    void timesAreRenderedInTheViewersZoneNotTheServers() {
        // The heart of the report. The server here is UTC; nobody sees UTC.
        var oslo = user();
        oslo.chooseZone("Europe/Oslo");
        var tokyo = user();
        tokyo.chooseZone("Asia/Tokyo");

        assertThat(formats.forUser(oslo, NB).time(AFTERNOON)).isEqualTo("14:05");
        assertThat(formats.forUser(tokyo, NB).time(AFTERNOON)).isEqualTo("22:05");
    }

    @Test
    void theDayKeyIsTheViewersCalendarDay() {
        // A divider keyed in the server's zone lands on the wrong message: it puts "Wednesday"
        // partway through Tuesday evening for a reader far enough east.
        var oslo = user();
        oslo.chooseZone("Europe/Oslo");
        var tokyo = user();
        tokyo.chooseZone("Asia/Tokyo");

        assertThat(formats.forUser(oslo, NB).dayKey(EVENING)).isEqualTo("2026-02-04");
        assertThat(formats.forUser(tokyo, NB).dayKey(EVENING)).isEqualTo("2026-02-04");

        var newYork = user();
        newYork.chooseZone("America/New_York");
        assertThat(formats.forUser(newYork, Locale.US).dayKey(EVENING)).isEqualTo("2026-02-03");
        assertThat(formats.forUser(newYork, Locale.US).sameDay(EVENING, AFTERNOON)).isTrue();
    }

    @Test
    void dateOrderIsChosenNotInferred() {
        var me = user();
        me.chooseZone("Europe/Oslo");

        me.chooseDateStyle(DateStyle.DMY);
        assertThat(formats.forUser(me, Locale.US).date(AFTERNOON)).isEqualTo("3 Feb 2026");
        assertThat(formats.forUser(me, NB).date(AFTERNOON)).isEqualTo("3 feb. 2026");

        me.chooseDateStyle(DateStyle.MDY);
        assertThat(formats.forUser(me, Locale.UK).date(AFTERNOON)).isEqualTo("Feb 3, 2026");
        // The style fixes the *order*, not the words: the month name stays in the reader's
        // language, so a Norwegian who asked for month-first gets "feb. 3, 2026".
        assertThat(formats.forUser(me, NB).date(AFTERNOON)).isEqualTo("feb. 3, 2026");

        me.chooseDateStyle(DateStyle.ISO);
        assertThat(formats.forUser(me, NB).date(AFTERNOON)).isEqualTo("2026-02-03");
    }

    @Test
    void theAdminStampStaysIsoButMovesWithTheZone() {
        // Log-shaped columns want a sortable, unambiguous shape — but still the reader's hours.
        var tokyo = user();
        tokyo.chooseZone("Asia/Tokyo");
        assertThat(formats.forUser(tokyo, Locale.US).stamp(EVENING)).isEqualTo("2026-02-04 08:30");
    }

    @Test
    void theDayDividerKeepsTheWeekdayAndDropsTheYear() {
        var me = user();
        me.chooseZone("Europe/Oslo");

        var english = formats.forUser(me, Locale.UK).day(AFTERNOON);
        assertThat(english).startsWith("Tuesday").contains("February").doesNotContain("2026");

        // Locales that build a long date out of quoted words ("d 'de' MMMM 'de' y") must not be
        // left with the dangling literal the year was attached to.
        var spanish = formats.forUser(me, Locale.forLanguageTag("es-ES")).day(AFTERNOON);
        assertThat(spanish).doesNotContain("2026").doesNotEndWith("de").doesNotEndWith(" ");
    }

    @Test
    void metaTagValuesAreTheResolvedOnes() {
        var me = user();
        me.chooseZone("Europe/Oslo");
        var view = formats.forUser(me, NB);

        assertThat(view.zoneId()).isEqualTo("Europe/Oslo");
        assertThat(view.localeTag()).isEqualTo("nb-NO");
        assertThat(view.sourceToken()).isEqualTo("chosen");
        assertThat(view.hourCycleToken()).isEqualTo("auto");
        assertThat(view.dateStyleToken()).isEqualTo("auto");
        // AUTO is resolved here so the client passes a concrete hour12 to Intl rather than
        // reimplementing the CLDR lookup that decided it.
        assertThat(view.twelveHour()).isFalse();
        assertThat(formats.forUser(me, Locale.US).twelveHour()).isTrue();
    }

    @Test
    void aMissingAcceptLanguageDoesNotBlowUp() {
        var me = user();
        me.chooseZone("Europe/Oslo");
        assertThat(formats.forUser(me, null).time(AFTERNOON)).isNotEmpty();
        assertThat(formats.forUser(null, null)).isNotNull();
        assertThat(formats.forUser(me, NB).time(null)).isEmpty();
    }
}
