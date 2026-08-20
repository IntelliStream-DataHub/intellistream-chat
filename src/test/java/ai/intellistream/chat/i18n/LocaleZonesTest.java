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

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-JavaScript guess: what {@code Accept-Language} alone is allowed to conclude about a
 * reader's clock.
 *
 * <p>Half of these pin what it must <em>not</em> conclude. A confident wrong guess is worse than an
 * admitted absent one here, because an absent one puts a "pick your time zone" banner in front of
 * the user and a wrong one silently shows them the wrong hour.
 */
class LocaleZonesTest {

    @Test
    void bareNorwegianImpliesOslo() {
        // The case from the bug report: a browser sending `nb` and nothing else.
        assertThat(LocaleZones.guess(Locale.forLanguageTag("nb"))).contains(ZoneId.of("Europe/Oslo"));
        assertThat(LocaleZones.guess(Locale.forLanguageTag("nn"))).contains(ZoneId.of("Europe/Oslo"));
        assertThat(LocaleZones.guess(Locale.forLanguageTag("no"))).contains(ZoneId.of("Europe/Oslo"));
    }

    @Test
    void regionWinsOverLanguage() {
        // fr implies France, but fr-CA is Canada — which keeps six clocks, so no guess at all.
        assertThat(LocaleZones.guess(Locale.forLanguageTag("fr"))).contains(ZoneId.of("Europe/Paris"));
        assertThat(LocaleZones.guess(Locale.forLanguageTag("fr-CA"))).isEmpty();
        // ...and en-GB is answerable even though bare `en` is not.
        assertThat(LocaleZones.guess(Locale.forLanguageTag("en-GB"))).contains(ZoneId.of("Europe/London"));
    }

    @Test
    void multiZoneCountriesGetNoGuess() {
        // Every one of these has more than one clock, so the honest answer is "we do not know".
        assertThat(LocaleZones.guess(Locale.forLanguageTag("en-US"))).isEmpty();
        assertThat(LocaleZones.guess(Locale.forLanguageTag("pt-BR"))).isEmpty();
        assertThat(LocaleZones.guess(Locale.forLanguageTag("ru-RU"))).isEmpty();
        assertThat(LocaleZones.guess(Locale.forLanguageTag("es-ES"))).isEmpty();
        assertThat(LocaleZones.guess(Locale.forLanguageTag("en-AU"))).isEmpty();
    }

    @Test
    void bareLanguagesWhoseLikelyRegionIsMultiZoneGetNoGuess() {
        // CLDR says en -> US, es -> ES, pt -> PT, ru -> RU. All four span several zones, so the
        // likely-subtags step resolves and the country step then refuses. This is the path that
        // ends in the pick-your-zone banner.
        assertThat(LocaleZones.guess(Locale.forLanguageTag("en"))).isEmpty();
        assertThat(LocaleZones.guess(Locale.forLanguageTag("es"))).isEmpty();
        assertThat(LocaleZones.guess(Locale.forLanguageTag("pt"))).isEmpty();
        assertThat(LocaleZones.guess(Locale.forLanguageTag("ru"))).isEmpty();
    }

    @Test
    void otherSingleZoneLanguagesResolve() {
        assertThat(LocaleZones.guess(Locale.forLanguageTag("ja"))).contains(ZoneId.of("Asia/Tokyo"));
        assertThat(LocaleZones.guess(Locale.forLanguageTag("de"))).contains(ZoneId.of("Europe/Berlin"));
        assertThat(LocaleZones.guess(Locale.forLanguageTag("sv"))).contains(ZoneId.of("Europe/Stockholm"));
        assertThat(LocaleZones.guess(Locale.forLanguageTag("da"))).contains(ZoneId.of("Europe/Copenhagen"));
        assertThat(LocaleZones.guess(Locale.forLanguageTag("hi"))).contains(ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void nonsenseAndAbsenceAreEmptyRatherThanThrowing() {
        assertThat(LocaleZones.guess(null)).isEmpty();
        assertThat(LocaleZones.guess(Locale.forLanguageTag("zz-ZZ"))).isEmpty();
        assertThat(LocaleZones.guess(Locale.ROOT)).isEmpty();
        assertThat(LocaleZones.forCountry(null)).isEmpty();
        assertThat(LocaleZones.forCountry("  ")).isEmpty();
        assertThat(LocaleZones.forCountry("no")).contains(ZoneId.of("Europe/Oslo"));
    }

    /**
     * Every name in the table is a zone this JVM's tzdb actually has.
     *
     * <p>Worth its own test because a typo cannot fail any of the above: {@code guess} drops a name
     * tzdb does not know and returns empty, which is indistinguishable from "that country has
     * several zones" — so a mistyped entry would quietly stop answering for a whole country and
     * nothing would say so.
     */
    @Test
    void everyMappedZoneExistsInTzdb() {
        assertThat(LocaleZones.mappedZoneNames())
                .allSatisfy(name -> assertThat(ZoneId.getAvailableZoneIds()).contains(name));
    }

    /**
     * Every language the likely-subtags table names resolves to an actual zone.
     *
     * <p>An entry pointing at a multi-zone country is dead weight that reads as a working guess;
     * this is the test that stops one being added.
     */
    @Test
    void everyLikelyRegionEntryProducesAZone() {
        for (var tag : new String[]{"nb", "sv", "da", "fi", "de", "fr", "it", "nl", "pl", "cs",
                "ja", "ko", "th", "vi", "tr", "el", "he", "uk", "hu", "ro", "bg", "hr", "sr",
                "sk", "sl", "lt", "lv", "et", "is", "fa", "ur", "hi", "bn", "zh", "ar"}) {
            assertThat(LocaleZones.guess(Locale.forLanguageTag(tag)))
                    .as("bare language %s", tag)
                    .isPresent();
        }
    }
}
