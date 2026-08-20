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

import ai.intellistream.chat.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.time.ZoneId;
import java.util.Locale;

/**
 * Resolves "what time is it for the person reading this page" into a {@link TimeView}.
 *
 * <p>The zone is answered from the strongest evidence available, and the answer carries a
 * {@link ZoneSource} saying which that was:
 *
 * <ol>
 *   <li><b>Their explicit choice</b> on the profile page. Outranks everything, permanently — the
 *       whole reason it is a separate column from the guesses is so a detection cannot undo it.</li>
 *   <li><b>The browser's own report</b> ({@code Intl.DateTimeFormat().resolvedOptions().timeZone},
 *       posted back by {@code time-format.js} and stored). This outranks the identity provider
 *       deliberately: {@code zoneinfo} is an account attribute somebody set once, while the browser
 *       is saying where this person is sitting right now, and "wherever the user logs in from" is
 *       the behaviour every chat client has.</li>
 *   <li><b>The identity provider's {@code zoneinfo} claim</b>, when there is one. Usually there
 *       is not — it is an optional OIDC claim and Keycloak needs a mapper configured.</li>
 *   <li><b>A guess from {@code Accept-Language}</b> ({@link LocaleZones}) — {@code nb} means
 *       Norway means Europe/Oslo. This exists to make the <em>first</em> page load right, before
 *       any JavaScript has run and reported anything.</li>
 *   <li><b>{@code ichat.default-zone}</b>, which itself defaults to the server's zone. Reaching
 *       here means we do not know, and the page says so rather than pretending.</li>
 * </ol>
 *
 * <p>The locale comes from {@code Accept-Language} on every request (Spring's
 * {@code AcceptHeaderLocaleResolver}) rather than being stored, because unlike the zone it is not
 * a fact about the user that we might know better than the browser does — the browser is the
 * authority on it, and it can change between requests.
 */
@Component
public class TimeFormats {

    /** {@code ichat.default-zone}; blank means the server's own zone. */
    private final ZoneId defaultZone;

    public TimeFormats(@Value("${ichat.default-zone:}") String defaultZone) {
        this.defaultZone = User.zoneOrSystemDefault(defaultZone);
    }

    /** The zone this view falls back to when nothing else answers. */
    public ZoneId defaultZone() {
        return defaultZone;
    }

    /** Everything a page needs to render a timestamp for this viewer. */
    public TimeView forUser(User user, Locale locale) {
        var safeLocale = sanitize(locale);
        return new TimeView(zoneFor(user, safeLocale), safeLocale, sourceFor(user, safeLocale),
                user == null ? ai.intellistream.chat.domain.HourCycle.AUTO : user.getHourCycle(),
                user == null ? ai.intellistream.chat.domain.DateStyle.AUTO : user.getDateStyle());
    }

    /**
     * Put the view on the model as {@code ${fmt}}, plus the two flags the shared head fragment and
     * the pick-your-zone banner read. One call per rendered page, so a new page cannot half-adopt
     * this and end up with server timestamps in one zone and client ones in another.
     */
    public TimeView into(Model model, User user, Locale locale) {
        var view = forUser(user, locale);
        model.addAttribute("fmt", view);
        // isUnknown, not isUnconfirmed: a locale we could read a country out of produced a real
        // answer, and nagging somebody about a guess that is right — over a screen of timestamps
        // that are also right — is worse than saying nothing. This is the corner where we have
        // nothing at all: no choice, no detection, no claim, and a locale that names no single-zone
        // country. Detection normally answers before anyone sees it, so what is left is the
        // no-JavaScript case.
        model.addAttribute("askForZone",
                view.source().isUnknown() && user != null && !user.isZonePromptDismissed());
        return view;
    }

    /** The zone alone, for callers that are not rendering a page. */
    public ZoneId zoneFor(User user, Locale locale) {
        if (user == null) {
            return LocaleZones.guess(sanitize(locale)).orElse(defaultZone);
        }
        return user.effectiveZone(LocaleZones.guess(sanitize(locale)).orElse(defaultZone));
    }

    /** Which rung of the ladder above answered. */
    public ZoneSource sourceFor(User user, Locale locale) {
        if (user != null) {
            if (user.getZoneId() != null) return ZoneSource.CHOSEN;
            if (user.getDetectedZoneId() != null) return ZoneSource.DETECTED;
            if (user.getOidcZoneId() != null) return ZoneSource.ACCOUNT;
        }
        return LocaleZones.guess(sanitize(locale)).isPresent() ? ZoneSource.LOCALE : ZoneSource.DEFAULT;
    }

    /**
     * A usable locale. {@code Accept-Language} can be absent (a curl, a health check, a browser
     * configured to send nothing), in which case the servlet container hands back the JVM default;
     * a locale with no language at all is treated the same way. Never null, so no formatter
     * construction has to defend against it.
     */
    private static Locale sanitize(Locale locale) {
        if (locale == null || locale.getLanguage() == null || locale.getLanguage().isBlank()) {
            return Locale.getDefault();
        }
        return locale;
    }
}
