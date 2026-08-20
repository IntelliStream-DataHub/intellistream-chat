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

/**
 * Where the zone a page is rendered in came from. Carried alongside the zone itself because a
 * guess the user cannot see is a guess they cannot correct — the profile page names the source in
 * words, and the chat pages decide from it whether to run browser detection and whether to show
 * the pick-your-zone banner.
 *
 * <p>Ordered strongest to weakest; {@link #ordinal()} is not relied on anywhere, but reading the
 * list top to bottom is reading the resolution order in {@code TimeFormats.zoneFor}.
 */
public enum ZoneSource {

    /** The user picked it on their profile page. Nothing overrides this, including detection. */
    CHOSEN,
    /** Reported by the browser's {@code Intl} and stored. The best evidence of where they are. */
    DETECTED,
    /** The identity provider's {@code zoneinfo} claim. An account attribute, often stale. */
    ACCOUNT,
    /** Inferred from {@code Accept-Language} by {@link LocaleZones}. A guess, and labelled one. */
    LOCALE,
    /** {@code ichat.default-zone}, or the server's own zone. We do not know and did not guess. */
    DEFAULT;

    /**
     * True when we are showing times in a zone nobody confirmed — a guess or a fallback. The
     * profile page says so in words on the strength of this.
     */
    public boolean isUnconfirmed() {
        return this == LOCALE || this == DEFAULT;
    }

    /**
     * True only when we could not work the zone out <em>at all</em> and fell back to the server's.
     *
     * <p>The distinction from {@link #isUnconfirmed()} is what the pick-your-zone banner hangs on,
     * and it is the difference between a guess and an absence. A browser asking for pages in
     * Norwegian gives us Norway, and telling that person "we could not tell which time zone you are
     * in" over a screen of correct Oslo timestamps is nonsense — the guess is right, and the profile
     * page labels it as a guess for the rare case it is not. A browser asking for plain
     * {@code en} gives us nothing, because English implies the United States and the United States
     * keeps six clocks. That is when there is a real question to put in front of somebody.
     */
    public boolean isUnknown() {
        return this == DEFAULT;
    }

    /** The lower-case token put in the {@code me-zone-source} meta tag for the client. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
