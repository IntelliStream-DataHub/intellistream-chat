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

package ai.intellistream.chat.web;

import ai.intellistream.chat.i18n.TimeFormats;
import ai.intellistream.chat.secretshare.SecretShareProperties;
import ai.intellistream.chat.security.CurrentUser;
import ai.intellistream.chat.service.AppSettingsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The two pages of one-time secrets: {@code /secrets}, where a signed-in person creates them and sees
 * what became of them, and {@code /s/{id}}, where whoever holds a link opens one.
 *
 * <p><b>{@code /s/{id}} is a shell and looks nothing up.</b> It renders the same bytes for an id that
 * exists and one that does not, names nobody, and says nothing about state. Partly that is the
 * verifier rule — nothing is disclosed to someone holding only the id, and the page is reachable
 * signed out — and partly it is that the server could not render the right page anyway: the session
 * cookie is {@code SameSite=Strict}, so a signed-in colleague clicking the link in an email arrives
 * looking anonymous. The page's script asks {@code /api/secrets/{id}/status} instead; that request is
 * same-origin, carries the cookie, and gets the true answer.
 *
 * <p>It renders no CSRF token, for the same cookie reason: rendering one on a navigation that arrived
 * without the {@code SameSite=Strict} CSRF cookie mints a new cookie, and every tab the person already
 * has open then fails its next POST with a stale token. The two POSTs the page makes are exempt from
 * CSRF instead (see {@code SecurityConfig}).
 */
@Controller
public class SecretSharePageController {

    /** 22 base64url characters: 16 random bytes. Anything else is not a link this app made. */
    static final String PUBLIC_ID_PATTERN = "[A-Za-z0-9_-]{22}";

    private final CurrentUser currentUser;
    private final TimeFormats timeFormats;
    private final AppSettingsService settings;
    private final SecretShareProperties props;

    public SecretSharePageController(CurrentUser currentUser, TimeFormats timeFormats,
                                     AppSettingsService settings, SecretShareProperties props) {
        this.currentUser = currentUser;
        this.timeFormats = timeFormats;
        this.settings = settings;
        this.props = props;
    }

    /** One entry of the lifetime picker. */
    public record LifetimeOption(long seconds, String label, boolean selected) {}

    @GetMapping("/secrets")
    public String secrets(Principal principal, Locale locale, Model model, HttpServletResponse response) {
        var me = currentUser.resolve(principal);
        model.addAttribute("me", me);
        timeFormats.into(model, me, locale);
        model.addAttribute("allowPublicSecrets", settings.publicSecretsAllowed());
        model.addAttribute("lifetimes", lifetimeOptions());
        model.addAttribute("maxPlaintextBytes", props.getMaxPlaintextBytes());
        model.addAttribute("displayMinutes", Math.max(1, props.getDisplaySeconds() / 60));
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return "secrets";
    }

    @GetMapping("/s/{publicId:" + PUBLIC_ID_PATTERN + "}")
    public String view(@PathVariable String publicId, Locale locale, Model model, HttpServletResponse response) {
        timeFormats.into(model, null, locale);
        linkPageHeaders(response);
        return "secret-view";
    }

    /**
     * No cache may keep the page, no request it makes may name it as a referrer, and no search engine
     * may index it. Set here, before Spring Security's header writers run: both the Cache-Control and
     * the Referrer-Policy writer leave a header alone that the response already has, so these replace
     * the site-wide defaults rather than competing with them ({@code SecretRoutesSecurityTest}).
     */
    public static void linkPageHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
    }

    /**
     * The one authenticated route under {@code /s/}. The page sends a signed-out visitor here when
     * the secret requires an account: being refused for want of authentication is what makes
     * {@code SecurityConfig.loginRequestCache} remember the URL, so the Keycloak round-trip ends back
     * on the secret. The page navigates here <em>without</em> the #fragment — a redirect carries a
     * fragment along, and the key would otherwise ride through Keycloak's URLs; it waits in
     * sessionStorage instead.
     */
    @GetMapping("/s/{publicId:" + PUBLIC_ID_PATTERN + "}/sign-in")
    public String signIn(@PathVariable String publicId) {
        return "redirect:/s/" + publicId;
    }

    List<LifetimeOption> lifetimeOptions() {
        return props.getLifetimes().stream()
                .map(d -> new LifetimeOption(d.toSeconds(), label(d), d.equals(props.getDefaultLifetime())))
                .toList();
    }

    static String label(Duration d) {
        if (d.toDays() > 0 && d.equals(Duration.ofDays(d.toDays()))) {
            return d.toDays() == 1 ? "1 day" : d.toDays() + " days";
        }
        if (d.toHours() > 0 && d.equals(Duration.ofHours(d.toHours()))) {
            return d.toHours() == 1 ? "1 hour" : d.toHours() + " hours";
        }
        long minutes = Math.max(1, d.toMinutes());
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
