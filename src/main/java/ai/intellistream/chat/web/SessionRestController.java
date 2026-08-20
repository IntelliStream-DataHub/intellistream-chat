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

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Am I still signed in?" — the one endpoint in the application that answers when the answer is no.
 *
 * <p>Every other route either requires authentication or is a static asset, so an expired session
 * produces a 302 to Keycloak. For a page that is a fine answer and for a background {@code fetch} it
 * is a terrible one: the redirect is followed transparently, the XHR resolves 200 with a login page
 * in the body, and nothing anywhere reports that the session is gone. The app then sits there
 * looking alive — sidebar rendered, composer accepting text — while the socket is dead and every
 * send fails silently. This route is {@code permitAll} precisely so it can say {@code false} instead
 * of redirecting.
 *
 * <p>It reports the username as well as the flag, which covers the second way a tab goes stale:
 * somebody signed in as a different account in another tab, so the session cookie is now valid but
 * belongs to somebody else. That reads as "still logged in" to a boolean and produces a page acting
 * on one identity while the server acts on another.
 *
 * <p><b>It renews the session.</b> Any request carrying the cookie updates Tomcat's last-accessed
 * time, and there is no per-request opt-out. That is already the status quo — {@code presence.js}
 * polls every 60 seconds — and the idle timeout that matters is enforced by {@code idle-logout.js},
 * which watches for user <em>input</em> rather than traffic and signs out after eight hours of none.
 * Polling only while the tab is visible (see {@code session-watch.js}) keeps a forgotten background
 * tab from holding a session open on its own.
 */
@RestController
public class SessionRestController {

    /**
     * @param authenticated false once the session is gone — the only state this endpoint exists to
     *                      be able to report
     * @param username      who the session belongs to now, or null; compared client-side against
     *                      the name the page was rendered for
     */
    public record SessionStatus(boolean authenticated, String username) {
    }

    @GetMapping("/api/session")
    public ResponseEntity<SessionStatus> status(Authentication authentication) {
        // The parameter is null when nothing authenticated the request at all; an expired session
        // that Spring replaced with an anonymous token arrives populated but unauthenticated, so
        // both have to be tested. SecurityContextHolder is the fallback for filter orderings that
        // do not resolve the argument.
        var auth = authentication != null ? authentication
                : SecurityContextHolder.getContext().getAuthentication();
        var signedIn = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());

        return ResponseEntity.ok()
                // A cached "yes" is the one answer that must never be served: it would keep a dead
                // tab convinced it is alive for the life of the cache entry.
                .cacheControl(CacheControl.noStore())
                .body(new SessionStatus(signedIn, signedIn ? auth.getName() : null));
    }
}
