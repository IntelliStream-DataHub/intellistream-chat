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
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AUTO} asks CLDR which clock a locale keeps rather than carrying a list of countries. These
 * pin that the question is being asked correctly — and that a form value nobody recognises lands on
 * AUTO instead of throwing, since these arrive from a {@code <select>} that can outlive a deploy.
 */
class HourCycleTest {

    @Test
    void autoFollowsTheLocale() {
        assertThat(HourCycle.AUTO.isTwelveHour(Locale.US)).isTrue();
        assertThat(HourCycle.AUTO.isTwelveHour(Locale.forLanguageTag("nb-NO"))).isFalse();
        assertThat(HourCycle.AUTO.isTwelveHour(Locale.UK)).isFalse();
        assertThat(HourCycle.AUTO.isTwelveHour(Locale.GERMANY)).isFalse();
        assertThat(HourCycle.AUTO.isTwelveHour(Locale.forLanguageTag("en-AU"))).isTrue();
    }

    @Test
    void anExplicitCycleIgnoresTheLocale() {
        assertThat(HourCycle.H24.isTwelveHour(Locale.US)).isFalse();
        assertThat(HourCycle.H12.isTwelveHour(Locale.forLanguageTag("nb-NO"))).isTrue();
    }

    @Test
    void aNullLocaleDoesNotThrow() {
        assertThat(HourCycle.H24.isTwelveHour(null)).isFalse();
        // AUTO has to consult *something*; the JVM default is the only thing left.
        HourCycle.AUTO.isTwelveHour(null);
    }

    @Test
    void unknownFormValuesLandOnAuto() {
        assertThat(HourCycle.parse("H24")).isEqualTo(HourCycle.H24);
        assertThat(HourCycle.parse("h12")).isEqualTo(HourCycle.H12);
        assertThat(HourCycle.parse(" auto ")).isEqualTo(HourCycle.AUTO);
        assertThat(HourCycle.parse(null)).isEqualTo(HourCycle.AUTO);
        assertThat(HourCycle.parse("")).isEqualTo(HourCycle.AUTO);
        assertThat(HourCycle.parse("military")).isEqualTo(HourCycle.AUTO);

        assertThat(DateStyle.parse("iso")).isEqualTo(DateStyle.ISO);
        assertThat(DateStyle.parse("nonsense")).isEqualTo(DateStyle.AUTO);
        assertThat(DateStyle.parse(null)).isEqualTo(DateStyle.AUTO);
    }
}
