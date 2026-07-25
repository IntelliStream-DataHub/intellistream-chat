/*
 * Copyright 2026 Olav Gjerde
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

package ai.intellistream.chat.moderation;

import ai.intellistream.chat.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Refuses every HTTP request from a suspended account.
 *
 * <p>A filter rather than a check in the controllers, an interceptor, or an
 * {@code @PreAuthorize}: those all enforce on the endpoints somebody remembered to annotate, and
 * the property wanted here is that a suspended account cannot use <em>anything</em>, including the
 * endpoint added next month. Installed in both security chains (see {@code SecurityConfig}) after
 * the authorization filter, so the authentication is resolved and there is no path through the
 * application that reaches a handler without passing this.
 *
 * <p><b>It answers from {@link SuspensionRegistry}, not the database.</b> The alternative is
 * resolving the domain user here, which every page load then pays for twice, once in this filter
 * and again in the controller. The registry is authoritative enough for this: it is seeded at
 * startup, and {@code CurrentUser.resolve} independently rejects a suspended row, so the worst a
 * stale registry can do is downgrade this explanatory 403 to the generic one — never let a request
 * through.
 *
 * <p><b>Hazard: this must not be auto-registered as a servlet filter.</b> It is a {@code Filter}
 * bean, which Boot would otherwise map onto the plain servlet chain as well. That copy would run
 * outside Spring Security with an empty {@code SecurityContext}, see no authentication, skip — and,
 * because {@link OncePerRequestFilter} marks the request as handled, suppress the copy inside the
 * security chain that would have blocked it. {@code SecurityConfig} disables the automatic
 * registration with a {@code FilterRegistrationBean}; do not remove it.
 */
@Component
public class SuspensionEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SuspensionEnforcementFilter.class);

    /**
     * Paths a suspended user may still reach. Static assets so the 403 page renders, health so
     * probes are unaffected, and the login/logout endpoints so a suspended user can sign out
     * instead of being stuck with a session they cannot use. Signing back in is pointless but
     * harmless — they will be refused again at the first page.
     */
    private static final String[] ALWAYS_ALLOWED = {
            "/css/", "/js/", "/img/", "/fonts/", "/webjars/", "/branding/",
            "/oauth2/", "/login", "/logout", "/actuator/health", "/error"
    };

    /** Message shown to the account itself. The administrator's note is deliberately not in it. */
    private static final String MESSAGE =
            "Your account has been suspended. Contact an administrator if you think this is a mistake.";

    /**
     * Written by hand because a filter runs outside the MVC stack — there is no message converter
     * here, and pulling an {@code ObjectMapper} in to serialise a constant is not worth it. Shape
     * matches {@code ApiExceptionHandler}'s envelope so existing clients read it the same way;
     * there is no {@code traceId} because this is a policy decision, not a failure to correlate.
     */
    private static final String JSON_BODY = """
            {"code":"account_suspended","message":"%s","error":"%s"}""".formatted(MESSAGE, MESSAGE);

    /**
     * Minimal, self-contained page — no inline script (the CSP forbids it) and no template, so this
     * cannot break because a Thymeleaf model attribute was missing on a path that never reaches a
     * controller.
     */
    private static final String HTML_BODY = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Account suspended</title>
            <style>
              body { font-family: system-ui, sans-serif; margin: 4rem auto; max-width: 32rem;
                     padding: 0 1.5rem; line-height: 1.6; }
              h1 { font-size: 1.5rem; margin-bottom: .5rem; }
              p { color: #444; }
            </style></head>
            <body><h1>Account suspended</h1><p>%s</p></body></html>
            """.formatted(MESSAGE);

    private final SuspensionRegistry suspensions;

    public SuspensionEnforcementFilter(SuspensionRegistry suspensions) {
        this.suspensions = suspensions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = pathWithinApplication(request);
        for (var allowed : ALWAYS_ALLOWED) {
            if (path.startsWith(allowed)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!suspensions.anySuspended()) {
            chain.doFilter(request, response);
            return;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var subject = auth == null ? null : CurrentUser.subjectOf(auth);
        if (subject == null || !suspensions.isSuspendedSubject(subject)) {
            chain.doFilter(request, response);
            return;
        }
        // Logged at info: a suspended account still hammering the API is worth seeing, and the
        // volume is bounded by one line per request from an account that has been told to stop.
        log.info("Refused request from suspended account {} to {}", auth.getName(), request.getRequestURI());
        deny(pathWithinApplication(request), response);
    }

    private static void deny(String path, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Never let this sit in a cache: the account will be restored one day and a cached 403 on
        // the channel list would outlive the suspension.
        response.setHeader("Cache-Control", "no-store");
        if (isProgrammatic(path)) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(JSON_BODY);
        } else {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.getWriter().write(HTML_BODY);
        }
    }

    /**
     * Decided on the path, not the {@code Accept} header: it is the same split
     * {@code SecurityConfig} routes the two filter chains on, and it gives the same answer for a
     * WebSocket handshake, which sends whatever the browser felt like sending.
     */
    private static boolean isProgrammatic(String path) {
        return path.equals("/api") || path.startsWith("/api/")
                || path.equals("/ws") || path.startsWith("/ws/");
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        var path = request.getRequestURI();
        var context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path;
    }
}
